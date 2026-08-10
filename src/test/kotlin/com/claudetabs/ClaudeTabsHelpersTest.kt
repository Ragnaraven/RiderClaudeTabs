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
 * Tests for [ClaudeTabsHelpers] — pure functions, no IDE/filesystem dependencies for most
 * (the transcript-lookup test uses TemporaryFolder).
 *
 * The 2.0 helpers are a slimmed subset of 1.x: name-classification helpers
 * (`isGenericTabName`, `isAiOverlayName`, `isRenameRedundant`, `NameEntry`) are gone.
 */
class ClaudeTabsHelpersTest {

    @get:Rule val tmp = TemporaryFolder()

    // ── extractJsonString ────────────────────────────────────────────

    @Test fun extractJsonString_simpleField() {
        assertEquals("hello", ClaudeTabsHelpers.extractJsonString("""{"x":"hello"}""", "x"))
    }

    @Test fun extractJsonString_missingKeyReturnsNull() {
        assertNull(ClaudeTabsHelpers.extractJsonString("""{"x":"hello"}""", "y"))
    }

    @Test fun extractJsonString_escapedQuotes() {
        assertEquals("a\"b", ClaudeTabsHelpers.extractJsonString("""{"x":"a\"b"}""", "x"))
    }

    @Test fun extractJsonString_escapedBackslashes() {
        // On-disk `\\` (two chars) means one literal backslash after decode.
        assertEquals("D:\\Dev\\X", ClaudeTabsHelpers.extractJsonString("""{"x":"D:\\Dev\\X"}""", "x"))
    }

    // ── esc ──────────────────────────────────────────────────────────

    @Test fun esc_quoteAndBackslash() {
        assertEquals("a\\\\b\\\"c", ClaudeTabsHelpers.esc("a\\b\"c"))
    }

    // ── parseConfig ──────────────────────────────────────────────────

    @Test fun parseConfig_emptyReturnsDefaults() {
        val c = ClaudeTabsHelpers.parseConfig(null)
        assertEquals(ClaudeTabsHelpers.Config.DEFAULT.backlogMaxEntries, c.backlogMaxEntries)
        assertEquals(ClaudeTabsHelpers.Config.DEFAULT.deadStrikesNeeded, c.deadStrikesNeeded)
    }

    @Test fun parseConfig_overrideBacklogMaxEntries() {
        val c = ClaudeTabsHelpers.parseConfig("""{"backlogMaxEntries": 100}""")
        assertEquals(100, c.backlogMaxEntries)
    }

    @Test fun parseConfig_overrideDeadStrikes() {
        val c = ClaudeTabsHelpers.parseConfig("""{"deadStrikesNeeded": 4}""")
        assertEquals(4, c.deadStrikesNeeded)
    }

    @Test fun parseConfig_malformedFallsBackToDefaults() {
        val c = ClaudeTabsHelpers.parseConfig("""not valid json""")
        assertEquals(ClaudeTabsHelpers.Config.DEFAULT.backlogMaxEntries, c.backlogMaxEntries)
    }

    // ── isShellCommand ────────────────────────────────────────────────

    @Test fun isShellCommand_recognisedShells() {
        assertTrue(ClaudeTabsHelpers.isShellCommand("/usr/bin/bash"))
        assertTrue(ClaudeTabsHelpers.isShellCommand("/usr/bin/zsh"))
        assertTrue(ClaudeTabsHelpers.isShellCommand("C:\\Windows\\System32\\cmd.exe"))
        assertTrue(ClaudeTabsHelpers.isShellCommand("C:\\Program Files\\PowerShell\\7\\pwsh.exe"))
    }

    @Test fun isShellCommand_rejectsClaudeAndOtherProcesses() {
        assertFalse(ClaudeTabsHelpers.isShellCommand("/usr/bin/claude"))
        assertFalse(ClaudeTabsHelpers.isShellCommand("C:\\Windows\\explorer.exe"))
    }

    // ── projectHashForPath ────────────────────────────────────────────

    @Test fun projectHashForPath_windowsPath() {
        assertEquals("D--Dev-MyApp", ClaudeTabsHelpers.projectHashForPath("D:\\Dev\\MyApp"))
    }

