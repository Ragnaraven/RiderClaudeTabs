package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure identity contract of the v3 own-the-terminal model:
 *  1. NEW sessions assign a caller-minted id (`claude --session-id <uuid>`); RESUME reuses a known
 *     id (`claude --resume <sid>`). Nothing else is ever appended.
 *  2. Hand-open adoption is strictly 1:1 — any ambiguity binds nothing.
 */
class OwnedTerminalSpawnerTest {

    @Test fun newSession_assignsMintedId() {
        assertEquals(
            "claude --session-id 11111111-2222-4333-8444-555555555555",
            OwnedTerminalSpawner.launchCommand("11111111-2222-4333-8444-555555555555", OwnedTerminalSpawner.Mode.NEW),
        )
    }

    @Test fun resume_reusesKnownId() {
        assertEquals(
            "claude --resume abc-sid",
            OwnedTerminalSpawner.launchCommand("abc-sid", OwnedTerminalSpawner.Mode.RESUME),
        )
    }

    @Test fun launchCommand_neverAddsExtraFlags() {
        // Defence-in-depth: the command is exactly two tokens + the id. If a future change appends a
        // global flag (e.g. --dangerously-skip-permissions) this fails and must be deliberate.
        val n = OwnedTerminalSpawner.launchCommand("s", OwnedTerminalSpawner.Mode.NEW)
        val r = OwnedTerminalSpawner.launchCommand("s", OwnedTerminalSpawner.Mode.RESUME)
        assertEquals(3, n.split(" ").size)
        assertEquals(3, r.split(" ").size)
        assertFalse(n.contains("--dangerously-skip-permissions"))
        assertFalse(r.contains("--dangerously-skip-permissions"))
    }

    @Test fun isMintedUuid_recognisesV4Only() {
        assertTrue(OwnedTerminalSpawner.isMintedUuid("11111111-2222-4333-8444-555555555555"))
        assertTrue(OwnedTerminalSpawner.isMintedUuid(java.util.UUID.randomUUID().toString()))
        assertFalse(OwnedTerminalSpawner.isMintedUuid("not-a-uuid"))
        // v1-shaped (version nibble != 4) is not what we mint.
        assertFalse(OwnedTerminalSpawner.isMintedUuid("11111111-2222-1333-8444-555555555555"))
    }

    @Test fun pairUniqueByKey_bindsOnlyUnambiguousOneToOne() {
        val sessions = mapOf(
            "/a" to listOf("sidA"),          // 1 session
            "/b" to listOf("sidB1", "sidB2"), // 2 sessions — ambiguous
            "/c" to listOf("sidC"),          // 1 session but 2 tabs — ambiguous
        )
        val tabs = mapOf(
            "/a" to listOf("tabA"),
            "/b" to listOf("tabB"),
            "/c" to listOf("tabC1", "tabC2"),
        )
        val pairs = OwnedTerminalSpawner.pairUniqueByKey(sessions, tabs)
        assertEquals(mapOf("tabA" to "sidA"), pairs)
    }

    @Test fun pairUniqueByKey_ignoresKeysMissingOnEitherSide() {
        val sessions = mapOf("/a" to listOf("sidA"), "/x" to listOf("sidX"))
        val tabs = mapOf("/a" to listOf("tabA"), "/y" to listOf("tabY"))
        assertEquals(mapOf("tabA" to "sidA"), OwnedTerminalSpawner.pairUniqueByKey(sessions, tabs))
    }

    // ── Direct-launch detection + session-id injection guards (the AI-agents-button shape) ──

