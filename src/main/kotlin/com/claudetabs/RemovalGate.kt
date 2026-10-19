package com.claudetabs

/**
 * Decides WHEN a session that has left `present` (its tab is gone) may leave the persisted
 * open-tabs snapshot — the restore authority.
 *
 * WHY THIS EXISTS. Removing a sid from the snapshot is the one irreversible decision this plugin
 * makes: after the next restart that conversation is not reopened. The old rule committed a single
 * absence immediately and deferred a bulk absence "one poll" — and a tab removal pokes the poll
 * loop, so that one poll was 250 ms away. Field losses from that: (a) a WSL distro died, the IDE
 * closed the tabs whose ptys had died, and eight sessions were committed out of their snapshots in
 * the twelve seconds before the IDE itself went down; (b) a bound tab's entry file was momentarily
 * unreadable (concurrent rewrite), the sid was absent for ONE poll, committed, and re-added six
 * seconds later — a crash inside that window loses the tab; (c) a session written into the
 * snapshot from outside (the recovery command) was absent from `present` and committed before the
 * retry-spawn pass could act on it.
 *
 * THE RULE. A departure is committed only when it has been confirmed for real:
 *  - the sid has been absent for at least [MIN_ABSENT_POLLS] polls AND [singleConfirmMs] — a
 *    flicker (the sid present again on the next poll) resets it;
 *  - its process was observed DEAD on a poll where its root was HEALTHY — a × kills the process,
 *    a flicker does not, and an unhealthy root (stopped, wedged, dying distro) proves nothing;
 *  - it is not a CRASH SUSPECT: a sid whose root was unhealthy at any poll while it was pending and
 *    before its death was seen. The IDE closes a tab on its own when the pty dies with the distro;
 *    that is not a close, and the session must come back at the next start. A suspect is held
 *    until its tab reappears (reset) or the IDE restarts (restored);
 *  - a BULK departure — two or more absences on the same root within [bulkWindowMs], across every
 *    open project — is additionally held until the root has stayed healthy for [bulkSettleMs]
 *    after the LAST death seen on that root: a crashing VM makes itself visible within seconds,
 *    a user closing several tabs does not make the root unhealthy.
 *
 * CLOSE EVIDENCE short-circuits all of that when it exists. At the instant a tab is removed the
 * poll loop can often ask whether the tab's own shell process was still alive (through the
 * widget's tty connector; a local `Process.isAlive`, never a share read — see the close probe).
 * A tab removed under a LIVING shell was closed by something other than its process dying: a ×,
 * or a tab MOVED (a re-layout, a tear-off, a re-created tool window — the Content goes, the shell
 * lives on). The two are told apart by what happens next: a × kills the shell, a move does not.
 * So `true` still requires the process seen dead on a healthy root, and skips everything else —
 * the poll count, the confirm time, the bulk settle, the crash-suspect marking. Closing five tabs
 * and quitting keeps them closed; a moved tab is held (alive) exactly as before. A tab removed
 * with its shell already DEAD closed itself — a crash, a teardown, a distro going down — and is a
 * crash suspect from the start. No evidence (a remote pty the terminal wraps without a local
 * process, a removal during teardown, a temporary removal) falls back to the rules above.
 * E2E-verified on the split terminal: × → alive, shell killed → dead, IDE quit → dead. The 1.x/2.x
 * attempts this replaces committed on the event alone and were falsified by re-layouts; the
 * death requirement is what they lacked.
 *
 * NO REMOVAL EVENT AT ALL is the strongest "not a ×" there is: every × goes through the content
 * manager and fires `contentRemoved`, so a sid that simply stops being present without one was
 * taken by something else — the IDE tearing the terminal tool window down before the
 * project-closing signal reached the plugin (field-observed: a frozen, memory-starved IDE took
 * ninety seconds to close a project; the tabs left the enumeration in waves, their processes
 * died, and fifteen sessions were committed out of the snapshot as closes just before the
 * shutdown froze it), a tool window re-created without events, a listener that was not on the
 * manager the tab lived in. Such a departure is a crash suspect from the start.
 *
 * COSTS, accepted: a × takes ~two polls to leave the snapshot instead of one; a × followed by an
 * IDE quit inside that window brings the tab back once. Several tabs closed within ten seconds
 * and then a quit inside [bulkSettleMs] bring them back once. A distro the user stopped on
 * purpose with tabs open reopens those tabs at the next start (they were never ×'d).
 *
 * Root-wide facts ([RootFacts]) are shared by every project's gate and merged back by the caller:
 * a burst is a property of the root, not of the project whose tab happened to go first.
 *
 * Pure: every observation, including `now`, is handed in.
 */
