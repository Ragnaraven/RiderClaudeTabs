package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [UnboundLedger] — the sticky membership rules for sessions that are alive and claimed
 * by the project but bound to no tab.
 *
 * The contract these all circle: membership is lost ONLY to an attributed close, to the bound arm
 * taking the sid, or to a death that arrives before the session was ever admitted. Never to the
 * clock, and never to a root the plugin failed to scan.
 */
class UnboundLedgerTest {

    private val ROOT = "root-x"
    private val ADMIT = 60_000L      // alive a minute before a session counts as open
    private val WINDOW = 15_000L     // death <-> tab-drop pairing window
    private val T0 = 1_700_000_000_000L

    // ── helpers ──────────────────────────────────────────────────────

    private fun alive(startedAt: Long? = null, headless: Boolean? = null, rootId: String = ROOT) =
        UnboundLedger.Alive(rootId, startedAt, headless)

    /** An observation of a session that has been running since before the admit window. */
    private fun old(now: Long) = alive(startedAt = now - ADMIT)

    /** One poll. Defaults describe the quiet case: nothing bound, no tabs dropped, root readable,
     *  and anything claimed is by definition alive somewhere. */
    private fun step(
        prev: UnboundLedger.State = UnboundLedger.State(),
        claimed: Map<String, UnboundLedger.Alive> = emptyMap(),
        anywhere: Set<String> = claimed.keys,
        observable: Set<String> = setOf(ROOT),
        neverAdmit: Set<String> = emptySet(),
        drops: Int = 0,
        tabs: Int? = null,
        now: Long,
    ) = UnboundLedger.step(
        prev = prev,
        aliveClaimed = claimed,
        aliveAnywhere = anywhere,
        observableRoots = observable,
        neverAdmit = neverAdmit,
        unboundTabDrop = drops,
        now = now,
        admitMs = ADMIT,
        attributionMs = WINDOW,
        unboundTabCount = tabs,
    )

    /** Run [polls] identical quiet polls (nobody alive) with [tabs] unbound tabs on screen. */
    private fun quietPolls(from: UnboundLedger.Step, polls: Int, tabs: Int?, startAt: Long): UnboundLedger.Step {
        var s = from
        repeat(polls) { i -> s = step(prev = s.state, tabs = tabs, now = startAt + (i + 1) * 5_000L) }
        return s
    }

    // ── the cap: no more unbound members than unbound tabs ───────────

    /** The field failure: every tab bound, zero unbound tabs, and three members nothing could close. */
    @Test fun cap_shedsDeadMembersThatHaveNoTabLeftToBeIn() {
        val admitted = step(claimed = mapOf("sid-a" to old(T0), "sid-b" to old(T0)), tabs = 2, now = T0)
        val died = step(prev = admitted.state, tabs = 2, now = T0 + 5_000)          // both die, tabs still open
        assertEquals("dead under an open tab must be held", setOf("sid-a", "sid-b"), died.members)
        val after = quietPolls(died, UnboundLedger.CAP_CONFIRM_POLLS, tabs = 0, startAt = T0 + 60_000)
        assertTrue("no unbound tab is left for either, so neither may stay a member", after.members.isEmpty())
        assertEquals("the shed is reported on the poll it happens", setOf("sid-a", "sid-b"), after.shedByCap)
    }

    @Test fun cap_needsConsecutiveConfirmationsBeforeSheddingAnything() {
        val admitted = step(claimed = mapOf("sid-a" to old(T0)), tabs = 1, now = T0)
        val died = step(prev = admitted.state, tabs = 1, now = T0 + 5_000)
        val twoOver = quietPolls(died, UnboundLedger.CAP_CONFIRM_POLLS - 1, tabs = 0, startAt = T0 + 60_000)
        assertEquals("one odd enumeration must not cost a session", setOf("sid-a"), twoOver.members)
        val backUnder = step(prev = twoOver.state, tabs = 1, now = T0 + 90_000)
        assertEquals("back under the cap resets the count", 0, backUnder.state.overCapPolls)
        assertEquals(setOf("sid-a"), quietPolls(backUnder, UnboundLedger.CAP_CONFIRM_POLLS - 1, 0, T0 + 120_000).members)
    }

    @Test fun cap_shedsOnlyTheExcessAndTheOldestDeathFirst() {
        val admitted = step(claimed = mapOf("sid-a" to old(T0), "sid-b" to old(T0)), tabs = 2, now = T0)
        val aDied = step(prev = admitted.state, claimed = mapOf("sid-b" to old(T0)), tabs = 2, now = T0 + 5_000)
        val bDied = step(prev = aDied.state, tabs = 2, now = T0 + 60_000)
        val after = quietPolls(bDied, UnboundLedger.CAP_CONFIRM_POLLS, tabs = 1, startAt = T0 + 120_000)
        assertEquals("one tab remains, so one member remains — the more recent death", setOf("sid-b"), after.members)
    }

