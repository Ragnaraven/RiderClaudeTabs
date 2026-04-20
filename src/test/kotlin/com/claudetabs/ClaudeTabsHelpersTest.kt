package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layer 1 unit tests — pure-function helpers in [ClaudeTabsHelpers].
 *
 * These lock in the logic for:
 *  - JSON field extraction + escape handling
 *  - Generic tab-name detection
 *  - Redundant-rename detection (the guard we added after seeing /resume churn)
 *  - Config parsing with lenient fallbacks
 *  - Shell-name detection
 *  - Project hash derivation
 *
 * No IntelliJ platform, no filesystem — pure JUnit.
 */
class ClaudeTabsHelpersTest {

    // ── extractJsonString ─────────────────────────────────────────

    @Test fun extractJsonString_basicKey() {
        val json = """{"sessionId":"abc-123","cwd":"/home/x"}"""
        assertEquals("abc-123", ClaudeTabsHelpers.extractJsonString(json, "sessionId"))
        assertEquals("/home/x", ClaudeTabsHelpers.extractJsonString(json, "cwd"))
    }

    @Test fun extractJsonString_missingKeyReturnsNull() {
        val json = """{"a":"1"}"""
        assertNull(ClaudeTabsHelpers.extractJsonString(json, "missing"))
    }

    @Test fun extractJsonString_handlesEscapedQuotes() {
        val json = """{"name":"Tab with \"quotes\""}"""
        assertEquals("Tab with \"quotes\"", ClaudeTabsHelpers.extractJsonString(json, "name"))
    }

    @Test fun extractJsonString_handlesEscapedBackslashes() {
        val json = """{"cwd":"C:\\Users\\Andrew"}"""
        assertEquals("C:\\Users\\Andrew", ClaudeTabsHelpers.extractJsonString(json, "cwd"))
    }

    @Test fun extractJsonString_ignoresWhitespaceAroundColon() {
        val json = """{"sessionId"  :   "abc"}"""
        assertEquals("abc", ClaudeTabsHelpers.extractJsonString(json, "sessionId"))
    }

    // ── esc ───────────────────────────────────────────────────────

    @Test fun esc_escapesBackslashAndQuote() {
        assertEquals("""C:\\Users\\Andrew""", ClaudeTabsHelpers.esc("""C:\Users\Andrew"""))
        assertEquals("""say \"hi\"""", ClaudeTabsHelpers.esc("""say "hi""""))
    }

    @Test fun esc_leavesPlainStringAlone() {
        assertEquals("Plain Text 123", ClaudeTabsHelpers.esc("Plain Text 123"))
    }

    // ── isGenericTabName ──────────────────────────────────────────

    @Test fun isGenericTabName_recognisesDefaults() {
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Local"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Local (2)"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Local (42)"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("bash"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("bash (3)"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("pwsh"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("pwsh (5)"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("PowerShell"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("cmd"))
    }

    @Test fun isGenericTabName_rejectsUserNames() {
        assertFalse(ClaudeTabsHelpers.isGenericTabName("Fix Auth Bug"))
        assertFalse(ClaudeTabsHelpers.isGenericTabName("Rider Plugin Tab Fix"))
        assertFalse(ClaudeTabsHelpers.isGenericTabName("local"))         // case-sensitive
        assertFalse(ClaudeTabsHelpers.isGenericTabName("Local-thing"))   // not the default format
        assertFalse(ClaudeTabsHelpers.isGenericTabName(""))
    }

    @Test fun isGenericTabName_trimsWhitespace() {
        assertTrue(ClaudeTabsHelpers.isGenericTabName("  Local  "))
    }

    // ── isRenameRedundant ─────────────────────────────────────────

    @Test fun isRenameRedundant_nullOrBlankCurrent_neverRedundant() {
        assertFalse(ClaudeTabsHelpers.isRenameRedundant(null, "Anything"))
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("", "Anything"))
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("   ", "Anything"))
    }