    @Test fun isDirectClaudeCommand_matchesBasenameAnyPathAnyCase() {
        assertTrue(OwnedTerminalSpawner.isDirectClaudeCommand(listOf("claude")))
        assertTrue(OwnedTerminalSpawner.isDirectClaudeCommand(listOf("""C:\path\to\claude.exe""")))
        assertTrue(OwnedTerminalSpawner.isDirectClaudeCommand(listOf("/usr/local/bin/claude")))
        assertTrue(OwnedTerminalSpawner.isDirectClaudeCommand(listOf("""C:\p\CLAUDE.CMD""", "--verbose")))
        assertFalse(OwnedTerminalSpawner.isDirectClaudeCommand(listOf("""C:\path\to\bash.exe""")))
        // A shell that merely RUNS claude is not a direct launch — the argv belongs to the shell.
        assertFalse(OwnedTerminalSpawner.isDirectClaudeCommand(listOf("bash", "-c", "claude")))
        assertFalse(OwnedTerminalSpawner.isDirectClaudeCommand(emptyList()))
        // Similar-named binary must not match.
        assertFalse(OwnedTerminalSpawner.isDirectClaudeCommand(listOf("claude-helper.exe")))
    }

    @Test fun alreadyPinsSession_blocksEveryPinningShape() {
        assertTrue(OwnedTerminalSpawner.alreadyPinsSession(listOf("claude", "--session-id", "x")))
        assertTrue(OwnedTerminalSpawner.alreadyPinsSession(listOf("claude", "--session-id=x")))
        assertTrue(OwnedTerminalSpawner.alreadyPinsSession(listOf("claude", "--resume", "x")))
        assertTrue(OwnedTerminalSpawner.alreadyPinsSession(listOf("claude", "--resume=x")))
        assertTrue(OwnedTerminalSpawner.alreadyPinsSession(listOf("claude", "-r")))
        assertTrue(OwnedTerminalSpawner.alreadyPinsSession(listOf("claude", "--continue")))
        assertTrue(OwnedTerminalSpawner.alreadyPinsSession(listOf("claude", "-c")))
        assertFalse(OwnedTerminalSpawner.alreadyPinsSession(listOf("claude")))
        assertFalse(OwnedTerminalSpawner.alreadyPinsSession(listOf("claude", "--verbose")))
    }

    @Test fun sessionIdFromCommand_readsBackInjectedAndResumeIds() {
        val sid = "11111111-2222-4333-8444-555555555555"
        assertEquals(sid, OwnedTerminalSpawner.sessionIdFromCommand(listOf("claude.exe", "--session-id", sid)))
        assertEquals(sid, OwnedTerminalSpawner.sessionIdFromCommand(listOf("claude", "--session-id=$sid")))
        assertEquals(sid, OwnedTerminalSpawner.sessionIdFromCommand(listOf("claude", "--resume", sid)))
        assertEquals(sid, OwnedTerminalSpawner.sessionIdFromCommand(listOf("claude", "--resume=$sid")))
        assertEquals(sid, OwnedTerminalSpawner.sessionIdFromCommand(listOf("claude", "-r", sid)))
    }

    @Test fun sessionIdFromCommand_rejectsNonUuidAndAbsentValues() {
        assertEquals(null, OwnedTerminalSpawner.sessionIdFromCommand(null))
        assertEquals(null, OwnedTerminalSpawner.sessionIdFromCommand(listOf("claude")))
        assertEquals(null, OwnedTerminalSpawner.sessionIdFromCommand(listOf("bash", "-l")))
        // Flag present but the value is not a UUID — must not bind to garbage.
        assertEquals(null, OwnedTerminalSpawner.sessionIdFromCommand(listOf("claude", "--session-id", "oops")))
        // Trailing flag with no value.
        assertEquals(null, OwnedTerminalSpawner.sessionIdFromCommand(listOf("claude", "--resume")))
    }

    @Test fun injectionRoundTrip_injectedCommandReadsBackTheSameSid() {
        // The contract the customizer + exact-adopt pass rely on: append the two tokens, read the
        // same sid back from the widget's shell command.
        val sid = java.util.UUID.randomUUID().toString()
        val launched = listOf("""C:\bin\claude.exe""") + listOf("--session-id", sid)
        assertTrue(OwnedTerminalSpawner.alreadyPinsSession(launched))
        assertEquals(sid, OwnedTerminalSpawner.sessionIdFromCommand(launched))
    }
}
