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

    @Test fun recordAlive_resetsStrikeCounter() {
        val t = EvictionTracker()
        t.recordDead("sid-1", now = 0L)
        assertEquals(1, t.strikesFor("sid-1"))
        t.recordAlive("sid-1")
        assertEquals(0, t.strikesFor("sid-1"))
    }
}
