package com.claudetabs.ui

import com.claudetabs.ui.E2eUtil.activateTerminal
import com.claudetabs.ui.E2eUtil.activeSessions
import com.claudetabs.ui.E2eUtil.await
import com.claudetabs.ui.E2eUtil.binCommand
import com.claudetabs.ui.E2eUtil.claudeHome
import com.claudetabs.ui.E2eUtil.closeTerminalTab
import com.claudetabs.ui.E2eUtil.connect
import com.claudetabs.ui.E2eUtil.cycleStateFile
import com.claudetabs.ui.E2eUtil.exitIde
import com.claudetabs.ui.E2eUtil.fakeHome
import com.claudetabs.ui.E2eUtil.ideSystemProperty
import com.claudetabs.ui.E2eUtil.invokeAction
import com.claudetabs.ui.E2eUtil.killStub
import com.claudetabs.ui.E2eUtil.openHandTabRunning
import com.claudetabs.ui.E2eUtil.projectOpen
import com.claudetabs.ui.E2eUtil.readExpectations
import com.claudetabs.ui.E2eUtil.setUserName
import com.claudetabs.ui.E2eUtil.snapshotSids
import com.claudetabs.ui.E2eUtil.stubLaunches
import com.claudetabs.ui.E2eUtil.stubSessions
import com.claudetabs.ui.E2eUtil.terminalTabTitles
import com.claudetabs.ui.E2eUtil.userNameOf
import com.claudetabs.ui.E2eUtil.writeExpectations
import com.intellij.remoterobot.RemoteRobot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MULTI-GENERATION restart E2E (driver: `e2e/run-e2e.ps1 -Cycles N [-Seed S]`).
 *
 * Every other phase pair is ONE restart from a wiped slate. This one is N of them in a row against
 * the SAME isolated state, because the bug class it exists to catch only shows up from the second
 * restart onwards: a RESTORED tab is a different kind of object from a freshly opened one (the
 * plugin spawned it, it is bound at birth, and its entry already carries a position, a user name
 * and a restore-attempt count), so "close a tab that was itself restored, then restart again" is
 * where a zombie hides — the session comes back although the user ×'d it. The mirror-image failure
 * is a tab that quietly stops surviving after surviving a few generations.
 *
 * ONE JVM per generation, because one generation = one IDE launch. The driver runs this same class
 * once per cycle and hands it the cycle index, the total and the run's RNG seed as system
 * properties; everything that has to outlive the JVM lives in `build/e2e/cycle-state.json`.
 *
 * Per cycle `i`:
 *  1. RESTORE (i > 1): exactly the sessions left open last cycle came back, each resumed ONCE and
 *     through ITS OWN root's launcher, in the order they were left in; nothing ever closed came
 *     back; the snapshot and the tab strip hold exactly them.
 *  2. OPEN (i ≤ N): four new tabs — a seeded mix of plugin-OWNED windows-root tabs and hand-opened
 *     fake-root ("WSL-shaped") tabs, always at least one of each.
 *  3. CLOSE: two of this cycle's new tabs plus (i ≥ 2) one tab that was RESTORED this cycle.
 *  4. Quit, with every gap drawn from the seed — including the nasty short end between the last ×
 *     and the quit, where the snapshot write has to win the race against teardown.
 *
 * Cycle N+1 is verify-only: assert the restore, open nothing, quit.
 *
 * Reproducibility: the seed is printed by the driver and every drawn value is printed here, so a
 * failed run replays with `-Cycles N -Seed <that seed>`.
 */
class E2eCycleTest {

    /** New tabs per generation. Fixed, so the seeded draw sequence does not depend on it. */
    private val TABS_PER_CYCLE = 4

    /** The fake ("WSL-shaped") session root the sandbox is given — see `-Dclaudetabs.fakeRoots`. */
    private val FAKE_ROOT = "fake:distroA"

