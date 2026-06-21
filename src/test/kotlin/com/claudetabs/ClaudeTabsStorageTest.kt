package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for the 2.0 [ClaudeTabsStorage]. Focus is on the migration path and the userClosed
 * store — the [ActiveSessionsStore] and [SessionBacklog] composition is covered by their
 * own test files.
 */
class ClaudeTabsStorageTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newStorage(): ClaudeTabsStorage = ClaudeTabsStorage(tmp.newFolder("claude-home"))

    // ══════════════════════════════════════════════════════════════
    // 1.x → 2.0 MIGRATION
    // ══════════════════════════════════════════════════════════════

    @Test fun migration_seedsActiveSessionsFromLegacyRestoreFiles() {
        val st = newStorage()
        // Lay down a 1.x-style restore file.
        st.stateDir.mkdirs()
        val restore = File(st.stateDir, "restore-D--Dev-MyApp.json")
        restore.writeText(
            """[
              {"sessionId":"sid-1","cwd":"D:\\Dev\\MyApp","tabName":"Fix Auth Flow","bypassPermissions":false},
              {"sessionId":"sid-2","cwd":"D:\\Dev\\MyApp","tabName":"Local","bypassPermissions":false},
              {"sessionId":"sid-3","cwd":"D:\\Dev\\MyApp-feature","tabName":"Plan Feature X","bypassPermissions":false}
            ]""".trimIndent()
        )

        val seeded = st.migrateLegacyRestoreFiles(now = 1_000_000L)
        assertEquals(3, seeded)

        // Per-sid files created with pid=null + lastSeen=now.
        val byId = st.activeSessions.listAll().associateBy { it.sid }
        assertEquals(3, byId.size)
        assertEquals("D:\\Dev\\MyApp", byId["sid-1"]!!.cwd)
        assertNull("pid should be null on migrated entries", byId["sid-1"]!!.pid)
        assertEquals(1_000_000L, byId["sid-1"]!!.lastSeen)
        // Descriptive tabName migrates as name.
        assertEquals("Fix Auth Flow", byId["sid-1"]!!.name)
        // Generic names ("Local") become null name.
        assertNull(byId["sid-2"]!!.name)
        // Worktree cwd preserved.
        assertEquals("D:\\Dev\\MyApp-feature", byId["sid-3"]!!.cwd)

        // Legacy file renamed.
        assertFalse("legacy restore file should be renamed", restore.exists())
        assertTrue(
            "legacy file should now have .pre-2.0 suffix",
            File(st.stateDir, "restore-D--Dev-MyApp.json.pre-2.0").exists()
        )
    }

    @Test fun migration_idempotent_secondCallIsNoOp() {
        val st = newStorage()
        st.stateDir.mkdirs()
        File(st.stateDir, "restore-D--Dev-X.json").writeText(
            """[{"sessionId":"sid-1","cwd":"D:\\Dev\\X","tabName":"x","bypassPermissions":false}]"""
        )
        val first = st.migrateLegacyRestoreFiles(now = 1L)
        val second = st.migrateLegacyRestoreFiles(now = 2L)
        assertEquals(1, first)
        assertEquals("second call should not re-seed", 0, second)
    }

    @Test fun migration_skipsWhenActiveSessionsAlreadyPopulated() {
        val st = newStorage()
        // Pre-populate active-sessions/ as if 2.0 had already run.
        st.activeSessions.writeOrUpdate("sid-existing", "/x", 1L, 1L)
        // Now place a legacy restore file — migration should NOT touch it.
        st.stateDir.mkdirs()
        val restore = File(st.stateDir, "restore-D--Dev-X.json")
        restore.writeText("""[{"sessionId":"sid-old","cwd":"/x","tabName":"x","bypassPermissions":false}]""")

        val seeded = st.migrateLegacyRestoreFiles()
        assertEquals(0, seeded)
        assertTrue("legacy file should be left alone when migration was already done", restore.exists())
    }

    @Test fun migration_renamesLegacyNamesJson() {
        val st = newStorage()
        st.stateDir.mkdirs()
        val namesJson = File(st.stateDir, "names.json")
        namesJson.writeText("{}")
        st.migrateLegacyRestoreFiles()
        assertFalse(namesJson.exists())
        assertTrue(File(st.stateDir, "names.json.pre-2.0").exists())
    }

    @Test fun migration_cleansLegacyDirsAndScripts() {
        val st = newStorage()
        st.stateDir.mkdirs()
        File(st.stateDir, "session-map").mkdirs()
        File(st.stateDir, "tabs").mkdirs()
        File(st.stateDir, "snapshots").mkdirs()
        File(st.stateDir, "rename-tab.sh").writeText("#!/usr/bin/env bash\n")
        File(st.stateDir, "tab.sh").writeText("#!/usr/bin/env bash\n")
        File(st.stateDir, "session-start-hook.sh").writeText("#!/usr/bin/env bash\n")
        File(st.stateDir, "tab-backup.js").writeText("// js")

        st.migrateLegacyRestoreFiles()

        assertFalse(File(st.stateDir, "session-map").exists())
        assertFalse(File(st.stateDir, "tabs").exists())
        assertFalse(File(st.stateDir, "snapshots").exists())
        assertFalse(File(st.stateDir, "rename-tab.sh").exists())
        assertFalse(File(st.stateDir, "tab.sh").exists())
        assertFalse(File(st.stateDir, "session-start-hook.sh").exists())
        assertFalse(File(st.stateDir, "tab-backup.js").exists())
    }

    // ══════════════════════════════════════════════════════════════
    // USER-CLOSED STORE
    // ══════════════════════════════════════════════════════════════

    @Test fun userClosed_roundTrip() {
        val st = newStorage()
        assertTrue(st.loadUserClosed("h1").isEmpty())
        assertTrue(st.addUserClosed("h1", "sid-1"))
        assertFalse("second add is idempotent", st.addUserClosed("h1", "sid-1"))
        assertEquals(setOf("sid-1"), st.loadUserClosed("h1"))
        st.addUserClosed("h1", "sid-2")
        assertEquals(setOf("sid-1", "sid-2"), st.loadUserClosed("h1"))
    }

    @Test fun userClosed_perProjectIsolation() {
        val st = newStorage()
        st.addUserClosed("h1", "sid-1")
        st.addUserClosed("h2", "sid-2")
        assertEquals(setOf("sid-1"), st.loadUserClosed("h1"))
        assertEquals(setOf("sid-2"), st.loadUserClosed("h2"))
    }

    @Test fun userClosed_prune() {
        val st = newStorage()
        st.addUserClosed("h1", "sid-keep")
        st.addUserClosed("h1", "sid-prune")
        val removed = st.pruneUserClosed("h1") { sid -> sid == "sid-keep" }
        assertEquals(1, removed)
        assertEquals(setOf("sid-keep"), st.loadUserClosed("h1"))
    }
}
