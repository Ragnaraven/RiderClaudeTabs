package com.claudetabs.ui

import com.claudetabs.ui.E2eUtil.await
import com.claudetabs.ui.E2eUtil.connect
import com.claudetabs.ui.E2eUtil.exitIde
import com.claudetabs.ui.E2eUtil.ideSystemProperty
import com.claudetabs.ui.E2eUtil.phase4ExpectationsFile
import com.claudetabs.ui.E2eUtil.projectOpen
import com.claudetabs.ui.E2eUtil.readExpectations
import com.claudetabs.ui.E2eUtil.resumedSids
import com.claudetabs.ui.E2eUtil.snapshotSids
import com.claudetabs.ui.E2eUtil.stubLaunches
import com.claudetabs.ui.E2eUtil.terminalTabTitles
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 5: the relaunch after phase 4's outage. Three sessions lost their tabs to a root that
 * died under a running IDE, were HELD in the snapshot by the removal gate, and must now all come
 * back through the fake root's launcher — the exact restart that lost eight sessions in the field.
 */
class E2ePhase5Test {

    @Test fun restoreEverythingAnOutageTookDown() {
        val exp = readExpectations(phase4ExpectationsFile)
        val expected = listOf(exp.getValue("first"), exp.getValue("second"), exp.getValue("third"))
        val phase4End = exp.getValue("phase4End").toLong()

        val robot = connect()
        await("project open", 300_000) { projectOpen(robot) }
        assertEquals(
            "sandbox IDE must carry the phase-3/4/5 unbound tuning (driver: -PclaudetabsE2eUnbound=true)",
            "2000", ideSystemProperty(robot, "claudetabs.unboundAdmitMs"),
        )

        fun restoreLaunches() = stubLaunches().filter { it.ts > phase4End && "--resume" in it.argv }
        await("all three outage-held sessions resumed", 180_000) {
            resumedSids(restoreLaunches()).keys.containsAll(expected)
        }
        val resumed = resumedSids(restoreLaunches())
        expected.forEach { sid ->
            assertEquals("a held session must resume through ITS OWN root's launcher", "fake", resumed[sid])
        }
        assertEquals(
            "exactly the three held sessions must be resumed, nothing else",
            expected.toSet(), resumed.keys,
        )
        await("snapshot settled to exactly the three", 120_000) { snapshotSids() == expected.toSet() }
        println("[e2e] restored tab titles: ${terminalTabTitles(robot)}")
        println("[e2e] phase 5 complete: all three sessions restored after the outage")
        exitIde(robot)
    }
}