    @Test fun isRenameRedundant_genericCurrent_neverRedundant() {
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("Local", "Local"))
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("Local (2)", "Local (2)"))
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("bash", "Some Name"))
    }

    @Test fun isRenameRedundant_exactMatch() {
        assertTrue(ClaudeTabsHelpers.isRenameRedundant("Fix Auth Bug", "Fix Auth Bug"))
    }

    @Test fun isRenameRedundant_caseInsensitiveMatch() {
        assertTrue(ClaudeTabsHelpers.isRenameRedundant("Fix Auth Bug", "fix auth bug"))
        assertTrue(ClaudeTabsHelpers.isRenameRedundant("Fix Auth Bug", "FIX AUTH BUG"))
    }

    @Test fun isRenameRedundant_whitespaceNormalised() {
        assertTrue(ClaudeTabsHelpers.isRenameRedundant("Fix Auth Bug", "  Fix  Auth  Bug  "))
    }

    @Test fun isRenameRedundant_highJaccardOverlap() {
        // Very similar rewordings — Claude often picks these on resume
        assertTrue(ClaudeTabsHelpers.isRenameRedundant("Fix Auth Bug", "Fix Auth Bug Details"))
        assertTrue(ClaudeTabsHelpers.isRenameRedundant("Voice Produce Bug", "Voice Produce Bug Fix"))
    }

    @Test fun isRenameRedundant_differentTopics_notRedundant() {
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("Fix Auth Bug", "TLS Certificate Renewal"))
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("Rider Plugin", "Database Migration"))
    }

    @Test fun isRenameRedundant_singleWord_notRedundantUnlessExact() {
        assertTrue(ClaudeTabsHelpers.isRenameRedundant("Foo", "Foo"))
        // Single-word names don't hit the Jaccard branch — only exact match matters
        assertFalse(ClaudeTabsHelpers.isRenameRedundant("Foo", "Bar"))
    }

    // ── parseConfig ───────────────────────────────────────────────

    @Test fun parseConfig_missingFileReturnsDefaults() {
        val c = ClaudeTabsHelpers.parseConfig(null)
        assertEquals(ClaudeTabsHelpers.Config.DEFAULT, c)
    }

    @Test fun parseConfig_validFields() {
        val c = ClaudeTabsHelpers.parseConfig("""{"historyMaxAgeDays":30,"snapshotKeepCount":5}""")
        assertEquals(30L * 24 * 60 * 60 * 1000, c.historyMaxAgeMs)
        assertEquals(5, c.snapshotKeepCount)
    }

    @Test fun parseConfig_missingFieldsFallBackToDefaults() {
        val c = ClaudeTabsHelpers.parseConfig("""{"historyMaxAgeDays":7}""")
        assertEquals(7L * 24 * 60 * 60 * 1000, c.historyMaxAgeMs)
        assertEquals(ClaudeTabsHelpers.Config.DEFAULT.snapshotKeepCount, c.snapshotKeepCount)
    }

    @Test fun parseConfig_zeroSnapshotsDisablesRotation() {
        val c = ClaudeTabsHelpers.parseConfig("""{"snapshotKeepCount":0}""")
        assertEquals(0, c.snapshotKeepCount)
    }

    @Test fun parseConfig_negativeHistoryIgnored() {
        // Negative history days are ignored — default applies.
        val c = ClaudeTabsHelpers.parseConfig("""{"historyMaxAgeDays":-1}""")
        assertEquals(ClaudeTabsHelpers.Config.DEFAULT.historyMaxAgeMs, c.historyMaxAgeMs)
    }

    @Test fun parseConfig_malformedReturnsDefaults() {
        val c = ClaudeTabsHelpers.parseConfig("not valid json at all {{}}")
        assertEquals(ClaudeTabsHelpers.Config.DEFAULT, c)
    }

    // ── isShellCommand ────────────────────────────────────────────

    @Test fun isShellCommand_recognisesShells() {
        assertTrue(ClaudeTabsHelpers.isShellCommand("/usr/bin/bash"))
        assertTrue(ClaudeTabsHelpers.isShellCommand("C:\\Windows\\System32\\cmd.exe"))
        assertTrue(ClaudeTabsHelpers.isShellCommand("pwsh.exe"))
        assertTrue(ClaudeTabsHelpers.isShellCommand("POWERSHELL.EXE"))  // case-insensitive
        assertTrue(ClaudeTabsHelpers.isShellCommand("zsh"))
        assertTrue(ClaudeTabsHelpers.isShellCommand("fish"))
    }

    @Test fun isShellCommand_rejectsNonShells() {
        assertFalse(ClaudeTabsHelpers.isShellCommand("node"))
        assertFalse(ClaudeTabsHelpers.isShellCommand("/usr/local/bin/claude"))
        assertFalse(ClaudeTabsHelpers.isShellCommand("rider64.exe"))
    }

    // ── projectHashForPath ────────────────────────────────────────

    @Test fun projectHash_stableForSamePath() {
        val a = ClaudeTabsHelpers.projectHashForPath("D:\\Dev\\OrOrbit")
        val b = ClaudeTabsHelpers.projectHashForPath("D:\\Dev\\OrOrbit")
        assertEquals(a, b)
    }

    @Test fun projectHash_normalisesSeparators() {
        val fwd = ClaudeTabsHelpers.projectHashForPath("D:/Dev/OrOrbit")
        val bwd = ClaudeTabsHelpers.projectHashForPath("D:\\Dev\\OrOrbit")
        assertEquals(fwd, bwd)
    }

    @Test fun projectHash_nullPath_usesDefault() {
        assertEquals("default", ClaudeTabsHelpers.projectHashForPath(null))
    }

    @Test fun projectHash_containsNoFilesystemReservedChars() {
        val hash = ClaudeTabsHelpers.projectHashForPath("D:\\Dev\\OrOrbit")
        // Expect no `:`, `\`, `/` — safe to use as a filename
        assertFalse(hash.contains(':'))
        assertFalse(hash.contains('\\'))
        assertFalse(hash.contains('/'))
    }
}
