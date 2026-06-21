package com.claudetabs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.ui.TerminalWidget
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

    private fun tickOnce(scope: CoroutineScope) {
        if (project.isDisposed) return
        val widgets = ctx.spawnedWidgets.entries.toList()
        if (widgets.isEmpty()) return
        frame++

        // Off-EDT: all file reads. Capped to sids that actually have widgets.
        val snaps = widgets.mapNotNull { (sid, widget) ->
            val entry = storage.activeSessions.read(sid) ?: return@mapNotNull null
            var busy = false
            var liveTopic: String? = null
            val pid = entry.pid
            if (pid != null) {
                val pidFile = File(storage.sessionsDir, "$pid.json")
                if (pidFile.exists()) {
                    val text = try { pidFile.readText() } catch (_: Exception) { null }
                    // sid must match — guards against PID recycling.
                    if (text != null && ClaudeTabsHelpers.extractJsonString(text, "sessionId") == sid) {
                        busy = ClaudeTabsHelpers.extractJsonString(text, "status") == "busy"
                        liveTopic = ClaudeTabsHelpers.prettifySessionName(
                            ClaudeTabsHelpers.extractJsonString(text, "name")
                        )
                    }
                }
            }
            val userName = pendingUserNames[sid] ?: entry.userName
            Snapshot(sid, widget, userName, liveTopic, entry.name, busy)
        }
        if (snaps.isEmpty()) return

        val f = frame
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val adoptions = mutableListOf<Pair<String, String>>()
            for (s in snaps) {
                val observed = try {
                    s.widget.terminalTitle.userDefinedTitle
                } catch (_: Throwable) {
                    // Widget mid-teardown — prune; close detection owns the rest.
                    ctx.spawnedWidgets.remove(s.sid, s.widget)
                    ctx.lastAppliedTitle.remove(s.sid)
                    continue
                }
                val d = TitleModel.tick(
                    observed = observed,
                    lastApplied = ctx.lastAppliedTitle[s.sid],
                    userName = s.userName,
                    liveTopic = s.liveTopic,
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
                        s.widget.terminalTitle.change { userDefinedTitle = d.apply }
                        ctx.lastAppliedTitle[s.sid] = d.apply
                    } catch (e: Exception) {
                        LOG.debug("[ClaudeTabs][title] apply failed for sid=${s.sid}: ${e.message}")
                    }
                }
            }
            if (adoptions.isNotEmpty()) {
                scope.launch { persistAdoptions(adoptions) }
            }
        }, ModalityState.nonModal())
    }

    private fun persistAdoptions(adoptions: List<Pair<String, String>>) {
        for ((sid, name) in adoptions) {
            try {
                val e = storage.activeSessions.read(sid) ?: continue
                storage.activeSessions.writeOrUpdate(
                    sid = sid, cwd = e.cwd, pid = e.pid, lastSeen = e.lastSeen,
                    userName = name,
                )
                pendingUserNames.remove(sid, name)
            } catch (ex: Exception) {
                LOG.warn("[ClaudeTabs][title] failed to persist userName for sid=$sid: ${ex.message}")
            }
        }
    }
}
