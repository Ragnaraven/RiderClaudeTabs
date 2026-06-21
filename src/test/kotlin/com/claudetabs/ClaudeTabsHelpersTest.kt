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
}
