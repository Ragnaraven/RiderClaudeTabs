package com.claudetabs

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory dead-strike counter for the per-sid eviction policy.
 *
 * The poll loop classifies each tracked sid as ALIVE or DEAD via the PID + sid cross-check
 * against `~/.claude/sessions/<pid>.json`:
 *  - File missing → DEAD (Claude exited and removed its pid file)
 *  - File exists with different sid → DEAD (PID was reused by an unrelated process)
 *  - File exists with our sid → ALIVE
 *
 * A single-poll DEAD verdict is too aggressive (Claude can be briefly between writes
 * during restart, scanner might miss it). So we count consecutive dead strikes per sid;
 * eviction only happens when [strikesNeeded] consecutive polls all classify the sid as DEAD.
 * Any ALIVE poll resets the counter to zero.
 *
 * The default of K=2 forgives a single transient miss without keeping zombies more than
 * ~10s past actual death (at the 5s poll cadence).
 *
 * Per-window instance: every Rider window's poll loop has its own tracker. Multiple windows
 * may independently decide to evict the same sid; the [ActiveSessionsStore] delete + the
 * [SessionBacklog] prepend are both idempotent / dedup-safe, so cross-window races are
 * benign.
 */
internal class EvictionTracker(
    private val strikesNeeded: Int = 2,
    private val minStrikeSpacingMs: Long = 4_000L,
) {

    private val strikes = ConcurrentHashMap<String, Int>()
    private val lastStrikeAt = ConcurrentHashMap<String, Long>()

    /** Sids this JVM has POSITIVELY observed alive at least once. Eviction (a destructive
     *  delete) is gated on membership here: a per-sid file whose recorded pid is dead but
     *  which we never watched run — a stale entry seeded from disk, a manual reseed, a
     *  backup-active.js write, anything added after the once-per-JVM startup reconcile — must
     *  NOT be deleted on the strength of a pid we never confirmed. Such entries are demoted to
     *  restore-pending instead. Append-only for the JVM's life (an evicted sid that genuinely
     *  resumes is simply seen alive again). */
    private val everSeenAlive: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Record a DEAD poll for [sid]. Returns true if this strike pushed [sid] to or past the
     *  eviction threshold (caller should now evict).
     *
     *  Strikes within [minStrikeSpacingMs] of the previous one don't increment — the tracker
     *  instance is shared across all open project windows in the same Rider JVM, and two
     *  windows polling milliseconds apart would otherwise burn both strikes in one logical
     *  poll round, defeating the K-poll grace. */
    fun recordDead(sid: String, now: Long = System.currentTimeMillis()): Boolean {
        val prev = lastStrikeAt[sid]
        if (prev != null && (now - prev) < minStrikeSpacingMs) {
            return (strikes[sid] ?: 0) >= strikesNeeded
        }
        lastStrikeAt[sid] = now
        val next = strikes.merge(sid, 1) { old, _ -> old + 1 } ?: 1
        return next >= strikesNeeded
    }

    /** Record an ALIVE poll for [sid] — resets its counter and marks it confirmed-alive. */
    fun recordAlive(sid: String) {
        strikes.remove(sid)
        lastStrikeAt.remove(sid)
        everSeenAlive.add(sid)
    }

    /** True if this JVM has ever observed [sid] alive. Gates the destructive eviction path. */
    fun hasBeenSeenAlive(sid: String): Boolean = sid in everSeenAlive

    /** Forget [sid] entirely (e.g. on actual eviction). */
    fun forget(sid: String) {
        strikes.remove(sid)
        lastStrikeAt.remove(sid)
    }

    /** Current strike count for [sid] (0 if untracked). For tests and diagnostics. */
    fun strikesFor(sid: String): Int = strikes[sid] ?: 0
}
