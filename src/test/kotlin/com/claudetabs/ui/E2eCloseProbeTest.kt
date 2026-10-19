package com.claudetabs.ui

import com.claudetabs.ui.E2eUtil.activateTerminal
import com.claudetabs.ui.E2eUtil.await
import com.claudetabs.ui.E2eUtil.awaitLogLine
import com.claudetabs.ui.E2eUtil.binCommand
import com.claudetabs.ui.E2eUtil.closeTerminalTab
import com.claudetabs.ui.E2eUtil.connect
import com.claudetabs.ui.E2eUtil.exitIde
import com.claudetabs.ui.E2eUtil.fakeHome
import com.claudetabs.ui.E2eUtil.ideaLogMark
import com.claudetabs.ui.E2eUtil.openHandTabRunning
import com.claudetabs.ui.E2eUtil.projectOpen
import com.claudetabs.ui.E2eUtil.requireIdeaLog
import com.claudetabs.ui.E2eUtil.stubSessions
import com.claudetabs.ui.E2eUtil.terminalTabTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 — the CLOSE PROBE. Standalone (`-Phases 6`), fresh state.
 *
 * Question under test: at the instant a terminal tab's Content is removed, does "the tab's shell
 * process is still alive" separate a × from a tab that closed itself? Two shapes, one tab each,
 * both running a live stub under a shell:
 *  - tab A is closed the way the × does it (`ContentManager.removeContent`) while its shell is
 *    alive → the probe must report the widget's process ALIVE;
 *  - tab B has its shell killed from outside, so the terminal closes the tab on its own (the
 *    shape of a distro dying under the IDE, and of the IDE's own teardown) → the widget's process
 *    must be seen DEAD.
 * The plugin's probe is log-only; this phase reads the lines. A failure here says the signal is
 * not usable, which is the point of probing before building on it.
 */
class E2eCloseProbeTest {

    @Test fun shellLivenessSeparatesACloseFromASelfClose() {
        val robot = connect()
        await("project open", 300_000) { projectOpen(robot) }
        requireIdeaLog()
        activateTerminal(robot)
        // The tool window's own first tab appears asynchronously after activation: let the tab
        // set settle before anything is counted against it.
        var settled = terminalTabTitles(robot)
        await("terminal tool window settled", 30_000) {
            Thread.sleep(2_000)
            val now = terminalTabTitles(robot)
            (now == settled).also { settled = now }
        }
        val tabsBefore = settled.size

        // Two hand-opened tabs, each running a stub (alive for the whole phase unless killed).
        openHandTabRunning(robot, binCommand("claude-fake.cmd"))
        await("stub A registered", 60_000) { stubSessions(fakeHome).size >= 1 }
        val stubA = stubSessions(fakeHome).single()
        openHandTabRunning(robot, binCommand("claude-fake.cmd"))
        await("stub B registered", 60_000) { stubSessions(fakeHome).size >= 2 }
        val stubB = stubSessions(fakeHome).single { it.pid != stubA.pid }
        await("two more tabs open (had $tabsBefore)", 60_000) { terminalTabTitles(robot).size == tabsBefore + 2 }
        // The two hand tabs appended at the end, in open order.
        val indexA = tabsBefore
        println("[e2e] tabs: ${terminalTabTitles(robot)}; stub A pid=${stubA.pid}, stub B pid=${stubB.pid}")

        // Shape 1: × tab A under a living shell.
        val markA = ideaLogMark()
        closeTerminalTab(robot, indexA)
        val lineA = awaitLogLine("close probe line for the ×", markA, 20_000) { it.contains("[close-probe]") }
        println("[e2e] × → $lineA")
        assertTrue("a × must be seen with the tab's shell ALIVE: $lineA", lineA.contains("widgetProcess=alive"))

        // Shape 2: kill the SHELL under tab B (the stub's ancestor chain up to, not including, the
        // IDE process), so the terminal closes the tab itself.
        val markB = ideaLogMark()
        val chain = E2eUtil.ancestorsBelowIde(stubB.pid)
        assertTrue("stub B must have a shell ancestor to kill (chain=$chain)", chain.isNotEmpty())
        println("[e2e] killing tab B's process chain (top first): $chain")
        chain.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        await("tab B closed itself after its shell died", 60_000) {
            terminalTabTitles(robot).size == tabsBefore
        }
        val lineB = awaitLogLine("close probe line for the self-close", markB, 20_000) { it.contains("[close-probe]") }
        println("[e2e] self-close → $lineB")
        assertTrue("a self-close must be seen with the tab's shell DEAD: $lineB", lineB.contains("widgetProcess=dead"))

        assertEquals(tabsBefore, terminalTabTitles(robot).size)
        println("[e2e] phase 6 complete: shell liveness separated a × (alive) from a self-close (dead)")
        exitIde(robot)
    }

}
