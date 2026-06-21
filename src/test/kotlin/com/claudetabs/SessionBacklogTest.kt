package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [SessionBacklog] — global, bounded eviction history, prepended on every eviction,
 * dedup-by-sid, trimmed to [SessionBacklog.MAX_ENTRIES].
 */
class SessionBacklogTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newBacklog(): SessionBacklog = SessionBacklog(File(tmp.newFolder(), "session-backlog.json"))

    @Test fun emptyOnFirstRead() {
        assertTrue(newBacklog().list().isEmpty())
    }

    @Test fun prepend_putsNewestAtIndexZero() {
        val b = newBacklog()
        b.prepend(SessionBacklog.Entry("sid-1", "/a", null, 100L))
        b.prepend(SessionBacklog.Entry("sid-2", "/b", null, 200L))
        b.prepend(SessionBacklog.Entry("sid-3", "/c", null, 300L))
        val list = b.list()
        assertEquals(3, list.size)
        assertEquals("sid-3", list[0].sid)
        assertEquals("sid-2", list[1].sid)
        assertEquals("sid-1", list[2].sid)
    }

    @Test fun prepend_dedupsSidAndKeepsLatestEvictedAt() {
        val b = newBacklog()
        b.prepend(SessionBacklog.Entry("sid-1", "/a", "old-note", 100L))
        b.prepend(SessionBacklog.Entry("sid-2", "/b", null, 200L))
        // Resurrect-evict-again: same sid evicted later with new name.
        b.prepend(SessionBacklog.Entry("sid-1", "/a", "new-note", 300L))
        val list = b.list()
        assertEquals("dedup removed the older sid-1 entry", 2, list.size)
        assertEquals("the resurfaced sid-1 is at the front", "sid-1", list[0].sid)
        assertEquals("evictedAt reflects the latest eviction", 300L, list[0].evictedAt)
        assertEquals("name reflects the latest eviction", "new-note", list[0].name)
        assertEquals("sid-2 still present", "sid-2", list[1].sid)
    }

    @Test fun prepend_nullUserNameInheritsPriorUserName() {
        val b = newBacklog()
        b.prepend(SessionBacklog.Entry("sid-1", "/a", "Topic", 100L, userName = "Chosen"))
        b.prepend(SessionBacklog.Entry("sid-1", "/a", null, 300L))
        val list = b.list()
        assertEquals(1, list.size)
        assertEquals("Chosen", list[0].userName)
        assertEquals("Topic", list[0].name)
    }

    @Test fun prepend_nullNameInheritsPriorName() {
        val b = newBacklog()
        b.prepend(SessionBacklog.Entry("sid-1", "/a", "Known Name", 100L))
        // Resurrect-evict-again where the cached name was lost before the second eviction
        // (e.g. resumed but killed before the session re-wrote its topic name): the backlog
        // must keep the known name rather than trading it for null.
        b.prepend(SessionBacklog.Entry("sid-1", "/a", null, 300L))
        val list = b.list()
        assertEquals(1, list.size)
        assertEquals("known name survives a null re-evict", "Known Name", list[0].name)
        assertEquals("evictedAt still reflects the latest eviction", 300L, list[0].evictedAt)
    }

    @Test fun trim_capsAtMaxEntries() {
        val b = newBacklog()
        // Insert MAX_ENTRIES + 5 entries; only the most recent MAX_ENTRIES should remain.
        for (i in 1..(SessionBacklog.MAX_ENTRIES + 5)) {
            b.prepend(SessionBacklog.Entry("sid-$i", "/cwd-$i", null, i.toLong()))
        }
        val list = b.list()
        assertEquals(SessionBacklog.MAX_ENTRIES, list.size)
        // Most recent at the front (sid-(MAX_ENTRIES+5))
        assertEquals("sid-${SessionBacklog.MAX_ENTRIES + 5}", list[0].sid)
        // The 5 oldest were dropped (sid-1 through sid-5 should NOT be in the list)
        for (i in 1..5) {
            assertTrue("sid-$i must have been trimmed away", list.none { it.sid == "sid-$i" })
        }
    }

    @Test fun nullMetaName_serialisesAsJsonNull_andRoundTrips() {
        val b = newBacklog()
        b.prepend(SessionBacklog.Entry("sid-1", "/a", null, 100L))
        assertEquals(null, b.list()[0].name)
    }

    @Test fun escaping_quotesAndBackslashesRoundTrip() {
        val b = newBacklog()
        b.prepend(SessionBacklog.Entry("sid-q", "C:\\Path \"quoted\"", "note \"x\" \\y", 1L))
        val r = b.list()[0]
        assertEquals("C:\\Path \"quoted\"", r.cwd)
        assertEquals("note \"x\" \\y", r.name)
    }
}
