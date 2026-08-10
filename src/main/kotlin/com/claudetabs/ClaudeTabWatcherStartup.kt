package com.claudetabs

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import org.jetbrains.plugins.terminal.arrangement.TerminalWorkingDirectoryManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry point for the Claude Terminal Tab Persistence plugin — **v3, own-the-terminal model**.
 *
 * ## The principle that ends the cycling
 *
 * Every pre-v3 build created tabs it didn't fully own, then *guessed* which Claude session lived
 * in each tab (reflection PID digs, process-ancestry walks, title handshakes, cwd/time
 * correlation). On Rider 2026.1's reworked (out-of-process pty) terminal those signals are all
 * unreliable, so identity drifted and the restore/close logic on top of it kept mis-firing.
 *
 * v3 deletes the guessing. The plugin OWNS every Claude tab's widget **and** knows its session id
 * at the instant of birth, so `widget ↔ Content ↔ sid` is a stored fact:
 *
 *  - **New session** (the "New Claude Session" action → [requestNewSession]): mint a fresh v4
 *    UUID and launch `claude --session-id <uuid>`. The id is known before Claude starts.
 *  - **Restore** ([performRestore]): launch `claude --resume <sid>` for a known id.
 *
 * Both route through [spawnOwnedTab], which records `contentToSid`/`spawnedWidgets` at birth.
 * The only correlation that survives is [bindSessions]' strict 1:1-by-cwd adoption of tabs the
 * user opened by hand (bypassing the action) — ambiguity binds nothing, so nothing mis-binds.
 *
 * ## Openness / durability (kept from the proven v2 layers — never the source of the churn)
 *
 *  - **Open = a PRESENT tab.** [ProjectCtx.presentSids] is recomputed every poll from the live
 *    ContentManager list ([recordTabOrder]); with `contentToSid` now a birth-time fact it is exact.
 *  - **Snapshot** `open-tabs-<hash>.json` = presentSids ∩ this-project, written crash-atomically
 *    ([DurableIo]) each normal poll and FROZEN during teardown, so window-close / IDE-quit / crash
 *    / reboot all reopen every tab, while an × (Content removed) drops one tab next poll with no
 *    event to detect and nothing ever force-killed.
 *  - [ConfigGuard] self-heals `~/.claude.json`; serialized spawns ([resumeSpawnMutex] +
 *    [awaitConfigSettled]) stop the thundering-herd corruption of that file on mass restore.
 */
class ClaudeTabWatcherStartup : StartupActivity.DumbAware {

