package com.claudetabs

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Layer 2 integration tests — [ClaudeTabsStorage] against a real filesystem (under a temp dir).
 *
 * These lock in the file-format contracts and the bugs we've already fixed:
 *   - saveState must not wipe the restore file while pendingRestores is non-empty
 *     (this was the bug where tabs disappeared on the first poll after startup)
 *   - loadRestoreWithFallback must fall back to snapshots when the live file is empty
 *   - appendToHistory must be thread-safe and prune old entries
 *   - snapshot rotation must respect keepCount
 */
class ClaudeTabsStorageTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var storage: ClaudeTabsStorage
    private val projectHash = "D--Dev-Test"

    @Before fun setup() {
        storage = ClaudeTabsStorage(tmp.root)
        storage.stateDir.mkdirs()
    }

    @After fun teardown() {
        // TemporaryFolder handles cleanup, nothing to do.
    }

    private fun session(id: String, name: String, bypass: Boolean = false) =
        ClaudeTabsStorage.SavedSession(id, "D:/Dev/Test", name, bypass)

    // ══════════════════════════════════════════════════════════════
    // serialise / parse round-trip
    // ══════════════════════════════════════════════════════════════

    @Test fun serialise_roundTrips() {
        val sessions = listOf(
            session("abc-123", "Fix Auth Bug"),
            session("def-456", "Rider Plugin", bypass = true),
        )
        val json = storage.serialiseSessions(sessions)
        val parsed = storage.parseSessions(json)
        assertEquals(sessions, parsed)
    }

    @Test fun parse_emptyArrayOrBlankReturnsEmpty() {
        assertTrue(storage.parseSessions("").isEmpty())
        assertTrue(storage.parseSessions("   ").isEmpty())
        assertTrue(storage.parseSessions("[]").isEmpty())
    }

    @Test fun parse_skipsEntriesMissingRequiredFields() {
        val json = """[{"sessionId":"ok","cwd":"/p","tabName":"Foo"},{"onlySessionId":"bad"}]"""
        val parsed = storage.parseSessions(json)
        assertEquals(1, parsed.size)
        assertEquals("ok", parsed[0].sessionId)
    }

    @Test fun serialise_escapesBackslashesAndQuotes() {
        val sessions = listOf(session("s", """Name with \ and "quote""""))
        val json = storage.serialiseSessions(sessions)
        val parsed = storage.parseSessions(json)
        assertEquals("""Name with \ and "quote"""", parsed[0].tabName)
    }

    // ══════════════════════════════════════════════════════════════
    // saveState — THE KEY REGRESSION TEST
    // ══════════════════════════════════════════════════════════════

    @Test fun saveState_emptyWithPendingRestores_preservesFile() {
        // Reproduces the bug: after startup the live restore file had 4 entries loaded into
        // pendingRestores. First poll saw 0 active Claude sessions (terminals hadn't launched
        // claude yet) and called saveState with empty — which USED TO wipe the file,
        // destroying the saved state. The fix is: if pendingRestores isn't empty, keep the
        // file alone so processPendingRestores can still consume it on later polls.
        val f = storage.restoreFile(projectHash)
        storage.saveState(projectHash, listOf(session("pre-existing", "Saved Tab")),
            pendingRestoresNonEmpty = false, keepCount = 0)
        assertTrue("file should exist after non-empty save", f.exists())

        storage.saveState(projectHash, emptyList(),
            pendingRestoresNonEmpty = true, keepCount = 0)
        assertTrue("file must survive empty save while restores pending", f.exists())
        assertTrue(f.readText().contains("pre-existing"))
    }

    @Test fun saveState_emptyWithNoPendingRestores_deletesFile() {
        val f = storage.restoreFile(projectHash)
        storage.saveState(projectHash, listOf(session("x", "Foo")), false, 0)
        assertTrue(f.exists())
        storage.saveState(projectHash, emptyList(), false, 0)
        assertFalse("empty save with no pending should delete", f.exists())
    }

    @Test fun saveState_writesSnapshotWhenKeepCountPositive() {
        storage.saveState(projectHash, listOf(session("x", "Foo")), false, keepCount = 3, now = 1000L)
        val snaps = storage.listSnapshots(projectHash)
        assertEquals(1, snaps.size)
        assertTrue(snaps[0].name.endsWith("-1000.json"))
    }

    @Test fun saveState_doesNotWriteSnapshotWhenKeepCountZero() {
        storage.saveState(projectHash, listOf(session("x", "Foo")), false, keepCount = 0, now = 1000L)
        assertTrue(storage.listSnapshots(projectHash).isEmpty())
    }

    // ══════════════════════════════════════════════════════════════
    // snapshot rotation
    // ══════════════════════════════════════════════════════════════

    @Test fun snapshots_pruneBeyondKeepCount() {
        repeat(5) { i ->
            storage.saveState(projectHash, listOf(session("s$i", "Tab $i")),
                false, keepCount = 3, now = 1000L + i)
        }
        val snaps = storage.listSnapshots(projectHash)
        assertEquals("should keep only newest 3", 3, snaps.size)
        // Newest first — verify by timestamp suffix
        assertTrue(snaps[0].name.endsWith("-1004.json"))
        assertTrue(snaps[1].name.endsWith("-1003.json"))
        assertTrue(snaps[2].name.endsWith("-1002.json"))
    }

    @Test fun snapshots_listedNewestFirst() {
        storage.writeSnapshot(projectHash, "[]", 10, now = 3000)
        storage.writeSnapshot(projectHash, "[]", 10, now = 1000)
        storage.writeSnapshot(projectHash, "[]", 10, now = 2000)
        val snaps = storage.listSnapshots(projectHash)
        assertTrue(snaps[0].name.endsWith("-3000.json"))
        assertTrue(snaps[1].name.endsWith("-2000.json"))
        assertTrue(snaps[2].name.endsWith("-1000.json"))
    }

    @Test fun snapshots_scopedByProjectHash() {
        storage.saveState("proj-A", listOf(session("a", "A")), false, 5, now = 1)
        storage.saveState("proj-B", listOf(session("b", "B")), false, 5, now = 2)
        assertEquals(1, storage.listSnapshots("proj-A").size)
        assertEquals(1, storage.listSnapshots("proj-B").size)
    }

    // ══════════════════════════════════════════════════════════════
    // loadRestoreWithFallback
    // ══════════════════════════════════════════════════════════════

    @Test fun load_prefersLiveFileWhenNonEmpty() {
        storage.saveState(projectHash, listOf(session("live", "From Live")), false, 5, now = 100)
        // Add a snapshot with DIFFERENT content too — live should still win
        storage.writeSnapshot(projectHash,
            storage.serialiseSessions(listOf(session("snap", "From Snap"))),
            5, now = 50)

        val result = storage.loadRestoreWithFallback(projectHash)
        assertEquals(1, result.sessions.size)
        assertEquals("live", result.sessions[0].sessionId)
        assertEquals(storage.restoreFile(projectHash), result.source)
    }

    @Test fun load_fallsBackToNewestSnapshotWhenLiveMissing() {
        storage.writeSnapshot(projectHash,
            storage.serialiseSessions(listOf(session("old", "Old"))),
            5, now = 100)
        storage.writeSnapshot(projectHash,
            storage.serialiseSessions(listOf(session("newer", "Newer"))),
            5, now = 200)

        val result = storage.loadRestoreWithFallback(projectHash)
        assertEquals(1, result.sessions.size)
        assertEquals("newer", result.sessions[0].sessionId)
    }

    @Test fun load_fallsBackWhenLiveFileEmpty() {
        // Write an explicitly-empty restore file, then a useful snapshot
        storage.restoreFile(projectHash).also { it.parentFile.mkdirs(); it.writeText("[]") }
        storage.writeSnapshot(projectHash,
            storage.serialiseSessions(listOf(session("recovered", "Recovered"))),
            5, now = 100)

        val result = storage.loadRestoreWithFallback(projectHash)
        assertEquals(1, result.sessions.size)
        assertEquals("recovered", result.sessions[0].sessionId)
    }

    @Test fun load_returnsEmptyWhenNothingAvailable() {
        val result = storage.loadRestoreWithFallback("never-saved")
        assertTrue(result.sessions.isEmpty())
        assertNull(result.source)
    }

    // ══════════════════════════════════════════════════════════════
    // history
    // ══════════════════════════════════════════════════════════════

    @Test fun history_appendAddsEntry() {
        storage.appendToHistory(session("abc", "Thing"), now = 1000L, maxAgeMs = 10_000L)
        val raw = storage.loadHistoryRaw()
        assertEquals(1, raw.size)
        assertEquals("abc", ClaudeTabsHelpers.extractJsonString(raw[0], "sessionId"))
    }

    @Test fun history_upsertReplacesExistingEntryBySessionId() {
        storage.appendToHistory(session("abc", "Old Name"), now = 1000L, maxAgeMs = 10_000L)
        storage.appendToHistory(session("abc", "New Name"), now = 2000L, maxAgeMs = 10_000L)
        val raw = storage.loadHistoryRaw()
        assertEquals(1, raw.size)
        assertEquals("New Name", ClaudeTabsHelpers.extractJsonString(raw[0], "tabName"))
    }

    @Test fun history_prunesEntriesOlderThanMaxAge() {
        // First entry at t=100, max age 50 means cutoff at t=2050 will prune it.
        storage.appendToHistory(session("old", "Old"), now = 100L, maxAgeMs = 1000L)
        storage.appendToHistory(session("new", "New"), now = 2000L, maxAgeMs = 1000L)
        val raw = storage.loadHistoryRaw()
        assertEquals(1, raw.size)
        assertEquals("new", ClaudeTabsHelpers.extractJsonString(raw[0], "sessionId"))
    }

    @Test fun history_concurrentAppendsAreSerialised() {
        // 50 concurrent appends — without synchronized, some writes would overwrite each other.
        val threads = (0 until 50).map { i ->
            Thread {
                storage.appendToHistory(session("id-$i", "Tab $i"), now = 1000L + i.toLong(), maxAgeMs = 1_000_000L)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val raw = storage.loadHistoryRaw()
        assertEquals("all 50 entries should survive", 50, raw.size)
    }

    // ══════════════════════════════════════════════════════════════
    // file paths
    // ══════════════════════════════════════════════════════════════

    @Test fun storagePaths_resolveUnderClaudeHome() {
        val home = tmp.root
        val s = ClaudeTabsStorage(home)
        assertEquals(File(home, "rider-plugin"), s.stateDir)
        assertEquals(File(home, "rider-plugin/tabs"), s.tabsDir)
        assertEquals(File(home, "rider-plugin/session-map"), s.sessionMapDir)
        assertEquals(File(home, "rider-plugin/snapshots"), s.snapshotsDir)
        assertEquals(File(home, "rider-plugin/history.json"), s.historyFile)
        assertEquals(File(home, "rider-plugin/config.json"), s.configFile)
        assertEquals(File(home, "CLAUDE.md"), s.claudeMdFile)
        assertEquals(File(home, "settings.json"), s.settingsFile)
        assertEquals(File(home, "sessions"), s.sessionsDir)
        assertEquals(File(home, "commands"), s.commandsDir)
    }

    @Test fun restoreFile_usesProjectHashSuffix() {
        assertEquals(File(storage.stateDir, "restore-D--Dev-OrOrbit.json"),
            storage.restoreFile("D--Dev-OrOrbit"))
    }
}
