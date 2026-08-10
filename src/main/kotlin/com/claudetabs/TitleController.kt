package com.claudetabs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the `userDefinedTitle` of every tab hosting a Claude session and drives the
 * thinking animation.
 *
 * Every [TICK_MS] (off-EDT): for each sid with a known widget, read the per-sid entry
 * (userName, cached topic, pid) and the session's live `~/.claude/sessions/<pid>.json`
 * (busy status + live topic), then hop to EDT and let [TitleModel.tick] decide what to
 * write. Tabs stuck at "Local" (or clobbered by anything) self-heal within one tick —
 * enforcement replaces every one-shot title write the plugin used to do.
 *
 * A user rename (IDE rename dialog) is ADOPTED: the text becomes the entry's persistent
 * `userName` and the controller keeps managing the glyph around it. `ModalityState.nonModal()`
 * keeps our writes out from under the open rename dialog.
 */
internal class TitleController(
    private val project: Project,
    private val storage: ClaudeTabsStorage,
    private val ctx: ClaudeTabWatcherStartup.ProjectCtx,
) {
    companion object {
        private val LOG = Logger.getInstance(TitleController::class.java)
        const val TICK_MS = 450L
    }

    private var frame = 0

    /** Adopted-but-not-yet-persisted user names, overlaid on the disk read so a rename
     *  can't be reverted by a tick that races the async persistence. */
    private val pendingUserNames = ConcurrentHashMap<String, String>()

    /** sid → last base name logged for a reworked tab (dedup so the per-frame animation apply
     *  doesn't spam idea.log; only a real name change re-logs). */
    private val lastLoggedReworkedName = ConcurrentHashMap<String, String>()

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                tickOnce(this)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs][title] tick failed: ${e.message}")
            }
            delay(TICK_MS)
        }
    }

    private data class Snapshot(
        val sid: String,
        val widget: TerminalWidget,
        val userName: String?,
        val liveTopic: String?,
        val cachedTopic: String?,
        val busy: Boolean,
    )

    /** Per-tick state for a widget-less reworked (agent-button) tab, titled via its own
     *  [ReworkedTerminalBridge.titlesByContent] TerminalTitle rather than a classic widget. */
    private data class ReworkedSnap(
        val content: Content,
        val sid: String,
        val userName: String?,
        val liveTopic: String?,
        val cachedTopic: String?,
        val busy: Boolean,
    )

    private fun tickOnce(scope: CoroutineScope) {
        if (project.isDisposed) return
        val widgets = ctx.spawnedWidgets.entries.toList()
        // Reworked agent tabs are bound Content-only (no classic widget), so they never appear in
        // spawnedWidgets and the widget path below can't title them. Collect them separately and
        // push the name onto the Content directly.
        val widgetlessBound = ctx.contentToSid.entries
            .filter { (_, sid) -> sid !in ctx.spawnedWidgets.keys }
            .map { it.key to it.value }
        if (widgets.isEmpty() && widgetlessBound.isEmpty()) return
        frame++

        // Off-EDT: gather per-reworked-tab state (userName, live/cached topic, busy). Applied on the
        // EDT below via the tab's own TerminalTitle — the SAME path as classic widgets.
        val reworkedSnaps = widgetlessBound.mapNotNull { (content, sid) ->
            val entry = storage.activeSessions.read(sid) ?: return@mapNotNull null
            val live = readLiveStatus(sid, entry.pid)
            ReworkedSnap(content, sid, displayableUserName(sid, entry.userName), live.topic, displayableCachedTopic(entry.name, live), live.busy)
        }

        // Off-EDT: all file reads. Capped to sids that actually have widgets.
        val snaps = widgets.mapNotNull { (sid, widget) ->
            val entry = storage.activeSessions.read(sid) ?: return@mapNotNull null
            val live = readLiveStatus(sid, entry.pid)
            Snapshot(sid, widget, displayableUserName(sid, entry.userName), live.topic, displayableCachedTopic(entry.name, live), live.busy)
        }
        if (snaps.isEmpty() && reworkedSnaps.isEmpty()) return

        val f = frame
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val adoptions = mutableListOf<Pair<String, String>>()
            val nameCaptures = mutableListOf<Pair<String, String>>()
            // Widget-less reworked (agent-button) tabs: drive their OWN TerminalView.title through
            // the exact same TitleModel path as classic widgets. Content.displayName is ignored by
            // the reworked renderer — the title comes from TerminalView.getTitle().
            if (reworkedSnaps.isNotEmpty()) {
                val rTitles = ReworkedTerminalBridge.titlesByContent(project)
                for (r in reworkedSnaps) {
                    val title = rTitles[r.content] ?: continue
                    val observed = try { title.userDefinedTitle } catch (_: Throwable) { null }
                    val capturedTopic = try {
                        TitleModel.cleanCapturedName(title.applicationTitle)
                    } catch (_: Throwable) { null }
                    if (capturedTopic != null && capturedTopic != r.cachedTopic && r.userName == null &&
                        !looksLikeCommandOrSecret(capturedTopic) &&
                        stableIdleCapture(r.sid, capturedTopic, r.busy)
                    ) {
                        nameCaptures.add(r.sid to capturedTopic)
                    }
                    val d = TitleModel.tick(
                        observed = observed,
                        lastApplied = ctx.lastAppliedTitle[r.sid],
                        userName = r.userName,
                        liveTopic = r.liveTopic ?: capturedTopic,
                        cachedTopic = r.cachedTopic,
                        busy = r.busy,
                        frameIndex = f,
                    )
                    if (d.adoptName != null) {
                        pendingUserNames[r.sid] = d.adoptName
                        adoptions.add(r.sid to d.adoptName)
                        LOG.info("[ClaudeTabs][title] Adopting user rename '${d.adoptName}' for reworked sid=${r.sid}")
                    }
                    if (d.apply != null) {
                        try {
                            title.change { userDefinedTitle = d.apply }
                            ctx.lastAppliedTitle[r.sid] = d.apply
                            val base = TitleModel.resolveDisplayName(r.userName, r.liveTopic ?: capturedTopic, r.cachedTopic)
                            if (!base.isNullOrBlank() && lastLoggedReworkedName[r.sid] != base) {
                                lastLoggedReworkedName[r.sid] = base
                                LOG.info("[ClaudeTabs][title] Reworked tab sid=${r.sid} → name '$base' (applied via TerminalView.title)")
                            }
                        } catch (e: Throwable) {
                            LOG.debug("[ClaudeTabs][title] reworked title apply failed for sid=${r.sid}: ${e.message}")
                        }
                    }
                }
            }
            for (s in snaps) {
                val title = try {
                    s.widget.terminalTitle
                } catch (_: Throwable) {
                    // Widget mid-teardown — prune; close detection owns the rest.
                    ctx.spawnedWidgets.remove(s.sid, s.widget)
                    ctx.lastAppliedTitle.remove(s.sid)
                    continue
                }
                val observed = try { title.userDefinedTitle } catch (_: Throwable) { null }
                // Capture the name Rider is actually showing — Claude sets the terminal's
                // applicationTitle via an escape sequence even when it never writes the topic to
                // its session file. Reading it here means a name that ever appears on a tab gets
                // persisted and round-trips on restore, independent of what Claude wrote to disk.
                val capturedTopic = try {
                    TitleModel.cleanCapturedName(title.applicationTitle)
                } catch (_: Throwable) { null }
                // NEVER persist a shell command / env dump as the session name. Claude sets a short
                // Title-Case topic; a shell (e.g. `some-cli --token=… run …`) sets the whole argv,
                // which has leaked secret env values into active-sessions/*.json and the log. Reject
                // anything that looks like a command line rather than a conversation topic.
                if (capturedTopic != null && capturedTopic != s.cachedTopic && s.userName == null &&
                    !looksLikeCommandOrSecret(capturedTopic) &&
                    stableIdleCapture(s.sid, capturedTopic, s.busy)
                ) {
                    nameCaptures.add(s.sid to capturedTopic)
                } else if (capturedTopic != null && looksLikeCommandOrSecret(capturedTopic)) {
                    LOG.debug("[ClaudeTabs][title] Skipped non-topic tab title for sid=${s.sid} (looks like a command/secret, len=${capturedTopic.length})")
                }
                val d = TitleModel.tick(
                    observed = observed,
                    lastApplied = ctx.lastAppliedTitle[s.sid],
                    userName = s.userName,
                    liveTopic = s.liveTopic ?: capturedTopic,
                    cachedTopic = s.cachedTopic,
                    busy = s.busy,
                    frameIndex = f,
                )
                if (d.adoptName != null) {
                    pendingUserNames[s.sid] = d.adoptName
                    adoptions.add(s.sid to d.adoptName)
                    LOG.info("[ClaudeTabs][title] Adopting user rename '${d.adoptName}' for sid=${s.sid}")
                }
                if (d.apply != null) {
                    try {
                        title.change { userDefinedTitle = d.apply }
                        ctx.lastAppliedTitle[s.sid] = d.apply
                    } catch (e: Exception) {
                        LOG.debug("[ClaudeTabs][title] apply failed for sid=${s.sid}: ${e.message}")
                    }
                }
            }
            if (adoptions.isNotEmpty()) {
                scope.launch { persistAdoptions(adoptions) }
            }
            if (nameCaptures.isNotEmpty()) {
                scope.launch { persistNameCaptures(nameCaptures) }
            }
        }, ModalityState.nonModal())
    }

    private data class LiveStatus(val busy: Boolean, val topic: String?, val derivedTopic: String? = null)

    /**
     * A persisted userName that is just a DEFAULT tab title ("Claude Code" — the agent launcher's
     * initial name, adopted as a "rename" by earlier builds) must not outrank real topic names.
     * Ignore it for display; the stored value is left alone (harmless once never honored).
     */
    private fun displayableUserName(sid: String, stored: String?): String? {
        val n = pendingUserNames[sid] ?: stored ?: return null
        return n.takeUnless { ClaudeTabsHelpers.isGenericTabName(it) }
    }

    /**
     * The cached topic, unless it is a previously-cached CLI-derived placeholder (this build stops
     * caching those, but entries polluted by earlier builds still hold e.g. "Project D3" — showing
     * it would defeat the derived-name filter until a real topic overwrites it).
     */
    private fun displayableCachedTopic(cached: String?, live: LiveStatus): String? =
        cached?.takeUnless { it == live.derivedTopic }

    /** sids already logged as stale-busy (dedup so the 450ms tick doesn't spam the log). */
    private val staleBusyLogged = ConcurrentHashMap.newKeySet<String>()

    /**
     * Read busy + live topic from the session's `sessions/<pid>.json`. The sessionId must match
     * the sid (guards against PID recycling). `busy` additionally requires the process to be
     * ALIVE: the CLI writes `status:"busy"` at turn start and only flips it back on completion,
     * so a claude killed mid-turn leaves a stale "busy" on disk — without the liveness check the
     * tab keeps animating until the poll demotes the pid several strikes later. Note "waiting"
     * (permission prompt / input needed) and "shell" (background shell while idle) are distinct
     * CLI statuses and intentionally do NOT animate.
     */
    private fun readLiveStatus(sid: String, pid: Long?): LiveStatus {
        if (pid == null) return LiveStatus(busy = false, topic = null)
        val pidFile = File(storage.sessionsDir, "$pid.json")
        if (!pidFile.exists()) return LiveStatus(busy = false, topic = null)
        val text = try { pidFile.readText() } catch (_: Exception) { null }
            ?: return LiveStatus(busy = false, topic = null)
        if (ClaudeTabsHelpers.extractJsonString(text, "sessionId") != sid) {
            return LiveStatus(busy = false, topic = null)
        }
        // The CLI writes a PLACEHOLDER name (cwd basename + suffix, e.g. "myapp-d3") with
        // nameSource:"derived" before the conversation has a real topic. Prettifying and showing
        // it produced useless tab names ("Myapp D3"); treat derived as no-name so the tab falls
        // back to the captured terminal title / cached topic / "Claude" until a real topic lands.
        val pretty = ClaudeTabsHelpers.prettifySessionName(
            ClaudeTabsHelpers.extractJsonString(text, "name")
        )
        val derived = ClaudeTabsHelpers.extractJsonString(text, "nameSource") == "derived"
        val topic = if (derived) null else pretty
        var busy = ClaudeTabsHelpers.extractJsonString(text, "status") == "busy"
        if (busy && !processAlive(pid)) {
            busy = false
            if (staleBusyLogged.add(sid)) {
                LOG.info("[ClaudeTabs][title] Ignoring stale busy for sid=$sid — process $pid is dead (killed mid-turn); animation stopped")
            }
        } else if (!busy) {
            staleBusyLogged.remove(sid)
        }
        return LiveStatus(busy, topic, derivedTopic = if (derived) pretty else null)
    }

    private fun processAlive(pid: Long): Boolean = try {
        java.lang.ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    } catch (_: Throwable) {
        false
    }

    /** Last topic OBSERVED per sid, not yet persisted — the capture debounce state. */
    private val pendingTopicCapture = HashMap<String, String>()

    /**
     * Capture debounce: a tab title is only persisted as the session name when the session is
     * IDLE and the same title was observed on two consecutive ticks. While Claude runs a tool,
     * the pty title churns with transient subprocess titles at roughly tick cadence
     * (field-captured: a test runner's worker names were being persisted as session names,
     * flickering against the real topic ~1/sec); idle-only + two-tick stability filters that
     * churn, while a real topic — stable for minutes — passes on its second tick. Cosmetic-only:
     * this affects the cached display name, never tab↔session identity.
     */
    private fun stableIdleCapture(sid: String, topic: String, busy: Boolean): Boolean {
        if (busy) return false // tool-run churn window — never capture while busy
        val stable = pendingTopicCapture[sid] == topic
        pendingTopicCapture[sid] = topic
        if (!stable) {
            LOG.debug("[ClaudeTabs][title] topic candidate for sid=$sid awaiting stability (one more tick)")
        }
        return stable
    }

    /**
     * True when a captured tab title looks like a shell command / env dump rather than a Claude
     * conversation topic — used to keep secrets and argv strings out of the persisted session name.
     * Claude topics are short Title-Case phrases; command lines carry `=`, `--`, path separators,
     * long secret-like tokens, or are simply very long.
     */
    private fun looksLikeCommandOrSecret(name: String): Boolean {
        if (name.length > 50) return true
        if (name.contains('=') || name.contains("--") || name.contains('\\')) return true
        if (name.count { it == ':' } >= 2 || name.count { it == '/' } >= 2) return true
        // A single CONTIGUOUS run of 20+ alphanumerics is a token/secret, not a real word.
        if (Regex("[A-Za-z0-9]{20,}").containsMatchIn(name)) return true
        return false
    }

    /** Persist topics captured off the live terminal title into the entry's cached `name`, so
     *  the name survives restart even if Claude never wrote it to its session file. Written as
     *  `name` (cached topic), never `userName` — an explicit /tab or rename still outranks it. */
    private fun persistNameCaptures(captures: List<Pair<String, String>>) {
        for ((sid, topic) in captures) {
            try {
                val e = storage.activeSessions.read(sid) ?: continue
                if (e.name == topic || e.userName != null) continue
                // Name-ONLY locked update: can't echo a stale pid over the poll's demote, and
                // no-ops if the entry was evicted between the read above and this write.
                if (storage.activeSessions.updateName(sid, name = topic)) {
                    LOG.info("[ClaudeTabs][title] Captured tab name '$topic' for sid=$sid from terminal title")
                }
            } catch (ex: Exception) {
                LOG.warn("[ClaudeTabs][title] failed to persist captured name for sid=$sid: ${ex.message}")
            }
        }
    }

    private fun persistAdoptions(adoptions: List<Pair<String, String>>) {
        for ((sid, name) in adoptions) {
            try {
                // Same safe write as captures: never touches pid/lastSeen, never recreates a
                // deleted entry. Failure (entry gone) keeps the pendingUserNames overlay, so
                // the rename still shows on the tab for the rest of the session.
                if (storage.activeSessions.updateName(sid, userName = name)) {
                    pendingUserNames.remove(sid, name)
                }
            } catch (ex: Exception) {
                LOG.warn("[ClaudeTabs][title] failed to persist userName for sid=$sid: ${ex.message}")
            }
        }
    }
}