internal object RemovalGate {

    /** Polls a sid must be absent before it can be committed — one absent poll is a flicker. */
    const val MIN_ABSENT_POLLS = 2

    data class Pending(
        val rootId: String,
        val firstAbsentAt: Long,
        val absentPolls: Int,
        val deadSince: Long?,      // first poll it was observed dead with its root healthy
        val crashSuspect: Boolean, // root unhealthy while pending, before any death was seen
        /** Close evidence at removal: true = shell alive (a ×), false = shell dead (self-close),
         *  null = unknown. Fixed for the life of the pending entry. */
        val closedUnderLiveShell: Boolean? = null,
    )

    /** Per-project state carried between polls. */
    data class State(val pending: Map<String, Pending> = emptyMap())

    /** Root-wide facts, shared across projects; the caller merges [Step.rootFacts] back. */
    data class RootFacts(
        /** sid → when its tab was seen gone, on this root, in any project. Keyed by sid so a
         *  burst of several tabs in ONE poll (identical stamps) is counted as several. */
        val absenceStamps: Map<String, Long> = emptyMap(),
        /** Latest death observed on this root (any project). */
        val lastDeathAt: Long? = null,
    )

    data class Step(
        val state: State,
        val persisted: Set<String>,     // what to write: computed ∪ held
        val committed: Set<String>,     // left the snapshot this poll
        val held: Set<String>,          // absent but kept
        val rootFacts: Map<String, RootFacts>,
    )