    /** Held back so several hand-opened fake tabs' sessions all land in ONE plugin poll: the 1:1
     *  adoption pass then meets N sessions and N candidate tabs in one directory and refuses, and
     *  the tabs stay UNBOUND — the shape phase 3 pins, carried across restarts here. */
    private val UNBOUND_REGISTER_DELAY_MS = 6_000

    private val cycle = requireIntProperty("claudetabs.e2e.cycle")
    private val cycles = requireIntProperty("claudetabs.e2e.cycles")
    private val seed = requireLongProperty("claudetabs.e2e.seed")

    /** Stamped into EVERY assertion message: a failure has to say which generation broke and how
     *  to replay it, or a seeded run is no more debuggable than an unseeded one. */
    private val tag = "cycle=$cycle seed=$seed"

    /**
     * This generation's draw stream. Derived from `seed` AND `cycle`: a plain `Random(seed)` in
     * each cycle's JVM would replay cycle 1's timings in every generation, so the "varied timing"
     * the scenario is about would never vary.
     */
    private val rng = java.util.Random(seed * 1_000_003L + cycle)

    // ── one tab of the scenario ──
    private data class TabRef(
        val sid: String,
        val label: String,
        /** The stub wrapper flavor that owns it, as the argv log records it: `windows` or `fake`. */
        val flavor: String,
        /** Restored by the plugin at this cycle's boot (as opposed to opened by this cycle). */
        val carried: Boolean = false,
    )

    private class CycleState(
        val expectedOpen: List<String>,
        val everClosed: List<String>,
        val labels: Map<String, String>,
        val flavors: Map<String, String>,
        val endedAt: Long,
    )

    @Test fun oneGenerationOfTheRestartCycle() {
        val verifyOnly = cycle > cycles
        println("[e2e] === $tag (${if (verifyOnly) "verify-only relaunch" else "generation $cycle of $cycles"}) ===")

        val robot = connect()
        await("project open [$tag]", 300_000) { projectOpen(robot) }
        // Let startup settle (restore scan, terminal tool window registration).
        Thread.sleep(10_000)

        // The whole scenario is timed against the non-default ledger tuning the driver passes for
        // every cycle. Without it the unbound tabs would need ten seconds each to be admitted and
        // every wait below would be measured against the wrong clock — fail where the reason shows.
        assertEquals(
            "sandbox IDE must carry the cycle-E2E unbound tuning (driver: -PclaudetabsE2eUnbound=true) [$tag]",
            "2000", ideSystemProperty(robot, "claudetabs.unboundAdmitMs"),
        )
        assertEquals(
            "sandbox IDE must carry the cycle-E2E attribution window [$tag]",
            "30000", ideSystemProperty(robot, "claudetabs.unboundAttributionMs"),
        )

        val prior = loadState()
        if (prior == null) {
            // Only generation 1 may find no state, and only because the driver wiped build/e2e for
            // this run. Asserting the slate is REALLY empty is what stops a half-wiped directory
            // from making generation 1's "nothing was resumed" true for the wrong reason.
            assertEquals("only generation 1 may start without a cycle state file [$tag]", 1, cycle)
            assertTrue(
                "generation 1 must start from a wiped slate — snapshot was ${snapshotSids()} [$tag]",
                snapshotSids().isEmpty(),
            )
            assertTrue(
                "generation 1 must start from a wiped stub argv log — found ${stubLaunches().size} launch(es) [$tag]",
                stubLaunches().isEmpty(),
            )
        } else {
            assertRestored(robot, prior)
        }

        if (verifyOnly) {
            println("[e2e] $tag verify-only relaunch complete: ${prior?.expectedOpen} came back and nothing else")
            exitIde(robot)
            return
        }
        runGeneration(robot, prior)
    }

    // ══════════════════════════════════════════════════════════════
    // 1. RESTORE ASSERTIONS
    // ══════════════════════════════════════════════════════════════

