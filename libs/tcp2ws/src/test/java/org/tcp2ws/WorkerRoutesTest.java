package org.tcp2ws;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class WorkerRoutesTest {
    @Test public void dcPreferenceAlsoAppliesToMediaDc() {
        assertEquals(Arrays.asList("sg", "nl", "us", "global"), WorkerRoutes.order(-5));
        assertEquals(Arrays.asList("nl", "us", "sg", "global"), WorkerRoutes.order(2));
        assertEquals(WorkerRoutes.order(2), WorkerRoutes.order(4));
        assertEquals(Arrays.asList("us", "sg", "nl", "global"), WorkerRoutes.order(1));
        assertEquals(WorkerRoutes.order(1), WorkerRoutes.order(3));
    }

    @Test public void failedRegionFallsThroughOnlyForTheAffectedDcAndRecovers() {
        String secret = "000102030405060708090a0b0c0d0e0f";
        Map<String, WorkerRelay> regional = new HashMap<>();
        for (String name : Arrays.asList("sg", "us", "nl")) {
            regional.put(name, new WorkerRelay("https://" + name + ".example.org", secret));
        }
        try (WorkerRoutes routes = new WorkerRoutes(new WorkerRelay("https://example.org", secret), regional)) {
            long now = TimeUnit.SECONDS.toNanos(100);
            routes.failed("sg", 5, now);
            assertEquals(Arrays.asList("nl", "us", "global"), routes.candidates(5, now));
            assertEquals(Arrays.asList("sg", "nl", "us", "global"), routes.candidates(-5, now));
            routes.failed("nl", 5, now);
            routes.failed("us", 5, now);
            assertEquals(Collections.singletonList("global"), routes.candidates(5, now));
            routes.failed("global", 5, now);
            assertEquals(Collections.singletonList("sg"), routes.candidates(5, now));
            assertEquals(WorkerRoutes.order(5), routes.candidates(5, now + TimeUnit.SECONDS.toNanos(45)));
        }
    }
}
