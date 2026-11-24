package org.tcp2ws;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

public class MtprotoStreamTest {
    @Test public void convertsTdlibFirstPacketToNiagramAbridged() throws Exception {
        byte[] payload = new byte[40];
        // A req_pq_multi envelope is 40 bytes. TDLib writes a four-byte length.
        payload[20] = (byte) 0xf1;
        payload[21] = (byte) 0x8e;
        payload[22] = 0x7e;
        payload[23] = (byte) 0xbe;
        byte[] header = header(0xee);
        Cipher tdlib = cipher(header, false);
        tdlib.update(header);
        byte[] packet = tdlib.update(ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(payload.length).put(payload).array());
        byte[] frame = new MtprotoStream(header).nextRequest(input(packet), true);
        assertEquals(105, frame.length);
        assertArrayEquals(Arrays.copyOf(header, 56), Arrays.copyOf(frame, 56));
        byte[] plaintext = cipher(frame, false).update(frame);
        assertArrayEquals(new byte[]{(byte) 0xef, (byte) 0xef, (byte) 0xef, (byte) 0xef, 2, 0},
            Arrays.copyOfRange(plaintext, 56, 62));
        assertEquals(10, plaintext[64]);
        assertArrayEquals(payload, Arrays.copyOfRange(plaintext, 65, plaintext.length));
    }

    @Test public void originalAbridgedHandshakeStaysByteIdentical() throws Exception {
        byte[] header = header(0xef);
        Cipher client = cipher(header, false);
        client.update(header);
        byte[] packet = client.update(new byte[]{1, 11, 12, 13, 14});
        byte[] expected = ByteBuffer.allocate(69).put(header).put(packet).array();
        assertArrayEquals(expected, new MtprotoStream(header).nextRequest(input(packet), true));
    }

    @Test public void handlesSplitRepliesAndQuickAckWithoutResettingCtr() throws Exception {
        byte[] header = header(0xee);
        MtprotoStream stream = new MtprotoStream(header);
        byte[] payload = new byte[512];
        Arrays.fill(payload, (byte) 42);
        byte[] response = ByteBuffer.allocate(524)
            .put(new byte[]{(byte) 0x89, 2, 3, 4, 0x7f, (byte) 128, 0, 0})
            .put(payload).put(new byte[]{1, 2, 3, 4}).array();
        // Last packet uses the short form. Its prefix is added separately.
        response = ByteBuffer.allocate(525).put(response, 0, 520).put((byte) 1)
            .put(response, 520, 4).array();
        byte[] encrypted = cipher(header, true).update(response);
        ByteArrayOutputStream local = new ByteArrayOutputStream();
        for (int start = 0; start < encrypted.length; start += 7) {
            stream.writeReply(Arrays.copyOfRange(encrypted, start, Math.min(start + 7, encrypted.length)), local);
        }
        ByteBuffer translated = ByteBuffer.wrap(cipher(header, true).update(local.toByteArray()))
            .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x89020304, translated.getInt());
        assertEquals(payload.length, translated.getInt());
        byte[] decoded = new byte[payload.length];
        translated.get(decoded);
        assertArrayEquals(payload, decoded);
        assertEquals(4, translated.getInt());
        assertEquals(0x04030201, translated.getInt());
        assertFalse(translated.hasRemaining());
    }

    @Test public void originalDcLabelsAndTestRangesResolve() throws Exception {
        DcMap map = new DcMap("example.test");
        String[] hosts = {"pluto", "venus", "aurora", "vesta", "flora", "test_pluto", "test_venus", "test_aurora"};
        String[] ips = {"149.154.175.50", "149.154.167.51", "149.154.175.100", "149.154.167.91",
            "91.108.56.130", "149.154.175.10", "149.154.167.40", "2001:b28:f23d:f003::e"};
        for (int i = 0; i < hosts.length; i++) {
            assertEquals(hosts[i] + ".example.test", map.lookup(InetAddress.getByName(ips[i])));
        }
    }

    @Test public void bindIsSynchronousAndStopClosesActiveSocksStreams() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket occupied = new ServerSocket(0, 1, loopback);
                tcp2wsServer relay = new tcp2wsServer(occupied.getLocalPort(), "example.test", false, "", "");
                Socket client = new Socket(loopback, relay.getPort())) {
            assertNotEquals(occupied.getLocalPort(), relay.getPort());
            client.setSoTimeout(2000);
            client.getOutputStream().write(new byte[]{5, 1, 0});
            byte[] response = new byte[2];
            DataInputStream input = new DataInputStream(client.getInputStream());
            input.readFully(response);
            assertArrayEquals(new byte[]{5, 0}, response);
            relay.close();
            assertEquals(-1, input.read());
        }
    }

    private static byte[] header(int tag) throws Exception {
        byte[] clear = new byte[64];
        for (int i = 0; i < clear.length; i++) clear[i] = (byte) (i + 3);
        Arrays.fill(clear, 56, 60, (byte) tag);
        clear[60] = 2;
        clear[61] = 0;
        byte[] encrypted = cipher(clear, false).update(clear);
        System.arraycopy(clear, 0, encrypted, 0, 56);
        return encrypted;
    }

    private static DataInputStream input(byte[] packet) {
        return new DataInputStream(new ByteArrayInputStream(packet));
    }

    private static Cipher cipher(byte[] header, boolean reverse) throws Exception {
        byte[] seed = Arrays.copyOf(header, 64);
        if (reverse) {
            for (int i = 0; i < 64; i++) seed[i] = header[63 - i];
        }
        Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(seed, 8, 40), "AES"),
            new IvParameterSpec(Arrays.copyOfRange(seed, 40, 56)));
        return aes;
    }
}
