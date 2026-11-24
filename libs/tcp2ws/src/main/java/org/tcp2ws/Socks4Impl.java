package org.tcp2ws;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;

// CONNECT negotiation adapted from Niagram's tcp2ws. See NOTICE.txt.
class Socks4Impl {
    final DataInputStream input;
    final OutputStream output;
    byte[] address = new byte[4];
    int port;

    Socks4Impl(DataInputStream input, OutputStream output) {
        this.input = input;
        this.output = output;
    }

    InetAddress readConnect() throws IOException {
        int command = input.readUnsignedByte();
        port = input.readUnsignedShort();
        input.readFully(address);
        int length = 0;
        while (input.readUnsignedByte() != 0) {
            if (++length > 255) throw new IOException("SOCKS4 user ID too long");
        }
        if (command != 1 || port == 0) {
            reply(false);
            throw new IOException("Invalid SOCKS4 CONNECT");
        }
        return InetAddress.getByAddress(address);
    }

    void reply(boolean success) throws IOException {
        output.write(new byte[]{0, (byte) (success ? 90 : 91), (byte) (port >> 8), (byte) port,
            address[0], address[1], address[2], address[3]});
        output.flush();
    }
}
