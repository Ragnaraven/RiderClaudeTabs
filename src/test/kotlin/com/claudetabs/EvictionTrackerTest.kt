package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [EvictionTracker] — in-memory K-strike counter with minimum strike spacing.
 *
 * K=2 default: a single transient dead-poll doesn't evict; two consecutive ones (spaced
 * at least minStrikeSpacingMs apart) do; any alive-poll resets.
 *
 * Strike spacing exists because the tracker is shared across project windows in one Rider
 * JVM — two windows polling milliseconds apart must not burn both strikes in one round.
 */
class EvictionTrackerTest {

    @Test fun singleDeadStrike_belowThreshold_doesNotEvict() {
        val t = EvictionTracker(strikesNeeded = 2)
        assertFalse(t.recordDead("sid-1", now = 0L))
        assertEquals(1, t.strikesFor("sid-1"))
    }

    @Test fun twoSpacedDeadStrikes_reachesThreshold_evicts() {
        val t = EvictionTracker(strikesNeeded = 2, minStrikeSpacingMs = 4_000L)
        assertFalse(t.recordDead("sid-1", now = 0L))
        assertTrue("second spaced dead strike should signal evict", t.recordDead("sid-1", now = 5_000L))
    }

    @Test fun rapidFireStrikes_withinSpacing_doNotDoubleCount() {
        // Two windows polling 100ms apart — the second call must NOT count as strike 2.
        val t = EvictionTracker(strikesNeeded = 2, minStrikeSpacingMs = 4_000L)
        assertFalse(t.recordDead("sid-1", now = 0L))
        assertFalse("strike within spacing window must not increment", t.recordDead("sid-1", now = 100L))
        assertEquals(1, t.strikesFor("sid-1"))
        // A properly-spaced third call counts.
        assertTrue(t.recordDead("sid-1", now = 5_000L))
    }

    @Test fun aliveResetsTheCounterAndTheSpacingClock() {
        val t = EvictionTracker(strikesNeeded = 2, minStrikeSpacingMs = 4_000L)
        t.recordDead("sid-1", now = 0L)
        t.recordAlive("sid-1")
        assertEquals(0, t.strikesFor("sid-1"))
        // After reset, a fresh strike is again below threshold.
        assertFalse(t.recordDead("sid-1", now = 100L))
        assertEquals(1, t.strikesFor("sid-1"))
    }

    @Test fun forgetClearsState() {
        val t = EvictionTracker(strikesNeeded = 2)
        t.recordDead("sid-1", now = 0L)
        t.forget("sid-1")
        assertEquals(0, t.strikesFor("sid-1"))
    }

    @Test fun perSidIndependence_oneSidStrikesDontAffectAnother() {
        val t = EvictionTracker(strikesNeeded = 2)
        t.recordDead("sid-1", now = 0L)
        assertEquals(0, t.strikesFor("sid-2"))
    }

    @Test fun customThreshold_K3() {
        val t = EvictionTracker(strikesNeeded = 3, minStrikeSpacingMs = 4_000L)
        assertFalse(t.recordDead("sid-1", now = 0L))
        assertFalse(t.recordDead("sid-1", now = 5_000L))
        assertTrue(t.recordDead("sid-1", now = 10_000L))
    }

    @Test fun seenAlive_unconfirmedSidIsNotMarked() {
        val t = EvictionTracker()
        // A sid we only ever saw dead (stale disk seed) must never be reported as seen-alive,
        // so the eviction path demotes it instead of deleting it.
        t.recordDead("sid-stale", now = 0L)
        assertFalse(t.hasBeenSeenAlive("sid-stale"))
    }

    @Test fun seenAlive_markedAfterAlivePoll_andStickyAcrossDeath() {
        val t = EvictionTracker()
        t.recordAlive("sid-1")
        assertTrue(t.hasBeenSeenAlive("sid-1"))
        // It stays "seen alive" through subsequent dead strikes (so a session we watched run
        // and then watched die in front of an open window is a genuine eviction candidate).
        t.recordDead("sid-1", now = 1_000L)
        assertTrue(t.hasBeenSeenAlive("sid-1"))
        // ...and even after being forgotten on eviction (historical fact, append-only).
        t.forget("sid-1")
        assertTrue(t.hasBeenSeenAlive("sid-1"))
    }
}
