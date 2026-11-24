package org.tcp2ws;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;

// No-auth SOCKS5 CONNECT for TDLib's numeric IPv4 and IPv6 DC addresses.
final class Socks5Impl extends Socks4Impl {
    Socks5Impl(DataInputStream input, OutputStream output) { super(input, output); }

    @Override InetAddress readConnect() throws IOException {
        int count = input.readUnsignedByte();
        boolean noAuth = false;
        for (int i = 0; i < count; i++) noAuth |= input.readUnsignedByte() == 0;
        output.write(new byte[]{5, (byte) (noAuth ? 0 : 255)});
        output.flush();
        if (!noAuth) throw new IOException("SOCKS5 client did not offer no-auth");

        int version = input.readUnsignedByte();
        int command = input.readUnsignedByte();
        int reserved = input.readUnsignedByte();
        int addressType = input.readUnsignedByte();
        if (version != 5 || reserved != 0) throw new IOException("Invalid SOCKS5 request");
        if (command != 1) throw reject(7, "SOCKS5 command is not CONNECT");
        if (addressType != 1 && addressType != 4) throw reject(8, "Expected a numeric DC address");
        address = new byte[addressType == 1 ? 4 : 16];
        input.readFully(address);
        port = input.readUnsignedShort();
        if (port == 0) throw reject(4, "Missing destination port");
        return InetAddress.getByAddress(address);
    }

    private IOException reject(int status, String message) throws IOException {
        replyCode(status);
        return new IOException(message);
    }

    @Override void reply(boolean success) throws IOException { replyCode(success ? 0 : 4); }

    private void replyCode(int status) throws IOException {
        output.write(new byte[]{5, (byte) status, 0, 1, 127, 0, 0, 1, 0, 0});
        output.flush();
    }
}
