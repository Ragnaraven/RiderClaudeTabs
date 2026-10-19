package com.claudetabs.ui

import com.claudetabs.ui.E2eUtil.activateTerminal
import com.claudetabs.ui.E2eUtil.activeSessions
import com.claudetabs.ui.E2eUtil.await
import com.claudetabs.ui.E2eUtil.awaitLogLine
import com.claudetabs.ui.E2eUtil.awaitMove
import com.claudetabs.ui.E2eUtil.binCommand
import com.claudetabs.ui.E2eUtil.closeTerminalTab
import com.claudetabs.ui.E2eUtil.connect
import com.claudetabs.ui.E2eUtil.exitIde
import com.claudetabs.ui.E2eUtil.fakeHome
import com.claudetabs.ui.E2eUtil.ideSystemProperty
import com.claudetabs.ui.E2eUtil.ideaLogLines
import com.claudetabs.ui.E2eUtil.ideaLogMark
import com.claudetabs.ui.E2eUtil.killStub
import com.claudetabs.ui.E2eUtil.openHandTabRunning
import com.claudetabs.ui.E2eUtil.phase3ExpectationsFile
import com.claudetabs.ui.E2eUtil.projectOpen
import com.claudetabs.ui.E2eUtil.requireIdeaLog
import com.claudetabs.ui.E2eUtil.snapshotSids
import com.claudetabs.ui.E2eUtil.stubLaunches
import com.claudetabs.ui.E2eUtil.stubSessions
import com.claudetabs.ui.E2eUtil.terminalTabTitles
import com.claudetabs.ui.E2eUtil.writeExpectations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 3 of the UNBOUND-LEDGER E2E (driver: `e2e/run-e2e.ps1 -Phases 3,4`).
 *
 * THE INCIDENT THIS PINS. The open-tabs snapshot is the only restore authority after a restart,
 * and it used to admit a session only when the plugin could BIND it to a specific tab widget.
 * Adopting a hand-opened tab needs an unambiguous 1:1 cwd pairing, which never happens once
 * several tabs share one project directory — the normal way this plugin gets used. So sessions the
 * user was actively working in were absent from the snapshot poll after poll, and a restart lost
 * them: 13 of 17 open sessions dropped in the field.
 *
 * Phase 1 deliberately AVOIDS this shape (it serialises its two fake-root tabs so the 1:1 rule
 * always has exactly one pairing to make). This phase walks straight into it — three fake-root
 * tabs in ONE directory, opened back-to-back — and asserts the second snapshot arm holds them:
 *
 *  1. three tabs, no adoption possible;
 *  2. all three sessions counted as open ANYWAY (before the fix: none of them);
 *  3. × one tab → exactly that session is evicted, the other two stay;
 *  4. the root goes UNOBSERVABLE (its `sessions/` dir cannot be listed) → membership HOLDS;
 *  5. the root's scan THROWS and it is benched → membership still HOLDS;
 *  6. quit — phase 4 asserts exactly the two survivors come back.
 *
 * The sandbox runs with `claudetabs.unboundAdmitMs=2000` / `unboundAttributionMs=30000` (the
 * driver's `-PclaudetabsE2eUnbound=true`); the phase asserts that up front rather than silently
 * testing the shipped 10s/30s defaults.
 */
class E2ePhase3Test {

    /** The fake ("WSL-shaped") session root the sandbox is given — see `-Dclaudetabs.fakeRoots`. */
    private val FAKE_ROOT = "fake:distroA"

    /** The plugin's poll cadence. Waits are expressed in polls so they read as what they are. */
    private val POLL_MS = 5_000L

    /** One wrapper per tab, each with its own topic: the tabs live in one directory and are
     *  otherwise identical, so the topic (which the stub records in its session file; it is NOT
     *  its terminal title) is what lets the phase name a tab and know which session is in it. */
    private val TABS = listOf(
        "Tab A" to "claude-fake-a.cmd",
        "Tab B" to "claude-fake-b.cmd",
        "Tab C" to "claude-fake-c.cmd",
    )

    @Test fun unboundSessionsAreOpenWithoutABinding() {
        val phaseStart = System.currentTimeMillis()
        val robot = connect()
        await("project open", 300_000) { projectOpen(robot) }
        // Let startup settle (restore scan of the empty home, terminal tool window registration).
        Thread.sleep(10_000)

        // The whole phase is timed against non-default ledger tuning. If the driver launched this
        // sandbox without it, every wait below is measured against the wrong clock and the verdict
        // means nothing — so fail here, where the reason is obvious.
        assertEquals(
            "sandbox IDE must carry the phase-3/4 unbound tuning (driver: -PclaudetabsE2eUnbound=true)",
            "2000", ideSystemProperty(robot, "claudetabs.unboundAdmitMs"),
        )
        assertEquals(
            "sandbox IDE must carry the phase-3/4 attribution window",
            "30000", ideSystemProperty(robot, "claudetabs.unboundAttributionMs"),
        )
        // Everything this phase asserts about the plugin's own reasoning is a log line. Take the
        // mark now, so a line an earlier phase in the same invocation wrote can never satisfy one.
        println("[e2e] reading plugin diagnostics from ${requireIdeaLog()}")
        val logMark = ideaLogMark()

        fun fakeSids() = activeSessions().filter { it.root.startsWith(FAKE_ROOT) }.map { it.sid }

        // 1. THREE fake-root tabs in ONE cwd, opened back-to-back. Only the tab's CONTENT is
        //    awaited between opens, never its session: all three Contents must exist before the
        //    first session file lands, or a poll could meet one session and one candidate tab and
        //    bind it 1:1 — and the ambiguity this phase exists to reproduce would never happen.
        //    (The wrappers' CLAUDE_STUB_REGISTER_DELAY_MS is the other half of that guarantee.)
        activateTerminal(robot)
        Thread.sleep(2_000)
        val tabsBefore = terminalTabTitles(robot).size
        TABS.forEachIndexed { i, (topic, wrapper) ->
            openHandTabRunning(robot, binCommand(wrapper))
            await("terminal tab ${i + 1} of ${TABS.size} ($topic) created", 60_000) {
                terminalTabTitles(robot).size == tabsBefore + i + 1
            }
        }
        // ISOLATION GATE, as in phase 1: the tabs must have run the STUB, not whatever `claude` the
        // sandbox shell could find. Both halves are checked — the session files the plugin scans,
        // and the argv log only a stub writes.
        await("three fake-root sessions registered", 180_000) { stubSessions(fakeHome).size == 3 }
        await("three fake-root stub launches recorded", 60_000) {
            stubLaunches().count { it.flavor == "fake" && it.ts >= phaseStart } == 3
        }
        await("the plugin scanned all three", 120_000) { fakeSids().size == 3 }
        // Nothing titles an UNBOUND tab — the plugin titles only tabs it has bound, and the stub's
        // own terminal title never reaches the tab on this terminal — so the tabs are told apart
        // by OPEN ORDER, not by name: new tabs append at the end, and each wrapper ran in the tab
        // opened for it. (That the titles never leave the shell shape is also why the adoption pass
        // sees no candidate tab and `adopted` stays 0, which is the premise of this phase.)
        println("[e2e] tab titles: ${terminalTabTitles(robot)}")

        // Same order as ClaudeTabsHelpers.restoreOrder for positionless entries: startedAt, then
        // sid — three stubs started within a millisecond can tie, and the tie must break the same way.
        val sessions = stubSessions(fakeHome).sortedWith(compareBy({ it.startedAt ?: Long.MAX_VALUE }, { it.sid }))
        val sids = sessions.map { it.sid }
        assertEquals(
            "each tab's session must be identifiable by its topic, got ${sessions.map { it.topic }}",
            TABS.map { it.first }.toSet(), sessions.mapNotNull { it.topic }.toSet(),
        )
        println("[e2e] sessions: ${sessions.map { "${it.topic}=${it.sid}" }}")

        // 2. THE INCIDENT. Nothing could be adopted — three sessions and three candidate tabs in
        //    one directory is exactly the ambiguity the 1:1 rule refuses — and the snapshot holds
        //    all three regardless. Before the unbound arm it held NONE of them, and the next
        //    restart lost every one.
        await("all three unbound sessions counted as open", 90_000) { snapshotSids().containsAll(sids) }
        val membership = awaitLogLine("membership line naming all three sids", logMark, 60_000) { line ->
            line.contains("live session(s) with no tab binding counted as open") && sids.all { line.contains(it) }
        }
        println("[e2e] $membership")
        assertNoAdoptions(logMark, sids)
        assertTrue(
            "the snapshot must hold all three unbound sids, got ${snapshotSids()}",
            snapshotSids().containsAll(sids),
        )

        // 2b. RECYCLED PID. A stale `sessions/<pid>.json` — left behind by an earlier process that
        //    had this pid — must not count as a live session of the conversation it names, and
        //    must never read as an in-app switch. The fake root's liveness marker (`proc/<pid>`,
        //    whose mtime is the process start) stands in for the OS: a session file whose own
        //    start stamp predates it is the leftover. A FRESH marker on a live pid is the control
        //    and must still be scanned into an entry. Field shape: four tabs re-pointed to dead
        //    conversations within a minute of one restore, all four restored at the next start.
        val staleSid = java.util.UUID.randomUUID().toString()
        val freshSid = java.util.UUID.randomUUID().toString()
        val stalePid = 777_001L
        val freshPid = 777_002L
        val claimedCwdJson = Regex("\"cwd\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(File(fakeHome, "sessions/${sessions[0].pid}.json").readText())!!.groupValues[1]
        val procMarkers = File(fakeHome, "proc").apply { mkdirs() }
        val markerNow = System.currentTimeMillis()
        File(procMarkers, "$stalePid").writeText("x")
        File(procMarkers, "$freshPid").writeText("x")
        File(fakeHome, "sessions/$stalePid.json").writeText(
            "{\"sessionId\":\"$staleSid\",\"cwd\":\"$claimedCwdJson\",\"startedAt\":${markerNow - 3_600_000L}}",
        )
        File(fakeHome, "sessions/$freshPid.json").writeText(
            "{\"sessionId\":\"$freshSid\",\"cwd\":\"C:\\\\elsewhere\\\\unclaimed\",\"startedAt\":$markerNow}",
        )
        val staleLine = awaitLogLine("stale marker ignored", logMark, 40_000) {
            it.contains("stale session marker ignored") && it.contains("pid $stalePid")
        }
        println("[e2e] $staleLine")
        await("fresh marker scanned into an entry", 40_000) { activeSessions().any { it.sid == freshSid } }
        assertTrue("a stale marker must not create a session entry", activeSessions().none { it.sid == staleSid })
        assertTrue("a stale marker must never enter the snapshot, got ${snapshotSids()}", staleSid !in snapshotSids())
        listOf(stalePid, freshPid).forEach { File(fakeHome, "sessions/$it.json").delete(); File(procMarkers, "$it").delete() }
        File(E2eUtil.activeSessionsDir, "$freshSid.json").delete()

        // 3. × ONE TAB. Its stub is asked to exit FIRST: closing a tab whose process is alive
        //    raises the terminal's terminate-confirmation modal, which blocks the EDT and with it
        //    every robot call (phase 1 does the same). That means the DEATH is observed before the
        //    tab DROP — the ledger pairs them in either order, as long as both land inside the
        //    attribution window.
        val closed = sessions.first { it.topic == "Tab C" }
        val kept = sessions.filter { it.sid != closed.sid }
        // The third wrapper ran in the third tab opened (tabs append at the end): its index is
        // fixed by open order, which is the only identity an unbound tab has.
        val closedTabIndex = tabsBefore + TABS.indexOfFirst { it.first == "Tab C" }
        killStub(FAKE_ROOT, closed.pid)
        println("[e2e] titles after the kill: ${terminalTabTitles(robot)}")
        closeTerminalTab(robot, closedTabIndex)
        // Promptness matters as much as correctness here: the eviction must land within a few polls
        // of the ×, not eventually. The log line is written in the same poll that shrinks the
        // snapshot (and just before it), so it is already there by the time this returns.
        await("the x'd session left the snapshot", 20_000) { closed.sid !in snapshotSids() }
        val eviction = awaitLogLine("eviction line naming the x'd sid", logMark, 10_000) {
            it.contains("unbound session(s) left with their tab") && it.contains(closed.sid)
        }
        println("[e2e] $eviction")
        assertKeptStillOpen("an × must evict its own session and nothing else", kept)
        assertEquals(
            "exactly the two surviving tabs must remain open, got ${terminalTabTitles(robot)}",
            tabsBefore + 2, terminalTabTitles(robot).size,
        )

        // 4. UNOBSERVABLE ROOT. With `sessions/` moved aside the listing returns null — which is
        //    NOT "the root is empty". A snapshot that read it as empty would drop every session the
        //    root owns: the same data loss by another route, and the one a sleeping WSL distro
        //    produces in the field.
        val sessionsDir = File(fakeHome, "sessions")
        val parked = File(fakeHome, "sessions.off")
        awaitMove(sessionsDir, parked)
        Thread.sleep(7 * POLL_MS)
        assertKeptStillOpen("an unobservable root must HOLD its sessions, not bury them", kept)
        awaitMove(parked, sessionsDir)
        Thread.sleep(2 * POLL_MS + 2_000)
        assertKeptStillOpen("membership must survive the root becoming observable again", kept)

        // 5. A SCAN THAT THROWS. The root is benched for five minutes, i.e. unobservable for the
        //    rest of this phase — membership has to hold through all of it. The control file is
        //    removed straight after: it is the BENCH, not the file, the phase runs under from here.
        //    The liveness markers are parked as well, and only AFTER the bench has landed: with
        //    them in place the per-entry liveness check would keep seeing the sessions alive
        //    around the benched scan, the ledger would never reach its hold branch, and this
        //    assertion would pass without the mechanism it names. Parked, the entries lose their
        //    pids to the dead-strike counter within two polls — and must still be members.
        val scanThrows = File(fakeHome, "scan-throws")
        scanThrows.writeText("throw")
        val benched = awaitLogLine("the fake root's scan failed and it was benched", logMark, 30_000) {
            it.contains("root $FAKE_ROOT scan failed") && it.contains("benched")
        }
        println("[e2e] $benched")
        val procDir = File(fakeHome, "proc")
        val parkedProc = File(fakeHome, "proc.off")
        awaitMove(procDir, parkedProc)
        Thread.sleep(4 * POLL_MS)
        assertKeptStillOpen("a benched root must HOLD its sessions, even once their pids are gone", kept)
        awaitMove(parkedProc, procDir)
        scanThrows.delete()
        assertTrue("the scan-throws control file must be gone before the restart", !scanThrows.exists())

        // Nothing may have been adopted at ANY point: a binding would have moved these sessions to
        // the snapshot's bound arm and phase 4 would prove the wrong mechanism.
        assertNoAdoptions(logMark, sids)

        // 6. Handoff + committed exit (the appClosing snapshot freeze).
        writeExpectations(
            phase3ExpectationsFile,
            mapOf(
                // In startedAt order: with no tab binding these entries never got a tab position,
                // so startedAt is what orders their restore.
                "keptFirst" to kept[0].sid,
                "keptSecond" to kept[1].sid,
                "closed" to closed.sid,
                // Phase 3's own launches are already in the argv log; only launches after this
                // instant count as restore work.
                "phase3End" to System.currentTimeMillis().toString(),
            ),
        )
        println("[e2e] phase 3 complete: kept ${kept.map { it.sid }}; x'd ${closed.sid}")
        exitIde(robot)
    }

    private fun assertKeptStillOpen(why: String, kept: List<E2eUtil.StubSession>) {
        val snapshot = snapshotSids()
        assertTrue("$why — expected ${kept.map { it.sid }} in the snapshot, got $snapshot",
            kept.all { it.sid in snapshot })
    }

    /**
     * No session in [sids] was ever bound to a tab.
     *
     * Three checks, because one alone would let the phase go green on the wrong mechanism:
     *  - the LATEST adopt scan reports `adopted=0` (the state right now);
     *  - so does every scan since [mark] — a TRANSIENT adoption would show up in one poll's summary
     *    and be gone from the next, and the snapshot would then be right for the wrong reason;
     *  - and no commit line (`… → sid=<sid>`, which every adopt path logs) names one of them,
     *    which also covers the paths that never reach the adopt-scan summary at all.
     */
    private fun assertNoAdoptions(mark: Long, sids: List<String>) {
        val lines = ideaLogLines(mark)
        val scans = lines.filter { it.contains("[ClaudeTabs][adopt-scan]") }
        assertTrue("expected an adopt-scan diagnostic since the phase started; none was logged", scans.isNotEmpty())
        assertTrue(
            "the latest adopt scan must report no adoptions, got: ${scans.last()}",
            scans.last().contains("adopted=0"),
        )
        val withAdoptions = scans.filterNot { it.contains("adopted=0") }
        assertTrue("no adopt scan may report an adoption, got: $withAdoptions", withAdoptions.isEmpty())
        val bound = lines.filter { line -> line.contains("→ sid=") && sids.any { line.contains(it) } }
        assertTrue("no fake-root session may be bound to a tab, got: $bound", bound.isEmpty())
    }
}
