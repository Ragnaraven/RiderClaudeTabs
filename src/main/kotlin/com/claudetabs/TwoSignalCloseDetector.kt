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
     * callback — so the *only* way to tell "user clicked X on one tab" from "the window is
     * tearing down all its tabs" is to rule out the shutdown/teardown cases.
     *
     * [appExiting] (IDE restart / quit — e.g. a plugin-install restart) and [projectClosing]
     * (this project window closing) must BOTH be checked, and BOTH should be sampled LIVE at
     * the moment of the event — a pre-set flag races the teardown and loses, which is what
     * caused window-close to be mis-recorded as a pile of individual user tab-closes.
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
