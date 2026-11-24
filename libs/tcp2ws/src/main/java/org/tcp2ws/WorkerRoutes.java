package org.tcp2ws;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Orders Worker carriers while leaving the signed Telegram DC in the MTProto header intact. */
public final class WorkerRoutes implements Closeable {
    private static final String[] REGIONS = {"sg", "nl", "us"};
    private static final long COOLDOWN = TimeUnit.SECONDS.toNanos(45);
    private final Map<String, WorkerRelay> regions;
    private final WorkerRelay global;
    private final Map<String, Long> retryAfter = new ConcurrentHashMap<>();

    public WorkerRoutes(WorkerRelay global, Map<String, WorkerRelay> regions) {
        this.global = global;
        regions.keySet().forEach(region -> {
            if (!Arrays.asList(REGIONS).contains(region)) throw new IllegalArgumentException("Unknown Worker region");
        });
        this.regions = new HashMap<>(regions);
    }

    static List<String> order(int dc) {
        int preferred;
        switch (Math.abs(dc)) {
            case 5: preferred = 0; break;
            case 2: case 4: preferred = 1; break;
            default: preferred = 2;
        }
        return Arrays.asList(REGIONS[preferred], REGIONS[(preferred + 1) % 3],
            REGIONS[(preferred + 2) % 3], "global");
    }

    List<String> candidates(int dc, long now) {
        List<String> ready = new ArrayList<>();
        String firstCooling = null;
        long soonest = Long.MAX_VALUE;
        for (String name : order(dc)) {
            if (relay(name) == null) continue;
            long remaining = retryAfter.getOrDefault(name + dc, now) - now;
            if (remaining <= 0) ready.add(name);
            else if (remaining < soonest) {
                soonest = remaining;
                firstCooling = name;
            }
        }
        // TDLib already backs off its own retries. If all carriers are cooling down,
        // retry just the oldest failure to recover when a whole network comes back.
        if (ready.isEmpty() && firstCooling != null) ready.add(firstCooling);
        return ready;
    }

    WorkerRelay relay(String name) { return "global".equals(name) ? global : regions.get(name); }
    void failed(String region, int dc, long now) { retryAfter.put(region + dc, now + COOLDOWN); }

    public void prepare() throws IOException { global.prepare(); }

    void invalidateSessions() {
        global.invalidateSession();
        regions.values().forEach(WorkerRelay::invalidateSession);
        retryAfter.clear();
    }

    @Override public void close() {
        global.close();
        regions.values().forEach(WorkerRelay::close);
    }
}