    /** Which alive session is the tabless one is unknowable; restore refuses a running session anyway. */
    @Test fun cap_neverShedsAnAliveMember() {
        var s = step(claimed = mapOf("sid-a" to old(T0)), tabs = 0, now = T0)
        repeat(UnboundLedger.CAP_CONFIRM_POLLS + 2) { i ->
            s = step(prev = s.state, claimed = mapOf("sid-a" to old(T0)), tabs = 0, now = T0 + (i + 1) * 5_000L)
        }
        assertEquals(setOf("sid-a"), s.members)
        assertTrue(s.shedByCap.isEmpty())
    }

    /** An alive tabless session keeps the membership over the cap for good. That must not arm the
     *  cap against the NEXT session to die — whose tab may well still be open. */
    @Test fun cap_doesNotLatchWhileNothingIsSheddable() {
        val both = mapOf("sid-outside" to old(T0), "sid-tab" to old(T0))
        var s = step(claimed = both, tabs = 1, now = T0)
        repeat(20) { i -> s = step(prev = s.state, claimed = both, tabs = 1, now = T0 + (i + 1) * 5_000L) }
        assertEquals("over the cap, but nothing is sheddable, so nothing may be counted", 0, s.state.overCapPolls)
        val died = step(prev = s.state, claimed = mapOf("sid-outside" to old(T0)), tabs = 1, now = T0 + 200_000)
        assertEquals("a fresh death is attribution's business, not the cap's", setOf("sid-outside", "sid-tab"), died.members)
    }

    @Test fun cap_unknownTabCountCapsNothing() {
        val admitted = step(claimed = mapOf("sid-a" to old(T0)), now = T0)
        val died = step(prev = admitted.state, now = T0 + 5_000)
        assertEquals(setOf("sid-a"), quietPolls(died, 10, tabs = null, startAt = T0 + 60_000).members)
    }

    private fun deadSince(s: UnboundLedger.Step, sid: String): Long? = s.state.records.getValue(sid).deadSince

    /** A single admitted, alive member — the starting point most transitions are measured from. */
    private fun admittedAt(now: Long, sid: String = "sid-a") =
        step(claimed = mapOf(sid to old(now)), now = now)

    // ── admission ────────────────────────────────────────────────────

    @Test fun admission_isImmediateWhenStartedAtAlreadyPredatesTheAdmitWindow() {
        // The restart case: the session has been running for a while, the plugin is only now
        // looking. Waiting another admitMs before counting it would lose it to a quick restart.
        val s = step(claimed = mapOf("sid-a" to alive(startedAt = T0 - ADMIT)), now = T0)
        assertEquals("an already-old session counts on the first poll that sees it", setOf("sid-a"), s.members)
        assertTrue(s.state.records.getValue("sid-a").admitted)
    }

    @Test fun admission_withoutStartedAtWaitsAdmitMsFromFirstSight() {
        // Unknown start stamp falls back to firstAliveAt — the session is tracked immediately but
        // does not count until it has proved it is not a one-shot process.
        val first = step(claimed = mapOf("sid-a" to alive()), now = T0)
        assertEquals("no start stamp yet, no membership", emptySet<String>(), first.members)
        assertNotNull("but it must already be tracked", first.state.records["sid-a"])

        val later = step(prev = first.state, claimed = mapOf("sid-a" to alive()), now = T0 + ADMIT)
        assertEquals(setOf("sid-a"), later.members)
    }

    @Test fun admission_headlessRunsAreNeverAddedButUnknownHeadlessnessIs() {
        // A non-interactive run never had a tab, so restoring one would invent a tab the user never
        // opened. Unknown (null) is NOT headless — failing closed there would lose real sessions.
        val s = step(
            claimed = mapOf(
                "sid-a" to alive(startedAt = T0 - ADMIT, headless = true),
                "sid-b" to alive(startedAt = T0 - ADMIT, headless = null),
            ),
            now = T0,
        )
        assertEquals(setOf("sid-b"), s.state.records.keys)
        assertEquals(setOf("sid-b"), s.members)
    }

    // ── the bound arm takes over ─────────────────────────────────────

    @Test fun neverAdmit_purgesAnAdmittedRecordOnTheSamePoll() {
        // The sid just gained a tab binding. It must leave the ledger the same poll, or both arms
        // report it and the snapshot counts one session twice.
        val admitted = admittedAt(T0)
        val bound = step(
            prev = admitted.state,
            claimed = mapOf("sid-a" to old(T0)),
            neverAdmit = setOf("sid-a"),
            now = T0 + 5_000,
        )
        assertEquals(setOf("sid-a"), bound.purged)
        assertEquals("a purged sid leaves membership immediately", emptySet<String>(), bound.members)
        assertTrue("purge removes the record, it does not merely block it", bound.state.records.isEmpty())
    }