    /**
     * Everything the previous generation left open came back, and NOTHING else did.
     *
     * Every fact here is scoped to `state.endedAt` — the instant the previous generation wrote its
     * handoff. The stub argv log and the sandbox idea.log both survive the whole run (build/e2e is
     * wiped once, not per cycle), so an unscoped read would happily let generation 3's assertions
     * be satisfied by generation 1's launches.
     */
    private fun assertRestored(robot: RemoteRobot, state: CycleState) {
        val expected = state.expectedOpen
        fun label(sid: String) = state.labels[sid] ?: sid
        fun resumesSince(): List<Pair<String, String?>> =
            stubLaunches().filter { it.ts > state.endedAt && "--resume" in it.argv }
                .map { it.argv[it.argv.indexOf("--resume") + 1] to it.flavor }

        await("all ${expected.size} carried-over session(s) resumed [$tag]", 240_000) {
            resumesSince().map { it.first }.containsAll(expected)
        }
        assertResumeSetIsClean("on arrival", state, resumesSince())
        resumesSince().forEach { (sid, flavor) ->
            assertEquals(
                "session ${label(sid)} must resume through ITS OWN root's launcher [$tag]",
                state.flavors[sid], flavor,
            )
        }

        // Late work is still work: restore is staggered, the plugin keeps polling and a failed
        // restore spawn gets retried, so a zombie resume can arrive seconds after the first wave.
        // Re-read after a settle rather than believing the first quiet moment.
        Thread.sleep(20_000)
        assertResumeSetIsClean("after a 20s settle", state, resumesSince())

        await("snapshot settled to exactly the carried-over sids [$tag]", 180_000) {
            snapshotSids() == expected.toSet()
        }
        assertEquals("snapshot after restore [$tag]", expected.toSet(), snapshotSids())

        // Tab strip. Every session in the scenario carries a `/tab`-style user name (see
        // [assignLabels]) which the plugin persists and re-applies to the restored tab's title —
        // the only handle a restored tab offers, and what makes the count and the ORDER observable.
        await("every carried-over session has exactly one tab wearing its label [$tag]", 180_000) {
            val titles = terminalTabTitles(robot)
            expected.all { sid -> titles.count { it.contains(label(sid)) } == 1 }
        }
        val titles = terminalTabTitles(robot)
        println("[e2e] $tag restored tab titles: $titles")
        val ours = titles.mapNotNull { t -> expected.firstOrNull { sid -> t.contains(label(sid)) } }
        assertEquals(
            "exactly one tab per carried-over session — no duplicates, nothing missing; got $titles [$tag]",
            expected.size, ours.size,
        )
        // ORDER. Restore sorts by saved tab position, then start time (ClaudeTabsHelpers.restoreOrder),
        // and the generation that quit left its tabs in exactly `expectedOpen` order: the plugin-bound
        // tabs carry positions in that order, and the UNBOUND ones (which have no position) are the
        // last tabs in the strip and are ordered by start time, i.e. by open order, which is the same
        // thing. That invariant is why the generation opens its owned tabs BEFORE its hand-opened
        // ones — see [runGeneration].
        assertEquals(
            "carried-over tabs must come back in the left-to-right order they were left in; got $titles [$tag]",
            expected, ours,
        )
        // Exact equality is deliberately NOT asserted on the raw tab count: the terminal tool window
        // keeps one IDE-owned tab of its own (the initial "Local"), which is not ours to predict.
        // The count that matters — one tab per carried session, no extras from the IDE's own tab
        // persistence and no duplicate restores — is the pair of assertions above plus this bound.
        assertTrue(
            "no tabs beyond the restored ones and at most one IDE-owned tab; got $titles [$tag]",
            titles.size - ours.size <= 1,
        )
    }

