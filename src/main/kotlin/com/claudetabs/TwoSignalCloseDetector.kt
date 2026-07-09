package com.claudetabs

internal object TwoSignalCloseDetector {

    sealed class Signal1 {
        object SkipAppExiting : Signal1()
        object SkipProjectClosing : Signal1()
        object SkipTemporary : Signal1()
        object SkipNoSid : Signal1()
        data class AddToPending(val sid: String) : Signal1()
    }

    /**
     * Classify a terminal content-removal. The JetBrains platform routes single-tab closes,
     * whole-window/app shutdown, and tab drag/split through the SAME `contentRemoveQuery`
     * callback, so we rule out the teardown cases and treat what's left as a user close:
     *
     *  - [appExiting] (IDE restart/quit, e.g. a plugin-install restart) and [projectClosing]
     *    (this window closing) — teardown guards, sampled LIVE at the moment of the event.
     *  - [isTemporary] — tab drag/reorder/split (TEMPORARY_REMOVED_KEY), not a close.
     *
     * What remains — a single tab removed while the project and app are alive — is a user close;
     * it goes to pending and is confirmed by signal 2 (the Claude process then dying). Process
     * death alone NEVER reaches here (that path only demotes to restore-pending), so reloads and
     * crashes can't delete a session — only a real tab removal can.
     */
    fun decideOnRemoveQuery(
        projectClosing: Boolean,
        isTemporary: Boolean,
        sid: String?,
        appExiting: Boolean = false,
    ): Signal1 {
        if (appExiting) return Signal1.SkipAppExiting
        if (projectClosing) return Signal1.SkipProjectClosing
        if (isTemporary) return Signal1.SkipTemporary
        if (sid == null) return Signal1.SkipNoSid
        return Signal1.AddToPending(sid)
    }

    data class ConfirmResult(
        val confirmed: Set<String>,
        val expired: Set<String>,
        val kept: Set<String>,
    )

    val PENDING_EXPIRY_MS: Long = 30000L

    /** Result of [attributeCloses]: which sids a batch of unattributed closes resolved to
     *  (mark user-closed + evict), and the close tokens still awaiting a vanisher. */
    data class CloseAttribution(
        val closedSids: Set<String>,
        val remainingCloses: List<Long>,
    )

    /**
     * Attribute "unattributed" tab closes to the sessions whose processes have since vanished.
     *
     * The reworked terminal can hide a tab's session id, so an X on an unclaimed tab reaches
     * `contentRemoveQuery` with no sid — the close would otherwise be lost and the session would
     * demote to a restore-pending seed and wrongly reopen (Rule 2 violation). Instead the listener
     * records a close TOKEN (a timestamp); this pass ties each pending token to a project-owned
     * session that was alive last poll and is dead now ([vanished]).
     *
     * Safety (protects Rule 4 — a crash must NOT close tabs): a crash fires NO close token
     * (contentRemoveQuery only fires on a real tab removal, and teardown is filtered before a
     * token is recorded). And if MORE sessions vanished than there are close tokens, some vanish
     * is unexplained (a coincident crash) — so NOTHING is attributed. Only when every vanisher is
     * covered by a close token do we attribute, oldest tokens first. Expired tokens are dropped.
     */
    fun attributeCloses(
        unattributedCloses: List<Long>,
        vanished: List<String>,
        now: Long,
        expiryMs: Long = PENDING_EXPIRY_MS,
    ): CloseAttribution {
        val live = unattributedCloses.filter { now - it <= expiryMs }.sorted()
        if (vanished.isEmpty() || live.isEmpty()) return CloseAttribution(emptySet(), live)
        // Unexplained vanish (more deaths than closes) → likely a crash coincided; attribute none.
        if (vanished.size > live.size) return CloseAttribution(emptySet(), live)
        return CloseAttribution(vanished.toSet(), live.drop(vanished.size))
    }

    fun confirmPending(
        pendingClose: Map<String, Long>,
        aliveSids: Set<String>,
        now: Long,
        expiryMs: Long = PENDING_EXPIRY_MS,
    ): ConfirmResult {
        val confirmed = mutableSetOf<String>()
        val expired = mutableSetOf<String>()
        val kept = mutableSetOf<String>()
        for ((sid, addedAt) in pendingClose) {
            val age = now - addedAt
            if (sid !in aliveSids) {
                confirmed.add(sid)
            } else if (age > expiryMs) {
                expired.add(sid)
            } else {
                kept.add(sid)
            }
        }
        return ConfirmResult(confirmed, expired, kept)
    }
}
