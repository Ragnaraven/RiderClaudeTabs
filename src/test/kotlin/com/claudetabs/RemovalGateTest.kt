package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovalGateTest {

    private val single = 4_000L
    private val window = 10_000L
    private val settle = 20_000L
    private val root = "root-a"

    private class Sim(val rootOf: Map<String, String>) {
        var state = RemovalGate.State()
        var snapshot: Set<String> = emptySet()
        val facts = HashMap<String, RemovalGate.RootFacts>()
        var now = 100_000L

        fun poll(
            computed: Set<String>,
            alive: Set<String>,
            health: Map<String, Boolean?>,
            advance: Long = 5_000L,
            single: Long = 4_000L,
            window: Long = 10_000L,
            settle: Long = 20_000L,
            evidence: Map<String, Boolean?>? = null,
            decided: Set<String> = emptySet(),
        ): RemovalGate.Step {
            now += advance
            // Default: every departure was OBSERVED (a removal event fired) with unknown shell
            // liveness — the slow rules' input. A test passes a map to model evidence, or an
            // empty map to model a tab that vanished with no event at all.
            val observed = evidence ?: (snapshot - computed - state.pending.keys).associateWith { null }
            val step = RemovalGate.step(
                prev = state,
                snapshot = snapshot,
                computed = computed,
                aliveAnywhere = alive,
                rootHealth = health,
                rootOf = { rootOf[it] },
                rootFacts = facts,
                now = now,
                singleConfirmMs = single,
                bulkWindowMs = window,
                bulkSettleMs = settle,
                closeEvidence = observed,
                decidedElsewhere = decided,
            )
            state = step.state
            snapshot = step.persisted
            RemovalGate.merge(facts, step.rootFacts, now, window, settle)
            return step
        }
    }

    private fun sim(vararg sids: String) = Sim(sids.associateWith { root }).also { it.snapshot = sids.toSet() }
    private val healthy = mapOf(root to true)
    private val unhealthy = mapOf(root to false)
    private val unknown = mapOf<String, Boolean?>(root to null)

    @Test fun singleCloseCommitsAfterTwoPollsAndDeath() {
        val s = sim("a", "b")
        // Poke poll 250 ms after the ×: absent, process still dying.
        var st = s.poll(computed = setOf("b"), alive = setOf("a", "b"), health = healthy, advance = 250)
        assertEquals(setOf("a", "b"), st.persisted)
        assertEquals(setOf("a"), st.held)
        // Next poll: dead, two polls, > 4 s → committed.
        st = s.poll(computed = setOf("b"), alive = setOf("b"), health = healthy)
        assertEquals(setOf("a"), st.committed)
        assertEquals(setOf("b"), st.persisted)
        assertTrue(st.state.pending.isEmpty())
    }

    @Test fun flickerNeverCommits() {
        val s = sim("a")
        var st = s.poll(computed = emptySet(), alive = setOf("a"), health = healthy)
        assertEquals(setOf("a"), st.persisted)
        st = s.poll(computed = setOf("a"), alive = setOf("a"), health = healthy)
        assertEquals(setOf("a"), st.persisted)
        assertTrue(st.committed.isEmpty())
        assertTrue(st.state.pending.isEmpty())
    }

    @Test fun absentButAliveIsHeldIndefinitely() {
        val s = sim("a")
        repeat(50) {
            val st = s.poll(computed = emptySet(), alive = setOf("a"), health = healthy)
            assertEquals(setOf("a"), st.persisted)
            assertTrue(st.committed.isEmpty())
        }
    }

    @Test fun deathOnUnhealthyRootDoesNotCount() {
        val s = sim("a")
        repeat(10) {
            val st = s.poll(computed = emptySet(), alive = emptySet(), health = unhealthy)
            assertEquals(setOf("a"), st.persisted)
        }
        // Root back, process dead — but it is a crash suspect now: still held.
        repeat(10) {
            val st = s.poll(computed = emptySet(), alive = emptySet(), health = healthy)
            assertEquals(setOf("a"), st.persisted)
            assertTrue(st.state.pending.getValue("a").crashSuspect)
        }
    }

    @Test fun unknownRootHealthHoldsWithoutSuspecting() {
        val s = sim("a")
        repeat(3) { s.poll(computed = emptySet(), alive = emptySet(), health = unknown) }
        assertFalse(s.state.pending.getValue("a").crashSuspect)
        // Healthy again and dead → commits (single, polls and time satisfied).
        val st = s.poll(computed = emptySet(), alive = emptySet(), health = healthy)
        assertEquals(setOf("a"), st.committed)
    }

    @Test fun unhealthyAfterDeathSeenIsNotASuspect() {
        val s = sim("a")
        s.poll(computed = emptySet(), alive = emptySet(), health = healthy, advance = 250)
        val st = s.poll(computed = emptySet(), alive = emptySet(), health = unhealthy)
        assertEquals(setOf("a"), st.committed)
    }

    @Test fun bulkOnHealthyRootWaitsForSettleAfterLastDeath() {
        val s = sim("a", "b", "c")
        // Two tabs vanish in one poll; the root stays healthy; both dead next poll.
        s.poll(computed = setOf("c"), alive = setOf("a", "b", "c"), health = healthy, advance = 250)
        var st = s.poll(computed = setOf("c"), alive = setOf("c"), health = healthy)
        assertEquals(setOf("a", "b"), st.held)
        // 15 s after the deaths: still held (settle is 20 s).
        st = s.poll(computed = setOf("c"), alive = setOf("c"), health = healthy, advance = 15_000)
        assertEquals(setOf("a", "b"), st.held)
        // 25 s after: committed.
        st = s.poll(computed = setOf("c"), alive = setOf("c"), health = healthy, advance = 10_000)
        assertEquals(setOf("a", "b"), st.committed)
        assertEquals(setOf("c"), st.persisted)
    }

    @Test fun bulkDuringOutageIsHeldForever() {
        val s = sim("a", "b", "c")
        // Distro dies: root unhealthy, tabs closed by the IDE, processes gone.
        s.poll(computed = setOf("c"), alive = emptySet(), health = unhealthy, advance = 250)
        s.poll(computed = emptySet(), alive = emptySet(), health = unhealthy, advance = 700)
        // Distro restarted an hour later: healthy, everything dead.
        repeat(20) {
            val st = s.poll(computed = emptySet(), alive = emptySet(), health = healthy, advance = 180_000)
            assertEquals(setOf("a", "b", "c"), st.persisted)
            assertTrue(st.committed.isEmpty())
        }
    }

    @Test fun rootWideBurstMakesAnotherProjectsSingleBulk() {
        // Project B has one tab; project A's burst on the same root is in the shared facts.
        val s = sim("solo")
        s.facts[root] = RemovalGate.RootFacts(absenceStamps = mapOf("p" to s.now + 4_000, "q" to s.now + 4_100), lastDeathAt = null)
        s.poll(computed = emptySet(), alive = setOf("solo"), health = healthy, advance = 5_000)
        val st = s.poll(computed = emptySet(), alive = emptySet(), health = healthy)
        assertEquals(setOf("solo"), st.held) // bulk: waits for the settle window
        val later = s.poll(computed = emptySet(), alive = emptySet(), health = healthy, advance = 25_000)
        assertEquals(setOf("solo"), later.committed)
    }

    @Test fun twoSinglesThreeSecondsApartBecomeABatch() {
        val s = sim("a", "b")
        s.poll(computed = setOf("b"), alive = setOf("a", "b"), health = healthy, advance = 250)
        s.poll(computed = emptySet(), alive = setOf("b"), health = healthy, advance = 3_000)
        // a: dead, 2 polls, > 4 s — but batch is 2 → settle applies.
        val st = s.poll(computed = emptySet(), alive = emptySet(), health = healthy, advance = 3_000)
        assertEquals(setOf("a", "b"), st.held)
        val later = s.poll(computed = emptySet(), alive = emptySet(), health = healthy, advance = 25_000)
        assertEquals(setOf("a", "b"), later.committed)
    }

    @Test fun externallyAddedDeadSidStaysForAtLeastTwoPolls() {
        val s = sim()
        s.snapshot = setOf("x") // written by the recovery tool; never present, already dead
        val first = s.poll(computed = emptySet(), alive = emptySet(), health = healthy)
        assertEquals(setOf("x"), first.persisted)
        val second = s.poll(computed = emptySet(), alive = emptySet(), health = healthy, advance = 3_000)
        assertEquals(setOf("x"), second.persisted) // < singleConfirmMs since first absence
        // Spawned meanwhile: present again → pending cleared.
        val third = s.poll(computed = setOf("x"), alive = setOf("x"), health = healthy)
        assertTrue(third.state.pending.isEmpty())
    }

    @Test fun externalClearDropsPendingWithoutCommitting() {
        val s = sim("a")
        s.poll(computed = emptySet(), alive = setOf("a"), health = healthy)
        s.snapshot = emptySet() // cleared from outside
        val st = s.poll(computed = emptySet(), alive = setOf("a"), health = healthy)
        assertTrue(st.state.pending.isEmpty())
        assertTrue(st.committed.isEmpty())
        assertTrue(st.persisted.isEmpty())
    }

    @Test fun unknownRootIsNeverCommitted() {
        val s = Sim(emptyMap()).also { it.snapshot = setOf("a") }
        repeat(5) {
            val st = s.poll(computed = emptySet(), alive = emptySet(), health = healthy)
            assertEquals(setOf("a"), st.persisted)
        }
    }

    @Test fun closedUnderALiveShellCommitsAsSoonAsTheProcessIsSeenDead() {
        val s = sim("a", "b", "c")
        // Three tabs x'd in one poll, shells alive at removal, processes still dying: held.
        var st = s.poll(
            computed = emptySet(), alive = setOf("a", "b", "c"), health = healthy, advance = 250,
            evidence = mapOf("a" to true, "b" to true, "c" to true),
        )
        assertTrue(st.committed.isEmpty())
        // Dead on the next poll: committed at once, no confirm time, no bulk settle.
        st = s.poll(computed = emptySet(), alive = emptySet(), health = healthy)
        assertEquals(setOf("a", "b", "c"), st.committed)
        assertTrue(st.persisted.isEmpty())
    }

    @Test fun aMovedTabReadsAliveAndStaysAliveSoItIsHeld() {
        val s = sim("a")
        s.poll(computed = emptySet(), alive = setOf("a"), health = healthy, advance = 250, evidence = mapOf("a" to true))
        repeat(20) {
            val st = s.poll(computed = emptySet(), alive = setOf("a"), health = healthy)
            assertEquals(setOf("a"), st.persisted)
            assertTrue(st.committed.isEmpty())
        }
        // Present again once the swapped Content is mapped back: reset.
        val back = s.poll(computed = setOf("a"), alive = setOf("a"), health = healthy)
        assertTrue(back.state.pending.isEmpty())
    }

    @Test fun closedWithADeadShellIsACrashSuspectEvenOnAHealthyRoot() {
        val s = sim("a")
        s.poll(computed = emptySet(), alive = emptySet(), health = healthy, advance = 250, evidence = mapOf("a" to false))
        repeat(20) {
            val st = s.poll(computed = emptySet(), alive = emptySet(), health = healthy, advance = 30_000)
            assertEquals(setOf("a"), st.persisted)
            assertTrue(st.state.pending.getValue("a").crashSuspect)
        }
    }

    @Test fun evidenceIsFixedWhenTheDepartureIsRecorded() {
        val s = sim("a")
        // No evidence at the first absent poll; evidence arriving later changes nothing.
        s.poll(computed = emptySet(), alive = setOf("a"), health = healthy, advance = 250)
        val st = s.poll(computed = emptySet(), alive = setOf("a"), health = healthy, evidence = mapOf("a" to true))
        assertTrue(st.committed.isEmpty())
        assertEquals(setOf("a"), st.held)
    }

    @Test fun liveShellEvidenceOnAnUnhealthyRootWaitsForAHealthyDeath() {
        val s = sim("a")
        var st = s.poll(computed = emptySet(), alive = emptySet(), health = unhealthy, advance = 250, evidence = mapOf("a" to true))
        assertTrue(st.committed.isEmpty())
        assertFalse(st.state.pending.getValue("a").crashSuspect)
        st = s.poll(computed = emptySet(), alive = emptySet(), health = healthy)
        assertEquals(setOf("a"), st.committed)
    }

    @Test fun vanishedWithoutARemovalEventIsHeldAsASuspect() {
        val s = sim("a", "b", "c")
        // The tool window torn down before the project-closing signal: tabs leave the
        // enumeration in waves, no contentRemoved for any of them, processes die, root healthy.
        s.poll(computed = setOf("b", "c"), alive = setOf("a", "b", "c"), health = healthy, advance = 250, evidence = emptyMap())
        s.poll(computed = emptySet(), alive = emptySet(), health = healthy, evidence = emptyMap())
        repeat(10) {
            val st = s.poll(computed = emptySet(), alive = emptySet(), health = healthy, advance = 30_000, evidence = emptyMap())
            assertEquals(setOf("a", "b", "c"), st.persisted)
            assertTrue(st.committed.isEmpty())
        }
    }

    @Test fun aLedgerRulingCommitsAtOnceWithoutAnyEvent() {
        val s = sim("a", "b")
        // The unbound ledger attributed a's × (its tab was never bound, so no event names it).
        val st = s.poll(computed = setOf("b"), alive = setOf("a", "b"), health = healthy, advance = 250, evidence = emptyMap(), decided = setOf("a"))
        assertEquals(setOf("a"), st.committed)
        assertEquals(setOf("b"), st.persisted)
        assertTrue(st.state.pending.isEmpty())
    }

    @Test fun mergeKeepsLatestDeathAndUnionOfStamps() {
        val into = HashMap<String, RemovalGate.RootFacts>()
        into[root] = RemovalGate.RootFacts(mapOf("a" to 1_000L), 2_000L)
        RemovalGate.merge(into, mapOf(root to RemovalGate.RootFacts(mapOf("a" to 1_000L, "b" to 1_500L), 1_800L)), now = 3_000L, bulkWindowMs = 10_000L, bulkSettleMs = 20_000L)
        assertEquals(mapOf("a" to 1_000L, "b" to 1_500L), into.getValue(root).absenceStamps)
        assertEquals(2_000L, into.getValue(root).lastDeathAt)
    }
}
