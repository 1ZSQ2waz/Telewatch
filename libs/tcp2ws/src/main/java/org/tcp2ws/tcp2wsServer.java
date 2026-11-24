package org.tcp2ws;

import java.io.Closeable;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

// Adapted from Niagram's tcp2ws server. See LICENSE-2.0.txt and NOTICE.txt.
public final class tcp2wsServer implements Closeable {
    public static final String LOOPBACK = "127.0.0.1";
    static final Logger LOG = Logger.getLogger("tcp2ws");
    private final DcMap cdn;
    final WorkerRoutes worker;
    final java.util.concurrent.ScheduledThreadPoolExecutor timeouts =
        new java.util.concurrent.ScheduledThreadPoolExecutor(1, task -> new Thread(task, "worker-timeout"));
    private final ServerSocket listener;
    private final Set<ProxyHandler> connections = new HashSet<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
        0, 24, 15, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> new Thread(task, "tcp2ws-client"));
    private final Thread acceptThread;
    private volatile boolean tls;
    private volatile boolean closed;
    final String userAgent;
    final String connHash;

    public tcp2wsServer(int preferredPort, String domain, boolean tls, String userAgent, String connHash)
            throws IOException {
        this(preferredPort, new DcMap(domain), tls, userAgent, connHash, null);
    }

    public tcp2wsServer(int preferredPort, WorkerRelay worker) throws IOException {
        this(preferredPort, new WorkerRoutes(worker, java.util.Collections.emptyMap()));
    }

    public tcp2wsServer(int preferredPort, WorkerRoutes worker) throws IOException {
        this(preferredPort, null, true, "", "", worker);
    }

    private tcp2wsServer(int preferredPort, DcMap cdn, boolean tls, String userAgent, String connHash,
            WorkerRoutes worker) throws IOException {
        this.cdn = cdn;
        this.worker = worker;
        this.tls = tls;
        this.userAgent = userAgent;
        this.connHash = connHash;
        ServerSocket bound;
        try {
            bound = bind(preferredPort);
        } catch (BindException occupied) {
            if (preferredPort == 0) throw occupied;
            bound = bind(0);
        }
        listener = bound;
        timeouts.setRemoveOnCancelPolicy(true);
        acceptThread = new Thread(this::acceptClients, "tcp2ws-listener");
        try {
            acceptThread.start();
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    private static ServerSocket bind(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK), port));
            return socket;
        } catch (IOException | RuntimeException error) {
            closeQuietly(socket);
            throw error;
        }
    }

    public int getPort() { return listener.getLocalPort(); }
    public boolean isOpen() { return !closed; }
    String destination(InetAddress address) { return cdn.lookup(address); }
    String relayUrl(String host) { return (tls ? "wss://" : "ws://") + host + "/api"; }

    private void acceptClients() {
        try {
            while (!closed) {
                Socket socket = listener.accept();
                synchronized (this) {
                    if (closed) {
                        closeQuietly(socket);
                        break;
                    }
                    ProxyHandler handler = new ProxyHandler(this, socket);
                    connections.add(handler);
                    try {
                        workers.execute(handler);
                    } catch (RejectedExecutionException overloaded) {
                        handler.close();
                    }
                }
            }
        } catch (IOException error) {
            if (!closed) LOG.log(Level.WARNING, "tcp2ws listener failed", error);
        } finally {
            close();
        }
    }

    synchronized void finished(ProxyHandler handler) { connections.remove(handler); }

    public synchronized void setTls(boolean enabled) {
        if (tls != enabled) {
            tls = enabled;
            disconnectClients();
        }
    }

    public synchronized void disconnectClients() {
        for (ProxyHandler handler : connections.toArray(new ProxyHandler[0])) handler.close();
    }

    public synchronized void resetConnections() {
        if (worker != null) worker.invalidateSessions();
        disconnectClients();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        closeQuietly(listener);
        disconnectClients();
        workers.shutdownNow();
        timeouts.shutdownNow();
        if (worker != null) worker.close();
    }

    static void closeQuietly(Closeable resource) {
        try { resource.close(); } catch (IOException ignored) { }
    }
}
