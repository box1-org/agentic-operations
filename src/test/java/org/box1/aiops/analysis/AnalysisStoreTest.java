package org.box1.aiops.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class AnalysisStoreTest {
    static final Instant T0 = Instant.parse("2026-11-07T04:00:00Z");

    static Clock clock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }

    @Test
    void entriesExpireAndTheOldestAreEvicted() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        AnalysisStore store = new AnalysisStore(clock(now), Duration.ofHours(24), 2);
        store.put(Analysis.queued("ana_1", "inc_ord_a", "ord_a", T0), "s");
        store.put(Analysis.queued("ana_2", "inc_ord_a", "ord_a", T0), "s");
        store.put(Analysis.queued("ana_3", "inc_ord_a", "ord_a", T0), "s");
        assertTrue(store.get("ana_1").isEmpty(), "evicted by size");
        assertTrue(store.get("ana_3").isPresent());
        now.set(T0.plus(Duration.ofHours(25)));
        assertTrue(store.get("ana_3").isEmpty(), "expired");
    }

    @Test
    void aNewStoreAfterARestartKnowsNothing() {
        AnalysisStore before = new AnalysisStore(Clock.systemUTC(), Duration.ofHours(24), 10);
        before.put(Analysis.queued("ana_x", "inc_ord_a", "ord_a", Instant.now()), "s");
        AnalysisStore after = new AnalysisStore(Clock.systemUTC(), Duration.ofHours(24), 10);
        assertTrue(after.get("ana_x").isEmpty());
        assertEquals(0, after.size());
    }

    @Test
    void updatesKeepTheOriginalExpiry() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        AnalysisStore store = new AnalysisStore(clock(now), Duration.ofHours(1), 10);
        Analysis q = Analysis.queued("ana_1", "inc_ord_a", "ord_a", T0);
        store.put(q, "s");
        now.set(T0.plus(Duration.ofMinutes(59)));
        store.put(q.running(), "s");
        now.set(T0.plus(Duration.ofMinutes(61)));
        assertTrue(store.get("ana_1").isEmpty());
    }
}
