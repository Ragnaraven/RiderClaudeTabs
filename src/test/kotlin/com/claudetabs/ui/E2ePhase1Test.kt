package com.claudetabs.ui

import com.claudetabs.ui.E2eUtil.activateTerminal
import com.claudetabs.ui.E2eUtil.activeSessions
import com.claudetabs.ui.E2eUtil.await
import com.claudetabs.ui.E2eUtil.closeTerminalTab
import com.claudetabs.ui.E2eUtil.connect
import com.claudetabs.ui.E2eUtil.exitIde
import com.claudetabs.ui.E2eUtil.invokeAction
import com.claudetabs.ui.E2eUtil.projectOpen
import com.claudetabs.ui.E2eUtil.snapshotSids
import com.claudetabs.ui.E2eUtil.terminalTabTitles
import com.claudetabs.ui.E2eUtil.writeExpectations
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 1 of the pre/post-restart E2E (driver: `e2e/run-e2e.ps1`).
 *
 * Scenario (the field acceptance script, automated):
 *  1. Windows-root claude tab 1 via the plugin's owned action  → should be in the snapshot.
 *  2. Windows-root claude tab 2 the same way                   → in the snapshot, then ×'d.
 *  3. Fake-root ("WSL-shaped") claude tab 1, hand-typed        → adopted, in the snapshot.
 *  4. Fake-root claude tab 2, hand-typed                       → adopted, then ×'d.
 *  5. × both tab 2s → snapshot must shrink to exactly {win1, fake1}.
 *  6. Hand-opened tab resuming a session whose id never reaches the tab's command line
 *     → must still be adopted, snapshotted, and renameable; stays OPEN at quit.
 *  7. Exit the IDE (committed exit → exercises the appClosing snapshot freeze).
 * Phase 2 (after the driver relaunches the IDE) asserts the restore side.
 */
class E2ePhase1Test {

    /** The name the simulated `/tab` writes — must survive the restart as the tab's title. */
    private val HAND_USER_NAME = "Hand Renamed"

