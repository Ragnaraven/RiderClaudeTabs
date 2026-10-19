package com.claudetabs

/**
 * Sticky membership ledger for sessions that are ALIVE and claimed by this project but bound to no
 * terminal tab — the sessions the open-tabs snapshot used to lose on every single poll.
 *
 * WHY THIS EXISTS. The open-tabs snapshot is the only authority for which sessions are reopened
 * after an IDE restart, and it used to admit a session only when the plugin could bind it to a
 * specific terminal tab widget. Binding a HAND-OPENED tab requires an unambiguous 1:1 cwd pairing
 * inside a five-minute freshness window — which never happens once several tabs share one project
 * directory, the normal way this plugin's users work. So a session the user was actively typing in
 * was simply absent from the snapshot, poll after poll, and was gone after the next restart. This
 * ledger admits such a session on its own LIVENESS, with no tab required, and the snapshot counts
 * its members as open.
 *
 * Membership is deliberately STICKY: admitted once the session has been alive for `admitMs`, and
 * left only three ways — an attributed close (a tab disappeared next to the session's death, i.e.
 * the user ×'d it), the sid becoming bound (from then on the bound arm owns it), or dying before it
 * was ever admitted. There is NO timed eviction here, and adding one would be the regression this
 * file was written to end: a spurious tab restored once costs the user a click, an evicted one
 * costs the user their work.
 *
 * ACCEPTED LIMITATIONS — all three are deliberate trades; do not "fix" one in isolation:
 *  - a plain-shell tab closed inside the attribution window of an UNRELATED session's crash-death
 *    pairs the two, and that session is evicted (it will not be restored);
 *  - a × whose Claude process lingers past the window leaves an admitted member behind, so that
 *    session is reopened once on the next restart — one click to close;
 *  - when fewer unbound tabs vanished than there are recent deaths, which × belongs to which death
 *    is unknowable, so NOTHING is evicted. The rule prefers a zombie tab over evicting the session
 *    the user is still working in.
 *
 * Pure: no platform, filesystem, process or clock access — every observation, including `now`,
 * is handed in by the poll loop, so the whole policy is unit-testable.
 */
internal object UnboundLedger {

    /** One alive session as seen this poll. [headless] true = launched non-interactively (never a tab); null = unknown. */
    data class Alive(val rootId: String, val startedAt: Long?, val headless: Boolean?)

    /**
     * One tracked session. [rootId] is the root the session was first seen under and never changes
     * afterwards: it is what transition 5 asks the observability question about, and a sid belongs
     * to exactly one root's history.
     */
    data class Record(
        val rootId: String,
        val startedAt: Long?,      // the session's own start stamp when known
        val firstAliveAt: Long,    // when this ledger first saw it alive
        val admitted: Boolean,
        val deadSince: Long?,      // first poll it was observed dead (root observable); null while alive/held
    )

    /** Persistent ledger state between polls. Immutable; [step] returns the next one. */
    data class State(
        val records: Map<String, Record> = emptyMap(),
        val pendingDrops: List<Long> = emptyList(),
        /** Consecutive polls the membership has exceeded the unbound-tab count (see the cap in [step]). */
        val overCapPolls: Int = 0,
    )

    /** One poll's outcome: the state to carry forward, plus everything the caller has to act on. */
    data class Step(
        val state: State,
        val members: Set<String>,          // sids that count as OPEN this poll
        val evictedByClose: Set<String>,   // attributed to a tab close this poll
        val purged: Set<String>,           // removed because they became bound (neverAdmit)
        val shedByCap: Set<String> = emptySet(), // dead members with no tab left to be in
    )

    /** Polls the membership must stay over the tab count before anything is shed: one odd
     *  enumeration (a tool window mid-re-creation reports no tabs) must never cost a session. */
    const val CAP_CONFIRM_POLLS = 3

