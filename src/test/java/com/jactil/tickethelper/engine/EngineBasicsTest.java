package com.jactil.tickethelper.engine;

import com.jactil.tickethelper.core.store.Store;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineBasicsTest {

    private Store.TaskRow task(Instant startAt) {
        Instant now = Instant.parse("2026-12-01T00:00:00Z");
        return new Store.TaskRow(1, "t", "RUNNING", "GRAB", "SZQ", "CSQ",
                List.of(), List.of(), List.of(), List.of(), 1000, startAt, now, now);
    }

    @Test
    void phaseWalksFromPendingToSteady() {
        Instant startAt = Instant.parse("2026-12-01T08:00:00Z");
        Store.TaskRow t = task(startAt);

        assertEquals(Timing.Phase.PENDING, Timing.phase(t, startAt.minusSeconds(31)));
        assertEquals(Timing.Phase.PREHEAT, Timing.phase(t, startAt.minusSeconds(10)));
        assertEquals(Timing.Phase.BURST, Timing.phase(t, startAt.plusSeconds(10)));
        assertEquals(Timing.Phase.STEADY, Timing.phase(t, startAt.plusSeconds(61)));
    }

    @Test
    void intervalFollowsPhase() {
        Instant startAt = Instant.parse("2026-12-01T08:00:00Z");
        Store.TaskRow t = task(startAt);

        assertEquals(300, Timing.intervalMs(t, Timing.Phase.BURST));
        assertEquals(1000, Timing.intervalMs(t, Timing.Phase.STEADY));
    }

    @Test
    void withoutStartAtAlwaysSteady() {
        Store.TaskRow t = task(null);

        assertEquals(Timing.Phase.STEADY, Timing.phase(t, Instant.now()));
        assertEquals(1000, Timing.intervalMs(t, Timing.Phase.STEADY));
    }

    @Test
    void blacklistEscalatesAfterThreeStrikes() {
        Instant now = Instant.parse("2026-12-01T00:00:00Z");
        Blacklist blacklist = new Blacklist();

        assertFalse(blacklist.blocked("G1", "O", now));
        assertEquals(1, blacklist.hit("G1", "O", now));
        assertTrue(blacklist.blocked("G1", "O", now.plusSeconds(29)));
        assertFalse(blacklist.blocked("G1", "O", now.plusSeconds(31)));

        blacklist.hit("G1", "O", now);
        blacklist.hit("G1", "O", now);

        assertTrue(blacklist.blocked("G1", "O", now.plus(Duration.ofMinutes(4))));
        assertFalse(blacklist.blocked("G1", "O", now.plus(Duration.ofMinutes(6))));
    }

    @Test
    void blacklistIsPerTrainAndSeat() {
        Instant now = Instant.parse("2026-12-01T00:00:00Z");
        Blacklist blacklist = new Blacklist();
        blacklist.hit("G1", "O", now);

        assertTrue(blacklist.blocked("G1", "O", now));
        assertFalse(blacklist.blocked("G2", "O", now));
        assertFalse(blacklist.blocked("G1", "M", now));
    }

    @Test
    void rateLimiterSpacesCalls() throws Exception {
        RateLimiter limiter = new RateLimiter(50);
        long begin = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.acquire());
        }
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;

        assertTrue(elapsedMs >= 180, "5 次调用至少要占 4 个间隔，实际 " + elapsedMs + "ms");
    }
}