    /**
     * Nothing was resumed that should not have been, and nothing twice — stated as two separate
     * assertions so a failure says WHICH it was.
     *
     * The set check is the zombie check, and it is deliberately wider than `everClosed`: a session
     * ×'d three generations ago is caught by either, but a sid that was never part of the scenario
     * at all is caught only here. The count check is the "two tabs appending to one conversation"
     * check — the plugin retries a restore spawn it thinks failed, and a retry that was not needed
     * is exactly that bug.
     */
    private fun assertResumeSetIsClean(whenTaken: String, state: CycleState, resumes: List<Pair<String, String?>>) {
        fun label(sid: String) = state.labels[sid] ?: sid
        val sids = resumes.map { it.first }
        // Named separately from the set check below although the set check subsumes it: THIS is
        // the headline failure of the whole scenario — a tab the user ×'d, in any earlier
        // generation, reopening itself — and it deserves to say so in its own words.
        val zombies = sids.filter { it in state.everClosed }
        assertTrue(
            "$whenTaken: a session whose tab was ×'d in an earlier generation was resumed again: " +
                "${zombies.map { label(it) }} [$tag]",
            zombies.isEmpty(),
        )
        assertEquals(
            "$whenTaken: exactly the carried-over sessions may be resumed — anything extra is a " +
                "session coming back from the dead (×'d before: ${state.everClosed.map { label(it) }}) [$tag]",
            state.expectedOpen.toSet(), sids.toSet(),
        )
        val twice = sids.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(
            "$whenTaken: no session may be resumed more than once — a second tab would append to " +
                "the same conversation; got ${twice.mapKeys { label(it.key) }} [$tag]",
            twice.isEmpty(),
        )
    }

    // ══════════════════════════════════════════════════════════════
    // 2-5. OPEN / CLOSE / QUIT
    // ══════════════════════════════════════════════════════════════