    @Test fun openCloseAndQuit() {
        val robot = connect()
        await("project open", 300_000) { projectOpen(robot) }
        // Let startup settle (restore scan of the empty home, terminal tool window registration).
        Thread.sleep(10_000)

        fun sidsByRoot(root: String) = activeSessions().filter { it.root.startsWith(root) }.map { it.sid }

        // 1. Windows tab 1 — the plugin's owned path (`<stub> --session-id <minted>`; the stub is
        //    pinned by absolute path through -Dclaudetabs.claudeCommand, never via PATH).
        invokeAction(robot, "ClaudeTabs.NewSession")
        // ISOLATION GATE: the very first owned spawn must land in the STUB. If it does not, the
        // sandbox just ran whatever `claude` its shell could find — possibly the machine's real
        // CLI — and no assertion downstream is worth trusting. Fail here, and exit the IDE first
        // so the driver's relaunch/cleanup is not left fighting an orphaned sandbox.
        try {
            await("stub launcher invoked by the first owned spawn", 30_000) {
                E2eUtil.stubLaunches().isNotEmpty()
            }
        } catch (e: AssertionError) {
            exitIde(robot)
            throw AssertionError(
                "stub launcher never invoked — the real CLI may have been launched; aborting", e,
            )
        }
        await("win session 1 registered", 90_000) { sidsByRoot("windows").size == 1 }
        val win1 = sidsByRoot("windows").single()
        await("win1 in snapshot", 60_000) { win1 in snapshotSids() }

        // 2. Windows tab 2.
        invokeAction(robot, "ClaudeTabs.NewSession")
        await("win session 2 registered", 90_000) { sidsByRoot("windows").size == 2 }
        val win2 = (sidsByRoot("windows") - win1).single()
        await("win2 in snapshot", 60_000) { win2 in snapshotSids() }

        // 3. Fake-root tab 1 — hand-open shape: a plain terminal tab (no birth binding) in which
        //    the fake root's claude runs. The plugin must adopt it 1:1 (fresh session ↔ new tab).
        activateTerminal(robot)
        Thread.sleep(2_000)
        E2eUtil.openHandTabRunning(robot, E2eUtil.binCommand("claude-fake.cmd"))
        await("fake session 1 registered", 90_000) { sidsByRoot("fake:distroA").size == 1 }
        val fake1 = sidsByRoot("fake:distroA").single()
        await("fake1 adopted into snapshot", 60_000) { fake1 in snapshotSids() }

        // 4. Fake-root tab 2 — sequential, so the 1:1 rule has exactly one fresh pairing.
        await("fake1 bound before opening fake2", 30_000) { fake1 in snapshotSids() }
        E2eUtil.openHandTabRunning(robot, E2eUtil.binCommand("claude-fake.cmd"))
        await("fake session 2 registered", 90_000) { sidsByRoot("fake:distroA").size == 2 }
        val fake2 = (sidsByRoot("fake:distroA") - fake1).single()
        await("fake2 adopted into snapshot", 60_000) { fake2 in snapshotSids() }

        println("[e2e] titles before close: ${terminalTabTitles(robot)}")
        println("[e2e] snapshot before close: ${snapshotSids()}")

        // 5. × both tab 2s. FIRST ask each tab-2 stub to exit via the kill-file control channel:
        //    a dead process means the terminal shows NO terminate-confirmation modal — that modal
        //    blocks the EDT and with it every robot call (field-observed in bring-up). The tabs
        //    themselves must STAY open and snapshotted through the process death (death is never
        //    a close) — pinned here before the actual ×.
        E2eUtil.killStub("windows", E2eUtil.pidOf(win2))
        E2eUtil.killStub("fake:distroA", E2eUtil.pidOf(fake2))
        await("all four tabs still snapshotted after process deaths", 30_000) {
            setOf(win1, win2, fake1, fake2).all { it in snapshotSids() }
        }
        val titles = terminalTabTitles(robot)
        // fake2's tab is the last created terminal tab; win2's is the second Claude tab.
        closeTerminalTab(robot, titles.size - 1)
        await("fake2 left snapshot", 60_000) { fake2 !in snapshotSids() }
        // win tabs: index 0/1 are the first two tabs unless an initial Local tab exists at 0.
        val titlesNow = terminalTabTitles(robot)
        val winTabIndices = titlesNow.indices.filter { titlesNow[it].contains("Claude") || titlesNow[it].contains("✳") || titlesNow[it].contains("·") }
        closeTerminalTab(robot, winTabIndices[1]) // second Claude-titled tab = win2
        await("win2 left snapshot", 60_000) { win2 !in snapshotSids() }

        await("snapshot settled to {win1, fake1}", 60_000) { snapshotSids() == setOf(win1, fake1) }
        assertEquals(setOf(win1, fake1), snapshotSids())

        // 6. HAND-OPEN regression: a tab the plugin never spawned, running a launcher that resumes
        //    an existing session with the id read from a FILE — so the tab's own command carries no
        //    session id, exactly as when a user types `claude --resume <sid>` into a shell tab. The
        //    tab starts out wearing a shell title; only the running program later announces itself.
        //    Field bug: such a tab was never adopted (no snapshot entry → silently not restored).
        val handSid = java.util.UUID.randomUUID().toString()
        E2eUtil.armHandLauncher(handSid)
        val variant = E2eUtil.openHandTab(
            robot,
            launcher = java.io.File(E2eUtil.e2eDir, "bin/claude-hand.cmd"),
            launcherOnPath = E2eUtil.binCommand("claude-hand.cmd"),
        )
        println("[e2e] hand tab created as a $variant terminal tab")
        // The REWORKED tab kind is the regression surface: it has no classic widget, so the
        // cwd-based adoption pass sees nothing. The classic fallback exercises a path that already
        // worked before the fix — accepting it would make this whole phase a green no-op, so the
        // helper's captured failure reason (`classic:<why>`) fails here instead.
        assertEquals("hand tab must be a REWORKED terminal tab", "reworked", variant)
        await("hand session registered", 90_000) { activeSessions().any { it.sid == handSid } }
        // THE fix assertion — adoption needs the tab's title to flip away from the shell shape and
        // may take a confirmation poll, so allow generously more than a normal bind.
        await("hand tab adopted into snapshot", 120_000) { handSid in snapshotSids() }

        // The `/tab` rename on an adopted hand tab must reach the tab title — and specifically the
        // HAND tab's: it is the newest terminal tab, so the LAST title must be the one that
        // changed. `any {}` would happily pass if the name landed on the wrong tab.
        // The write is re-applied every poll because it races the plugin's own rewrite of the same
        // entry (see setUserName) — a single lost write would otherwise burn the whole timeout.
        try {
            await("hand tab (the newest tab) wears its user name", 90_000) {
                E2eUtil.setUserName(handSid, HAND_USER_NAME)
                terminalTabTitles(robot).last().contains(HAND_USER_NAME)
            }
        } catch (e: AssertionError) {
            throw AssertionError(
                "the /tab rename never reached the NEWEST tab title; titles=${terminalTabTitles(robot)}", e,
            )
        }

        writeExpectations(
            keptWin = win1, keptFake = fake1, closedWin = win2, closedFake = fake2,
            handSid = handSid, handUserName = HAND_USER_NAME, phase1End = System.currentTimeMillis(),
        )
        println("[e2e] phase 1 complete: kept win=$win1 fake=$fake1 hand=$handSid; closed win=$win2 fake=$fake2")

        // 7. Committed exit — the snapshot must survive teardown UNCHANGED (hand tab still open).
        exitIde(robot)
    }
}
