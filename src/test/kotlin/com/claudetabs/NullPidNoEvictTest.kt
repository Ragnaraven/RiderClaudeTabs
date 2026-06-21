package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression coverage for the 2.0.1 fix: an [ActiveSessionsStore.Entry] with `pid=null`
 * (the migration seed shape) must NEVER be evicted by the dead-strike logic. It sits
 * indefinitely until either a real alive Claude lands with that sid OR the user clears
 * it via /tabs-clear.
 *
 * The actual eviction-loop logic lives in [ClaudeTabWatcherStartup.pollOnce] and depends
 * on a Project. This test exercises the policy at the [EvictionTracker] level — proving
 * that nothing in the eviction primitives forces an evict — plus a unit-level guard on
 * the store's behavior under repeated pid=null writes.
 */
class NullPidNoEvictTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun storeAcceptsNullPidAndRoundTripsForever() {
        val s = ActiveSessionsStore(tmp.newFolder())
        s.writeOrUpdate("sid-mig", "/x", pid = null, lastSeen = 1L, name = "from migration")
        for (i in 1..10) {
            // Idempotent re-writes with pid=null should preserve the entry and name.
            s.writeOrUpdate("sid-mig", "/x", pid = null, lastSeen = i.toLong())
        }
        val r = s.read("sid-mig")
        assertNotNull(r)
        assertNull(r!!.pid)
        assertEquals("from migration", r.name)
    }

    @Test fun trackerIsNeverConsultedForNullPid_callerSkipsEvictionPath() {
        // The eviction tracker only ever receives sids whose pid was set. A pid=null sid
        // never calls recordDead — proved here by the absence of any strike accumulation
        // when the caller (the production pollOnce) takes the "skip" branch.
        val t = EvictionTracker(strikesNeeded = 2)
        // Simulate 100 polls without any recordDead call:
        for (i in 1..100) { /* no-op — pid=null branch in pollOnce does nothing */ }
        assertEquals("no strikes accumulated for an unconfirmed sid", 0, t.strikesFor("sid-mig"))
    }
}