    @Test fun neverAdmit_sidIsNeverAddedEvenWhileAliveAndClaimed() {
        val s = step(claimed = mapOf("sid-a" to old(T0)), neverAdmit = setOf("sid-a"), now = T0)
        assertTrue(s.state.records.isEmpty())
        assertEquals(emptySet<String>(), s.members)
        assertEquals("nothing was removed — it was never a record", emptySet<String>(), s.purged)
    }

    // ── holds: absence that proves nothing ───────────────────────────

    @Test fun aliveElsewhere_holdsTheRecordCompletelyUnchanged() {
        // The project's cwd claim flickered (a `cd` out of the tree, a claim lost to another
        // window) while the process is plainly still running. Nothing about the record may move.
        val admitted = admittedAt(T0)
        val held = step(prev = admitted.state, claimed = emptyMap(), anywhere = setOf("sid-a"), now = T0 + 5_000)
        assertEquals(admitted.state.records, held.state.records)
        assertEquals(setOf("sid-a"), held.members)
    }

    @Test fun unobservableRoot_holdsAndNeverStartsTheDeathClock() {
        // A scan that failed is not evidence of death (sleeping distro, unreadable home). Setting
        // deadSince here is how a session gets evicted while the user is typing in it.
        val admitted = admittedAt(T0)
        val held = step(
            prev = admitted.state, claimed = emptyMap(), anywhere = emptySet(),
            observable = emptySet(), now = T0 + 5_000,
        )
        assertNull("an unreadable root must never mark a session dead", deadSince(held, "sid-a"))
        assertEquals(setOf("sid-a"), held.members)
    }

    // ── real absence ─────────────────────────────────────────────────

    @Test fun absentWithObservableRoot_recordsDeadSinceButKeepsMembership() {
        // Death alone is not a close: the session stays in the snapshot until a × is attributed.
        val admitted = admittedAt(T0)
        val dead = step(prev = admitted.state, claimed = emptyMap(), anywhere = emptySet(), now = T0 + 5_000)
        assertEquals("deadSince stamps the first poll that observed it gone", T0 + 5_000, deadSince(dead, "sid-a"))
        assertEquals(setOf("sid-a"), dead.members)
    }

    @Test fun unadmittedRecordThatDies_isRemovedSilently() {
        // A short-lived process that never became a member is noise, not a close.
        val fresh = step(claimed = mapOf("sid-a" to alive()), now = T0)
        val gone = step(prev = fresh.state, claimed = emptyMap(), anywhere = emptySet(), now = T0 + 5_000)
        assertTrue(gone.state.records.isEmpty())
        assertEquals("silent removal is not a close", emptySet<String>(), gone.evictedByClose)
    }

    @Test fun aliveAgain_clearsDeadSince() {
        val admitted = admittedAt(T0)
        val dead = step(prev = admitted.state, claimed = emptyMap(), anywhere = emptySet(), now = T0 + 5_000)
        assertNotNull(deadSince(dead, "sid-a"))

        val back = step(prev = dead.state, claimed = mapOf("sid-a" to old(T0)), now = T0 + 10_000)
        assertNull("a session seen alive again is not dying", deadSince(back, "sid-a"))
        assertEquals(setOf("sid-a"), back.members)
    }

    // ── close attribution ────────────────────────────────────────────

    @Test fun oneDropAndOneRecentDeath_evictsAndSpendsTheDrop() {
        val admitted = admittedAt(T0)
        val dead = step(prev = admitted.state, claimed = emptyMap(), anywhere = emptySet(), now = T0 + 5_000)
        val closed = step(
            prev = dead.state, claimed = emptyMap(), anywhere = emptySet(),
            drops = 1, now = T0 + 10_000,
        )
        assertEquals(setOf("sid-a"), closed.evictedByClose)
        assertEquals(emptySet<String>(), closed.members)
        assertTrue("the drop is spent, not left to evict a later death", closed.state.pendingDrops.isEmpty())
    }

    @Test fun dropObservedBeforeTheDeath_stillAttributes() {
        // The tab vanishes first and the process is reaped a poll or two later — the ordinary
        // shape of a × on a tab hosting a session. The drop waits out its window for the death.
        val admitted = admittedAt(T0)
        val dropped = step(prev = admitted.state, claimed = mapOf("sid-a" to old(T0)), drops = 1, now = T0)
        assertEquals("nothing has died yet — the drop is kept", listOf(T0), dropped.state.pendingDrops)
        assertEquals(emptySet<String>(), dropped.evictedByClose)

        val died = step(prev = dropped.state, claimed = emptyMap(), anywhere = emptySet(), now = T0 + 6_000)
        assertEquals(setOf("sid-a"), died.evictedByClose)
        assertEquals(emptySet<String>(), died.members)
    }

