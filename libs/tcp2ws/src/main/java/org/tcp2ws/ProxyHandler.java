package org.tcp2ws;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;
import com.neovisionaries.ws.client.OpeningHandshakeException;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

// Niagram tcp2ws /api relay, with a separate WebSocket for each SOCKS stream.
final class ProxyHandler implements Runnable {
    private final tcp2wsServer server;
    private final Socket client;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile WebSocket relay;
    private volatile MtprotoStream stream;
    private volatile WorkerLane workerLane;

    ProxyHandler(tcp2wsServer server, Socket client) {
        this.server = server;
        this.client = client;
    }

    @Override public void run() {
        try {
            client.setSoTimeout(15000);
            DataInputStream input = new DataInputStream(new BufferedInputStream(client.getInputStream()));
            OutputStream output = client.getOutputStream();
            int version = input.readUnsignedByte();
            Socks4Impl socks;
            if (version == 5) socks = new Socks5Impl(input, output);
            else if (version == 4) socks = new Socks4Impl(input, output);
            else return;

            InetAddress target = socks.readConnect();
            if (server.worker != null) {
                forwardThroughWorker(socks, input, output);
                return;
            }
            String host = server.destination(target);
            if (host == null) {
                socks.reply(false);
                throw new IOException("Destination is outside the Niagram Telegram DC map");
            }
            WebSocket websocket;
            try {
                websocket = connect(server.relayUrl(host), output);
            } catch (IOException | WebSocketException error) {
                socks.reply(false);
                throw error;
            }
            socks.reply(true);

            byte[] header = new byte[64];
            input.readFully(header);
            stream = new MtprotoStream(header);
            websocket.sendBinary(stream.nextRequest(input, true));
            client.setSoTimeout(0);
            while (!closed.get() && websocket.isOpen()) {
                websocket.sendBinary(stream.nextRequest(input, false));
            }
        } catch (EOFException ignored) {
            // TDLib closed its local stream.
        } catch (IOException | WebSocketException | GeneralSecurityException error) {
            if (!closed.get()) tcp2wsServer.LOG.log(Level.FINE, "tcp2ws stream ended", error);
        } finally {
            close();
        }
    }

    private void forwardThroughWorker(Socks4Impl socks, DataInputStream input, OutputStream output)
            throws IOException, WebSocketException, GeneralSecurityException {
        // The signed DC is only known after SOCKS CONNECT succeeds. Never change that DC or
        // replay an encrypted request; TDLib starts a fresh stream if an active lane fails.
        socks.reply(true);
        byte[] header = new byte[64];
        input.readFully(header);
        int dc = WorkerMtproto.readDc(header);
        WorkerRoutes routes = server.worker;
        for (String region : routes.candidates(dc, System.nanoTime())) {
            if (closed.get()) return;
            WorkerRelay carrier = routes.relay(region);
            WorkerLane lane = null;
            Runnable onFailure = () -> {
                if (closed.get()) return;
                routes.failed(region, dc, System.nanoTime());
                tcp2wsServer.LOG.info("Worker " + region + " failed for DC" + Math.abs(dc));
            };
            try {
                lane = carrier.lane(output, this::close, onFailure);
                workerLane = lane;
                if (closed.get()) {
                    lane.close();
                    return;
                }
                lane.connect();
            } catch (IOException | WebSocketException error) {
                if (lane != null) lane.close();
                if (error instanceof OpeningHandshakeException) carrier.invalidateSession();
                onFailure.run();
                continue;
            }
            tcp2wsServer.LOG.fine("Worker " + region + " connected for DC" + Math.abs(dc));
            lane.forward(input, client, header, server.timeouts);
            return;
        }
        throw new IOException("No Worker carrier could open for DC" + Math.abs(dc));
    }

    private WebSocket connect(String url, OutputStream output) throws IOException, WebSocketException {
        for (int attempt = 0; attempt < 10 && !closed.get(); attempt++) {
            WebSocket websocket = new WebSocketFactory()
                .setConnectionTimeout(5000)
                .setSocketTimeout(5000)
                .createSocket(url)
                .setFrameQueueSize(8)
                .addExtension("permessage-deflate")
                .addProtocol("binary")
                .addHeader("User-Agent", server.userAgent)
                .addHeader("Conn-Hash", server.connHash)
                .addListener(new WebSocketAdapter() {
                    @Override public void onBinaryMessage(WebSocket socket, byte[] bytes) {
                        MtprotoStream active = stream;
                        if (active == null || closed.get()) return;
                        try {
                            active.writeReply(bytes, output);
                        } catch (IOException error) {
                            close();
                        }
                    }

                    @Override public void onDisconnected(WebSocket socket, WebSocketFrame serverCloseFrame,
                            WebSocketFrame clientCloseFrame, boolean closedByServer) {
                        close();
                    }
                });
            relay = websocket;
            try {
                if (closed.get()) throw new IOException("tcp2ws stream closed");
                websocket.connect();
                if (closed.get()) throw new IOException("tcp2ws stream closed");
                websocket.getSocket().setSoTimeout(0);
                return websocket;
            } catch (OpeningHandshakeException error) {
                closeRelay(websocket);
                if (error.getStatusLine().getStatusCode() != 520 || attempt == 9) throw error;
            } catch (IOException | WebSocketException error) {
                closeRelay(websocket);
                throw error;
            }
        }
        throw new IOException("tcp2ws stream closed");
    }

    void close() {
        if (!closed.compareAndSet(false, true)) return;
        tcp2wsServer.closeQuietly(client);
        WebSocket socket = relay;
        if (socket != null) closeRelay(socket);
        WorkerLane lane = workerLane;
        if (lane != null) lane.close();
        server.finished(this);
    }

    private static void closeRelay(WebSocket socket) {
        Socket transport = socket.getSocket();
        if (transport != null) tcp2wsServer.closeQuietly(transport);
        socket.disconnect();
    }
}
