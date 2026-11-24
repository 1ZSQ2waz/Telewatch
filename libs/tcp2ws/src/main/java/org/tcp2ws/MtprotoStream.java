package org.tcp2ws;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * TDLib uses obfuscated intermediate framing (EE) over SOCKS5. Niagram sends
 * abridged framing (EF) to tcp2ws. Convert only the transport envelope and
 * its AES-CTR stream. The MTProto message payload and DC ID stay untouched.
 */
final class MtprotoStream {
    private static final int MAX_PACKET = 2 * 1024 * 1024;
    private final boolean intermediate;
    private final Cipher fromClient;
    private final Cipher toRelay;
    private final Cipher fromRelay;
    private final Cipher toClient;
    final byte[] relayHeader;
    private final byte[] replyPrefix = new byte[4];
    private int replyPrefixSize;
    private int replyRemaining;

    MtprotoStream(byte[] header) throws IOException, GeneralSecurityException {
        fromClient = cipher(header);
        byte[] decoded = fromClient.update(header);
        int tag = decoded[56] & 255;
        if ((tag != 0xee && tag != 0xef) || (decoded[57] & 255) != tag ||
                (decoded[58] & 255) != tag || (decoded[59] & 255) != tag) {
            throw new IOException("Unsupported MTProto obfuscation header");
        }
        intermediate = tag == 0xee;
        Arrays.fill(decoded, 56, 60, (byte) 0xef);
        toRelay = cipher(header);
        relayHeader = header.clone();
        System.arraycopy(toRelay.update(decoded), 56, relayHeader, 56, 8);
        byte[] reverse = new byte[64];
        for (int i = 0; i < reverse.length; i++) reverse[i] = header[63 - i];
        fromRelay = cipher(reverse);
        toClient = cipher(reverse);
    }

    private static Cipher cipher(byte[] header) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(header, 8, 32, "AES"),
            new IvParameterSpec(header, 40, 16));
        return cipher;
    }

    byte[] nextRequest(DataInputStream input, boolean first) throws IOException {
        byte[] prefix = new byte[intermediate ? 4 : 1];
        input.readFully(prefix);
        prefix = fromClient.update(prefix);
        boolean quickAck = (prefix[prefix.length - 1] & 128) != 0;
        int length;
        if (intermediate) {
            length = (prefix[0] & 255) | ((prefix[1] & 255) << 8) |
                ((prefix[2] & 255) << 16) | ((prefix[3] & 127) << 24);
        } else {
            int words = prefix[0] & 127;
            if (words == 127) {
                byte[] extended = new byte[3];
                input.readFully(extended);
                extended = fromClient.update(extended);
                words = (extended[0] & 255) | ((extended[1] & 255) << 8) | ((extended[2] & 255) << 16);
            }
            length = words * 4;
        }
        checkLength(length);
        int words = length / 4;
        int flag = quickAck ? 128 : 0;
        byte[] abridged = words < 127 ? new byte[]{(byte) (words | flag)}
            : new byte[]{(byte) (127 | flag), (byte) words, (byte) (words >> 8), (byte) (words >> 16)};
        byte[] body = new byte[length];
        input.readFully(body);
        int offset = first ? relayHeader.length : 0;
        byte[] frame = new byte[offset + abridged.length + body.length];
        if (first) System.arraycopy(relayHeader, 0, frame, 0, offset);
        System.arraycopy(toRelay.update(abridged), 0, frame, offset, abridged.length);
        byte[] encryptedBody = toRelay.update(fromClient.update(body));
        System.arraycopy(encryptedBody, 0, frame, offset + abridged.length, length);
        return frame;
    }

    void writeReply(byte[] bytes, OutputStream output) throws IOException {
        if (bytes.length == 0) return;
        if (!intermediate) {
            output.write(bytes);
            output.flush();
            return;
        }
        byte[] clear = fromRelay.update(bytes);
        int index = 0;
        while (index < clear.length) {
            if (replyRemaining > 0) {
                int count = Math.min(replyRemaining, clear.length - index);
                output.write(toClient.update(clear, index, count));
                replyRemaining -= count;
                index += count;
                continue;
            }
            replyPrefix[replyPrefixSize++] = clear[index++];
            int first = replyPrefix[0] & 255;
            if (first >= 127 && replyPrefixSize < 4) continue;
            if (first >= 128) {
                output.write(toClient.update(new byte[]{replyPrefix[3], replyPrefix[2], replyPrefix[1], replyPrefix[0]}));
            } else {
                int words = first == 127 ? (replyPrefix[1] & 255) |
                    ((replyPrefix[2] & 255) << 8) | ((replyPrefix[3] & 255) << 16) : first;
                replyRemaining = words * 4;
                checkLength(replyRemaining);
                output.write(toClient.update(new byte[]{(byte) replyRemaining, (byte) (replyRemaining >> 8),
                    (byte) (replyRemaining >> 16), (byte) (replyRemaining >> 24)}));
            }
            replyPrefixSize = 0;
        }
        output.flush();
    }

    private static void checkLength(int length) throws IOException {
        if (length <= 0 || length > MAX_PACKET || length % 4 != 0) {
            throw new IOException("Invalid MTProto packet length");
        }
    }
}