    @Test fun twoRecentDeathsAndOneDrop_areAmbiguousSoNothingIsEvicted() {
        // Which × belongs to which death is unknowable. A zombie tab costs one click; evicting the
        // wrong session costs the user their work.
        val dead = twoAdmittedThenBothDead()
        val closed = step(
            prev = dead.state, claimed = emptyMap(), anywhere = emptySet(),
            drops = 1, now = T0 + 10_000,
        )
        assertEquals(emptySet<String>(), closed.evictedByClose)
        assertEquals(setOf("sid-a", "sid-b"), closed.members)
        assertEquals("the unspent drop is kept until it expires", 1, closed.state.pendingDrops.size)
    }

    @Test fun twoRecentDeathsAndTwoDrops_evictBoth() {
        // Every death has a × to explain it — the counts settle the pairing without needing to
        // know which tab was which.
        val dead = twoAdmittedThenBothDead()
        val closed = step(
            prev = dead.state, claimed = emptyMap(), anywhere = emptySet(),
            drops = 2, now = T0 + 10_000,
        )
        assertEquals(setOf("sid-a", "sid-b"), closed.evictedByClose)
        assertEquals(emptySet<String>(), closed.members)
        assertTrue(closed.state.records.isEmpty())
        assertTrue(closed.state.pendingDrops.isEmpty())
    }

    @Test fun dropOlderThanTheWindow_expiresAndCannotAttributeALaterDeath() {
        val admitted = admittedAt(T0)
        val dropped = step(prev = admitted.state, claimed = mapOf("sid-a" to old(T0)), drops = 1, now = T0)
        val died = step(
            prev = dropped.state, claimed = emptyMap(), anywhere = emptySet(),
            now = T0 + WINDOW + 1,
        )
        assertEquals("a stale drop is not evidence for this death", emptySet<String>(), died.evictedByClose)
        assertTrue(died.state.pendingDrops.isEmpty())
        assertEquals(setOf("sid-a"), died.members)
    }

    @Test fun deathOlderThanTheWindow_isNoLongerACandidateAndSurvivesALaterDrop() {
        // The record is dead-held for good: it stays a member (restored once, closed with a click)
        // rather than becoming a standing target for the next unrelated tab close.
        val admitted = admittedAt(T0)
        val dead = step(prev = admitted.state, claimed = emptyMap(), anywhere = emptySet(), now = T0 + 1_000)
        val late = step(
            prev = dead.state, claimed = emptyMap(), anywhere = emptySet(),
            drops = 1, now = T0 + 1_000 + WINDOW + 1,
        )
        assertEquals(emptySet<String>(), late.evictedByClose)
        assertEquals(setOf("sid-a"), late.members)
        assertEquals("with no candidate to spend it on, the drop is simply kept", 1, late.state.pendingDrops.size)
    }

    // ── stickiness ───────────────────────────────────────────────────

    @Test fun membership_neverShrinksByTimeAlone() {
        // THE REGRESSION THIS FILE EXISTS TO PREVENT. No amount of elapsed time evicts a member;
        // only an attributed close, a binding, or a pre-admission death ever does.
        var s = admittedAt(T0)
        var now = T0
        repeat(8) {
            now += 3 * 3_600_000L   // 8 polls x 3h = a full day of wall clock
            s = step(prev = s.state, claimed = emptyMap(), anywhere = emptySet(), now = now)
            assertEquals("membership must survive arbitrary elapsed time", setOf("sid-a"), s.members)
        }
    }

    @Test fun longUnobservableStretchThenAliveAgain_keepsTheMemberAndNeverMarksItDead() {
        // A distro asleep across many polls, then readable again: the session was alive the whole
        // time and the ledger must never have started counting it out.
        var s = admittedAt(T0)
        var now = T0
        repeat(20) {
            now += 5_000
            s = step(
                prev = s.state, claimed = emptyMap(), anywhere = emptySet(),
                observable = emptySet(), now = now,
            )
            assertNull("an unobservable root proves nothing, poll after poll", deadSince(s, "sid-a"))
            assertEquals(setOf("sid-a"), s.members)
        }
        val back = step(prev = s.state, claimed = mapOf("sid-a" to old(T0)), now = now + 5_000)
        assertNull(deadSince(back, "sid-a"))
        assertEquals(setOf("sid-a"), back.members)
    }

    /** Two admitted sessions, both observed gone on the same later poll. */
    private fun twoAdmittedThenBothDead(): UnboundLedger.Step {
        val admitted = step(
            claimed = mapOf("sid-a" to old(T0), "sid-b" to old(T0)),
            now = T0,
        )
        assertEquals(setOf("sid-a", "sid-b"), admitted.members)
        return step(prev = admitted.state, claimed = emptyMap(), anywhere = emptySet(), now = T0 + 5_000)
    }
}
