package com.claudetabs.ui

import com.claudetabs.ui.E2eUtil.activeSessionsDir
import com.claudetabs.ui.E2eUtil.await
import com.claudetabs.ui.E2eUtil.awaitLogLine
import com.claudetabs.ui.E2eUtil.awaitMove
import com.claudetabs.ui.E2eUtil.connect
import com.claudetabs.ui.E2eUtil.exitIde
import com.claudetabs.ui.E2eUtil.fakeHome
import com.claudetabs.ui.E2eUtil.ideSystemProperty
import com.claudetabs.ui.E2eUtil.ideaLogLines
import com.claudetabs.ui.E2eUtil.ideaLogMark
import com.claudetabs.ui.E2eUtil.phase3ExpectationsFile
import com.claudetabs.ui.E2eUtil.phase4ExpectationsFile
import com.claudetabs.ui.E2eUtil.projectOpen
import com.claudetabs.ui.E2eUtil.readExpectations
import com.claudetabs.ui.E2eUtil.resumedSids
import com.claudetabs.ui.E2eUtil.snapshotSids
import com.claudetabs.ui.E2eUtil.stateDir
import com.claudetabs.ui.E2eUtil.stubLaunches
import com.claudetabs.ui.E2eUtil.stubSessions
import com.claudetabs.ui.E2eUtil.terminalTabTitles
import com.claudetabs.ui.E2eUtil.writeExpectations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Phase 4 of the unbound-ledger E2E: the driver relaunched the sandbox IDE against the state
 * phase 3 left behind, and this asserts the payoff of the whole scenario.
 *
 * The two surviving sessions were NEVER bound to a tab — no adoption ever committed for them — so
 * the snapshot's unbound arm is the only reason they can come back at all. They must:
 *  - both resume, each through the FAKE root's own launcher (identity never crosses roots);
 *  - resume in `startedAt` order, which is what orders entries that never had a tab position;
 *  - and be the ONLY things resumed: the session whose tab the user ×'d stays closed.
 *
 * It then goes on to the two shapes of the FOURTH incident, against the now-bound tabs:
 *  7. a single session written into the snapshot from OUTSIDE (the recovery command) while the
 *     IDE runs is reopened — the old rule committed its removal before retry-spawn could act;
 *  8. the root DIES with the three tabs open: it becomes unhealthy, its processes die, the IDE
 *     closes the tabs (here: the harness ×'s them, after the deaths) — all three sessions must
 *     stay in the snapshot, through the outage and after the root comes back, so phase 5 can
 *     restore them. The old rule committed them within a second of the tabs vanishing.
 */
class E2ePhase4Test {

    private val FAKE_ROOT = "fake:distroA"
    private val POLL_MS = 5_000L

    @Test fun restoreFromTheUnboundLedger() {
        val exp = readExpectations(phase3ExpectationsFile)
        val kept = listOf(exp.getValue("keptFirst"), exp.getValue("keptSecond"))
        val closed = exp.getValue("closed")
        val phase3End = exp.getValue("phase3End").toLong()

        val robot = connect()
        await("project open", 300_000) { projectOpen(robot) }
        // Same guard as phase 3: this half of the pair must run under the same tuning, or the two
        // halves are not describing the same plugin.
        assertEquals(
            "sandbox IDE must carry the phase-3/4 unbound tuning (driver: -PclaudetabsE2eUnbound=true)",
            "2000", ideSystemProperty(robot, "claudetabs.unboundAdmitMs"),
        )

        // Only launches AFTER phase 3 ended are restore work — the three that opened phase 3's tabs
        // are in the same argv log.
        fun restoreLaunches() = stubLaunches().filter { it.ts > phase3End && "--resume" in it.argv }
        await("both surviving sessions resumed", 180_000) {
            resumedSids(restoreLaunches()).keys.containsAll(kept)
        }
        val resumed = resumedSids(restoreLaunches())
        kept.forEach { sid ->
            assertEquals(
                "an unbound-arm session must resume through ITS OWN root's launcher", "fake", resumed[sid],
            )
        }

        // Restore order. Entries that never had a tab binding never got a tab position either, so
        // startedAt is the only thing that can order them — phase 3 recorded them in that order.
        val order = restoreLaunches().map { it.argv[it.argv.indexOf("--resume") + 1] }.distinct()
        assertEquals("the survivors must reopen in startedAt order, and nothing else with them", kept, order)

        // The ×'d session must never be resumed — not now, not after further polls. Checked against
        // the WHOLE argv log: phase 3 never resumed it either, so a single appearance is a failure.
        Thread.sleep(20_000)
        assertTrue(
            "the x'd session must NOT be resumed", closed !in resumedSids(stubLaunches()),
        )

        // Snapshot integrity across the restart: the restored tabs are plugin-spawned, so the two
        // survivors are now held by the snapshot's BOUND arm — and the evicted one is simply gone.
        await("snapshot settled to exactly the two survivors", 120_000) { snapshotSids() == kept.toSet() }
        assertEquals(kept.toSet(), snapshotSids())

        println("[e2e] restored tab titles: ${terminalTabTitles(robot)}")
        println("[e2e] unbound-arm restore verified for $kept; $closed stayed closed")

        // 7. SINGLE OUTSIDE RECOVERY. A dead session with an entry and a transcript, added to the
        //    snapshot file by an outside writer: the plugin must notice the addition and reopen
        //    it — not commit its removal because no tab holds it.
        val recovered = UUID.randomUUID().toString()
        writeRecoverableEntry(templateSid = kept[0], sid = recovered)
        val recoverMark = ideaLogMark()
        val recoverStart = System.currentTimeMillis()
        // The plugin's own load→save is not coordinated with outside writers, so one write can be
        // lost to it; re-apply each poll until the plugin has seen it (the recovery command's
        // documented contract is the same).
        await("outside addition noticed", 60_000, stepMs = POLL_MS) {
            addToSnapshotFromOutside(recovered)
            ideaLogLines(recoverMark).any { it.contains("added from outside") && it.contains(recovered) }
        }
        await("outside-added session reopened through the fake launcher", 90_000) {
            stubLaunches().any { it.ts > recoverStart && "--resume" in it.argv && recovered in it.argv && it.flavor == "fake" }
        }
        await("recovered session settled into the snapshot", 60_000) {
            snapshotSids() == kept.toSet() + recovered && stubSessions(fakeHome).any { it.sid == recovered }
        }
        println("[e2e] outside recovery of $recovered verified")

        // 8. THE ROOT DIES. Order matters and is the field order: the distro stops answering
        //    (sessions/ unlistable → unhealthy), then the shells under the tabs die and the
        //    terminal closes those tabs ITSELF — the harness kills each tab's shell chain, it does
        //    not × anything. Every departure must be HELD: each tab went with its shell already
        //    dead (close evidence), and no death was ever seen on a healthy root.
        val allThree = kept + recovered
        // LIVE stubs only, newest per sid: a stub a previous phase left behind (or one the harness
        // killed without its cleanup) leaves a session file with the same sid and a dead pid.
        val pids = stubSessions(fakeHome)
            .filter { it.sid in allThree && ProcessHandle.of(it.pid).map { h -> h.isAlive }.orElse(false) }
            .groupBy { it.sid }
            .mapValues { (_, v) -> v.maxByOrNull { it.startedAt ?: 0L }!!.pid }
        assertEquals("all three sessions must be running before the outage", allThree.toSet(), pids.keys)
        println("[e2e] outage targets (sid → stub pid): $pids")
        val crashMark = ideaLogMark()
        val sessionsDir = File(fakeHome, "sessions")
        val parked = File(fakeHome, "sessions.off")
        awaitMove(sessionsDir, parked)
        awaitLogLine("the fake root reported unhealthy", crashMark, 30_000) {
            it.contains("[root] $FAKE_ROOT UNHEALTHY")
        }
        val tabsBefore = terminalTabTitles(robot).size
        pids.values.forEach { E2eUtil.killTabChain(FAKE_ROOT, it) }
        await("three tabs closed themselves after their shells died", 60_000) {
            terminalTabTitles(robot).size == tabsBefore - 3
        }
        val held = awaitLogLine("departures held as crash suspects", crashMark, 40_000) {
            it.contains("HELD pending confirmation") && it.contains("crash suspects: 3")
        }
        println("[e2e] $held")
        Thread.sleep(7 * POLL_MS)
        assertTrue(
            "an outage must not remove a single session from the snapshot, got ${snapshotSids()}",
            snapshotSids().containsAll(allThree),
        )
        // The root comes back (an operator restarted it); everything in it is dead. Still held:
        // these tabs were never closed by the user, and the bulk settle window passes untouched.
        awaitMove(parked, sessionsDir)
        awaitLogLine("the fake root reported healthy again", crashMark, 30_000) {
            it.contains("[root] $FAKE_ROOT healthy")
        }
        Thread.sleep(8 * POLL_MS)
        assertTrue(
            "crash suspects must stay in the snapshot after the root recovers, got ${snapshotSids()}",
            snapshotSids().containsAll(allThree),
        )

        writeExpectations(
            phase4ExpectationsFile,
            mapOf(
                "first" to allThree[0], "second" to allThree[1], "third" to allThree[2],
                "phase4End" to System.currentTimeMillis().toString(),
            ),
        )
        println("[e2e] phase 4 complete: outage held $allThree; phase 5 must restore all three")
        exitIde(robot)
    }

    /** An active-sessions entry for [sid] shaped like [templateSid]'s (same cwd and root), with no
     *  pid — what the recovery command backfills — plus the transcript the restore gate needs. */
    private fun writeRecoverableEntry(templateSid: String, sid: String) {
        val template = File(activeSessionsDir, "$templateSid.json").readText()
        val entry = template
            .replace(templateSid, sid)
            .replace(Regex("\"pid\"\\s*:\\s*\\d+"), "\"pid\":null")
            .replace(Regex(",?\\s*\"startedAt\"\\s*:\\s*\\d+"), "")
        val target = File(activeSessionsDir, "$sid.json")
        val tmp = File(activeSessionsDir, "$sid.json.tmp")
        tmp.writeText(entry)
        java.nio.file.Files.move(
            tmp.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
        val transcripts = File(fakeHome, "projects/stub").apply { mkdirs() }
        File(transcripts, "$sid.jsonl").writeText("{\"type\":\"stub\",\"sid\":\"$sid\",\"ts\":${System.currentTimeMillis()}}\n")
    }

    /** Add [sid] to the project's snapshot file the way the recovery command does: read, union,
     *  atomic replace. Idempotent. */
    private fun addToSnapshotFromOutside(sid: String) {
        val snap = stateDir.listFiles { f -> f.name.startsWith("open-tabs-") && f.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() } ?: throw AssertionError("no snapshot file to add to")
        val current = snapshotSids()
        if (sid in current) return
        val sids = current + sid
        val tmp = File(snap.parentFile, "${snap.name}.tmp")
        tmp.writeText(sids.joinToString(",\n", "[\n", "\n]") { "  \"$it\"" })
        java.nio.file.Files.move(
            tmp.toPath(), snap.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }
}
