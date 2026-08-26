package com.claudetabs.ui

import com.claudetabs.ui.E2eUtil.await
import com.claudetabs.ui.E2eUtil.connect
import com.claudetabs.ui.E2eUtil.exitIde
import com.claudetabs.ui.E2eUtil.projectOpen
import com.claudetabs.ui.E2eUtil.readExpectations
import com.claudetabs.ui.E2eUtil.resumedSids
import com.claudetabs.ui.E2eUtil.snapshotSids
import com.claudetabs.ui.E2eUtil.stubLaunches
import com.claudetabs.ui.E2eUtil.terminalTabTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 of the pre/post-restart E2E: the driver relaunched the sandbox IDE against the same
 * isolated state; this asserts the restore contract phase 1 set up:
 *  - exactly the KEPT sessions are resumed, each THROUGH ITS OWN ROOT's launcher
 *    (the stub's argv log records which wrapper flavor executed each `--resume`);
 *  - the two ×'d sessions are NOT resumed;
 *  - the snapshot still holds exactly the kept sids;
 *  - the restored tabs carry plugin-owned titles, and the hand-opened tab still wears the name
 *    `/tab` gave it before the restart.
 */
class E2ePhase2Test {

    @Test fun restoreAfterRestart() {
        val exp = readExpectations()
        val keptWin = exp.getValue("keptWin")
        val keptFake = exp.getValue("keptFake")
        val closedWin = exp.getValue("closedWin")
        val closedFake = exp.getValue("closedFake")
        val handSid = exp.getValue("handSid")
        val handUserName = exp.getValue("handUserName")
        val phase1End = exp.getValue("phase1End").toLong()

        val robot = connect()
        await("project open", 300_000) { projectOpen(robot) }

        // Restore: both kept sessions resumed, each through its own root's launcher.
        await("kept sessions resumed", 180_000) {
            val resumed = resumedSids(stubLaunches())
            keptWin in resumed && keptFake in resumed
        }
        val resumed = resumedSids(stubLaunches())
        assertEquals("windows session must resume through the windows launcher", "windows", resumed[keptWin])
        assertEquals("fake-root session must resume through ITS root's launcher", "fake", resumed[keptFake])

        // The hand-opened session too. Phase 1 already `--resume`d it once (that IS how the tab was
        // opened), so only launches AFTER phase 1 ended count as restore work here.
        await("hand-opened session resumed after the restart", 180_000) {
            handSid in resumedSids(stubLaunches().filter { it.ts > phase1End })
        }
        assertEquals(
            "hand-opened session must resume through the windows launcher",
            "windows",
            resumedSids(stubLaunches().filter { it.ts > phase1End })[handSid],
        )

        // The ×'d sessions must never be resumed — not now, not after further polls.
        Thread.sleep(20_000)
        val resumedAfterSettle = resumedSids(stubLaunches())
        assertTrue("closed windows session must NOT be resumed", closedWin !in resumedAfterSettle)
        assertTrue("closed fake session must NOT be resumed", closedFake !in resumedAfterSettle)

        // Snapshot integrity across the restart + restore cycle.
        await("snapshot settled to kept sids", 90_000) { snapshotSids() == setOf(keptWin, keptFake, handSid) }
        assertEquals(setOf(keptWin, keptFake, handSid), snapshotSids())

        // Restored tabs wear plugin-owned titles (the ✳/· glyph prefix) — and NOTHING ELSE came
        // back: the IDE terminal's own tab persistence is cleared at shutdown, so IDE-restored
        // shells / re-run claude commands (which would spawn brand-new sessions) must be absent.
        // Phase 1 quit with 4 tabs present (win1, fake1, the hand tab, the initial Local); without
        // suppression the IDE restores those 4 PLUS the plugin restores 3 → 7+.
        // The `/tab` rename survived the restart: the restored hand tab still wears its user name.
        // Awaited FIRST so the title assertions below read a settled tab set.
        await("exactly one restored tab wears its user name", 90_000) {
            terminalTabTitles(robot).count { it.contains(handUserName) } == 1
        }
        val titles = terminalTabTitles(robot)
        println("[e2e] restored tab titles: $titles")
        // Restore order is not phase 1's creation order, so WHICH tab carries the name cannot be
        // pinned by position here — but EXACTLY ONE must carry it: the name landing on a second tab
        // as well is the wrong-tab-bound bug wearing a green `any {}`.
        assertTrue(
            "exactly one restored tab must wear the /tab name, got: $titles",
            titles.count { it.contains(handUserName) } == 1,
        )
        // The lower bound IS the "nothing was lost" check: all three restored sessions (win, fake,
        // hand) are plugin-titled, so a count of 2 means one restored tab never came back. Chosen
        // over a `titles.size` floor because IDE-owned tabs (the initial Local) count toward size
        // and would paper over exactly that loss.
        assertTrue(
            "expected 3 plugin-titled restored tabs (win + fake + hand), got: $titles",
            titles.count { it.contains("✳") || it.contains("·") || it.contains("Claude") } >= 3,
        )
        assertTrue(
            "IDE-restored non-authored tabs must not reappear (own-startup-tabs), got: $titles",
            titles.size <= 4,
        )

        println("[e2e] phase 2 complete: restore verified for win=$keptWin fake=$keptFake hand=$handSid")
        exitIde(robot)
    }
}
