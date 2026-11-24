package org.tcp2ws;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single stream using the Worker project's tproxy-lane-v1 wire format. */
final class WorkerLane implements Closeable {
    private static final int OPEN = 1, DATA = 2, CLOSE = 3, WINDOW = 4;
    private static final int INITIAL_CREDIT = 4 * 1024 * 1024;
    private final int sid;
    private final byte[] key;
    private final OutputStream output;
    private final Runnable closeClient;
    private final Runnable onFailure;
    private final WebSocket socket;
    private volatile WorkerMtproto crypto;
    private final AtomicBoolean firstReplyOrFailure = new AtomicBoolean();
    private ScheduledFuture<?> deadlineTask;
    private int credit = INITIAL_CREDIT;
    private volatile boolean closed;

    WorkerLane(String url, String protocol, int sid, byte[] key, OutputStream output, Runnable closeClient,
            Runnable onFailure)
            throws IOException {
        this.sid = sid;
        this.key = key;
        this.output = output;
        this.closeClient = closeClient;
        this.onFailure = onFailure;
        socket = new WebSocketFactory().setConnectionTimeout(7000).setSocketTimeout(7000)
            .createSocket(url).setFrameQueueSize(8).addProtocol(protocol).addListener(new WebSocketAdapter() {
                @Override public void onBinaryMessage(WebSocket websocket, byte[] bytes) {
                    try {
                        receive(bytes);
                    } catch (IOException | RuntimeException error) {
                        peerClosed(true);
                    }
                }

                @Override public void onTextMessage(WebSocket websocket, String text) { peerClosed(true); }

                @Override public void onDisconnected(WebSocket websocket, WebSocketFrame remote,
                        WebSocketFrame local, boolean closedByServer) {
                    peerClosed(remote == null || remote.getCloseCode() != 1000);
                }
            });
    }

    void connect() throws IOException, WebSocketException {
        if (closed) throw new IOException("Worker lane is closed");
        try {
            socket.connect();
            if (closed || socket.getAgreedProtocol() == null) {
                throw new IOException("Worker lane did not negotiate its protocol");
            }
            socket.getSocket().setSoTimeout(0);
        } catch (IOException | WebSocketException error) {
            closeTransport();
            throw error;
        }
    }

    void forward(DataInputStream input, Socket client) throws IOException, GeneralSecurityException {
        byte[] header = new byte[64];
        input.readFully(header);
        forward(input, client, header, null);
    }

    void forward(DataInputStream input, Socket client, byte[] header, ScheduledExecutorService timeouts)
            throws IOException, GeneralSecurityException {
        crypto = new WorkerMtproto(header, key);
        if (!socket.isOpen()) {
            peerClosed(true);
            throw new IOException("Worker lane closed before forwarding");
        }
        socket.sendBinary(frame(OPEN, new byte[0]));
        sendData(crypto.header);
        client.setSoTimeout(0);
        byte[] buffer = new byte[20 * 1024];
        boolean first = true;
        for (int count; !closed && (count = input.read(buffer)) != -1;) {
            if (count == 0) continue;
            if (first) {
                first = false;
                synchronized (this) {
                    if (!closed && !firstReplyOrFailure.get() && timeouts != null) {
                        deadlineTask = timeouts.schedule(() -> {
                            if (!closed && firstReplyOrFailure.compareAndSet(false, true)) {
                                onFailure.run();
                                closeClient.run();
                            }
                        }, 9, TimeUnit.SECONDS);
                    }
                }
            }
            sendData(crypto.toWorker(buffer, count));
        }
    }

    private void peerClosed(boolean abnormal) {
        if (crypto == null || closed) return; // Failed upgrades are handled by the route selector.
        if (firstReplyOrFailure.compareAndSet(false, true) || abnormal) onFailure.run();
        closeClient.run();
    }

    private synchronized void cancelDeadline() {
        if (deadlineTask != null) {
            deadlineTask.cancel(false);
            deadlineTask = null;
        }
    }

    private void sendData(byte[] data) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        synchronized (this) {
            while (!closed && credit < data.length) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("Worker lane window stalled");
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Worker lane interrupted", error);
                }
            }
            if (closed || !socket.isOpen()) throw new IOException("Worker lane is closed");
            credit -= data.length;
        }
        socket.sendBinary(frame(DATA, data));
    }

    private void receive(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > 2 * 1024 * 1024) throw new IOException("Invalid lane message");
        ByteBuffer message = ByteBuffer.wrap(bytes);
        int frames = 0;
        while (message.hasRemaining() && !closed) {
            if (++frames > 4096 || message.remaining() < 8) throw new IOException("Truncated lane frame");
            int tag = message.getInt();
            int type = tag >>> 24;
            int length = message.getInt();
            if ((tag & 0xffffff) != sid || length < 0 || length > 1024 * 1024 || length > message.remaining()) {
                throw new IOException("Invalid Worker lane frame");
            }
            if (type == DATA && length > 0 && crypto != null) {
                if (firstReplyOrFailure.compareAndSet(false, true)) cancelDeadline();
                output.write(crypto.toTdlib(bytes, message.position(), length));
                output.flush();
                message.position(message.position() + length);
                socket.sendBinary(frame(WINDOW, ByteBuffer.allocate(4).putInt(length).array()));
            } else if (type == WINDOW && length == 4) {
                int delta = message.getInt();
                synchronized (this) {
                    if (delta <= 0 || delta > INITIAL_CREDIT - credit) throw new IOException("Invalid Worker lane credit");
                    credit += delta;
                    notifyAll();
                }
            } else if (type == CLOSE && length == 0) {
                peerClosed(false);
                return;
            } else {
                throw new IOException("Unexpected Worker lane frame");
            }
        }
    }

    private byte[] frame(int type, byte[] data) {
        return ByteBuffer.allocate(8 + data.length).putInt(type << 24 | sid).putInt(data.length).put(data).array();
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            cancelDeadline();
            notifyAll();
        }
        // Closing the carrier also closes its stream in the upstream Worker.
        closeTransport();
    }

    private void closeTransport() {
        Socket transport = socket.getSocket();
        if (transport != null) tcp2wsServer.closeQuietly(transport);
        socket.disconnect();
    }
}
