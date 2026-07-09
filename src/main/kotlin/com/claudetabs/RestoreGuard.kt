package com.claudetabs

/**
 * Pure logic for the poll's retry-spawn resurrection guard — zero IDE imports, fully unit-testable.
 *
 * Retry-spawn (poll step 4) re-issues `claude --resume` for an `active-sessions` entry that sits at
 * pid=null. Its ONLY legitimate job is to recover a restore SEED whose startup spawn silently failed
 * (Terminal tool window not ready, widget creation threw). It must NEVER bring back a session the
 * user closed or that crashed — doing so is the "I closed a tab and it came back a minute later" bug.
 *
 * The distinction is made purely from per-Rider-session tracking sets: a session that was ever
 * alive, ever held a tab, is mid-close, already has a tab, or was user-closed is by definition NOT a
 * failed seed. Only a sid that was NEVER alive and NEVER held a widget this session is a genuine
 * retry candidate.
 */
internal object RestoreGuard {

    /**
     * True when [sid] must NOT be retry-spawned, based on per-session tracking sets:
     *  - [userClosed] — the user deliberately closed its tab (or did before a crash, hydrated from disk);
     *  - [everHadWidget] — it held a tab at some point this session, so its absence is a close/crash;
     *  - [everSeenAlive] — the poll saw its process alive at some point this session. This is the
     *    broader sibling of [everHadWidget]: it covers a user-opened tab the plugin tracked but never
     *    claimed a widget for (PID dig failed / ambiguous cwd), whose close can't be detected and
     *    which would otherwise demote to pid=null and resurrect;
     *  - [pendingClose] — a close is mid-confirmation (don't race signal 2);
     *  - [spawnedWidgets] — we already hold a live tab for it (the entry can sit at pid=null briefly
     *    after spawn while Claude's `--resume` writes its pid file).
     */
    fun blocksRetrySpawn(
        sid: String,
        userClosed: Set<String>,
        everHadWidget: Set<String>,
        everSeenAlive: Set<String>,
        pendingClose: Set<String>,
        spawnedWidgets: Set<String>,
    ): Boolean =
        sid in userClosed ||
            sid in everHadWidget ||
            sid in everSeenAlive ||
            sid in pendingClose ||
            sid in spawnedWidgets

    // NOTE: the old `planRestore` flood guard (staleness window + herd cap) was removed. It
    // retired genuinely-open tabs by age/count, which violates the restore contract (project
    // memory `tab-restore-contract`): a tab reopens unless the user X-closed it, regardless of
    // age. Accumulation is prevented by reliable X-close eviction at the source, and config-churn
    // safety by serialized spawns — not by gating which tabs restore.
}