    @Test fun projectHashForPath_unixPath() {
        assertEquals("-home-me-repos-MyApp", ClaudeTabsHelpers.projectHashForPath("/home/me/repos/MyApp"))
    }

    @Test fun projectHashForPath_nullDefaults() {
        assertEquals("default", ClaudeTabsHelpers.projectHashForPath(null))
    }

    // ── hasTranscriptAnywhere ─────────────────────────────────────────

    @Test fun hasTranscriptAnywhere_fastPathHit() {
        val projects = tmp.newFolder("projects")
        val cwd = "D:\\Dev\\Foo"
        val encoded = "D--Dev-Foo"
        File(projects, encoded).mkdirs()
        File(File(projects, encoded), "sid-1.jsonl").writeText("[]")
        assertTrue(ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "sid-1", cwd))
    }

    @Test fun hasTranscriptAnywhere_fallback_worktreeResume() {
        // Session originally started in D:\Dev\Foo (transcript under D--Dev-Foo/).
        // Later resumed from D:\Dev\Foo-feature; cwd-derived path won't find it,
        // fallback scan must.
        val projects = tmp.newFolder("projects")
        File(projects, "D--Dev-Foo").mkdirs()
        File(File(projects, "D--Dev-Foo"), "sid-1.jsonl").writeText("[]")
        assertTrue(
            "fallback scan must find sid-1 in any project dir, not just the cwd-derived one",
            ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "sid-1", "D:\\Dev\\Foo-feature")
        )
    }

    @Test fun hasTranscriptAnywhere_missing_returnsFalse() {
        val projects = tmp.newFolder("projects")
        File(projects, "D--Dev-X").mkdirs()
        assertFalse(ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "missing-sid", "D:\\Dev\\X"))
    }

    @Test fun hasTranscriptAnywhere_blankSid_false() {
        val projects = tmp.newFolder("projects")
        assertFalse(ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "", "D:\\Dev\\X"))
    }

    // ── transcriptFile — resolves the .jsonl path ────────────────────────

    @Test fun transcriptFile_returnsFileOnHit_nullOnMiss() {
        val projects = tmp.newFolder("projects")
        val dir = File(projects, "D--Dev-Foo").apply { mkdirs() }
        val f = File(dir, "sid-1.jsonl").apply { writeText("[]") }
        assertEquals(f, ClaudeTabsHelpers.transcriptFile(projects, "sid-1", "D:\\Dev\\Foo"))
        assertNull(ClaudeTabsHelpers.transcriptFile(projects, "missing", "D:\\Dev\\Foo"))
    }

    // ── extractResumeIdFromArgs ──────────────────────────────────────

    @Test fun extractResumeIdFromArgs_dashDashResumeWithSpace() {
        val args = arrayOf("--resume", "12345678-1234-1234-1234-123456789012")
        assertEquals("12345678-1234-1234-1234-123456789012", ClaudeTabsHelpers.extractResumeIdFromArgs(args))
    }

    @Test fun extractResumeIdFromArgs_dashR() {
        val args = arrayOf("-r", "12345678-1234-1234-1234-123456789012")
        assertEquals("12345678-1234-1234-1234-123456789012", ClaudeTabsHelpers.extractResumeIdFromArgs(args))
    }

    @Test fun extractResumeIdFromArgs_dashDashResumeEquals() {
        val args = arrayOf("--resume=12345678-1234-1234-1234-123456789012")
        assertEquals("12345678-1234-1234-1234-123456789012", ClaudeTabsHelpers.extractResumeIdFromArgs(args))
    }

    @Test fun extractResumeIdFromArgs_noResumeFlag() {
        assertNull(ClaudeTabsHelpers.extractResumeIdFromArgs(arrayOf("--debug")))
    }

    @Test fun extractResumeIdFromArgs_nonUuidValue() {
        // Garbage after --resume is rejected (not a UUID).
        assertNull(ClaudeTabsHelpers.extractResumeIdFromArgs(arrayOf("--resume", "not-a-uuid")))
    }

    @Test fun extractResumeIdFromArgs_nullArgs() {
        assertNull(ClaudeTabsHelpers.extractResumeIdFromArgs(null))
    }

    // ── computeOpenSnapshot ─────────────────────────────────────────

    private val WARMUP = 45_000L

    @Test fun computeOpenSnapshot_presentAreAlwaysIncluded() {
        val snap = ClaudeTabsHelpers.computeOpenSnapshot(
            prev = setOf("a"), present = setOf("a", "b"),
            materializedSids = emptySet(), lastSpawnAttempt = emptyMap(), now = 1000, warmupMs = WARMUP,
        )
        assertEquals(setOf("a", "b"), snap)
    }

    @Test fun computeOpenSnapshot_warmupKeepsJustSpawnedNotYetPresent() {
        // "s" was in prev, isn't present yet, spawned 1s ago, never materialized → kept (warming).
        val snap = ClaudeTabsHelpers.computeOpenSnapshot(
            prev = setOf("s"), present = emptySet(),
            materializedSids = emptySet(), lastSpawnAttempt = mapOf("s" to 1_000L), now = 2_000L, warmupMs = WARMUP,
        )
        assertEquals(setOf("s"), snap)
    }

    @Test fun computeOpenSnapshot_materializedThenAbsentIsNotResurrected() {
        // THE REGRESSION: "s" spawned 1s ago (inside warmup) AND already materialized, now absent
        // (user ×'d it). Must NOT be re-added — otherwise the tab can never be killed.
        val snap = ClaudeTabsHelpers.computeOpenSnapshot(
            prev = setOf("s"), present = emptySet(),
            materializedSids = setOf("s"), lastSpawnAttempt = mapOf("s" to 1_000L), now = 2_000L, warmupMs = WARMUP,
        )
        assertEquals(emptySet<String>(), snap)
    }

    @Test fun computeOpenSnapshot_warmupExpiresAfterWindow() {
        // Spawned long ago, never materialized, still absent → warmup no longer protects it.
        val snap = ClaudeTabsHelpers.computeOpenSnapshot(
            prev = setOf("s"), present = emptySet(),
            materializedSids = emptySet(), lastSpawnAttempt = mapOf("s" to 1_000L), now = 1_000L + WARMUP + 1, warmupMs = WARMUP,
        )
        assertEquals(emptySet<String>(), snap)
    }

    @Test fun computeOpenSnapshot_absentWithNoSpawnStampIsDropped() {
        // A zombie carries no recent spawn stamp → never re-admitted.
        val snap = ClaudeTabsHelpers.computeOpenSnapshot(
            prev = setOf("z"), present = emptySet(),
            materializedSids = emptySet(), lastSpawnAttempt = emptyMap(), now = 5_000L, warmupMs = WARMUP,
        )
        assertEquals(emptySet<String>(), snap)
    }

    // ── applyRemovalDeferral (teardown-race safety net) ─────────────

    @Test fun removalDeferral_singleAbsentSidCommitsImmediately() {
        // One tab ×'d → removed this very poll. An ×'d tab must never resurrect, even if the
        // user quits right after closing it.
        val pending = mutableMapOf<String, Long>()
        val out = ClaudeTabsHelpers.applyRemovalDeferral(
            prev = setOf("a", "b", "c"), computed = setOf("a", "b"), pendingRemovals = pending, now = 1_000L,
        )
        assertEquals(setOf("a", "b"), out)
        assertTrue(pending.isEmpty())
    }

    @Test fun removalDeferral_bulkVanishIsDeferredOnePollThenCommitted() {
        // Several tabs vanish in ONE poll — the teardown signature. The first poll keeps them
        // (nothing lost if the app dies right now); a second consecutive absent poll — which only
        // runs when the teardown flags are clear — confirms and commits.
        val pending = mutableMapOf<String, Long>()
        val first = ClaudeTabsHelpers.applyRemovalDeferral(
            prev = setOf("a", "b", "c"), computed = setOf("a"), pendingRemovals = pending, now = 1_000L,
        )
        assertEquals(setOf("a", "b", "c"), first)
        assertEquals(setOf("b", "c"), pending.keys)
        val second = ClaudeTabsHelpers.applyRemovalDeferral(
            prev = first, computed = setOf("a"), pendingRemovals = pending, now = 6_000L,
        )
        assertEquals(setOf("a"), second)
        assertTrue(pending.isEmpty())
    }

    @Test fun removalDeferral_reappearingSidCancelsItsPendingRemoval() {
        val pending = mutableMapOf<String, Long>()
        ClaudeTabsHelpers.applyRemovalDeferral(
            prev = setOf("a", "b", "c"), computed = setOf("a"), pendingRemovals = pending, now = 1_000L,
        )
        // "b" is present again next poll (transient vanish); only "c" remains absent — and as a
        // SINGLE absent sid it commits.
        val out = ClaudeTabsHelpers.applyRemovalDeferral(
            prev = setOf("a", "b", "c"), computed = setOf("a", "b"), pendingRemovals = pending, now = 6_000L,
        )
        assertEquals(setOf("a", "b"), out)
        assertTrue(pending.isEmpty())
    }

    @Test fun removalDeferral_mixedFreshAndConfirmedCommitsOnlyConfirmed() {
        val pending = mutableMapOf("b" to 1_000L, "c" to 1_000L)
        // "b"/"c" were deferred by an earlier poll; "d" vanishes fresh in this bulk poll.
        val out = ClaudeTabsHelpers.applyRemovalDeferral(
            prev = setOf("a", "b", "c", "d"), computed = setOf("a"), pendingRemovals = pending, now = 6_000L,
        )
        assertEquals(setOf("a", "d"), out) // b+c committed (confirmed), d held one more poll
        assertEquals(setOf("d"), pending.keys)
    }

    @Test fun removalDeferral_noAbsenceIsANoOpAndClearsStalePending() {
        val pending = mutableMapOf("stale" to 1_000L)
        val out = ClaudeTabsHelpers.applyRemovalDeferral(
            prev = setOf("a"), computed = setOf("a", "new"), pendingRemovals = pending, now = 2_000L,
        )
        assertEquals(setOf("a", "new"), out)
        assertTrue(pending.isEmpty())
    }

    // ── Generic tab names: launcher defaults must never become a persisted user rename ──

    @Test fun genericTabName_includesAgentLauncherDefaults() {
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Claude Code"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("claude code"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Claude Code (2)"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Claude"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Local"))
        // Real names stay adoptable.
        assertTrue(!ClaudeTabsHelpers.isGenericTabName("Fix Auth Token Rotation"))
        assertTrue(!ClaudeTabsHelpers.isGenericTabName("Claude Refactor Plan"))
    }

    // ── Shell-titled tab detection: keeps FIFO/last-resort from binding a session to a shell ──

    @Test fun shellTabTitle_detectsShellDefaultsAndPromptShapes() {
        assertTrue(ClaudeTabsHelpers.titleLooksLikeShellTab(listOf("Local", null, null, null)))
        assertTrue(ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, "MINGW64:/path/to/project", "Local", null)))
        assertTrue(ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, """C:\path\to\project""", null, null)))
        assertTrue(ClaudeTabsHelpers.titleLooksLikeShellTab(listOf("Git Bash", null, null, null)))
        assertTrue(ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, "user@host /path/to/project", null, null)))
        assertTrue(ClaudeTabsHelpers.titleLooksLikeShellTab(listOf("Local (2)", null, null, null)))
    }

    @Test fun shellTabTitle_claudeishTitleAlwaysWinsOverShellSignals() {
        // The agent launcher titles its tabs "Claude Code" — never a shell, even when another
        // field carries a path-shaped application title.
        assertTrue(!ClaudeTabsHelpers.titleLooksLikeShellTab(listOf("Claude Code", "MINGW64:/path/to/project", null, null)))
        assertTrue(!ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, null, "claude", null)))
    }

    @Test fun shellTabTitle_topicTitlesAndUnreadableTitlesStayEligible() {
        // A real conversation topic is not a shell signal.
        assertTrue(!ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, "Fix Auth Token Rotation", null, null)))
        // All titles unreadable → cannot rule the tab out (bridge dark ≠ shell).
        assertTrue(!ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, null, null, null)))
        assertTrue(!ClaudeTabsHelpers.titleLooksLikeShellTab(emptyList()))
    }
}
