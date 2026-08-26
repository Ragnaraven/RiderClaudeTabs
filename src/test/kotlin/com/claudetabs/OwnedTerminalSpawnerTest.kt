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
 *  3. The two EXACT adoption passes, which bind on a stored/OS fact instead of a correlation:
 *     the tab's own launch argv naming the session, and the session's process descending from the
 *     tab's pty root. Ambiguity still refuses; a session from a foreign SESSION ROOT is never
 *     ancestry-bound, because its pid lives in a different pid namespace and can collide.
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

    /** Run [body] with `claudetabs.claudeCommand` set to [value], restoring the previous state. */
    private fun withClaudeCommand(value: String, body: () -> Unit) {
        val key = "claudetabs.claudeCommand"
        val previous = System.getProperty(key)
        System.setProperty(key, value)
        try { body() } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }

    @Test fun claudeCommandOverride_replacesTheBareExecutable() {
        // Test-isolation hook only: it lets a sandboxed run pin a stub CLI by absolute path
        // instead of trusting the shell's PATH.
        withClaudeCommand("C:/tools/claude-stub.cmd") {
            assertEquals(
                "C:/tools/claude-stub.cmd --session-id sid-1",
                OwnedTerminalSpawner.launchCommand("sid-1", OwnedTerminalSpawner.Mode.NEW),
            )
            assertEquals(
                "C:/tools/claude-stub.cmd --resume sid-1",
                OwnedTerminalSpawner.launchCommand("sid-1", OwnedTerminalSpawner.Mode.RESUME),
            )
        }
    }

    @Test fun claudeCommandOverride_isQuotedWhenItContainsASpace() {
        withClaudeCommand("C:/tool dir/claude-stub.cmd") {
            assertEquals(
                "\"C:/tool dir/claude-stub.cmd\" --resume sid-1",
                OwnedTerminalSpawner.launchCommand("sid-1", OwnedTerminalSpawner.Mode.RESUME),
            )
        }
    }

    @Test fun withoutOverride_theCommandStartsWithBareClaude() {
        val previous = System.getProperty("claudetabs.claudeCommand")
        System.clearProperty("claudetabs.claudeCommand")
        try {
            assertTrue(OwnedTerminalSpawner.launchCommand("s", OwnedTerminalSpawner.Mode.NEW).startsWith("claude "))
            assertTrue(OwnedTerminalSpawner.launchCommand("s", OwnedTerminalSpawner.Mode.RESUME).startsWith("claude "))
        } finally {
            if (previous != null) System.setProperty("claudetabs.claudeCommand", previous)
        }
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

    @Test fun pairUniqueByKey_resolvesTheShellFilteredEverydayShape() {
        // The everyday shape once a reworked tab reports its cwd: the project directory holds the
        // Claude tab plus a plain shell the user keeps open there. Shell-titled tabs are filtered
        // OUT of the pool before this runs (they can never receive a session), so what reaches the
        // strict rule is one session and one candidate tab — no tie-break needed.
        val sessions = mapOf("/path/to/project" to listOf("sidA"))
        val tabsAfterShellFilter = mapOf("/path/to/project" to listOf("tabClaude"))
        assertEquals(
            mapOf("tabClaude" to "sidA"),
            OwnedTerminalSpawner.pairUniqueByKey(sessions, tabsAfterShellFilter),
        )
        // Two plausible (non-shell) tabs remain ambiguous and bind nothing.
        val twoTabs = mapOf("/path/to/project" to listOf("tabOne", "tabTwo"))
        assertTrue(OwnedTerminalSpawner.pairUniqueByKey(sessions, twoTabs).isEmpty())
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

    @Test fun sessionIdFromCommandLine_parsesRawProcessArgvString() {
        val sid = "11111111-2222-4333-8444-555555555555"
        // ProcessHandle.info().commandLine() form: one whitespace-joined string.
        assertEquals(sid, OwnedTerminalSpawner.sessionIdFromCommandLine("""C:\path\to\claude.exe --session-id $sid"""))
        assertEquals(sid, OwnedTerminalSpawner.sessionIdFromCommandLine("claude --resume $sid"))
        assertEquals(sid, OwnedTerminalSpawner.sessionIdFromCommandLine("claude --session-id=$sid"))
        assertEquals(sid, OwnedTerminalSpawner.sessionIdFromCommandLine("  claude   -r   $sid  "))
    }

    @Test fun sessionIdFromCommandLine_rejectsBlankPlainAndNonUuid() {
        assertEquals(null, OwnedTerminalSpawner.sessionIdFromCommandLine(null))
        assertEquals(null, OwnedTerminalSpawner.sessionIdFromCommandLine(""))
        assertEquals(null, OwnedTerminalSpawner.sessionIdFromCommandLine("""C:\path\to\claude.exe"""))
        assertEquals(null, OwnedTerminalSpawner.sessionIdFromCommandLine("claude --session-id not-a-uuid"))
        assertEquals(null, OwnedTerminalSpawner.sessionIdFromCommandLine("some-other-tool --resume 12345"))
    }

    // ── (A) Argv session id — the exact pass that reads a tab's own launch argv ──────────────

    @Test fun argvPass_bindsTabWhoseLaunchArgvNamesTheSession() {
        // The reworked tab's startup-options argv, exactly as the bridge reports it.
        val sid = "11111111-1111-4111-8111-111111111111"
        assertEquals(
            sid,
            OwnedTerminalSpawner.sessionIdFromCommand(listOf("""C:\bin\claude.exe""", "--session-id", sid)),
        )
        // A hand-typed resume in an agent tab carries the id the same way.
        assertEquals(sid, OwnedTerminalSpawner.sessionIdFromCommand(listOf("claude", "--resume", sid)))
    }

    @Test fun argvPass_doesNotBindArgvWithoutASessionIdFlag() {
        // A plain shell tab, and a plain `claude` with no session pinned: nothing to bind to.
        assertEquals(null, OwnedTerminalSpawner.sessionIdFromCommand(listOf("""C:\Windows\System32\cmd.exe""")))
        assertEquals(null, OwnedTerminalSpawner.sessionIdFromCommand(listOf("claude")))
        assertEquals(null, OwnedTerminalSpawner.sessionIdFromCommand(listOf("claude", "--verbose")))
        assertEquals(null, OwnedTerminalSpawner.sessionIdFromCommand(null))
    }

    // ── (B) Pid ancestry — the exact pass that adopts a hand-typed `claude` ──────────────────

    private fun winSession(sid: String, pid: Long) =
        OwnedTerminalSpawner.AncestrySession(sid, pid, "windows")

    private fun tab(name: String, ptyRootPid: Long?) =
        OwnedTerminalSpawner.AncestryTab(name, ptyRootPid)

    /** Parent lookup from a plain child→parent map. */
    private fun parents(vararg pairs: Pair<Long, Long>): (Long) -> Long? {
        val m = pairs.toMap()
        return { pid -> m[pid] }
    }

    @Test fun ancestry_bindsSessionWhoseParentIsTheTabsPtyRoot() {
        // The everyday shape: the user types `claude` into Tab A's shell (pty root pid 100); the
        // CLI (pid 101) is its child.
        val pairs = OwnedTerminalSpawner.pairByPidAncestry(
            sessions = listOf(winSession("11111111-1111-4111-8111-111111111111", 101L)),
            tabs = listOf(tab("Tab A", 100L), tab("Tab B", 200L)),
            parentOf = parents(101L to 100L),
        )
        assertEquals(mapOf("Tab A" to "11111111-1111-4111-8111-111111111111"), pairs)
    }

    @Test fun ancestry_bindsWhenTheTabsPtyRootIsTheSessionProcessItself() {
        // The agent-button shape: claude IS the pty root, so the walk matches at hop 0.
        val pairs = OwnedTerminalSpawner.pairByPidAncestry(
            sessions = listOf(winSession("11111111-1111-4111-8111-111111111111", 100L)),
            tabs = listOf(tab("Tab A", 100L)),
            parentOf = parents(),
        )
        assertEquals(mapOf("Tab A" to "11111111-1111-4111-8111-111111111111"), pairs)
    }

    @Test fun ancestry_bindsMultiHopChainWithinTheBudgetOnly() {
        // 100 is the pty root; the session process sits N hops below it (shell → wrappers → CLI).
        val chain = parents(
            106L to 105L, 105L to 104L, 104L to 103L,
            103L to 102L, 102L to 101L, 101L to 100L,
        )
        val sid = "11111111-1111-4111-8111-111111111111"
        // 106 → 100 is exactly 6 parent lookups — the budget.
        assertEquals(
            mapOf("Tab A" to sid),
            OwnedTerminalSpawner.pairByPidAncestry(
                sessions = listOf(winSession(sid, 106L)),
                tabs = listOf(tab("Tab A", 100L)),
                parentOf = chain,
                maxHops = 6,
            ),
        )
        // One hop short of reaching it: refuses rather than guessing.
        assertTrue(
            OwnedTerminalSpawner.pairByPidAncestry(
                sessions = listOf(winSession(sid, 106L)),
                tabs = listOf(tab("Tab A", 100L)),
                parentOf = chain,
                maxHops = 5,
            ).isEmpty(),
        )
    }

    @Test fun ancestry_refusesWhenTwoTabsMatchTheSameSessionsAncestry() {
        // Pathological but decidable: two tabs report pids that both sit in one session's chain.
        // Ambiguity binds NOTHING, exactly like the strict by-cwd rule.
        val pairs = OwnedTerminalSpawner.pairByPidAncestry(
            sessions = listOf(winSession("11111111-1111-4111-8111-111111111111", 102L)),
            tabs = listOf(tab("Tab A", 101L), tab("Tab B", 100L)),
            parentOf = parents(102L to 101L, 101L to 100L),
        )
        assertTrue(pairs.isEmpty())
    }

    @Test fun ancestry_refusesWhenTwoSessionsClaimTheSameTab() {
        val pairs = OwnedTerminalSpawner.pairByPidAncestry(
            sessions = listOf(
                winSession("11111111-1111-4111-8111-111111111111", 101L),
                winSession("22222222-2222-4222-8222-222222222222", 102L),
            ),
            tabs = listOf(tab("Tab A", 100L)),
            parentOf = parents(101L to 100L, 102L to 100L),
        )
        assertTrue(pairs.isEmpty())
    }

    @Test fun ancestry_terminatesOnCycleAndSelfParent() {
        // A cycle (a ↔ b) and a self-parent must end the walk, not spin the EDT forever. The call
        // returning at all IS the assertion; the lookup counter pins that it stayed bounded.
        var lookups = 0
        val cyclic = { pid: Long ->
            lookups++
            when (pid) {
                101L -> 102L
                102L -> 101L   // cycle back
                201L -> 201L   // self-parent
                else -> null
            }
        }
        assertTrue(
            OwnedTerminalSpawner.pairByPidAncestry(
                sessions = listOf(
                    winSession("11111111-1111-4111-8111-111111111111", 101L),
                    winSession("22222222-2222-4222-8222-222222222222", 201L),
                ),
                tabs = listOf(tab("Tab A", 999L)),
                parentOf = cyclic,
            ).isEmpty(),
        )
        // 101→102, 102→(101, already seen) = 2 lookups; 201→(itself, already seen) = 1.
        assertEquals(3, lookups)
    }

    @Test fun ancestry_neverBindsAForeignRootSessionEvenWhenPidsCollide() {
        // THE CROSS-NAMESPACE HAZARD. A WSL session's pid is a LINUX pid; it can equal an unrelated
        // Windows pty-root pid by pure coincidence. Binding on that would attach one account's
        // session to another root's tab, so a non-Windows-root session is never walked at all.
        val wsl = OwnedTerminalSpawner.AncestrySession(
            "11111111-1111-4111-8111-111111111111", 100L, "wsl:distro:user",
        )
        assertTrue(
            OwnedTerminalSpawner.pairByPidAncestry(
                sessions = listOf(wsl),
                tabs = listOf(tab("Tab A", 100L)),   // numerically identical pid
                parentOf = parents(),
            ).isEmpty(),
        )
        // Same pid shape, Windows root → binds. Proves the refusal above is the ROOT gate and not
        // an unrelated failure of the matcher.
        assertEquals(
            mapOf("Tab A" to "11111111-1111-4111-8111-111111111111"),
            OwnedTerminalSpawner.pairByPidAncestry(
                sessions = listOf(wsl.copy(rootId = "windows")),
                tabs = listOf(tab("Tab A", 100L)),
                parentOf = parents(),
            ),
        )
        // A null root id is the pre-3.1 persisted shape and means Windows.
        assertEquals(
            mapOf("Tab A" to "11111111-1111-4111-8111-111111111111"),
            OwnedTerminalSpawner.pairByPidAncestry(
                sessions = listOf(wsl.copy(rootId = null)),
                tabs = listOf(tab("Tab A", 100L)),
                parentOf = parents(),
            ),
        )
    }

    @Test fun ancestry_ignoresTabsAndSessionsWithNoKnownPid() {
        // A remote / WSL-adapter tab reports no pty-root pid, and a session may be seen before its
        // pid is known. Neither may become a match — and neither may throw.
        val sid = "11111111-1111-4111-8111-111111111111"
        assertTrue(
            OwnedTerminalSpawner.pairByPidAncestry(
                sessions = listOf(winSession(sid, 101L)),
                tabs = listOf(tab("Tab A", null)),
                parentOf = parents(101L to 100L),
            ).isEmpty(),
        )
        assertTrue(
            OwnedTerminalSpawner.pairByPidAncestry(
                sessions = listOf(OwnedTerminalSpawner.AncestrySession(sid, null, "windows")),
                tabs = listOf(tab("Tab A", 100L)),
                parentOf = parents(),
            ).isEmpty(),
        )
    }

    @Test fun ancestry_survivesAParentLookupThatThrows() {
        // The real lookup is ProcessHandle-backed and can throw on a vanished/inaccessible process.
        // The walk must end quietly, never propagate into the binding pass.
        val pairs = OwnedTerminalSpawner.pairByPidAncestry(
            sessions = listOf(winSession("11111111-1111-4111-8111-111111111111", 101L)),
            tabs = listOf(tab("Tab A", 100L)),
            parentOf = { throw IllegalStateException("process gone") },
        )
        assertTrue(pairs.isEmpty())
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
