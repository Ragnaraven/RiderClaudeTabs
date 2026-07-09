package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SettingsPermissions.rewriteAllowArray] — the settings.json permission editor.
 *
 * Regression focus: a 1.x build removed entries with whitespace-fragile string replacement that
 * only matched a single-line `"a", "b"` separator. On a pretty-printed (multi-line) file that left
 * a dangling comma and broke the whole file's JSON on the 1.8 → 2.0 upgrade. The rebuild approach
 * must (1) remove cleanly with no stray comma, (2) repair a file already left broken, and (3) be a
 * no-op when nothing needs changing.
 */
class SettingsPermissionsTest {

    private val LEGACY = setOf(
        "Bash(bash ~/.claude/rider-plugin/rename-tab.sh *)",
        "Bash(node ~/.claude/rider-plugin/tab-now.js)",
    )
    private val CURRENT = listOf(
        "Bash(node ~/.claude/rider-plugin/backup-active.js)",
        "Bash(node ~/.claude/rider-plugin/tab-name.js *)",
    )

    /** Crude validity check sufficient for these arrays: no `,]`, `[,`, or `,,`. Parses through
     *  the production regex (SettingsPermissions.allowArrayBody) so the test can't drift. */
    private fun assertValidArray(text: String) {
        val body = SettingsPermissions.allowArrayBody(text)!!
        assertFalse("trailing comma: <$body>", body.trim().endsWith(","))
        assertFalse("leading comma: <$body>", body.trim().startsWith(","))
        assertFalse("double comma: <$body>", Regex(",\\s*,").containsMatchIn(body))
    }

    private fun entriesOf(text: String): List<String> = SettingsPermissions.parseAllowEntries(text)!!

    // ── the actual regression ───────────────────────────────────────────

    @Test fun removingLegacyFromPrettyPrintedArray_leavesNoDanglingComma() {
        val input = """
            {
              "permissions": {
                "allow": [
                  "Bash(bash ~/.claude/rider-plugin/rename-tab.sh *)",
                  "Bash(node ~/.claude/rider-plugin/backup-active.js)"
                ]
              }
            }
        """.trimIndent()
        val out = SettingsPermissions.rewriteAllowArray(input, LEGACY, CURRENT)!!
        assertValidArray(out)
        val entries = entriesOf(out)
        assertFalse("legacy entry must be gone", entries.any { it in LEGACY })
        assertTrue("current entries must be present", entries.containsAll(CURRENT))
    }

    @Test fun removingLastLegacyEntry_doesNotStrandTrailingComma() {
        // Legacy entry is LAST — the case that strands a `,` before `]` under naive replacement.
        val input = """
            { "permissions": { "allow": [
                "Bash(node ~/.claude/rider-plugin/backup-active.js)",
                "Bash(node ~/.claude/rider-plugin/tab-name.js *)",
                "Bash(node ~/.claude/rider-plugin/tab-now.js)"
            ] } }
        """.trimIndent()
        val out = SettingsPermissions.rewriteAllowArray(input, LEGACY, CURRENT)!!
        assertValidArray(out)
        assertFalse(entriesOf(out).contains("Bash(node ~/.claude/rider-plugin/tab-now.js)"))
    }

    // ── self-repair of an already-broken file ───────────────────────────

    @Test fun repairsTrailingCommaLeftByOlderBuild() {
        // Exactly what the broken 1.8→2.0 upgrade produced: entry's string removed, comma left.
        val input = """
            {
              "permissions": {
                "allow": [
                  "Bash(node ~/.claude/rider-plugin/backup-active.js)",
                  "Bash(node ~/.claude/rider-plugin/tab-name.js *)",
                ]
              }
            }
        """.trimIndent()
        val out = SettingsPermissions.rewriteAllowArray(input, LEGACY, CURRENT)
        assertTrue("malformed body must trigger a rewrite", out != null)
        assertValidArray(out!!)
        assertTrue(entriesOf(out).containsAll(CURRENT))
    }

    @Test fun userEntryContainingBracket_doesNotTruncateOrCorrupt() {
        // A `]` inside a quoted user entry (glob character class) must not end the array match —
        // a naive body pattern truncates there and the rewrite corrupts the whole file.
        val bracketEntry = "Bash(ls src/[a-z]*)"
        val input = """
            { "permissions": { "allow": [
                "$bracketEntry",
                "Bash(node ~/.claude/rider-plugin/tab-now.js)"
            ] } }
        """.trimIndent()
        val out = SettingsPermissions.rewriteAllowArray(input, LEGACY, CURRENT)!!
        assertValidArray(out)
        val entries = entriesOf(out)
        assertTrue("bracket entry must survive intact", entries.contains(bracketEntry))
        assertFalse(entries.contains("Bash(node ~/.claude/rider-plugin/tab-now.js)"))
        assertTrue(entries.containsAll(CURRENT))
    }

    @Test fun repairsDoubleComma() {
        val input = """{ "permissions": { "allow": [ "Bash(node ~/.claude/rider-plugin/backup-active.js)", , "Bash(node ~/.claude/rider-plugin/tab-name.js *)" ] } }"""
        val out = SettingsPermissions.rewriteAllowArray(input, LEGACY, CURRENT)
        assertTrue(out != null)
        assertValidArray(out!!)
    }

    // ── add / no-op / absent ────────────────────────────────────────────

    @Test fun addsMissingCurrentEntriesToEmptyArray() {
        val input = """{ "permissions": { "allow": [] } }"""
        val out = SettingsPermissions.rewriteAllowArray(input, LEGACY, CURRENT)!!
        assertValidArray(out)
        assertEquals(CURRENT, entriesOf(out))
    }

    @Test fun noChangeWhenAllCurrentPresentAndNoLegacy_returnsNull() {
        val input = """
            {
              "permissions": {
                "allow": [
                  "Bash(node ~/.claude/rider-plugin/backup-active.js)",
                  "Bash(node ~/.claude/rider-plugin/tab-name.js *)"
                ]
              }
            }
        """.trimIndent()
        assertNull(SettingsPermissions.rewriteAllowArray(input, LEGACY, CURRENT))
    }

    @Test fun noAllowArray_returnsNull() {
        assertNull(SettingsPermissions.rewriteAllowArray("""{ "model": "opus" }""", LEGACY, CURRENT))
    }

    @Test fun preservesUnrelatedEntriesAndOrder() {
        val keep = "Bash(git status)"
        val input = """
            { "permissions": { "allow": [
                "$keep",
                "Bash(node ~/.claude/rider-plugin/tab-now.js)",
                "Bash(node ~/.claude/rider-plugin/backup-active.js)"
            ] } }
        """.trimIndent()
        val out = SettingsPermissions.rewriteAllowArray(input, LEGACY, CURRENT)!!
        val entries = entriesOf(out)
        assertEquals("unrelated entry kept first, in order", keep, entries.first())
        assertFalse(entries.contains("Bash(node ~/.claude/rider-plugin/tab-now.js)"))
    }

    @Test fun uninstall_removesAllPluginEntries_keepsUserEntries() {
        val keep = "Bash(git status)"
        val input = """
            { "permissions": { "allow": [
                "$keep",
                "Bash(node ~/.claude/rider-plugin/backup-active.js)",
                "Bash(node ~/.claude/rider-plugin/tab-name.js *)"
            ] } }
        """.trimIndent()
        val out = SettingsPermissions.rewriteAllowArray(
            input, remove = (LEGACY + CURRENT).toSet(), add = emptyList(),
        )!!
        assertValidArray(out)
        assertEquals(listOf(keep), entriesOf(out))
    }
}