    /**
     * @param prev the persisted snapshot as of the last poll
     * @param computed present sids plus warmup (see [ClaudeTabsHelpers.computeOpenSnapshot])
     * @param aliveAnywhere sids seen alive this poll, in any root or cwd
     * @param rootHealth per root: true = listed and probed, false = unhealthy, absent/null = unknown
     * @param rootOf the root a sid belongs to; a sid with no known root is treated as unhealthy
     *               (held) rather than guessed
     * @param rootFacts root-wide facts as merged so far (see [RootFacts])
     * @param closeEvidence sids whose tab removal was OBSERVED (a `contentRemoved` event), mapped to
     *   whether the shell behind the tab was alive at that instant (null = could not tell). A sid
     *   absent from this map vanished without an event — see the class KDoc.
     * @param decidedElsewhere sids another authority has already ruled closed this poll — the
     *   unbound ledger's attributed closes and cap sheds. Its tabs were never bound, so no removal
     *   event can name them; they are committed at once, never held.
     */
    fun step(
        prev: State,
        snapshot: Set<String>,
        computed: Set<String>,
        aliveAnywhere: Set<String>,
        rootHealth: Map<String, Boolean?>,
        rootOf: (String) -> String?,
        rootFacts: Map<String, RootFacts>,
        now: Long,
        singleConfirmMs: Long,
        bulkWindowMs: Long,
        bulkSettleMs: Long,
        closeEvidence: Map<String, Boolean?> = emptyMap(),
        decidedElsewhere: Set<String> = emptySet(),
    ): Step {
        val pending = LinkedHashMap<String, Pending>()
        val facts = HashMap<String, RootFacts>(rootFacts)

        // Present again → no longer a departure. Anything else that was pending stays pending.
        for ((sid, p) in prev.pending) if (sid !in computed) pending[sid] = p

        // New departures: in the snapshot, not computed, not already pending.
        val absent = snapshot - computed
        val committed = LinkedHashSet<String>()
        for (sid in absent) {
            if (sid in decidedElsewhere) { pending.remove(sid); committed.add(sid); continue }
            if (sid in pending) continue
            val root = rootOf(sid) ?: UNKNOWN_ROOT
            val observed = sid in closeEvidence
            val evidence = closeEvidence[sid]
            // A tab whose shell was already dead when it went closed itself; a tab that went with
            // no removal event at all was taken, not closed. Both are suspects from the start.
            pending[sid] = Pending(
                root, now, 0, null,
                crashSuspect = evidence == false || !observed,
                closedUnderLiveShell = evidence,
            )
            val f = facts[root] ?: RootFacts()
            facts[root] = f.copy(absenceStamps = f.absenceStamps + (sid to now))
        }
        // A pending sid that is no longer in the snapshot was removed by someone else
        // (an external clear) — nothing left to hold.
        pending.keys.retainAll(absent)

        // Observe this poll for every pending sid: another absent poll; death on a healthy root;
        // an unhealthy root before any death was seen makes it a crash suspect.
        for ((sid, p) in pending.entries.toList()) {
            val health = rootHealth[p.rootId]
            var next = p.copy(absentPolls = p.absentPolls + 1)
            if (health == true && sid !in aliveAnywhere && next.deadSince == null) {
                next = next.copy(deadSince = now)
                val f = facts[p.rootId] ?: RootFacts()
                facts[p.rootId] = f.copy(lastDeathAt = maxOf(f.lastDeathAt ?: 0L, now))
            } else if (health == false && next.deadSince == null && next.closedUnderLiveShell != true) {
                next = next.copy(crashSuspect = true)
            }
            pending[sid] = next
        }

        // Prune stamps nothing pending can still be batched with.
        val keepMs = keepStampsMs(bulkWindowMs, bulkSettleMs)
        for ((root, f) in facts.entries.toList()) {
            facts[root] = f.copy(absenceStamps = f.absenceStamps.filterValues { now - it <= keepMs })
        }

        // Commit.
        for ((sid, p) in pending.entries.toList()) {
            if (p.deadSince == null) continue
            // Closed under a living shell and now dead: a ×. Nothing else waits.
            if (p.closedUnderLiveShell == true) {
                committed.add(sid)
                pending.remove(sid)
                continue
            }
            if (p.crashSuspect) continue
            if (p.absentPolls < MIN_ABSENT_POLLS || now - p.firstAbsentAt < singleConfirmMs) continue
            val f = facts[p.rootId] ?: RootFacts()
            val batch = f.absenceStamps.values.count { kotlin.math.abs(it - p.firstAbsentAt) <= bulkWindowMs }
            if (batch >= 2) {
                if (rootHealth[p.rootId] != true) continue
                val lastDeath = f.lastDeathAt ?: continue
                if (now - lastDeath < bulkSettleMs) continue
            }
            committed.add(sid)
            pending.remove(sid)
        }

        val held = pending.keys.toSet()
        return Step(
            state = State(pending.toMap()),
            persisted = computed + held,
            committed = committed,
            held = held,
            rootFacts = facts,
        )
    }

    /** Merge one project's updated facts into the shared map (union of stamps, latest death). */
    fun merge(
        into: MutableMap<String, RootFacts>,
        from: Map<String, RootFacts>,
        now: Long,
        bulkWindowMs: Long,
        bulkSettleMs: Long,
    ) {
        val keepMs = keepStampsMs(bulkWindowMs, bulkSettleMs)
        for ((root, f) in from) {
            into.compute(root) { _, old ->
                val stamps = ((old?.absenceStamps ?: emptyMap()) + f.absenceStamps)
                    .filterValues { now - it <= keepMs }
                val death = listOfNotNull(old?.lastDeathAt, f.lastDeathAt).maxOrNull()
                RootFacts(stamps, death)
            }
        }
    }

    /** A stamp matters while a sid absent at that time can still be waiting out the settle. */
    private fun keepStampsMs(bulkWindowMs: Long, bulkSettleMs: Long) = 2 * (bulkWindowMs + bulkSettleMs)

    private const val UNKNOWN_ROOT = "?"
}