    /**
     * Advance the ledger by one poll. The transitions are applied in a fixed order, and the order
     * is load-bearing:
     *
     *  1. every record whose sid is in [neverAdmit] is REMOVED, not merely blocked — the bound arm
     *     owns it from the moment a tab binding exists, and two arms counting one session double
     *     it in the snapshot;
     *  2. a sid in [aliveClaimed] the ledger has never seen is added, unless it is `headless`
     *     (a non-interactive run has no tab to reopen and must never enter restore);
     *  3. a sid in [aliveClaimed] the ledger knows is un-marked dead, and is ADMITTED once it has
     *     been alive for [admitMs] — measured from its own `startedAt` when known, so a session
     *     that has been running for hours is admitted by the very first poll that sees it (the
     *     IDE-restart case), and only from `firstAliveAt` when the start stamp is unknown;
     *  4. a known sid alive somewhere else ([aliveAnywhere], e.g. another root or a cwd this
     *     project does not claim) is HELD unchanged — a claim can flicker while the process is
     *     plainly still running;
     *  5. a known sid that is absent while its root is NOT in [observableRoots] is HELD unchanged,
     *     `deadSince` included: a scan that failed (a sleeping WSL distro, an unreadable home)
     *     proves nothing, and letting it start a death clock is how a working session gets evicted
     *     while the user is looking at it;
     *  6. a known sid absent with its root observable is genuinely gone: an un-admitted one is
     *     dropped silently (a short-lived process that never counted is noise), an admitted one
     *     starts holding `deadSince` and STAYS a member;
     *  7. close attribution, below;
     *  8. THE CAP. There cannot be more DEAD unbound sessions than there are unbound TABS — a
     *     dead member beyond that count has no tab it could still be sitting in. When they have
     *     exceeded [unboundTabCount] for [CAP_CONFIRM_POLLS] polls running, the excess is shed from
     *     the DEAD members, oldest death first; an alive member is never shed (which alive session
     *     is the tabless one is unknowable, and restore refuses a still-running session anyway —
     *     once it dies it is dead, and shed). This is what closes the two holes attribution alone
     *     leaves open: a session that ran in the project WITHOUT a tab (another window, a plain
     *     terminal) and a × whose process outlived the pairing window. Field-observed without it:
     *     14 tabs, all bound, and three members nothing could ever close — restored as tabs the
     *     user had never had. A null count means "not observed yet" and caps nothing;
     *  9. members are the admitted records — alive or dead-held alike.
     *
     * CLOSE ATTRIBUTION (7). [unboundTabDrop] unbound tabs disappearing is the only evidence a ×
     * ever produces here, and it arrives on its own schedule: the tab can vanish a poll before the
     * process dies, or a poll after. So each drop is remembered as a timestamp until it is either
     * spent or ages out of [attributionMs], and candidates are the admitted records whose death was
     * observed within that same window. When there are at least as many drops as candidates, every
     * candidate has a × to explain it and all of them are evicted, spending one drop each (oldest
     * first). When there are fewer, the pairing is ambiguous and nothing is evicted — see the
     * accepted limitations on [UnboundLedger]. A death that ages past the window stops being a
     * candidate for good; a later drop cannot evict it, and it stays a member until it comes alive
     * again (which re-arms it) or is purged.
     */
    fun step(
        prev: State,
        aliveClaimed: Map<String, Alive>,  // alive AND cwd claimed by this project (exclusively)
        aliveAnywhere: Set<String>,        // alive in any root, any cwd (superset of aliveClaimed.keys)
        observableRoots: Set<String>,      // roots whose scan actually succeeded this poll
        neverAdmit: Set<String>,           // sids that ever had a tab binding — the bound arm owns them
        unboundTabDrop: Int,               // how many unbound tabs disappeared since the last poll (>= 0)
        now: Long,
        admitMs: Long,                     // alive this long (from startedAt, else firstAliveAt) before counting
        attributionMs: Long,               // window inside which a death and a tab drop are paired
        unboundTabCount: Int? = null,      // terminal tabs with no binding right now; null = unknown
    ): Step {
        val records = LinkedHashMap(prev.records)

        // ── 1. The bound arm takes ownership ────────────────────────────
        val purged = prev.records.keys.intersect(neverAdmit)
        records.keys.removeAll(purged)

        // ── 2 + 3. Alive and claimed by this project ────────────────────
        for ((sid, seen) in aliveClaimed) {
            if (sid in neverAdmit) continue
            val existing = records[sid]
            if (existing == null && seen.headless == true) continue
            val record = existing ?: Record(
                rootId = seen.rootId,
                startedAt = seen.startedAt,
                firstAliveAt = now,
                admitted = false,
                deadSince = null,
            )
            val aliveSince = record.startedAt ?: record.firstAliveAt
            records[sid] = record.copy(
                deadSince = null,
                // Once admitted, always admitted (until evicted or purged).
                admitted = record.admitted || now - aliveSince >= admitMs,
            )
        }

        // ── 4 + 5 + 6. Everything this project did not see alive ────────
        for (sid in records.keys.toList()) {
            if (sid in aliveClaimed) continue
            if (sid in aliveAnywhere) continue                  // 4: alive elsewhere — hold
            val record = records.getValue(sid)
            if (record.rootId !in observableRoots) continue     // 5: root unreadable — proves nothing
            if (!record.admitted) records.remove(sid)           // 6: never counted, never missed
            else if (record.deadSince == null) records[sid] = record.copy(deadSince = now)
        }

        // ── 7. Close attribution ────────────────────────────────────────
        val drops = ArrayList(prev.pendingDrops)
        repeat(maxOf(unboundTabDrop, 0)) { drops.add(now) }     // defensive max: a negative delta is not evidence
        drops.sort()
        // Oldest first, so spending drops below spends the ones closest to expiry.
        var pendingDrops = drops.filter { now - it <= attributionMs }
        val candidates = records.filterValues { isCloseCandidate(it, now, attributionMs) }.keys.toSet()
        val evictedByClose: Set<String>
        if (candidates.isNotEmpty() && pendingDrops.size >= candidates.size) {
            evictedByClose = candidates
            records.keys.removeAll(candidates)
            pendingDrops = pendingDrops.drop(candidates.size)
        } else {
            // Ambiguous, or nothing plausible has died yet — keep the drops, which either pair with
            // a death observed on a later poll or expire harmlessly.
            evictedByClose = emptySet()
        }

        // ── 8. The cap: no more unbound members than unbound tabs ──────────
        val admitted = records.filterValues { it.admitted }
        // Sheddable = dead for longer than the attribution window. A fresher death still belongs
        // to close attribution; and the confirmation count only runs while something IS sheddable
        // — otherwise an alive tabless session (never sheddable) keeps the membership over the cap
        // indefinitely, the count latches, and the next death is shed the instant it happens.
        val sheddable = admitted.filterValues { it.deadSince != null && now - it.deadSince > attributionMs }
        // Counted against the DEAD members only. An alive member may be running with no tab at
        // all (a plain terminal in the project directory), so it must not use up a tab's place:
        // counting it would shed a dead session whose tab is still open the moment it aged past
        // the window. The cost of that choice is a dead session surviving while its tab is reused
        // by a new one — reopened once, a click to close.
        val dead = admitted.count { it.value.deadSince != null }
        val excess = if (unboundTabCount == null) 0 else dead - maxOf(unboundTabCount, 0)
        val overCap = excess > 0 && sheddable.isNotEmpty()
        val overCapPolls = if (overCap) prev.overCapPolls + 1 else 0
        val shedByCap = LinkedHashSet<String>()
        if (overCap && overCapPolls >= CAP_CONFIRM_POLLS) {
            sheddable.entries
                .sortedBy { it.value.deadSince }
                .take(excess)
                .forEach { shedByCap.add(it.key) }
            records.keys.removeAll(shedByCap)
        }

        // ── 9. Membership ───────────────────────────────────────────────
        val members = records.filterValues { it.admitted }.keys.toSet()
        return Step(
            state = State(records.toMap(), pendingDrops, overCapPolls),
            members = members,
            evictedByClose = evictedByClose,
            purged = purged,
            shedByCap = shedByCap,
        )
    }

    /** True when [r]'s death is recent enough that an unbound tab disappearing now could be its ×.
     *  Un-admitted records never qualify — they are removed at transition 6 and never counted. */
    private fun isCloseCandidate(r: Record, now: Long, attributionMs: Long): Boolean {
        val died = r.deadSince ?: return false
        return r.admitted && now - died <= attributionMs
    }
}