    private fun runGeneration(robot: RemoteRobot, prior: CycleState?) {
        val carriedSids = prior?.expectedOpen ?: emptyList()
        val labels = LinkedHashMap(prior?.labels ?: emptyMap())
        val flavors = LinkedHashMap(prior?.flavors ?: emptyMap())
        val everClosed = ArrayList(prior?.everClosed ?: emptyList())

        // Every draw happens HERE, before a single IDE interaction, and in a fixed order: a stream
        // whose shape depended on what the IDE did would not replay from the same seed.
        val ownedCount = 1 + rng.nextInt(TABS_PER_CYCLE - 1)          // 1..3, so ≥1 of each kind
        val handCount = TABS_PER_CYCLE - ownedCount
        val openGaps = List(TABS_PER_CYCLE) { drawMs(300, 5_000) }
        val dwellMs = drawMs(1_000, 15_000)
        val drawnQuitGapMs = drawMs(300, 8_000)
        val closeOrder = newTabDrawOrder()
        val carriedPick = if (carriedSids.isEmpty()) -1 else rng.nextInt(carriedSids.size)
        // Cycle 2 is the DETERMINISTIC nasty case: the first generation that closes a restored tab
        // quits about a second after the ×, so the snapshot write always races teardown at least
        // once per run instead of only when the dice say so.
        val quitGapMs = if (cycle == 2) 1_000L else drawnQuitGapMs
        println(
            "[e2e] $tag draws: owned=$ownedCount hand=$handCount openGaps=$openGaps dwellMs=$dwellMs " +
                "closeOrder=$closeOrder carriedPick=$carriedPick quitGapMs=$quitGapMs " +
                "(drawn $drawnQuitGapMs${if (cycle == 2) ", overridden: cycle 2 is the deterministic short quit" else ""})",
        )

        activateTerminal(robot)
        Thread.sleep(2_000)

        // The tab-strip model. An UNBOUND tab has no title at all, so open order is its only
        // identity (phase 3's rule) — and an index is only meaningful while nothing is added or
        // removed. So the harness keeps its own left-to-right list (sid, or null for a tab that is
        // not ours) and re-checks it against the live strip before every ×.
        val titlesBefore = terminalTabTitles(robot)
        val strip: MutableList<String?> = titlesBefore
            .map { t -> carriedSids.firstOrNull { sid -> t.contains(labels.getValue(sid)) } }
            .toMutableList()
        assertEquals(
            "the tab strip must hold exactly the carried-over sessions before this generation opens " +
                "anything; got $titlesBefore [$tag]",
            carriedSids, strip.filterNotNull(),
        )

        val newTabs = ArrayList<TabRef>()

        // ── OWNED windows-root tabs, through the plugin's own action: bound at birth. Opened
        //    FIRST so this generation's UNBOUND tabs are the last tabs in the strip, which is what
        //    makes restore order predictable (see the ORDER assertion in [assertRestored]).
        repeat(ownedCount) { k ->
            val label = "C$cycle-T${newTabs.size + 1}"
            invokeAction(robot, "ClaudeTabs.NewSession")
            if (cycle == 1 && k == 0) assertFirstSpawnHitTheStub(robot)
            await("owned tab $label appeared [$tag]", 90_000) {
                terminalTabTitles(robot).size == strip.size + 1
            }
            // Captured INSIDE the wait, not re-read after it: two reads of the live session set
            // could straddle a registration and turn "exactly one new session" into a crash.
            // `labels` holds every sid the scenario has ever seen, so the difference is this tab's.
            var sid = ""
            await("windows-root session for $label registered [$tag]", 120_000) {
                val fresh = sidsByFlavor("windows") - labels.keys
                if (fresh.size == 1) { sid = fresh.single(); true } else false
            }
            strip.add(sid)
            newTabs.add(TabRef(sid, label, "windows"))
            labels[sid] = label
            flavors[sid] = "windows"
            println("[e2e] $tag opened owned $label sid=$sid; sleeping ${openGaps[k]}ms")
            Thread.sleep(openGaps[k])
        }

        // ── HAND-OPENED fake-root tabs, all in the project directory. Two or more go up
        //    BACK-TO-BACK on the register-delay wrappers so no 1:1 adoption is possible and they
        //    stay unbound (phase 3's shape); a lone one has an unambiguous pairing and MAY be
        //    adopted — both outcomes are fine here and nothing asserts on which happened.
        val handLabels = (1..handCount).map { "C$cycle-T${ownedCount + it}" }
        val registerDelay = if (handCount >= 2) UNBOUND_REGISTER_DELAY_MS else 0
        handLabels.forEach { label ->
            openHandTabRunning(robot, "${binCommand("claude-fake-topic.cmd")} $label $registerDelay")
            await("hand-opened fake tab $label created [$tag]", 90_000) {
                terminalTabTitles(robot).size == strip.size + 1
            }
            strip.add(null) // the sid is not knowable until the session registers
        }
        await("all $handCount fake-root session(s) of this generation registered [$tag]", 180_000) {
            handLabels.all { l -> stubSessions(fakeHome).any { it.topic == l } }
        }
        handLabels.forEachIndexed { i, label ->
            // The wrapper's topic is what says WHICH tab a session belongs to: the tabs share one
            // directory and are otherwise identical, and each wrapper ran in the tab opened for it.
            val sid = stubSessions(fakeHome).first { it.topic == label }.sid
            strip[strip.size - handCount + i] = sid
            newTabs.add(TabRef(sid, label, "fake"))
            labels[sid] = label
            flavors[sid] = "fake"
            println("[e2e] $tag opened hand fake $label sid=$sid (registerDelay=${registerDelay}ms)")
        }

        assignLabels(robot, newTabs)
        await("all ${newTabs.size} new session(s) admitted to the snapshot [$tag]", 180_000) {
            snapshotSids().containsAll(newTabs.map { it.sid })
        }
        println("[e2e] $tag strip after opening: ${terminalTabTitles(robot)}")

        // ── DWELL, then the ×s.
        println("[e2e] $tag dwelling ${dwellMs}ms before closing anything")
        Thread.sleep(dwellMs)

        val toClose = closeOrder.take(2).map { newTabs[it] } +
            if (carriedPick >= 0) {
                val sid = carriedSids[carriedPick]
                // THE POINT OF THIS WHOLE TEST: a tab that the plugin itself restored is a
                // different object from a freshly opened one, and closing it is where a zombie
                // hides.
                listOf(TabRef(sid, labels.getValue(sid), flavors.getValue(sid), carried = true))
            } else {
                emptyList()
            }
        val survivorsAfter = (carriedSids + newTabs.map { it.sid }) - toClose.map { it.sid }.toSet()
        assertTrue(
            "this generation must leave at least one tab open (closing ${toClose.map { it.label }}) [$tag]",
            survivorsAfter.isNotEmpty(),
        )

        toClose.forEach { t -> closeTab(robot, strip, t) ; everClosed.add(t.sid) }

        // ── QUIT. The snapshot must already describe exactly what is left open: every × was
        //    awaited out of it and every new session awaited into it, so anything else here is the
        //    plugin disagreeing with the tab strip, not a race.
        // Strip order IS restore order — except among this generation's hand-opened tabs, which have
        // no recorded strip position: the plugin reopens those by their session's start time and,
        // on an exact tie, by sid (ClaudeTabsHelpers.restoreOrder). A tie is a harness artifact —
        // two stubs launched back-to-back land in one ~15 ms Windows clock tick, which two real
        // hand-typed sessions cannot — so the expectation follows the plugin's documented
        // tie-break there instead of insisting on an order no clock can tell apart. Untied, this
        // is exactly open order.
        val handSids = newTabs.filter { it.label in handLabels }.map { it.sid }.toSet()
        val startOf = stubSessions(fakeHome).groupBy { it.sid }
            .mapValues { (_, files) -> files.maxOf { it.startedAt ?: 0L } }
        val inStrip = strip.filterNotNull()
        val expectedOpen = inStrip.filterNot { it in handSids } +
            inStrip.filter { it in handSids }.sortedWith(compareBy({ startOf[it] ?: Long.MAX_VALUE }, { it }))
        val titlesAtQuit = terminalTabTitles(robot)
        assertEquals(
            "the harness's tab-strip model must match the live strip at quit; got $titlesAtQuit [$tag]",
            strip.size, titlesAtQuit.size,
        )
        assertEquals("survivors [$tag]", survivorsAfter.toSet(), expectedOpen.toSet())
        await("snapshot settled to the tabs left open [$tag]", 60_000) { snapshotSids() == expectedOpen.toSet() }
        assertEquals("snapshot at quit [$tag]", expectedOpen.toSet(), snapshotSids())

        writeExpectations(
            cycleStateFile,
            mapOf(
                // seed + cycle are the STALENESS guard: a state file left by another run (another
                // seed, or the same seed one generation ahead) must not be asserted against.
                "seed" to seed.toString(),
                "cycle" to cycle.toString(),
                "expectedOpen" to expectedOpen.joinToString(","),
                "everClosed" to everClosed.joinToString(","),
                "labels" to labels.entries.joinToString(",") { "${it.key}=${it.value}" },
                "flavors" to flavors.entries.joinToString(",") { "${it.key}=${it.value}" },
                // Everything the NEXT generation asserts is scoped to this instant: the argv log
                // outlives the whole run, so only launches after it count as restore work.
                "cycleEndedAt" to System.currentTimeMillis().toString(),
            ),
        )
        println(
            "[e2e] $tag complete: left open ${expectedOpen.map { labels.getValue(it) }}; " +
                "closed ${toClose.map { it.label }}",
        )
        println("[e2e] $tag quitting ${quitGapMs}ms after the last × — the snapshot write must win that race")
        Thread.sleep(quitGapMs)
        exitIde(robot)
    }