    companion object {
        private val LOG = Logger.getInstance(ClaudeTabWatcherStartup::class.java)

        /** Poll cadence. */
        private const val POLL_INTERVAL_MS = 5_000L

        /** Per-sid cooldown between retry-spawn attempts when a restore spawn doesn't produce an
         *  alive Claude within the window. 60s covers `--resume` startup on a slow disk. */
        private const val RESPAWN_COOLDOWN_MS = 60_000L

        /** Hard cap on spawn attempts per sid per Rider session — a consistently-failing resume
         *  (deleted worktree, corrupt transcript) must not open an endless stream of tabs. */
        private const val MAX_SPAWN_ATTEMPTS = 3

        /** Grace window after a spawn during which a snapshot sid is kept even though its Content
         *  isn't mapped into [ProjectCtx.presentSids] yet (the widget→Content link is async), and
         *  during which an owned new tab's [ProjectCtx.PendingNew] awaits its session handshake. */
        private const val SPAWN_WARMUP_MS = 45_000L

        /** FIFO injection-order binding (the burst case — several agent-button tabs opened together
         *  in one project, all with unreadable cwd so the 1:1 rule refuses). A pending injected sid
         *  is eligible to bind while ALIVE, or for [INJECT_FIFO_FRESH_MS] after injection (Claude may
         *  not have registered its session file yet), and is pruned from the queue after
         *  [INJECT_FIFO_TTL_MS] if never bound (its tab was closed before we caught it). */
        private const val INJECT_FIFO_FRESH_MS = 20_000L
        private const val INJECT_FIFO_TTL_MS = 120_000L

        /** Base delay after a spawn before the next, giving the just-launched Claude a moment to
         *  BEGIN its startup write of `~/.claude.json`; paired with [awaitConfigSettled] which then
         *  waits for that write to FINISH. Together they serialize the writes — the real fix for the
         *  thundering-herd corruption of that shared file. The plugin only READS it. */
        private const val RESTORE_STAGGER_MS = 300L

        /** Hard cap on how long [awaitConfigSettled] waits for `~/.claude.json` to stop changing. */
        private const val CONFIG_SETTLE_CAP_MS = 5000L

        // NOTE: no restore staleness window, herd cap, or ghost-decay cap. The restore contract
        // (project memory `tab-restore-contract`) forbids age/count-based retirement — a tab reopens
        // unless the user ×-closed it. Config-churn safety is serialized spawns, not count caps.

        /** JVM-GLOBAL serialization of every `claude` spawn (new + resume). Multiple project windows
         *  restore concurrently and the action fires outside any loop; without a shared lock two
         *  spawns could observe a "settled" `~/.claude.json` in the same instant and race the write. */
        private val resumeSpawnMutex = Mutex()

        /** Scope for spawns triggered outside a project poll loop (the "New Claude Session" action). */
        private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        /** `~/.claude.json` — Claude's own config. Never written; restore reads size+mtime only. */
        private val CLAUDE_GLOBAL_CONFIG = File(System.getProperty("user.home"), ".claude.json")

        /** Root of Claude Code's user data. */
        private val CLAUDE_HOME = File(System.getProperty("user.home"), ".claude")

        /** Markers wrapping the plugin's legacy section of `~/.claude/CLAUDE.md` (1.x only). */
        private const val CLAUDE_MD_MARKER = "<!-- rider-claude-tabs-plugin -->"

        private fun stripClaudeMdSection() {
            try {
                val claudeMd = File(CLAUDE_HOME, "CLAUDE.md")
                if (!claudeMd.exists()) return
                val text = claudeMd.readText()
                if (!text.contains(CLAUDE_MD_MARKER)) return
                val pattern = Regex(
                    "\n?${Regex.escape(CLAUDE_MD_MARKER)}.*?${Regex.escape(CLAUDE_MD_MARKER)}\n?",
                    RegexOption.DOT_MATCHES_ALL,
                )
                claudeMd.writeText(text.replace(pattern, "\n").trim() + "\n")
            } catch (_: Exception) { /* best effort */ }
        }

        /** Every permission line any build has ever written — uninstall removes all of them. */
        private val PERMISSION_ENTRIES =
            SettingsPermissions.CURRENT_ENTRIES + SettingsPermissions.LEGACY_ENTRIES

        /** Singleton storage helper. */
        private val storage = ClaudeTabsStorage(CLAUDE_HOME)

        /** Self-healing guard for `~/.claude.json`. JVM-global like the spawn mutex. */
        private val configGuard = ConfigGuard(
            configFile = CLAUDE_GLOBAL_CONFIG,
            lastGoodFile = File(storage.stateDir, "claude-json.last-good"),
        )

        /** Cross-window in-memory dead-strike tracker. */
        private val evictionTracker = EvictionTracker()

        /** Per-project state, keyed by project.locationHash. */
        private val projectCtx = ConcurrentHashMap<String, ProjectCtx>()

        /** One-shot guard for [reconcileStaleEntriesOnStartup] across the JVM. */
        private val reconciledThisJvm = java.util.concurrent.atomic.AtomicBoolean(false)

        private const val PLUGIN_VERSION = "3.0.1"

        /** The official Claude Code plugin's "open Claude in the terminal" action (its toolbar
         *  button + Ctrl+Esc). Replaced at startup with [NewClaudeSessionAction] so the button the
         *  user already clicks routes through the owned spawner — tabs it opens get a plugin-
         *  assigned session id at birth and restore durably. Field-tested rationale: tabs the
         *  official action spawned were unowned; with several opened at once the strict 1:1
         *  adoption rule correctly refused to guess, so none entered the snapshot and none
         *  restored. */
        private const val OFFICIAL_OPEN_CLAUDE_ACTION_ID =
            "com.anthropic.code.plugin.actions.OpenClaudeInTerminalAction"

        private val claudeButtonTakeoverAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Replace the official button's action with the owned spawner, keeping its id — and with
         *  it the icon/text (copied presentation), toolbar placement, and Ctrl+Esc shortcut. */
        private fun takeOverOfficialClaudeButton() {
            if (!claudeButtonTakeoverAttempted.compareAndSet(false, true)) return
            ApplicationManager.getApplication().invokeLater {
                try {
                    val am = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    val official = am.getAction(OFFICIAL_OPEN_CLAUDE_ACTION_ID)
                    if (official == null) {
                        LOG.info("[ClaudeTabs] Official Claude Code button not present — nothing to take over " +
                            "(New Claude Session remains in the Tools menu)")
                        return@invokeLater
                    }
                    if (official is NewClaudeSessionAction) return@invokeLater
                    val replacement = NewClaudeSessionAction()
                    replacement.templatePresentation.copyFrom(official.templatePresentation)
                    am.replaceAction(OFFICIAL_OPEN_CLAUDE_ACTION_ID, replacement)
                    LOG.info("[ClaudeTabs] Claude Code button taken over — it now opens an owned, durable session")
                } catch (e: Exception) {
                    LOG.warn("[ClaudeTabs] Claude Code button takeover failed: ${e.message}")
                }
            }
        }

        /** True once the IDE begins shutting down (quit OR restart). Freezes the snapshot so a
         *  shutdown's tab-removal burst can't be mistaken for user ×-closes. */
        @Volatile private var appShuttingDown = false
        private val appClosingSubscribed = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * The EARLIEST platform quit signal: `ApplicationEx.isExitInProgress` is set synchronously
         * the moment exit is REQUESTED — before any lifecycle listener fires and before tool
         * windows dispose. Listener flags alone lose the teardown race: field evidence showed the
         * terminal killing pty processes (agent tabs self-close on process death, exactly like ×
         * clicks) several seconds before `projectClosing`/`appWillBeClosed` fired, so a poll in
         * that window recorded the closures as user closes and shrank the final snapshot.
         */
        private fun appExitInProgress(): Boolean = try {
            (ApplicationManager.getApplication() as? com.intellij.openapi.application.ex.ApplicationEx)
                ?.isExitInProgress == true
        } catch (_: Throwable) { false }

        internal fun ctxOf(project: Project): ProjectCtx =
            projectCtx.getOrPut(project.locationHash) { ProjectCtx() }

        internal fun projectHashOf(project: Project): String =
            ClaudeTabsHelpers.projectHashForPath(project.basePath)

        // ──────────────────────────────────────────────────────────────
        // Owned-terminal spawning — the ONLY place Claude tabs are created
        // ──────────────────────────────────────────────────────────────

        /**
         * Public entry for the "New Claude Session" action ([NewClaudeSessionAction]). Mints a
         * fresh session id and spawns an owned tab running `claude --session-id <uuid>`, serialized
         * against every other spawn so the `~/.claude.json` startup write can't be raced.
         */
        @JvmStatic
        fun requestNewSession(project: Project) {
            appScope.launch {
                try {
                    resumeSpawnMutex.withLock {
                        withContext(Dispatchers.Main) {
                            if (!project.isDisposed) spawnNewSession(project)
                        }
                        delay(RESTORE_STAGGER_MS)
                        awaitConfigSettled()
                    }
                } catch (e: Exception) {
                    LOG.warn("[ClaudeTabs] requestNewSession failed: ${e.message}")
                }
            }
        }

        /**
         * [ClaudeAgentLaunchCustomizer]'s worker: when a terminal is about to launch the Claude
         * CLI as its own pty process (the terminal AI-agents button's shape) with no session
         * pinned, append `--session-id <minted-uuid>` and write a provisional per-sid entry so
         * the session claims its cwd immediately. The exact-adopt pass in [bindSessions] then
         * reads the id back from the widget's shell command — ownership without ever having
         * created the tab ourselves.
         */
        @JvmStatic
        fun injectSessionIdIfClaudeLaunch(
            project: Project,
            workingDirectory: String?,
            command: Array<String>,
        ): Array<String> {
            try {
                val cmd = command.toList()
                if (!OwnedTerminalSpawner.isDirectClaudeCommand(cmd)) return command
                if (OwnedTerminalSpawner.alreadyPinsSession(cmd)) return command
                val cwd = workingDirectory ?: project.basePath ?: return command
                val sid = java.util.UUID.randomUUID().toString()
                storage.activeSessions.writeOrUpdate(sid, cwd, pid = null, lastSeen = System.currentTimeMillis())
                // Enqueue for FIFO binding: the tab this launches will appear in the ContentManager
                // in this same order, so we can pair it deterministically even when several are
                // opened at once (the burst case the 1:1 rule can't handle).
                val q = ctxOf(project).pendingInjectedSids
                q.addLast(ProjectCtx.InjectedTab(sid, cwd, System.currentTimeMillis()))
                LOG.info("[ClaudeTabs] Injected --session-id into direct claude launch: sid=$sid cwd=$cwd " +
                    "(fifo queue depth=${q.size})")
                return command + arrayOf("--session-id", sid)
            } catch (e: Exception) {
                LOG.warn("[ClaudeTabs] session-id injection failed: ${e.message}")
                return command
            }
        }

        /** Mint a brand-new session in an owned tab. A provisional per-sid entry is written FIRST
         *  so the tab enters the open-tabs snapshot immediately (before Claude's own
         *  `sessions/<pid>.json` appears), protecting a quit within the first seconds. */
        private fun spawnNewSession(project: Project) {
            val cwd = project.basePath
            if (cwd.isNullOrBlank()) return
            val sid = java.util.UUID.randomUUID().toString()
            storage.activeSessions.writeOrUpdate(sid, cwd, pid = null, lastSeen = System.currentTimeMillis())
            val widget = spawnOwnedTab(project, sid, cwd, displayName = "Claude", mode = OwnedTerminalSpawner.Mode.NEW)
                ?: return
            ctxOf(project).pendingNew[sid] = ProjectCtx.PendingNew(widget, cwd, System.currentTimeMillis())
            LOG.info("[ClaudeTabs] New Claude session sid=$sid cwd=$cwd (--session-id)")
        }

        /**
         * Create an owned terminal tab via the public [TerminalToolWindowManager.createShellWidget]
         * API, bind `widget`/`Content` to [sid] at birth, and send the launch command. Must run on
         * the EDT. Returns the widget, or null on failure.
         */
        private fun spawnOwnedTab(
            project: Project,
            sid: String,
            cwd: String,
            displayName: String,
            mode: OwnedTerminalSpawner.Mode,
        ): TerminalWidget? {
            return try {
                val initialTitle = TitleModel.compose(displayName, busy = false, frameIndex = 0)
                val mgr = TerminalToolWindowManager.getInstance(project)
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                    .getToolWindow("Terminal")?.activate(null, false, false)
                val widget = mgr.createShellWidget(
                    cwd,           // workingDirectory
                    initialTitle,  // never "Local", even for one frame
                    false,         // requestFocus=false — restoring N tabs must not steal focus
                    false,         // deferSessionStartUntilUiShown=false — start eagerly
                )
                val c = ctxOf(project)
                c.lastAppliedTitle[sid] = initialTitle
                c.spawnedWidgets[sid] = widget
                c.everHadWidget.add(sid)
                c.restoreSpawnLastAttempt[sid] = System.currentTimeMillis()
                // Content is created asynchronously — capture the widget↔Content link on the EDT.
                ApplicationManager.getApplication().invokeLater {
                    try {
                        findContentForWidget(project, widget)?.let { c.contentToSid[it] = sid }
                    } catch (_: Exception) { /* best effort */ }
                }
                val cmd = OwnedTerminalSpawner.launchCommand(sid, mode)
                ApplicationManager.getApplication().invokeLater {
                    try {
                        widget.sendCommandToExecute(cmd)
                    } catch (ex: Exception) {
                        LOG.warn("[ClaudeTabs] sendCommandToExecute failed for sid=$sid: ${ex.message}")
                    }
                }
                LOG.info("[ClaudeTabs] Spawned owned tab sid=$sid mode=$mode cwd=$cwd")
                widget
            } catch (ex: Throwable) {
                LOG.warn("[ClaudeTabs] spawnOwnedTab failed for sid=$sid: ${ex.message}")
                null
            }
        }

        /** Walk the terminal tool window's ContentManager for the Content backing [widget]. */
        private fun findContentForWidget(project: Project, widget: TerminalWidget): Content? {
            val tw = TerminalToolWindowManager.getInstance(project).toolWindow ?: return null
            for (content in tw.contentManager.contents) {
                try {
                    if (TerminalToolWindowManager.findWidgetByContent(content) === widget) return content
                } catch (_: Exception) { /* try next */ }
            }
            return null
        }

        /**
         * Suspend until `~/.claude.json` stops changing (the resuming Claude finished its startup
         * write), so the NEXT spawn doesn't race it. Reads mtime+size only; caps at
         * [CONFIG_SETTLE_CAP_MS] so a slow/hung start can't stall spawning.
         */
        private suspend fun awaitConfigSettled() {
            val deadline = System.currentTimeMillis() + CONFIG_SETTLE_CAP_MS
            var lastSig = -1L to -1L
            var stableSince = 0L
            while (System.currentTimeMillis() < deadline) {
                val sig = if (CLAUDE_GLOBAL_CONFIG.exists())
                    CLAUDE_GLOBAL_CONFIG.lastModified() to CLAUDE_GLOBAL_CONFIG.length()
                else 0L to 0L
                val now = System.currentTimeMillis()
                if (sig == lastSig) {
                    if (stableSince == 0L) stableSince = now
                    if (now - stableSince >= 350L) return
                } else {
                    lastSig = sig
                    stableSince = 0L
                }
                delay(120L)
            }
        }

        /**
         * Removes all plugin artifacts from `~/.claude` (called on plugin uninstall). Best-effort.
         */
        @JvmStatic
        fun uninstall() {
            stripClaudeMdSection()
            val settings = File(CLAUDE_HOME, "settings.json")
            if (settings.exists()) {
                val text = settings.readText()
                val updated = SettingsPermissions.rewriteAllowArray(
                    text, remove = PERMISSION_ENTRIES.toSet(), add = emptyList(),
                )
                if (updated != null) settings.writeText(updated)
            }
            File(CLAUDE_HOME, "rider-plugin").deleteRecursively()
            listOf(
                "commands/tab.md", "commands/tabs-clear.md", "commands/tabs-restore.md",
                "commands/tabs-history.md", "commands/tabs-backup.md", "commands/tabs-status.md",
                "commands/clear-tabs.md", "commands/restore-tabs.md", "commands/tab-history.md",
                "commands/backup-tabs.md",
            ).forEach { File(CLAUDE_HOME, it).delete() }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Per-project state
    // ──────────────────────────────────────────────────────────────────

    internal class ProjectCtx {
        /** An owned NEW tab awaiting its session handshake. The plugin launched
         *  `claude --session-id <mintedUuid>` in [widget]; when Claude's `sessions/<pid>.json`
         *  appears the poll either confirms the minted id (--session-id honored) or, if Claude
         *  persisted a different id, [rebindOwnedTab] adopts the real one. Serialized spawns keep
         *  at most one pending per cwd, so the adoption is unambiguous. */
        data class PendingNew(val widget: TerminalWidget, val cwd: String, val spawnedAt: Long)

        /** Terminal Content → sid, populated at spawn (birth-time fact) or by [bindSessions]'
         *  hand-open adoption. THE mapping the openness computation reads. */
        val contentToSid = ConcurrentHashMap<Content, String>()

        /** Widgets we own, keyed by sid. Read by [TitleController] to enforce titles/animation. */
        val spawnedWidgets = ConcurrentHashMap<String, TerminalWidget>()

        /** sids that held a live tab at any point this session — retry-spawn resurrection guard. */
        val everHadWidget: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** sids observed alive at any point this session — the broader resurrection guard. */
        val everSeenAlive: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** mintedUuid → [PendingNew] for owned new tabs awaiting their session handshake. */
        val pendingNew = ConcurrentHashMap<String, PendingNew>()

        /** THE openness signal — sids whose Content is CURRENTLY in the tool window, recomputed
         *  every poll on the EDT by [recordTabOrder]. The UI's own tab list can't be wrong about
         *  what it shows: an ×'d tab has no Content (drops next poll), a never-opened session has
         *  no Content (no flood). */
        @Volatile var presentSids: Set<String> = emptySet()

        /** Set by [com.intellij.openapi.project.ProjectManagerListener.projectClosing]. */
        @Volatile var projectClosing: Boolean = false

        /** sid → first time (ms) it was observed absent while OTHER sids vanished in the same
         *  poll. Bulk removals defer one poll before committing — the teardown-race safety net
         *  ([ClaudeTabsHelpers.applyRemovalDeferral]). */
        val pendingSnapshotRemovals: MutableMap<String, Long> = ConcurrentHashMap()

        /** Dedup for the teardown-freeze log line (one line per distinct reason, not per poll). */
        @Volatile var lastTeardownWhy: String? = null

        /** Dedup for the recordTabOrder teardown-freeze log line. */
        @Volatile var tabOrderFrozenLogged: Boolean = false

        /** sid → last spawn attempt (ms) — retry cooldown + snapshot warmup stickiness. */
        val restoreSpawnLastAttempt = ConcurrentHashMap<String, Long>()

        /**
         * Sids that have been observed as a PRESENT Content at least once this session. Warmup
         * stickiness (which keeps a just-spawned sid in the snapshot until its async widget→Content
         * link lands) must NOT apply to a sid that already materialized: once present, a later
         * absence is a real × close, not a warmup gap. Without this, a tab ×'d within
         * [SPAWN_WARMUP_MS] of restore is resurrected every poll (field-verified: "can't kill" it).
         */
        val materializedSids: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** A sid the [ClaudeAgentLaunchCustomizer] injected into an agent-button/direct claude
         *  launch, awaiting FIFO binding to its Content. Order = tab creation order = the order new
         *  Contents append to the ContentManager, which is what makes the FIFO pairing correct. */
        data class InjectedTab(val sid: String, val cwd: String, val injectedAt: Long)

        /** Ordered queue of injected-but-not-yet-bound sids (see [InjectedTab]). Drained in FIFO
         *  order by `fifoBindInjected`; the enqueue side is [injectSessionIdIfClaudeLaunch]. */
        val pendingInjectedSids = java.util.concurrent.ConcurrentLinkedDeque<InjectedTab>()

        /** sid → spawn attempt count, capped at [MAX_SPAWN_ATTEMPTS]. */
        val restoreSpawnAttempts = ConcurrentHashMap<String, Int>()

        /** sid → the exact title [TitleController] last wrote (for rename detection). */
        val lastAppliedTitle = ConcurrentHashMap<String, String>()

        /** Last adopt-scan summary logged — dedup so the poll loop doesn't spam idea.log. */
        @Volatile var lastAdoptSummary: String? = null

        /** Last bindSessions failure message logged — same dedup. */
        @Volatile var lastBindError: String? = null

        /** Wakes the poll loop early (conflated — bursts coalesce). Poked by the terminal
         *  ContentManager listener on tab add/remove so an × is snapshotted IMMEDIATELY: the 5s
         *  cadence loses the race against an IDE close right after an × (field-tested — the ×'d
         *  tab "necroed" on restart because the frozen snapshot predated the close). */
        val poke = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

        /** One-shot guard for installing the terminal ContentManager listener. */
        val contentListenerInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

        val startupAt: Long = System.currentTimeMillis()
    }

    private fun ctx(project: Project): ProjectCtx = ctxOf(project)

    private fun projectHash(project: Project): String = projectHashOf(project)

    // ──────────────────────────────────────────────────────────────────
    // Cwd ownership arbitration
    // ──────────────────────────────────────────────────────────────────

    /**
     * True if THIS project's window should claim a session whose cwd is [cwd]. Base rule:
     * [ClaudeTabsHelpers.isCwdUnderProject] (exact, descendant, or `<base>-<suffix>` worktree).
     * Arbitration: a dash-suffix (sibling) match is rejected when a MORE SPECIFIC known project
     * base exists — any open project, or any entry in `project-index.json` — so `MyApp` never
     * claims `MyApp-mobile`'s sessions. A genuine git worktree is never its own Rider project, so
     * no more-specific base exists and the parent claims it. Exact/descendant never arbitrate.
     */
    private fun claimsCwd(cwd: String, project: Project): Boolean {
        val basePath = project.basePath ?: return false
        if (!ClaudeTabsHelpers.isCwdUnderProject(cwd, basePath)) return false

        val norm = { p: String -> p.replace("\\", "/").trimEnd('/').lowercase() }
        val nCwd = norm(cwd)
        val nBase = norm(basePath)
        if (nCwd == nBase || nCwd.startsWith("$nBase/")) return true

        val knownBases = mutableSetOf<String>()
        try {
            for (p in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) {
                p.basePath?.let { knownBases.add(norm(it)) }
            }
        } catch (_: Exception) { }
        try {
            val indexFile = File(storage.stateDir, "project-index.json")
            if (indexFile.exists()) {
                for (m in Regex(""""basePath"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").findAll(indexFile.readText())) {
                    knownBases.add(norm(m.groupValues[1].replace("\\\\", "\\")))
                }
            }
        } catch (_: Exception) { }

        for (other in knownBases) {
            if (other == nBase) continue
            if (other.length > nBase.length && (nCwd == other || nCwd.startsWith("$other/"))) return false
        }
        return true
    }

    /** Upsert this project into `project-index.json` so [claimsCwd] arbitration works even when
     *  this project's window is closed later. */
    private fun updateProjectIndex(project: Project) {
        val basePath = project.basePath ?: return
        try {
            val indexFile = File(storage.stateDir, "project-index.json")
            val hash = projectHash(project)
            val name = ClaudeTabsHelpers.esc(project.name)
            val basePathEsc = ClaudeTabsHelpers.esc(basePath.replace("\\", "/"))
            val entry = """{"hash":"${ClaudeTabsHelpers.esc(hash)}","basePath":"$basePathEsc","name":"$name"}"""
            val existing = if (indexFile.exists()) indexFile.readText() else ""
            if (existing.contains("\"hash\":\"${ClaudeTabsHelpers.esc(hash)}\"")) return
            val entries = Regex("""\{[^}]+\}""").findAll(existing).map { it.value }.toMutableList()
            entries.add(entry)
            indexFile.parentFile?.mkdirs()
            storage.writeAtomic(
                indexFile,
                entries.joinToString(prefix = "{\n  \"projects\": [\n    ", postfix = "\n  ]\n}", separator = ",\n    ")
            )
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] project-index update failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Activity entry point
    // ──────────────────────────────────────────────────────────────────

    override fun runActivity(project: Project) {
        val ideInfo = try {
            val app = com.intellij.openapi.application.ApplicationInfo.getInstance()
            "${app.versionName} ${app.fullVersion} (build ${app.build.asString()})"
        } catch (_: Exception) { "unknown" }
        LOG.info("[ClaudeTabs] ════════════════════════════════════════════════════════")
        LOG.info("[ClaudeTabs] Started for: ${project.name}")
        LOG.info("[ClaudeTabs] Plugin version: $PLUGIN_VERSION (own-the-terminal model)")
        LOG.info("[ClaudeTabs] IDE: $ideInfo")
        LOG.info("[ClaudeTabs] Project base path: ${project.basePath}")
        if (AiAgentsDetector.isActive(project)) {
            LOG.info("[ClaudeTabs] JetBrains AI Assistant / Claude Agent host detected — this plugin manages " +
                "terminal-launched Claude CLI sessions only.")
        }
        LOG.info("[ClaudeTabs] ════════════════════════════════════════════════════════")

        try {
            val seeded = storage.migrateLegacyRestoreFiles()
            if (seeded > 0) LOG.info("[ClaudeTabs] Migration: seeded $seeded session(s) from legacy restore-*.json")
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] Migration failed: ${e.message}")
        }

        deployClaudeIntegration()
        updateProjectIndex(project)
        reconcileStaleEntriesOnStartup()
        takeOverOfficialClaudeButton()

        // App-shutdown subscription (once per JVM). BOTH lifecycle hooks set the flag — appClosing
        // fires earlier in the exit sequence than appWillBeClosed — but neither is sufficient by
        // itself: pty teardown can precede both (field-verified), which is why pollOnce ALSO checks
        // [appExitInProgress] directly every poll. The listeners exist for the logs and as backup.
        if (appClosingSubscribed.compareAndSet(false, true)) {
            try {
                val app = ApplicationManager.getApplication()
                app.messageBus.connect(app).subscribe(
                    com.intellij.ide.AppLifecycleListener.TOPIC,
                    object : com.intellij.ide.AppLifecycleListener {
                        override fun appClosing() {
                            appShuttingDown = true
                            LOG.info("[ClaudeTabs] appClosing — all open-tabs snapshots frozen")
                        }
                        override fun appWillBeClosed(isRestart: Boolean) {
                            appShuttingDown = true
                            LOG.info("[ClaudeTabs] appWillBeClosed(isRestart=$isRestart) — all open-tabs snapshots frozen")
                        }
                    },
                )
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] AppLifecycleListener subscribe failed: ${e.message}")
            }
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val pmListener = object : com.intellij.openapi.project.ProjectManagerListener {
                override fun projectClosing(p: com.intellij.openapi.project.Project) {
                    if (p == project) {
                        ctx(project).projectClosing = true
                        LOG.info("[ClaudeTabs] Project closing — freezing open-tabs snapshot")
                    }
                }
            }
            com.intellij.openapi.project.ProjectManager.getInstance().addProjectManagerListener(project, pmListener)
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] ProjectManagerListener install failed: ${e.message}")
        }

        Disposer.register(project as Disposable, Disposable {
            val c = ctx(project)
            c.projectClosing = true
            LOG.info("[ClaudeTabs] Project closed — dropping ProjectCtx")
            projectCtx.remove(project.locationHash)
            scope.cancel()
        })

        // Title ownership: enforce `✳ <name>` + busy animation on every owned Claude tab.
        TitleController(project, storage, ctx(project)).start(scope)

        // Main poll loop. First poll after a 3s delay so post-restore spawns have settled.
        // Sleeps up to POLL_INTERVAL_MS but wakes early on a poke (tab added/removed) so an ×
        // lands in the snapshot before a same-instant IDE close can freeze the stale one.
        scope.launch {
            delay(3_000)
            performRestore(project)
            val c = ctx(project)
            while (isActive) {
                try {
                    pollOnce(project)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] Poll error: ${e.message}")
                }
                withTimeoutOrNull(POLL_INTERVAL_MS) { c.poke.receive() }
                // Brief settle so a burst of Content events (multi-tab close, teardown start)
                // coalesces and the closing flags land before the recompute reads the tab list.
                delay(250)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Startup reconcile — observed vs unobserved deaths
    // ──────────────────────────────────────────────────────────────────

    /**
     * Convert stale per-sid entries into restore candidates. Runs ONCE per JVM, before any poll.
     * When Rider restarts, every Claude died with it while no plugin watched; those entries have
     * dead recorded pids indistinguishable from observed deaths. Rewriting each dead-pid entry to
     * `pid=null` (restore-pending — never evicted) means only deaths OBSERVED by a live poll get
     * demoted, so a restart can't wipe saved state before the restore loops run.
     */
    private fun reconcileStaleEntriesOnStartup() {
        if (!reconciledThisJvm.compareAndSet(false, true)) return
        try {
            val now = System.currentTimeMillis()
            var reset = 0
            for (entry in storage.activeSessions.listAll()) {
                val pid = entry.pid ?: continue
                if (verifyAlive(pid, entry.sid)) continue
                storage.activeSessions.writeOrUpdate(
                    sid = entry.sid, cwd = entry.cwd, pid = null, lastSeen = now, name = null,
                )
                reset++
            }
            if (reset > 0) {
                LOG.info("[ClaudeTabs] Startup reconcile: $reset stale entr(ies) reset to restore-pending")
            }
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] Startup reconcile failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Poll loop
    // ──────────────────────────────────────────────────────────────────

    /**
     * Single iteration:
     *  1. Scan `~/.claude/sessions/<pid>.json`; write `active-sessions/<sid>.json` per alive Claude.
     *  1b. Bind unbound sessions ([bindSessions]): reconcile owned pending-new + hand-open 1:1.
     *  1c. Record tab order + recompute openness ([recordTabOrder]).
     *  2. Demote dead recorded-pid entries to restore-pending (never evicted on death).
     *  2b. Persist the open-tabs snapshot (frozen during teardown).
     *  4. Retry-spawn snapshot sids whose startup spawn silently failed.
     */
    internal suspend fun pollOnce(project: Project) {
        val now = System.currentTimeMillis()
        val seenSids = mutableSetOf<String>()
        val sidToPid = mutableMapOf<String, Long>()
        val sidToCwd = mutableMapOf<String, String>()
        val c = ctx(project)

        // Step 0: self-heal ~/.claude.json (Claude's concurrent writers corrupt it under churn).
        when (val status = configGuard.check()) {
            ConfigGuard.Status.VALID, ConfigGuard.Status.SUSPECT -> {}
            ConfigGuard.Status.UNREPAIRABLE ->
                LOG.warn("[ClaudeTabs] ConfigGuard: ~/.claude.json is corrupt and no repair applies — leaving untouched")
            else ->
                LOG.warn("[ClaudeTabs] ConfigGuard: ~/.claude.json was corrupt — auto-repaired ($status)")
        }

        // Step 1: every alive Claude process → per-sid file. No attachment/ancestry gating — that
        // was falsified (dead chains on live tabs). Openness is a present tab, computed in step 1c.
        data class Scan(val pid: Long, val sid: String, val cwd: String, val name: String?, val argvSid: String?)
        val scans = mutableListOf<Scan>()
        storage.sessionsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { sf ->
            val pid = sf.nameWithoutExtension.toLongOrNull() ?: return@forEach
            val text = try { sf.readText() } catch (_: Exception) { return@forEach }
            val sid = ClaudeTabsHelpers.extractJsonString(text, "sessionId") ?: return@forEach
            val cwd = ClaudeTabsHelpers.extractJsonString(text, "cwd") ?: return@forEach
            val ph = ProcessHandle.of(pid).orElse(null) ?: return@forEach
            if (!ph.isAlive) return@forEach
            val info = ph.info()
            val procInfo = SessionsDirScanner.ProcessInfo(info.command().orElse(""), info.commandLine().orElse(""))
            if (!SessionsDirScanner.looksLikeClaude(procInfo)) return@forEach
            // nameSource:"derived" marks a CLI placeholder (cwd basename + suffix), not a real
            // conversation topic — caching it as the entry's name produced useless tab titles.
            val claudeName =
                if (ClaudeTabsHelpers.extractJsonString(text, "nameSource") == "derived") null
                else ClaudeTabsHelpers.prettifySessionName(ClaudeTabsHelpers.extractJsonString(text, "name"))
            // The sid this process was LAUNCHED with — its argv never changes, so it still names
            // the tab the plugin bound at birth even after an in-app conversation switch.
            val argvSid = OwnedTerminalSpawner.sessionIdFromCommandLine(procInfo.commandLine)
            scans.add(Scan(pid, sid, cwd, claudeName, argvSid))
        }
        for ((sid, group) in scans.groupBy { it.sid }) {
            val s = group.first()
            try {
                storage.activeSessions.writeOrUpdate(sid, s.cwd, s.pid, now, s.name)
                seenSids.add(sid)
                sidToPid[sid] = s.pid
                sidToCwd[sid] = s.cwd
                c.everSeenAlive.add(sid)
                evictionTracker.recordAlive(sid)
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] writeOrUpdate failed for sid=$sid: ${e.message}")
            }
        }

        // Step 1a½: detect in-app conversation switches. A process whose session file names a
        // DIFFERENT sid than its own argv has been re-pointed by the user (claude's in-app resume
        // picker switches the live process to an old conversation; the argv keeps the launch sid).
        // The launch sid is the one the plugin bound the tab to at birth, so the pair
        // (launch sid → live sid) tells bindSessions exactly which tab binding to re-point.
        // Field evidence for why this matters: a re-pointed tab stays bound to a stub sid that
        // never registers a session entry, silently drops out of the snapshot, and the
        // conversation the user is ACTIVELY WORKING IN is lost on restart.
        val flipCandidates = mutableMapOf<String, MutableList<String>>() // launch sid → live sids
        for ((sid, group) in scans.groupBy { it.sid }) {
            val launchSid = group.first().argvSid ?: continue
            if (launchSid == sid) continue
            flipCandidates.getOrPut(launchSid) { mutableListOf() }.add(sid)
        }
        val argvFlips = mutableMapOf<String, String>()
        for ((launchSid, liveSids) in flipCandidates) {
            if (liveSids.size == 1) {
                argvFlips[launchSid] = liveSids.single()
            } else {
                LOG.info(
                    "[ClaudeTabs][rebind] ${liveSids.size} live sessions all carry launch sid=$launchSid " +
                        "in their argv — ambiguous, rebinding nothing (live=$liveSids)",
                )
            }
        }

        // Step 1b: bind sessions to owned widgets. Submitted before 1c so newly-bound Contents
        // are reflected in this same poll's presentSids.
        val aliveByCwd = sidToCwd.filter { (_, cwd) -> claimsCwd(cwd, project) }.toMap()
        ApplicationManager.getApplication().invokeLater { bindSessions(project, aliveByCwd, argvFlips.toMap()) }

        // Step 1c: record left-to-right order + recompute openness.
        ApplicationManager.getApplication().invokeLater { recordTabOrder(project) }

        // Step 2: walk active-sessions/. pid=null → retry-spawn candidate (never evicted); dead
        // recorded pid → demote to restore-pending after K strikes (process death is never a close).
        val unconfirmed = mutableListOf<ActiveSessionsStore.Entry>()
        for (entry in storage.activeSessions.listAll()) {
            if (entry.sid in seenSids) continue
            val recordedPid = entry.pid
            if (recordedPid == null) { unconfirmed.add(entry); continue }
            if (verifyAlive(recordedPid, entry.sid)) {
                evictionTracker.recordAlive(entry.sid)
                c.everSeenAlive.add(entry.sid)
                sidToPid[entry.sid] = recordedPid
                sidToCwd[entry.sid] = entry.cwd
            } else if (evictionTracker.recordDead(entry.sid)) {
                storage.activeSessions.writeOrUpdate(entry.sid, entry.cwd, pid = null, lastSeen = now)
                evictionTracker.forget(entry.sid)
                LOG.info("[ClaudeTabs] sid=${entry.sid} process gone (cwd=${entry.cwd}) — demoted to restore-pending")
            }
        }

        // Step 2b: persist the open-tabs snapshot for THIS project (frozen during teardown).
        // `isExitInProgress` is checked EVERY poll because it is the only signal guaranteed to be
        // set before the terminal starts killing ptys at quit (which self-closes agent tabs,
        // indistinguishable from × clicks) — the listener-set flags arrive seconds too late.
        val exitInProgress = appExitInProgress()
        val teardown = c.projectClosing || appShuttingDown || exitInProgress ||
            try { project.isDisposed } catch (_: Throwable) { false }
        if (teardown) {
            val why = "projectClosing=${c.projectClosing} appShuttingDown=$appShuttingDown " +
                "exitInProgress=$exitInProgress"
            if (c.lastTeardownWhy != why) {
                c.lastTeardownWhy = why
                LOG.info("[ClaudeTabs][snapshot] teardown detected ($why) — snapshot FROZEN, keeping last durable write")
            }
        } else if (!project.basePath.isNullOrBlank()) {
            val hash = projectHash(project)
            val present = c.presentSids.filter { sid ->
                storage.activeSessions.read(sid)?.let { claimsCwd(it.cwd, project) } == true
            }.toSet()
            // Warmup stickiness: a just-spawned tab isn't "present" for a poll or two (the
            // widget→Content link is async). Keep prev-snapshot sids spawned within SPAWN_WARMUP_MS
            // that aren't present yet. Can't re-admit a zombie: a zombie carries no recent spawn stamp.
            val prev = storage.loadOpenTabs(hash) ?: emptySet()
            // Warmup keeps just-spawned-but-not-yet-present sids; but never a sid that already
            // materialized then went absent (that's a real × — see computeOpenSnapshot).
            val snapshot = ClaudeTabsHelpers.computeOpenSnapshot(
                prev = prev,
                present = present,
                materializedSids = c.materializedSids,
                lastSpawnAttempt = c.restoreSpawnLastAttempt,
                now = now,
                warmupMs = SPAWN_WARMUP_MS,
            )
            // Teardown-race safety net: a poll that would drop SEVERAL sids at once (the shape of
            // pty teardown racing ahead of every close signal) defers the shrink one poll; a
            // single × still commits instantly. See applyRemovalDeferral for the full contract.
            val persisted = ClaudeTabsHelpers.applyRemovalDeferral(prev, snapshot, c.pendingSnapshotRemovals, now)
            val removed = prev - persisted
            val deferred = (prev - snapshot) - removed
            try {
                storage.saveOpenTabs(hash, persisted)
                if (persisted != prev) {
                    val added = persisted - prev
                    LOG.info(
                        "[ClaudeTabs][snapshot] $hash ${prev.size}→${persisted.size} open" +
                            (if (added.isNotEmpty()) " added=$added" else "") +
                            (if (removed.isNotEmpty()) " removed=$removed" else ""),
                    )
                }
                if (deferred.isNotEmpty()) {
                    LOG.info(
                        "[ClaudeTabs][snapshot] ${deferred.size} tab(s) vanished in one poll — removal DEFERRED " +
                            "one poll for confirmation (bulk vanish = teardown signature): $deferred",
                    )
                }
            } catch (e: Exception) {
                LOG.warn("[ClaudeTabs] open-tabs snapshot write failed: ${e.message}")
            }
        }

        // Step 4: retry-spawn for unconfirmed (pid=null) entries in the snapshot. Snapshot-gated
        // exactly like performRestore — a seed the user didn't have open is never re-spawned.
        val basePath = project.basePath
        val retrySnapshot = if (!basePath.isNullOrBlank()) storage.loadOpenTabs(projectHash(project)) ?: emptySet() else emptySet()
        if (!basePath.isNullOrBlank() && unconfirmed.isNotEmpty()) {
            for (entry in unconfirmed) {
                if (entry.sid !in retrySnapshot) continue
                if (RestoreGuard.blocksRetrySpawn(
                        entry.sid,
                        userClosed = emptySet(),
                        everHadWidget = c.everHadWidget,
                        everSeenAlive = c.everSeenAlive,
                        pendingClose = emptySet(),
                        spawnedWidgets = c.spawnedWidgets.keys,
                    )
                ) continue
                if (!claimsCwd(entry.cwd, project)) continue
                if (!ClaudeTabsHelpers.hasTranscriptAnywhere(storage.projectsDir, entry.sid, entry.cwd)) continue
                val attempts = c.restoreSpawnAttempts[entry.sid] ?: 0
                if (attempts >= MAX_SPAWN_ATTEMPTS) continue
                val last = c.restoreSpawnLastAttempt[entry.sid]
                if (last != null && (now - last) < RESPAWN_COOLDOWN_MS) continue
                c.restoreSpawnAttempts[entry.sid] = attempts + 1
                val displayName = TitleModel.resolveDisplayName(entry.userName, null, entry.name)
                resumeSpawnMutex.withLock {
                    withContext(Dispatchers.Main) {
                        if (!project.isDisposed) spawnOwnedTab(project, entry.sid, entry.cwd, displayName, OwnedTerminalSpawner.Mode.RESUME)
                    }
                    delay(RESTORE_STAGGER_MS)
                    awaitConfigSettled()
                }
                LOG.info("[ClaudeTabs] Retry-spawn sid=${entry.sid} cwd=${entry.cwd} attempt=${attempts + 1}/$MAX_SPAWN_ATTEMPTS")
                if (attempts + 1 >= MAX_SPAWN_ATTEMPTS) {
                    LOG.warn("[ClaudeTabs] sid=${entry.sid} reached spawn-attempt cap — leaving entry for manual recovery (claude --resume ${entry.sid})")
                }
            }
        }

        LOG.debug("[ClaudeTabs] poll done: alive=${seenSids.size} pendingNew=${c.pendingNew.size}")
    }

    // ──────────────────────────────────────────────────────────────────
    // Session ↔ tab binding (deterministic — replaces the whole reflection cascade)
    // ──────────────────────────────────────────────────────────────────

    /**
     * Bind alive sessions to owned widgets. Runs on the EDT (ContentManager access). Two parts:
     *
     *  (A) **Reconcile owned pending-new tabs.** For each `mintedUuid → PendingNew` the plugin
     *      launched via `claude --session-id <uuid>`: if a session file now carries the uuid,
     *      `--session-id` was honored and the birth-time binding stands. Otherwise Claude persisted
     *      a different id — adopt the single new unbound session in that tab's cwd (serialized spawns
     *      keep it unambiguous) via [rebindOwnedTab]. Give up after [SPAWN_WARMUP_MS].
     *
     *  (B) **Adopt hand-opened tabs.** A tab the user opened by hand (bypassing the action) has no
     *      birth binding. Correlate unowned present tabs to unowned alive sessions strictly 1:1 by
     *      cwd ([OwnedTerminalSpawner.pairUniqueByKey]) — ambiguity binds nothing, so a session can
     *      never attach to the wrong tab (and an × can never kill the wrong Claude).
     *
     * @param aliveByCwd sid → cwd for sessions seen alive this poll that belong to this project
     */
    private fun bindSessions(
        project: Project,
        aliveByCwd: Map<String, String>,
        argvFlips: Map<String, String> = emptyMap(),
    ) {
        if (project.isDisposed) return
        val c = ctx(project)
        try {
            val tw = TerminalToolWindowManager.getInstance(project).toolWindow ?: return
            val now = System.currentTimeMillis()

            // (A0) Argv-anchored re-point — follow in-app conversation switches. Runs FIRST so a
            // switched-to session is bound to its true tab before the pending-new reconcile below
            // could mistake it for a mint's real sid, and before the adopt passes could try to
            // pair it as an unowned session. Exact by construction: the argv ties the pid to the
            // tab (via the birth binding), the session scan ties the pid to the live sid. This is
            // inherently project-scoped — the launch sid is only bound in the project holding the
            // tab. The old sid's session entry is KEPT (it may be a real conversation the user
            // switched away from; it simply leaves the snapshot when its tab binding moves on).
            for ((launchSid, liveSid) in argvFlips) {
                val boundNow = c.contentToSid.values.toHashSet().apply { addAll(c.spawnedWidgets.keys) }
                if (launchSid !in boundNow) continue // tab not in this project, or already re-pointed
                if (liveSid in boundNow) {
                    LOG.debug("[ClaudeTabs][rebind] live sid=$liveSid already bound — not re-pointing from $launchSid")
                    continue
                }
                repointTabBinding(c, launchSid, liveSid)
                LOG.info(
                    "[ClaudeTabs][rebind] In-app conversation switch: tab launched as sid=$launchSid " +
                        "now hosts sid=$liveSid — binding re-pointed, snapshot follows the live conversation",
                )
            }

            // (A) Reconcile owned pending-new tabs.
            for ((uuid, pending) in c.pendingNew.toMap()) {
                if (uuid in aliveByCwd) {
                    // --session-id honored: birth binding is correct. Confirm and clear.
                    c.everHadWidget.add(uuid)
                    c.pendingNew.remove(uuid)
                    continue
                }
                val boundSids = c.contentToSid.values.toHashSet().apply { addAll(c.spawnedWidgets.keys) }
                val pendCwd = ClaudeTabsHelpers.normalizeCwd(pending.cwd)
                val candidates = aliveByCwd.filter { (sid, cwd) ->
                    sid !in boundSids && sid !in c.pendingNew.keys &&
                        ClaudeTabsHelpers.normalizeCwd(cwd) == pendCwd
                }.keys
                val pendingForCwd = c.pendingNew.values.count { ClaudeTabsHelpers.normalizeCwd(it.cwd) == pendCwd }
                if (candidates.size == 1 && pendingForCwd == 1) {
                    val realSid = candidates.first()
                    rebindOwnedTab(c, pending.widget, oldSid = uuid, newSid = realSid)
                    c.pendingNew.remove(uuid)
                    LOG.info("[ClaudeTabs] Adopted real sid=$realSid for owned new tab (minted=$uuid; --session-id not persisted)")
                } else if (now - pending.spawnedAt > SPAWN_WARMUP_MS) {
                    // Give up waiting. The tab stays bound to the minted uuid; harmless (no transcript
                    // under it, so it won't restore) and TitleController still names it.
                    c.pendingNew.remove(uuid)
                    LOG.debug("[ClaudeTabs] pendingNew uuid=$uuid aged out without a matching session")
                }
            }

            // Content → widget bridge. findWidgetByContent returns null for reworked-terminal tabs
            // the plugin didn't create (field-tested on 2026.2 — the 3.0.0/3.0.1 adopter died here),
            // so build the map from the manager's own container registry instead and keep
            // findWidgetByContent only as a fallback.
            val tm = TerminalToolWindowManager.getInstance(project)
            val widgetOfContent = HashMap<Content, TerminalWidget>()
            try {
                for (w in tm.terminalWidgets) {
                    val content = try { tm.getContainer(w)?.content } catch (_: Throwable) { null } ?: continue
                    widgetOfContent[content] = w
                }
            } catch (e: Throwable) {
                LOG.debug("[ClaudeTabs] container-registry widget map failed: ${e.message}")
            }

            // (B0) EXACT adopt: bind a tab whose OWN launch command carries the session id —
            // `claude --session-id <sid>` as the pty root command, the shape every AI-agents-button
            // launch has once ClaudeAgentLaunchCustomizer injects the id. This is a birth-time fact
            // read through public API, not a correlation, so it binds immediately (before Claude
            // even registers the session) and is immune to the ambiguity that makes the 1:1 pass
            // refuse. Two sources are merged, both keyed on the SAME Content instances the tool
            // window's ContentManager holds:
            //   - classic widgets (plugin-spawned tabs), via getShellCommand();
            //   - reworked-terminal tabs (the AI-agents launcher's tabs — invisible to the classic
            //     registry), via ReworkedTerminalBridge.
            // A sid the plugin already owns (a tab it spawned, or an earlier binding) is never
            // stolen — that guards against the IDE's own terminal persistence re-running a
            // previously injected argv into a duplicate tab.
            val exactByContent = HashMap<Content, String>()
            for ((content, w) in widgetOfContent) {
                if (c.contentToSid.containsKey(content) || c.spawnedWidgets.containsValue(w)) continue
                OwnedTerminalSpawner.sessionIdFromCommand(
                    try { w.shellCommand } catch (_: Throwable) { null },
                )?.let { exactByContent[content] = it }
            }
            for ((content, sid) in ReworkedTerminalBridge.presentClaudeTabs(project)) {
                if (!c.contentToSid.containsKey(content)) exactByContent.putIfAbsent(content, sid)
            }
            for ((content, sid) in exactByContent) {
                if (c.spawnedWidgets.containsKey(sid) || c.contentToSid.containsValue(sid)) continue
                c.contentToSid[content] = sid
                c.everHadWidget.add(sid)
                widgetOfContent[content]?.let { c.spawnedWidgets[sid] = it }
                LOG.info("[ClaudeTabs] Exact adopt (sid from tab's own launch command) → sid=$sid")
            }

            // Enumerate every UNBOUND tab ONCE, split into cwd-readable (a shell — has shell
            // integration) and undetermined (unreadable cwd = a full-screen pty = a Claude tab; also
            // any widgetless reworked tab). Built before the 1:1 pass so the FIFO pass below can run
            // even when no session is "alive" yet (a freshly injected tab whose Claude hasn't
            // registered its session file).
            val tabsByCwd = HashMap<String, MutableList<Pair<Content, TerminalWidget?>>>()
            val undetermined = mutableListOf<Pair<Content, TerminalWidget?>>()
            for (content in tw.contentManager.contents) {
                if (c.contentToSid.containsKey(content)) continue
                val w = widgetOfContent[content]
                    ?: try { TerminalToolWindowManager.findWidgetByContent(content) } catch (_: Exception) { null }
                if (w != null && c.spawnedWidgets.containsValue(w)) continue
                val cwd = currentDirectoryOf(w)
                if (cwd == null) { undetermined.add(content to w); continue }
                tabsByCwd.getOrPut(ClaudeTabsHelpers.normalizeCwd(cwd)) { mutableListOf() }.add(content to w)
            }

            // Title gate for the order/count-based passes below: on the reworked terminal an
            // unbound PLAIN SHELL tab is just as cwd-unreadable as an unbound Claude tab, so FIFO
            // and last-resort adoption could bind a session to a bash/pwsh tab the user opened
            // alongside (field-observed: a shell tab wearing a session's glyph and name). A tab
            // whose readable titles say "shell" is excluded from those passes; anything claude-ish
            // in a title, or no readable title at all, keeps the tab eligible.
            val bridgeTitles = ReworkedTerminalBridge.titlesByContent(project)
            fun observableTitles(content: Content, w: TerminalWidget?): List<String?> {
                val t = bridgeTitles[content] ?: try { w?.terminalTitle } catch (_: Throwable) { null }
                return listOf(
                    try { t?.userDefinedTitle } catch (_: Throwable) { null },
                    try { t?.applicationTitle } catch (_: Throwable) { null },
                    try { t?.defaultTitle } catch (_: Throwable) { null },
                    try { content.displayName } catch (_: Throwable) { null },
                )
            }
            val (shellTabs, claudeCandidates) = undetermined.partition {
                ClaudeTabsHelpers.titleLooksLikeShellTab(observableTitles(it.first, it.second))
            }
            if (shellTabs.isNotEmpty()) {
                LOG.info(
                    "[ClaudeTabs][adopt] ${shellTabs.size} unbound tab(s) titled like plain shells — " +
                        "excluded from FIFO/last-resort session binding",
                )
            }

            // (B1) FIFO injection-order binding — the BURST case. Several agent-button tabs opened
            // together in one project all land in `undetermined` (Claude owns the pty → cwd
            // unreadable), so the strict 1:1 rule refuses them. But each got a known --session-id
            // injected IN ORDER, and new Contents append to the ContentManager in that same order, so
            // pair still-unbound undetermined tabs to still-unclaimed injected sids in FIFO order.
            val fifoBound = fifoBindInjected(c, claudeCandidates, widgetOfContent, aliveByCwd, now)

            // (B2) Hand-open 1:1 adoption for anything the exact + FIFO passes didn't bind.
            val boundSids = c.contentToSid.values.toHashSet().apply { addAll(c.spawnedWidgets.keys) }
            val unownedSessions = aliveByCwd.filterKeys { it !in boundSids }
            val stillUndetermined = claudeCandidates.filter { !c.contentToSid.containsKey(it.first) }
            val pairs = HashMap<Pair<Content, TerminalWidget?>, String>()
            if (unownedSessions.isNotEmpty()) {
                val sessionsByCwd = unownedSessions.entries
                    .groupBy({ ClaudeTabsHelpers.normalizeCwd(it.value) }, { it.key })
                val readableTabsByCwd = HashMap<String, MutableList<Pair<Content, TerminalWidget?>>>()
                for ((cwd, list) in tabsByCwd) {
                    val fresh = list.filter { !c.contentToSid.containsKey(it.first) }
                    if (fresh.isNotEmpty()) readableTabsByCwd[cwd] = fresh.toMutableList()
                }
                // Pair among cwd-readable tabs (strict 1:1 per cwd — ambiguity binds nothing).
                pairs.putAll(
                    OwnedTerminalSpawner.pairUniqueByKey(
                        sessionsByKey = sessionsByCwd,
                        tabsByKey = readableTabsByCwd.mapValues { it.value.toList() },
                    ),
                )
                // Last resort: exactly ONE unowned session left, exactly ONE undetermined tab, and no
                // readable tab in the session's cwd competing for it — the only possible pairing.
                val leftoverSessions = unownedSessions.keys - pairs.values.toSet()
                if (leftoverSessions.size == 1 && stillUndetermined.size == 1) {
                    val sid = leftoverSessions.first()
                    val sessCwd = ClaudeTabsHelpers.normalizeCwd(unownedSessions[sid] ?: "")
                    if (readableTabsByCwd[sessCwd].isNullOrEmpty()) {
                        pairs[stillUndetermined.first()] = sid
                        LOG.info("[ClaudeTabs] Last-resort adopt: single unowned session + single undetermined tab → sid=$sid")
                    }
                }
                for ((tab, sid) in pairs) {
                    val (content, widget) = tab
                    c.contentToSid[content] = sid
                    c.everHadWidget.add(sid)
                    // Widget may be null for a bridged-but-widgetless tab: durability works off
                    // contentToSid alone; only title/animation needs the widget.
                    widget?.let { c.spawnedWidgets[sid] = it }
                    LOG.info("[ClaudeTabs] Hand-open 1:1 adopted tab (cwd match) → sid=$sid")
                }
            }
            // Always-on scan diagnostic (deduped): if adoption isn't happening, idea.log must say why.
            val summary = "[ClaudeTabs][adopt-scan] unownedSessions=${unownedSessions.size} " +
                "bridgedWidgets=${widgetOfContent.size} readableTabs=${tabsByCwd.values.sumOf { it.size }} " +
                "undetermined=${undetermined.size} shellTitled=${shellTabs.size} fifoBound=$fifoBound " +
                "adopted=${pairs.size} fifoQueue=${c.pendingInjectedSids.size}"
            if (summary != c.lastAdoptSummary) { c.lastAdoptSummary = summary; LOG.info(summary) }
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            if (msg != c.lastBindError) {
                c.lastBindError = msg
                LOG.warn("[ClaudeTabs] bindSessions failed — hand-opened tabs will NOT be adopted this session", e)
            }
        }
    }

    /**
     * (B1) FIFO injection-order binding. Pairs still-unbound [undetermined] tabs (unreadable cwd =
     * Claude tabs) to still-unclaimed injected sids in FIFO order — the deterministic answer to the
     * burst case the strict 1:1 rule can't touch. Order holds because ContentManager order = tab
     * creation order = injection order. Binds Content-only when the tab is widgetless (reworked).
     * Returns the number bound this pass. Heavily logged so the queue can be tracked in idea.log.
     */
    private fun fifoBindInjected(
        c: ProjectCtx,
        undetermined: List<Pair<Content, TerminalWidget?>>,
        widgetOfContent: Map<Content, TerminalWidget>,
        aliveByCwd: Map<String, String>,
        now: Long,
    ): Int {
        // Prune: drop injected sids already bound, evicted from storage, or aged past the TTL.
        val bound = c.contentToSid.values.toHashSet().apply { addAll(c.spawnedWidgets.keys) }
        val itr = c.pendingInjectedSids.iterator()
        while (itr.hasNext()) {
            val inj = itr.next()
            val gone = storage.activeSessions.read(inj.sid) == null
            val aged = now - inj.injectedAt > INJECT_FIFO_TTL_MS
            if (inj.sid in bound || gone || aged) {
                itr.remove()
                LOG.info("[ClaudeTabs][fifo] prune sid=${inj.sid} (alreadyBound=${inj.sid in bound} gone=$gone aged=$aged) → depth ${c.pendingInjectedSids.size}")
            }
        }
        // Only bind sids that are ALIVE or still fresh (Claude may not have registered yet); a sid
        // whose tab was closed before we caught it is neither → it waits out the TTL and is pruned,
        // rather than mis-binding a later tab.
        val bindable = c.pendingInjectedSids.filter {
            it.sid in aliveByCwd || (now - it.injectedAt) < INJECT_FIFO_FRESH_MS
        }
        if (bindable.isEmpty()) return 0
        val tabs = undetermined.map { it.first }.filter { !c.contentToSid.containsKey(it) }
        if (tabs.isEmpty()) {
            LOG.info("[ClaudeTabs][fifo] ${bindable.size} injected sid(s) bindable but no unbound undetermined tab yet (queue=${c.pendingInjectedSids.size})")
            return 0
        }
        val n = minOf(tabs.size, bindable.size)
        LOG.info("[ClaudeTabs][fifo] binding $n by injection order (unboundUndeterminedTabs=${tabs.size} bindableInjected=${bindable.size} queue=${c.pendingInjectedSids.size})")
        var bound2 = 0
        for (i in 0 until n) {
            val content = tabs[i]
            val inj = bindable[i]
            c.contentToSid[content] = inj.sid
            c.everHadWidget.add(inj.sid)
            val w = widgetOfContent[content]
            if (w != null) c.spawnedWidgets[inj.sid] = w
            c.pendingInjectedSids.remove(inj)
            bound2++
            LOG.info("[ClaudeTabs][fifo] bound tab#$i → sid=${inj.sid} widget=${w != null} (was injected ${now - inj.injectedAt}ms ago)")
        }
        return bound2
    }

    /**
     * Re-point a tab's bindings from [oldSid] to [newSid] WITHOUT deleting the old sid's session
     * entry — the in-app-resume path. Unlike [rebindOwnedTab] (whose old sid is a provisional mint
     * with no conversation behind it), here the old sid may be a REAL conversation the user
     * switched away from; its entry and transcript must survive so it stays resumable later. It
     * simply leaves `presentSids` (and hence the snapshot) once no tab binding points at it.
     * Handles widget-less (reworked/agent-button) tabs: durability works off `contentToSid` alone.
     */
    private fun repointTabBinding(c: ProjectCtx, oldSid: String, newSid: String) {
        c.spawnedWidgets.remove(oldSid)?.let { c.spawnedWidgets[newSid] = it }
        c.contentToSid.entries.firstOrNull { it.value == oldSid }?.key?.let { c.contentToSid[it] = newSid }
        c.lastAppliedTitle.remove(oldSid)
        c.restoreSpawnLastAttempt.remove(oldSid)?.let { c.restoreSpawnLastAttempt[newSid] = it }
        if (c.materializedSids.remove(oldSid)) c.materializedSids.add(newSid)
        c.everHadWidget.add(newSid)
    }

    /** Re-point an owned tab's bindings from [oldSid] (the minted uuid) to [newSid] (the id Claude
     *  actually persisted), and delete the provisional minted-uuid entry — the real sid's entry is
     *  written by the poll's session scan. */
    private fun rebindOwnedTab(c: ProjectCtx, widget: TerminalWidget, oldSid: String, newSid: String) {
        c.spawnedWidgets.remove(oldSid)
        c.spawnedWidgets[newSid] = widget
        c.contentToSid.entries.firstOrNull { it.value == oldSid }?.key?.let { c.contentToSid[it] = newSid }
        c.lastAppliedTitle.remove(oldSid)?.let { c.lastAppliedTitle[newSid] = it }
        c.restoreSpawnLastAttempt.remove(oldSid)?.let { c.restoreSpawnLastAttempt[newSid] = it }
        if (c.materializedSids.remove(oldSid)) c.materializedSids.add(newSid)
        c.everHadWidget.add(newSid)
        try { storage.activeSessions.delete(oldSid) } catch (_: Exception) { }
    }

    /** The tab's cwd, or null. Cascade of PUBLIC accessors (no private-field reflection):
     *  1. `getCurrentDirectory()` on the widget — present on the reworked TerminalWidgetImpl, but
     *     sourced from shell integration, which can go quiet while a full-screen app owns the pty.
     *  2. [TerminalWorkingDirectoryManager.getWorkingDirectory] — the terminal plugin's own
     *     cwd-tracking service (what the IDE itself uses for "open new tab in same directory"). */
    private fun currentDirectoryOf(widget: TerminalWidget?): String? {
        widget ?: return null
        try {
            val getter = widget.javaClass.methods.find { it.name == "getCurrentDirectory" && it.parameterCount == 0 }
            (getter?.invoke(widget) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Throwable) { }
        return try {
            TerminalWorkingDirectoryManager.getWorkingDirectory(widget)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) { null }
    }

    /**
     * Record each owned tab's left-to-right position into its per-sid `ordinal` (so restore rebuilds
     * the same order) AND recompute [ProjectCtx.presentSids] from the live ContentManager list. Runs
     * on the EDT. A stale `contentToSid` entry whose Content left the tool window is pruned here, so
     * it can't inflate openness.
     */
    private fun recordTabOrder(project: Project) {
        try {
            if (project.isDisposed) return
            val c = ctx(project)
            // Teardown guard: during quit the terminal kills ptys and agent tabs self-close, so
            // Contents leave the manager exactly like × clicks. Recomputing presence / pruning
            // bindings from that dissolving state would poison presentSids (and via it any late
            // snapshot write) — freeze the model too, not just the write.
            if (c.projectClosing || appShuttingDown || appExitInProgress()) {
                if (!c.tabOrderFrozenLogged) {
                    c.tabOrderFrozenLogged = true
                    LOG.info(
                        "[ClaudeTabs] recordTabOrder FROZEN — teardown in progress; keeping " +
                            "last-known presence (${c.presentSids.size} sid(s))",
                    )
                }
                return
            }
            val tw = TerminalToolWindowManager.getInstance(project).toolWindow ?: return

            // One-shot: poke the poll loop on tab add/remove. This is NOT close detection (the
            // falsified 1.x/2.x territory was CLASSIFYING removals); it only wakes the presence
            // recompute so an × is snapshotted before a same-instant IDE close freezes the
            // snapshot. During teardown the write guard in pollOnce still freezes everything.
            if (c.contentListenerInstalled.compareAndSet(false, true)) {
                tw.contentManager.addContentManagerListener(object : com.intellij.ui.content.ContentManagerListener {
                    override fun contentAdded(event: com.intellij.ui.content.ContentManagerEvent) { c.poke.trySend(Unit) }
                    override fun contentRemoved(event: com.intellij.ui.content.ContentManagerEvent) { c.poke.trySend(Unit) }
                })
            }
            val contents = tw.contentManager.contents

            val present = LinkedHashSet<String>()
            contents.forEachIndexed { index, content ->
                val sid = c.contentToSid[content] ?: run {
                    val w = try { TerminalToolWindowManager.findWidgetByContent(content) } catch (_: Exception) { null }
                    if (w != null) c.spawnedWidgets.entries.firstOrNull { it.value === w }?.key else null
                } ?: return@forEachIndexed
                present.add(sid)
                c.materializedSids.add(sid) // once present, warmup must never re-admit it after an ×
                val entry = storage.activeSessions.read(sid) ?: return@forEachIndexed
                if (entry.ordinal != index) {
                    storage.activeSessions.writeOrUpdate(entry.sid, entry.cwd, entry.pid, entry.lastSeen, ordinal = index)
                }
            }
            c.presentSids = present
            val liveContents = contents.toHashSet()
            c.contentToSid.keys.retainAll { it in liveContents }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] recordTabOrder failed: ${e.message}")
        }
    }

    /** PID + sid cross-check (handles PID reuse): `<pid>.json` exists AND its sessionId matches. */
    private fun verifyAlive(pid: Long, expectedSid: String): Boolean {
        val pidFile = File(storage.sessionsDir, "$pid.json")
        if (!pidFile.exists()) return false
        val text = try { pidFile.readText() } catch (_: Exception) { return false }
        if (ClaudeTabsHelpers.extractJsonString(text, "sessionId") != expectedSid) return false
        return try { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) } catch (_: Exception) { false }
    }

    // ──────────────────────────────────────────────────────────────────
    // Restore
    // ──────────────────────────────────────────────────────────────────

    /**
     * On project open, spawn one owned tab running `claude --resume <sid>` for each session in this
     * project's open-tabs snapshot whose transcript still exists, in saved order, serialized.
     */
    internal suspend fun performRestore(project: Project) {
        val basePath = project.basePath
        if (basePath.isNullOrBlank()) return
        val c = ctx(project)
        val entries = storage.activeSessions.listAll()
        // The snapshot written by the last normal poll before shutdown is the ONLY restore authority.
        // No snapshot (fresh install) → restore NOTHING; tabs the user opens get bound within a poll
        // and enter the snapshot from then on. There is deliberately no restore-all fallback (the flood).
        val openSnapshot = storage.loadOpenTabs(projectHash(project)) ?: emptySet()
        val toRestore = entries.filter { e ->
            claimsCwd(e.cwd, project) &&
                e.sid in openSnapshot &&
                ClaudeTabsHelpers.hasTranscriptAnywhere(storage.projectsDir, e.sid, e.cwd)
        }.sortedWith(compareBy({ it.ordinal ?: Int.MAX_VALUE }, { it.sid }))
        if (toRestore.isEmpty()) {
            LOG.info("[ClaudeTabs] Restore: nothing to spawn (snapshot=${openSnapshot.size}, entries=${entries.size})")
            return
        }
        LOG.info("[ClaudeTabs] Restore: spawning ${toRestore.size} owned tab(s) from a snapshot of ${openSnapshot.size}")
        val spawned = mutableListOf<ActiveSessionsStore.Entry>()
        for (e in toRestore) {
            storage.activeSessions.bumpRestoreAttempts(e.sid)
            val displayName = TitleModel.resolveDisplayName(e.userName, null, e.name)
            resumeSpawnMutex.withLock {
                withContext(Dispatchers.Main) {
                    if (project.isDisposed) return@withContext
                    c.restoreSpawnAttempts[e.sid] = (c.restoreSpawnAttempts[e.sid] ?: 0) + 1
                    spawnOwnedTab(project, e.sid, e.cwd, displayName, OwnedTerminalSpawner.Mode.RESUME)
                    spawned.add(e)
                }
                if (project.isDisposed) return
                delay(RESTORE_STAGGER_MS)
                awaitConfigSettled()
            }
        }
        writeLastRestoreSnapshot(project, spawned)
    }

    /** Drop a snapshot to `last-restore.json` so `/tabs-status` can show what was restored. */
    private fun writeLastRestoreSnapshot(project: Project, restored: List<ActiveSessionsStore.Entry>) {
        try {
            val out = File(storage.stateDir, "last-restore.json")
            out.parentFile?.mkdirs()
            val sessions = restored.joinToString(",") { e ->
                """{"sid":"${ClaudeTabsHelpers.esc(e.sid)}","cwd":"${ClaudeTabsHelpers.esc(e.cwd)}"}"""
            }
            val json = """{"restoredAt":${System.currentTimeMillis()},"projectName":"${ClaudeTabsHelpers.esc(project.name)}","count":${restored.size},"sessions":[$sessions]}"""
            out.writeText(json)
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] last-restore.json write failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Integration deployment — install Node helpers + skill files
    // ──────────────────────────────────────────────────────────────────

    private fun deployClaudeIntegration() {
        try {
            val cl = javaClass.classLoader
            storage.stateDir.mkdirs()
            for (name in listOf("current-project.js", "backup-active.js", "tab-name.js")) {
                val inStream = cl.getResourceAsStream("claude-integration/$name") ?: continue
                inStream.use { input -> File(storage.stateDir, name).outputStream().use { input.copyTo(it) } }
            }
            stripClaudeMdSection()
            for (legacy in listOf(
                    "tab-now.js", "rename-tab.sh", "tab.sh", "tab-backup.js",
                    "session-start-hook.sh", "rename-tab.log", "hook-debug.log", "last-hook-input.log",
            )) {
                try { File(storage.stateDir, legacy).delete() } catch (_: Exception) { }
            }
            storage.commandsDir.mkdirs()
            for (name in listOf("tab.md", "tabs-backup.md", "tabs-restore.md", "tabs-history.md", "tabs-status.md", "tabs-clear.md")) {
                val inStream = cl.getResourceAsStream("claude-integration/$name") ?: continue
                inStream.use { input -> File(storage.commandsDir, name).outputStream().use { input.copyTo(it) } }
            }
            ensureSettingsPermissions()
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] deployClaudeIntegration failed: ${e.message}")
        }
    }

    /** Ensure current permission entries exist in `~/.claude/settings.json`; remove legacy ones. */
    private fun ensureSettingsPermissions() {
        val settings = storage.settingsFile
        try {
            if (!settings.exists()) return
            val original = settings.readText()
            val updated = SettingsPermissions.rewriteAllowArray(
                original,
                remove = SettingsPermissions.LEGACY_ENTRIES.toSet(),
                add = SettingsPermissions.CURRENT_ENTRIES.toList(),
            ) ?: return
            if (updated != original) settings.writeText(updated)
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] settings.json update failed: ${e.message}")
        }
    }
}
