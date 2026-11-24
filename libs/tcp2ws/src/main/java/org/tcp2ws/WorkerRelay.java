package org.tcp2ws;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

/** Native carrier client for ToiCF/CF-Workers-TGProxy, GPL-3.0. */
public final class WorkerRelay implements Closeable {
    private static final String SESSION_PATH = "/api/v1/session";
    private static final Pattern BOOTSTRAP = Pattern.compile("\\bboot=\"([A-Za-z0-9_-]{43})\"");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final byte[] HELLO = {16, 0, 0, 0, 0, 0, 0, 1, 1};
    private static final byte[] WELCOME = {17, 0, 0, 0, 0, 0, 0, 0};
    private final URI origin;
    private final byte[] key;
    private final String capability;
    private String sessionToken;
    private long renewAt;
    private int nextId;
    private boolean closed;
    private final AtomicLong generation = new AtomicLong();
    private long sessionGeneration;

    public WorkerRelay(String endpoint, String secret) {
        URI uri = URI.create(endpoint.trim());
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !host.contains(".")
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Enter the Worker HTTPS origin");
        }
        host = host.toLowerCase(Locale.ROOT);
        origin = URI.create("https://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort()));
        String normalized = secret.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("(?:[a-f0-9]{32}|(?:dd|ee|ef)[a-f0-9]{32})")) {
            throw new IllegalArgumentException("Invalid Worker SECRET");
        }
        key = decodeHex(normalized.substring(normalized.length() - 32));
        byte[] bridgeKey = normalized.length() == 34 && normalized.startsWith("dd")
            ? decodeHex(normalized) : key;
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(bridgeKey, "HmacSHA256"));
            capability = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac.doFinal(
                ("tdesktop-web-proxy-bridge-v1\n" + host).getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", error);
        }
    }

    public synchronized void prepare() throws IOException {
        if (closed) throw new IOException("Worker relay is closed");
        long activeGeneration = generation.get();
        if (sessionToken != null && sessionGeneration == activeGeneration && System.nanoTime() < renewAt && nextId <= 4096) return;

        HttpsURLConnection page = request("GET", "/?bridge=" + capability, null);
        String bootstrap;
        try {
            if (page.getResponseCode() != 200) throw new IOException("Worker bridge HTTP " + page.getResponseCode());
            Matcher match = BOOTSTRAP.matcher(new String(read(page, 64 * 1024), StandardCharsets.UTF_8));
            if (!match.find()) throw new IOException("Worker did not issue a bootstrap token");
            bootstrap = match.group(1);
        } finally {
            page.disconnect();
        }
        HttpsURLConnection session = request("POST", SESSION_PATH, bootstrap);
        try {
            session.setDoOutput(true);
            session.setFixedLengthStreamingMode(HELLO.length);
            session.setRequestProperty("Content-Type", "application/octet-stream");
            try (OutputStream output = session.getOutputStream()) { output.write(HELLO); }
            int status = session.getResponseCode();
            String token = session.getHeaderField("X-Session-Token");
            if (status != 200 || !"websocket-lanes".equals(session.getHeaderField("X-Carrier-Mode"))
                    || token == null || !TOKEN.matcher(token).matches()
                    || !java.util.Arrays.equals(WELCOME, read(session, 64))) {
                throw new IOException("Worker session rejected (HTTP " + status + ")");
            }
            sessionToken = token;
            sessionGeneration = activeGeneration;
            renewAt = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(4);
            nextId = 1;
        } finally {
            session.disconnect();
        }
    }

    void invalidateSession() { generation.incrementAndGet(); }

    synchronized WorkerLane lane(OutputStream output, Runnable closeClient)
            throws IOException {
        return lane(output, closeClient, () -> {});
    }

    synchronized WorkerLane lane(OutputStream output, Runnable closeClient, Runnable onFailure)
            throws IOException {
        prepare();
        int id = nextId++;
        String url = "wss://" + origin.getRawAuthority() + "/api/v1/ws";
        return new WorkerLane(url, "tproxy-lane-v1." + sessionToken + "." + id, id, key, output, closeClient, onFailure);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        String token = sessionToken;
        sessionToken = null;
        if (token == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                HttpsURLConnection request = request("DELETE", SESSION_PATH, token);
                try { request.getResponseCode(); } finally { request.disconnect(); }
            } catch (IOException ignored) { }
        });
    }

    private HttpsURLConnection request(String method, String path, String token) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) origin.resolve(path).toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setUseCaches(false);
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }

    private static byte[] read(HttpsURLConnection connection, int limit) throws IOException {
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int count; (count = input.read(chunk)) != -1;) {
                if (count > limit - result.size()) throw new IOException("Worker reply is too large");
                result.write(chunk, 0, count);
            }
            return result.toByteArray();
        }
    }

    private static byte[] decodeHex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
