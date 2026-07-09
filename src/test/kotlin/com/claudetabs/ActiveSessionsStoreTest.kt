package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for [ActiveSessionsStore] — one file per alive Claude session under
 * `active-sessions/<sid>.json`. The 2.0 plugin's primary storage.
 */
class ActiveSessionsStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newStore(): ActiveSessionsStore = ActiveSessionsStore(tmp.newFolder("active-sessions"))

    @Test fun writeOrUpdate_createsFile_andReadRoundTrips() {
        val s = newStore()
        s.writeOrUpdate(sid = "sid-1", cwd = "D:\\Dev\\MyApp", pid = 12345L, lastSeen = 1_000_000L, name = "Notes")
        val r = s.read("sid-1")
        assertNotNull(r)
        assertEquals("sid-1", r!!.sid)
        assertEquals("D:\\Dev\\MyApp", r.cwd)
        assertEquals(12345L, r.pid)
        assertEquals(1_000_000L, r.lastSeen)
        assertEquals("Notes", r.name)
    }

    @Test fun writeOrUpdate_nullPid_serialisesAsJsonNull() {
        val s = newStore()
        s.writeOrUpdate(sid = "sid-mig", cwd = "/x", pid = null, lastSeen = 1L)
        val r = s.read("sid-mig")
        assertNotNull(r)
        assertNull("pid was null on write, should round-trip as null", r!!.pid)
    }

    @Test fun writeOrUpdate_nullMetaNamePreservesExisting() {
        val s = newStore()
        s.writeOrUpdate(sid = "sid-a", cwd = "/x", pid = 1L, lastSeen = 1L, name = "stay-put")
        s.writeOrUpdate(sid = "sid-a", cwd = "/x", pid = 2L, lastSeen = 2L, name = null)
        val r = s.read("sid-a")
        assertNotNull(r)
        assertEquals("name=null on update should NOT clear existing name", "stay-put", r!!.name)
        assertEquals(2L, r.pid)
        assertEquals(2L, r.lastSeen)
    }

    // ── updateName: the title controller's ONLY allowed write ─────────

    @Test fun updateName_neverTouchesPid_andCannotResurrectDemotedPid() {
        val s = newStore()
        s.writeOrUpdate(sid = "sid-a", cwd = "/x", pid = 1234L, lastSeen = 1L)
        // Poll demotes to restore-pending (process died)...
        s.writeOrUpdate(sid = "sid-a", cwd = "/x", pid = null, lastSeen = 2L)
        // ...then a racing name-capture lands. It must not bring pid=1234 back.
        assertTrue(s.updateName("sid-a", name = "Captured Topic"))
        val r = s.read("sid-a")!!
        assertNull("name write must not resurrect the demoted pid", r.pid)
        assertEquals("Captured Topic", r.name)
    }

    @Test fun updateName_noOpsOnMissingEntry_neverRecreatesEvictedFile() {
        val s = newStore()
        s.writeOrUpdate(sid = "sid-a", cwd = "/x", pid = 1L, lastSeen = 1L)
        s.delete("sid-a") // close path evicted it
        assertFalse("update after evict must no-op", s.updateName("sid-a", name = "Zombie"))
        assertNull("evicted entry must stay gone", s.read("sid-a"))
    }

    @Test fun updateName_userNameVariant_persistsRename() {
        val s = newStore()
        s.writeOrUpdate(sid = "sid-a", cwd = "/x", pid = 1L, lastSeen = 1L, name = "Topic")
        assertTrue(s.updateName("sid-a", userName = "My Name"))
        val r = s.read("sid-a")!!
        assertEquals("My Name", r.userName)
        assertEquals("topic untouched by a userName-only update", "Topic", r.name)
    }

    // ── restoreAttempts: ghost-decay counter ──────────────────────────

    @Test fun restoreAttempts_bumpIncrements_aliveWriteResets() {
        val s = newStore()
        s.writeOrUpdate(sid = "sid-g", cwd = "/x", pid = null, lastSeen = 1L)
        s.bumpRestoreAttempts("sid-g")
        s.bumpRestoreAttempts("sid-g")
        assertEquals(2, s.read("sid-g")!!.restoreAttempts)
        // Session observed alive → counter starts over; only never-alive ghosts decay.
        s.writeOrUpdate(sid = "sid-g", cwd = "/x", pid = 99L, lastSeen = 2L)
        assertEquals(0, s.read("sid-g")!!.restoreAttempts)
    }

    @Test fun restoreAttempts_missingInLegacyFile_parsesAsZero() {
        val s = newStore()
        val legacy = """{"sid":"sid-old","cwd":"/x","pid":null,"lastSeen":5,"name":null,"userName":null,"ordinal":null}"""
        assertEquals(0, s.parse(legacy)!!.restoreAttempts)
    }

    @Test fun bumpRestoreAttempts_missingEntry_noOps() {
        val s = newStore()
        s.bumpRestoreAttempts("sid-none") // must not throw or create a file
        assertNull(s.read("sid-none"))
    }

    @Test fun writeOrUpdate_nonNullMetaNameOverwrites() {
        val s = newStore()
        s.writeOrUpdate(sid = "sid-a", cwd = "/x", pid = 1L, lastSeen = 1L, name = "original")
        s.writeOrUpdate(sid = "sid-a", cwd = "/x", pid = 1L, lastSeen = 2L, name = "updated")
        assertEquals("updated", s.read("sid-a")!!.name)
    }

    @Test fun userName_roundTrips() {
        val s = newStore()
        s.writeOrUpdate(sid = "sid-u", cwd = "/x", pid = 1L, lastSeen = 1L, name = "Topic", userName = "My Chosen Name")
        val r = s.read("sid-u")
        assertNotNull(r)
        assertEquals("My Chosen Name", r!!.userName)
        assertEquals("Topic", r.name)
    }

    @Test fun writeOrUpdate_nullUserNamePreservesExisting() {
        val s = newStore()
        s.writeOrUpdate(sid = "sid-u", cwd = "/x", pid = 1L, lastSeen = 1L, userName = "Chosen")
        // Topic-mirror style refresh: name set, userName omitted.
        s.writeOrUpdate(sid = "sid-u", cwd = "/x", pid = 1L, lastSeen = 2L, name = "Auto Topic")
        val r = s.read("sid-u")
        assertEquals("userName must survive topic refreshes", "Chosen", r!!.userName)
        assertEquals("Auto Topic", r.name)
    }

    @Test fun parse_legacyFileWithoutUserName_nullField() {
        val s = newStore()
        val r = s.parse("""{"sid":"sid-l","cwd":"/x","pid":1,"lastSeen":5,"name":"N"}""")
        assertNotNull(r)
        assertNull(r!!.userName)
        assertEquals("N", r.name)
    }

    @Test fun ordinal_roundTrips() {
        val s = newStore()
        s.writeOrUpdate(sid = "sid-o", cwd = "/x", pid = 1L, lastSeen = 1L, ordinal = 7)
        assertEquals(7, s.read("sid-o")!!.ordinal)
    }

    @Test fun ordinal_nullPreservesExisting() {
        val s = newStore()
        s.writeOrUpdate(sid = "sid-o", cwd = "/x", pid = 1L, lastSeen = 1L, ordinal = 3)
        // A topic/pid refresh that doesn't know the position must NOT wipe the saved order.
        s.writeOrUpdate(sid = "sid-o", cwd = "/x", pid = 2L, lastSeen = 2L, name = "Topic")
        val r = s.read("sid-o")
        assertEquals("ordinal must survive refreshes that omit it", 3, r!!.ordinal)
        assertEquals(2L, r.pid)
    }

    @Test fun ordinal_legacyFileWithoutOrdinal_nullField() {
        val s = newStore()
        val r = s.parse("""{"sid":"sid-l","cwd":"/x","pid":1,"lastSeen":5,"name":"N","userName":null}""")
        assertNotNull(r)
        assertNull("missing ordinal parses as null", r!!.ordinal)
    }

    @Test fun delete_removesFile_idempotent() {
        val s = newStore()
        s.writeOrUpdate(sid = "sid-x", cwd = "/x", pid = 1L, lastSeen = 1L)
        assertTrue(s.delete("sid-x"))
        assertNull(s.read("sid-x"))
        assertFalse("deleting non-existent returns false", s.delete("sid-x"))
    }

    @Test fun listAll_returnsAllEntries() {
        val s = newStore()
        s.writeOrUpdate("sid-1", "/a", 1L, 100L)
        s.writeOrUpdate("sid-2", "/b", 2L, 200L)
        s.writeOrUpdate("sid-3", "/c", null, 300L, "with note")
        val all = s.listAll().associateBy { it.sid }
        assertEquals(3, all.size)
        assertEquals("/a", all["sid-1"]!!.cwd)
        assertEquals(null, all["sid-3"]!!.pid)
        assertEquals("with note", all["sid-3"]!!.name)
    }

    @Test fun listAll_emptyDir_returnsEmptyList() {
        assertTrue(newStore().listAll().isEmpty())
    }

    @Test fun read_missingFile_returnsNull() {
        assertNull(newStore().read("nope"))
    }

    @Test fun escaping_quotesAndBackslashesRoundTrip() {
        val s = newStore()
        s.writeOrUpdate("sid-q", "C:\\Path with \"quotes\"\\sub", 1L, 1L, "note with \"qs\" and \\backslash")
        val r = s.read("sid-q")
        assertNotNull(r)
        assertEquals("C:\\Path with \"quotes\"\\sub", r!!.cwd)
        assertEquals("note with \"qs\" and \\backslash", r.name)
    }
}
