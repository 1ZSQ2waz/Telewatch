package org.tcp2ws;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Adds the Worker secret to TDLib's obfuscation keys without changing the transport mode. */
final class WorkerMtproto {
    private final Cipher tdlibOut, tdlibIn, workerOut, workerIn;
    final byte[] header;

    WorkerMtproto(byte[] raw, byte[] secret) throws GeneralSecurityException, IOException {
        tdlibOut = cipher(raw, false, null);
        workerOut = cipher(raw, false, secret);
        byte[] plain = tdlibOut.update(raw);
        checkedDc(plain);
        header = workerOut.update(plain);
        System.arraycopy(raw, 0, header, 0, 56);
        tdlibIn = cipher(raw, true, null);
        workerIn = cipher(raw, true, secret);
    }

    static int readDc(byte[] raw) throws GeneralSecurityException, IOException {
        return checkedDc(cipher(raw, false, null).update(raw));
    }

    private static int checkedDc(byte[] plain) throws IOException {
        int mode = plain[56] & 255;
        if ((mode != 0xef && mode != 0xee && mode != 0xdd)
                || plain[56] != plain[57] || plain[56] != plain[58] || plain[56] != plain[59]) {
            throw new IOException("Expected an obfuscated TDLib MTProto header");
        }
        int dc = (short) ((plain[60] & 255) | ((plain[61] & 255) << 8));
        if (dc == 0 || Math.abs(dc) > 5) throw new IOException("Worker supports Telegram production DCs 1–5");
        return dc;
    }

    byte[] toWorker(byte[] bytes, int length) {
        return workerOut.update(tdlibOut.update(bytes, 0, length));
    }

    byte[] toTdlib(byte[] bytes, int offset, int length) {
        return tdlibIn.update(workerIn.update(bytes, offset, length));
    }

    private static Cipher cipher(byte[] header, boolean reverse, byte[] secret) throws GeneralSecurityException {
        byte[] material = new byte[48];
        for (int i = 0; i < material.length; i++) material[i] = header[reverse ? 55 - i : 8 + i];
        byte[] key = Arrays.copyOf(material, 32);
        if (secret != null) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(key);
            key = digest.digest(secret);
        }
        Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(material, 32, 16));
        return aes;
    }
}