    /**
     * × one tab, the only way that does not wedge the harness: the stub is asked to exit FIRST
     * (a live process raises the terminal's terminate-confirmation modal, which blocks the EDT and
     * with it every robot call), and its index is resolved BEFORE anything is killed or removed.
     */
    private fun closeTab(robot: RemoteRobot, strip: MutableList<String?>, t: TabRef) {
        val titles = terminalTabTitles(robot)
        assertEquals(
            "the harness's tab-strip model must match the live strip before closing ${t.label}; " +
                "model=$strip live=$titles [$tag]",
            strip.size, titles.size,
        )
        val index = strip.indexOf(t.sid)
        assertTrue("no tab left in the strip for ${t.label} (model=$strip) [$tag]", index >= 0)
        // A tab the plugin has bound wears its label; its index must agree with the model, or the
        // harness is about to × a tab it has misidentified. An unbound tab has no title to check
        // against — open order is all it has, which is why the model is re-verified above.
        val titled = titles[index].contains(t.label)
        if (t.carried || t.flavor == "windows") {
            assertTrue(
                "the tab at index $index should be ${t.label} but reads '${titles[index]}' (live=$titles) [$tag]",
                titled,
            )
        }
        println("[e2e] $tag closing ${t.label} sid=${t.sid} at index $index (titled=$titled) live=$titles")
        killStub(if (t.flavor == "fake") FAKE_ROOT else "windows", livePidOf(t))
        closeTerminalTab(robot, index)
        strip.removeAt(index)
        // A bound tab's eviction lands within a poll or two of the ×. An unbound one's has to be
        // ATTRIBUTED — its session death paired with its tab disappearing inside
        // claudetabs.unboundAttributionMs (30s here) — so it gets a wider budget.
        val budget = if (titled) 45_000L else 90_000L
        await("${t.label} left the snapshot after its × [$tag]", budget) { t.sid !in snapshotSids() }
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    /**
     * Give every session of this generation a `/tab`-style user name.
     *
     * It is not decoration: a restored tab's title is the ONLY handle the harness has on it after a
     * restart (the plugin's derived name is `<project>-<2 sid chars>`, which collides, and the
     * fake root's resume launcher carries one shared topic for every session it resumes). The user
     * name wins the display-name priority and the plugin persists it across restarts, so this is
     * what makes "which tab is which, three generations later" answerable at all.
     *
     * The write is a cross-process read-modify-write racing the plugin's own rewrite of the same
     * entry, so it is re-applied until it is still there a rewrite later — a single lost write
     * would otherwise leave an unlabelled tab for the rest of the run.
     */
    private fun assignLabels(robot: RemoteRobot, newTabs: List<TabRef>) {
        newTabs.forEach { t ->
            await("active-sessions entry for ${t.label} [$tag]", 120_000) {
                java.io.File(E2eUtil.activeSessionsDir, "${t.sid}.json").isFile
            }
        }
        await("every new session wears its label as a user name [$tag]", 180_000) {
            newTabs.forEach { runCatching { setUserName(it.sid, it.label) } }
            Thread.sleep(6_000) // outlast one of the plugin's own rewrites of these entries
            newTabs.all { userNameOf(it.sid) == it.label }
        }
        // Only a BOUND tab gets titled, so only the owned tabs can be checked here. The hand-opened
        // ones are unbound by construction and stay untitled until the restart binds them — which
        // is exactly what the next generation's title assertions verify.
        newTabs.filter { it.flavor == "windows" }.forEach { t ->
            await("owned tab ${t.label} wears its label [$tag]", 120_000) {
                terminalTabTitles(robot).count { it.contains(t.label) } == 1
            }
        }
    }

    /**
     * The pid of [t]'s CURRENTLY RUNNING stub.
     *
     * Read from the stub's own `sessions/<pid>.json` rather than active-sessions: an unbound
     * session's entry may carry no pid, and — the trap this exists for — a session that has been
     * resumed across generations has one session file PER generation, all naming the same sid. The
     * newest registration is the live process; killing an older generation's pid would leave the
     * real one running, and the × would then raise the terminate modal and wedge the robot.
     */
    private fun livePidOf(t: TabRef): Long {
        val home = if (t.flavor == "fake") fakeHome else claudeHome
        await("a stub session file for ${t.label} [$tag]", 60_000) {
            stubSessions(home).any { it.sid == t.sid }
        }
        return stubSessions(home).filter { it.sid == t.sid }
            .maxByOrNull { it.startedAt ?: 0L }!!.pid
    }

    /** Sessions the plugin currently tracks for a root, by the stub flavor that owns it. */
    private fun sidsByFlavor(flavor: String): Set<String> {
        val prefix = if (flavor == "fake") FAKE_ROOT else "windows"
        return activeSessions().filter { it.root.startsWith(prefix) }.map { it.sid }.toSet()
    }

    /**
     * The isolation gate, as phase 1 states it: the very first owned spawn of the run must land in
     * the STUB. If it does not, the sandbox just ran whatever `claude` its shell could find and no
     * assertion in any generation is worth trusting — so fail here, and exit the IDE first so the
     * driver's next boot is not left fighting an orphaned sandbox.
     */
    private fun assertFirstSpawnHitTheStub(robot: RemoteRobot) {
        try {
            await("stub launcher invoked by the first owned spawn [$tag]", 30_000) {
                stubLaunches().isNotEmpty()
            }
        } catch (e: AssertionError) {
            exitIde(robot)
            throw AssertionError("stub launcher never invoked — the real CLI may have been launched [$tag]", e)
        }
    }

    /** A shuffle of this generation's new-tab indices; the first two are the ones that get ×'d. */
    private fun newTabDrawOrder(): List<Int> {
        val order = (0 until TABS_PER_CYCLE).toMutableList()
        java.util.Collections.shuffle(order, rng)
        return order
    }

    private fun drawMs(loMs: Int, hiMs: Int): Long = (loMs + rng.nextInt(hiMs - loMs + 1)).toLong()

    /** The previous generation's handoff, or null in generation 1. */
    private fun loadState(): CycleState? {
        if (!cycleStateFile.isFile) return null
        val exp = readExpectations(cycleStateFile)
        // A state file from ANOTHER run (a different seed, or the same seed a generation out of
        // step) would make every restore assertion below assert against the wrong expectations —
        // and most of them would still pass. Refuse it instead.
        assertEquals("cycle-state.json belongs to another run [$tag]", seed.toString(), exp["seed"])
        assertEquals(
            "cycle-state.json was not written by the immediately preceding generation [$tag]",
            (cycle - 1).toString(), exp["cycle"],
        )
        return CycleState(
            expectedOpen = splitList(exp["expectedOpen"]),
            everClosed = splitList(exp["everClosed"]),
            labels = splitMap(exp["labels"]),
            flavors = splitMap(exp["flavors"]),
            endedAt = exp.getValue("cycleEndedAt").toLong(),
        )
    }

    // An empty list is written as `""`, which readExpectations' quoted-value regex does not match,
    // so the key simply is not there — that is the same thing as an empty list here.
    private fun splitList(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun splitMap(raw: String?): Map<String, String> =
        splitList(raw).mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
            .associate { it[0] to it[1] }

    private fun requireIntProperty(key: String): Int = requireLongProperty(key).toInt()

    /**
     * A driver-supplied system property. Deliberately FATAL when absent rather than skipped: a
     * mis-wired driver invocation that failed to pass the cycle wiring would otherwise report a
     * green `uiTest` having asserted nothing at all.
     */
    private fun requireLongProperty(key: String): Long =
        System.getProperty(key)?.trim()?.toLongOrNull()
            ?: throw AssertionError(
                "-D$key must be set — E2eCycleTest is driven by `e2e/run-e2e.ps1 -Cycles N`, " +
                    "which passes it through the uiTest task",
            )
}
