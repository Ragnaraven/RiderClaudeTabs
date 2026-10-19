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
 *    / reboot all reopen every tab, while an × (Content removed) drops one tab once the removal
 *    gate has confirmed it ([RemovalGate]: two polls, process dead, root healthy) with no
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

        /** Slack for the FIFO first-seen guard. An injected tab's Content necessarily appears
         *  AFTER its `--session-id` was injected, so a tab first observed BEFORE that instant can
         *  never be the injection's tab. The stamp itself comes from the ContentManager's own
         *  `contentAdded` event (no quantisation); this slack only covers the poll-time fallback
         *  stamp for a tab that already existed when the listener was installed. The guard exists because a
         *  hand-opened tab can now leave `undetermined` (its cwd became readable) and re-enter the
         *  FIFO candidate list, where without it a long-standing hand tab would absorb the first
         *  sid of a later injection burst. */
        private const val FIFO_FIRST_SEEN_SLACK_MS = 6_000L

        /** Adoption freshness window: the CORRELATION passes (hand-open 1:1 by cwd, last-resort
         *  single-pairing, minted-sid readback) may only pair a session with a tab while the
         *  session's OS process is at most this old. The legitimate hand-open case — the user
         *  types `claude` into a terminal — produces a seconds-old process; a long-running orphan
         *  (hand-resumed hours ago, a claude running OUTSIDE the IDE in the same cwd, or a stale
         *  session whose tab is shell-titled and unbindable) must NEVER latch onto a fresh empty
         *  tab, wear its name, or leak its identity across runtimes/accounts (a tab hosting a
         *  WSL-side claude is indistinguishable from an unreadable tab — the only safe rule is to
         *  refuse stale pairings entirely). Exact-identity passes (argv readback, injected-sid
         *  FIFO) are NOT gated: they carry positive identity, not correlation. */
        private const val ADOPT_FRESH_WINDOW_MS = 5 * 60_000L

        /** A correlation pairing that is not obviously safe must be observed stable for this long
         *  before it commits — one extra poll in which a competing signal can land and turn the
         *  1:1 into a refusal. Exactly one shape needs it: a FOREIGN-root (WSL/fake) sid, which
         *  would otherwise steal the tab of a just-typed Windows claude whose session file hasn't
         *  landed yet, and strand it outside the snapshot. (Shell-titled tabs used to defer here
         *  too; they are now excluded from every pass instead — a confirming poll cannot refute a
         *  static derivation, so it only delayed the wrong binding by one poll.) */
        private const val ADOPT_CONFIRM_MS = 8_000L

        /** Unbound-ledger tuning (see [UnboundLedger]). A session must be alive this long —
         *  clocked from its own `startedAt`, so a long-running one qualifies on first sight —
         *  before it counts as open without a tab binding; a death and an unbound-tab drop
         *  within ATTRIBUTION of each other read as the user closing that tab. Overridable so
         *  the E2E can exercise the arms in seconds rather than tens of seconds. */
        private val UNBOUND_ADMIT_MS =
            System.getProperty("claudetabs.unboundAdmitMs")?.toLongOrNull() ?: 10_000L
        private val UNBOUND_ATTRIBUTION_MS =
            System.getProperty("claudetabs.unboundAttributionMs")?.toLongOrNull() ?: 30_000L

        /** Every periodic diagnostic is deduped on change, so a healthy-but-quiet plugin and a
         *  dead one look identical in idea.log (field-verified: 2.4 h of idle sessions were read
         *  as a dead poll loop). One unconditional line per this many polls (~5 min) tells them
         *  apart. */
        private const val HEARTBEAT_EVERY_POLLS = 60L

        /** How long a session must KEEP looking alive at startup before restore believes it is
         *  running elsewhere rather than being the previous IDE's process still exiting. */
        private const val STILL_RUNNING_GRACE_MS = 15_000L
        private const val STILL_RUNNING_RECHECK_MS = 2_000L

        /** [sweepIdeRestoredTabs]: the IDE's own tab restore is asynchronous — the tab count must
         *  hold still this long to be believed, and the wait is bounded so a busy IDE cannot stall
         *  the plugin's restore. */
        private const val IDE_TAB_RESTORE_SETTLE_MS = 1_500L
        private const val IDE_TAB_RESTORE_SETTLE_MAX_MS = 10_000L
        /** A steady count of ZERO is believed only after this long: the IDE's restore may simply
         *  not have produced its first tab yet, and sweeping nothing would be a silent no-op. */
        private const val IDE_TAB_RESTORE_ZERO_WAIT_MS = 5_000L

        private val silentCloseUnavailableLogged = java.util.concurrent.atomic.AtomicBoolean(false)


        /** Circuit breaker for non-Windows roots: a scan that blocks longer than SLOW (a wedged
         *  \\wsl.localhost share hangs rather than errors) benches that root for BACKOFF, so
         *  repeated polls don't park thread after thread on the same dead share. */
        private const val ROOT_SCAN_SLOW_MS = 10_000L
        private const val ROOT_SCAN_BACKOFF_MS = 5 * 60_000L
        private val rootScanBackoffUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
        /** Why a root is benched: "throw" (its listing failed — UNHEALTHY) or "slow" (it answered,
         *  late — health UNKNOWN while benched). The removal gate treats the two differently. */
        private val rootBenchReason = java.util.concurrent.ConcurrentHashMap<String, String>()
        /** Roots discovered at least once this JVM: one that stops being discovered (a distro
         *  that went down) is UNHEALTHY, not merely unknown. */
        private val knownRootIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        private val lastRootHealthLogged = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** Removal gate ([RemovalGate]) timings; overridable for the E2E. */
        private val REMOVAL_SINGLE_CONFIRM_MS =
            System.getProperty("claudetabs.removalConfirmMs")?.toLongOrNull() ?: 4_000L
        private val REMOVAL_BULK_WINDOW_MS =
            System.getProperty("claudetabs.removalBulkWindowMs")?.toLongOrNull() ?: 10_000L
        private val REMOVAL_BULK_SETTLE_MS =
            System.getProperty("claudetabs.removalBulkSettleMs")?.toLongOrNull() ?: 20_000L
        /** Root-wide facts for the removal gate, shared by every project's poll: a burst of tab
         *  departures belongs to the ROOT that died, not to the project whose tab went first. */
        private val removalRootFacts = java.util.concurrent.ConcurrentHashMap<String, RemovalGate.RootFacts>()
        /** An outside addition to a snapshot is honoured for this long, then forgotten. */
        private const val EXTERNAL_REQUEST_TTL_MS = 2 * 60_000L
        /** Close evidence not consumed by the gate within this long is stale (the tab came back). */
        private const val CLOSE_EVIDENCE_TTL_MS = 60_000L
        /** A removal event with no sid pairs with a departure seen within this long of it. */
        private const val UNATTRIBUTED_REMOVAL_MS = 15_000L

        /** sid → cwd / root as last read or scanned. A session's cwd and root never change, so a
         *  cached value is as good as the file — and unlike the file it cannot be mid-rewrite. */
        private val sessionCwd = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val sessionRoot = java.util.concurrent.ConcurrentHashMap<String, String>()
        /** Last per-sid entry write from the scan (see [EntryWriteMark]); throttles the
         *  every-project-every-poll rewrite of every alive entry. */
        private val lastEntryWrite = java.util.concurrent.ConcurrentHashMap<String, EntryWriteMark>()
        /** root:pid:sid triples already reported as stale markers — one line per leftover. */
        private val staleMarkerLogged: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        private const val ENTRY_WRITE_MIN_INTERVAL_MS = 30_000L
        private data class EntryWriteMark(val at: Long, val pid: Long, val cwd: String, val name: String?, val startedAt: Long?)

        internal data class CloseEvidence(
            val at: Long,
            val ptyRootPid: Long?,
            val ptyAlive: Boolean?,
            /** The tab's own shell process (through the widget's tty connector): alive / dead / unknown. */
            val widgetProcessAlive: Boolean?,
            val teardown: Boolean,
        )

        /** Application-level user choice from the one-time startup-tabs takeover notification:
         *  "on"/"off"; absent = not asked yet. See [suppressIdeTerminalTabRestore]. */
        private const val OWN_STARTUP_TABS_CHOICE_KEY = "claudetabs.ownStartupTabs.choice"
        private val ownStartupTabsAskedThisSession = java.util.concurrent.atomic.AtomicBoolean(false)

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
        private val CLAUDE_GLOBAL_CONFIG = ClaudeHome.globalConfig

        /** Root of Claude Code's user data. */
        private val CLAUDE_HOME = ClaudeHome.dir

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

        /** Node helpers copied into every `~/.claude/rider-plugin`. SINGLE SOURCE OF TRUTH —
         *  the deploy path writes these and uninstall removes the directory holding them. */
        internal val INTEGRATION_HELPERS = listOf(
            "current-project.js", "backup-active.js", "tab-name.js", "recover-tabs.js",
        )

        /** Slash commands copied into every `~/.claude/commands`. Same single-source rationale:
         *  a command added here is deployed AND uninstalled, never one without the other. */
        internal val INTEGRATION_COMMANDS = listOf(
            "tab.md", "tabs-backup.md", "tabs-restore.md", "tabs-history.md",
            "tabs-status.md", "tabs-clear.md", "tabs-recover.md",
        )

        /** Command names written by 1.x builds — deleted on uninstall, never re-deployed. */
        private val LEGACY_COMMANDS = listOf(
            "clear-tabs.md", "restore-tabs.md", "tab-history.md", "backup-tabs.md",
        )

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

        private const val PLUGIN_VERSION = "3.0.0"

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

        /**
         * True once the IDE begins shutting down (quit OR restart). Freezes the snapshot so a
         * shutdown's tab-removal burst can't be mistaken for user ×-closes.
         *
         * Set on [com.intellij.ide.AppLifecycleListener.appClosing] — the earliest PUBLIC quit
         * signal, published app-wide once exit is committed and BEFORE any project starts closing
         * (pty kills happen inside project/tool-window teardown, strictly after it). The
         * platform-internal `ApplicationEx.isExitInProgress` flips marginally earlier (at the exit
         * REQUEST), but the gap between the two contains no teardown, and internal API is barred
         * from Marketplace plugins — so this flag plus the per-project `projectClosing` flag and
         * the bulk-vanish removal deferral (the net for any poll landing mid-burst) carry the
         * quit-race protection. The teardown races observed in the field predate this listener:
         * they were losses against the LATE signals (`projectClosing` of a second project,
         * `appWillBeClosed`), not against `appClosing`.
         */
        @Volatile private var appShuttingDown = false
        private val appClosingSubscribed = java.util.concurrent.atomic.AtomicBoolean(false)

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
            /** Launch command override — non-null when the session's root needs a different
             *  launcher than the plain Windows `claude` (a WSL session resumes through
             *  `wsl.exe -d <distro> …` so it comes back under ITS installation/account). */
            command: String? = null,
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
                c.markBound(sid)
                c.restoreSpawnLastAttempt[sid] = System.currentTimeMillis()
                // Content is created asynchronously — capture the widget↔Content link on the EDT.
                ApplicationManager.getApplication().invokeLater {
                    try {
                        findContentForWidget(project, widget)?.let { c.contentToSid[it] = sid }
                    } catch (_: Exception) { /* best effort */ }
                }
                val cmd = command ?: OwnedTerminalSpawner.launchCommand(sid, mode)
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
            val tw = TerminalTabs.toolWindow(project) ?: return null
            for (content in TerminalTabs.contents(project, tw)) {
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
            val homes = buildList {
                add(CLAUDE_HOME)
                try { WslDiscovery.runningRoots().forEach { add(it.claudeHome) } } catch (_: Exception) { }
            }
            for (home in homes) {
                File(home, "rider-plugin").deleteRecursively()
                (INTEGRATION_COMMANDS.map { "commands/$it" } + LEGACY_COMMANDS.map { "commands/$it" })
                    .forEach { File(home, it).delete() }
            }
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
         *  what it shows: an ×'d tab has no Content (drops once the removal gate confirms), a never-opened session has
         *  no Content (no flood). */
        @Volatile var presentSids: Set<String> = emptySet()
        /** When [presentSids] was last recomputed — evidence is only judged against a NEWER pass. */
        @Volatile var presentSidsAt: Long = 0L

        /** Sessions alive in this project that no tab binding accounts for — the second arm of
         *  the open-tabs snapshot. See [UnboundLedger]; replaced wholesale each poll. */
        @Volatile var unboundLedger: UnboundLedger.State = UnboundLedger.State()

        /** Dedup key for the ledger-membership log line. */
        @Volatile var lastUnboundLogged: Set<String> = emptySet()

        /** Sids a scan has reported as headless (`-p`/`--print`). Step 2's survivors carry no argv,
         *  so without this a headless run refused while its root was scannable would be offered
         *  again as "unknown" on the first poll the root is benched — and admitted. */
        val headlessSids: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Terminal Contents with no sid binding as of [recordTabOrder]'s last EDT pass
         *  (recursive, so split panes count). The close signal for the unbound arm is IDENTITY,
         *  not arithmetic: one of these that is no longer present was closed — its × removed a
         *  Content the plugin could not name — whereas one that merely gained a binding is still
         *  present and must not read as a close. A count delta gets that wrong every time an
         *  owned tab spawns: its Content is unresolvable for a poll or two while the widget link
         *  lands, so the count rises and then falls with nothing closed at all. */
        @Volatile var lastUnboundContents: Set<com.intellij.ui.content.Content> = emptySet()

        /** Closed unbound Contents observed since the poll last consumed them ([UnboundLedger]'s
         *  `unboundTabDrop`). Written on the EDT, drained on the poll thread. */
        val unboundDrops = java.util.concurrent.atomic.AtomicInteger(0)

        /** Tab counts from [recordTabOrder]'s last trusted pass: the heartbeat prints them, and the
         *  unbound one caps the ledger (a session cannot be open in a tab that does not exist).
         *  Meaningless until [tabCountsObserved]. */
        @Volatile var unboundContentCount: Int = 0
        @Volatile var contentCount: Int = 0
        @Volatile var tabCountsObservedAt: Long = 0L
        /** Consecutive [recordTabOrder] passes that enumerated no tabs at all. EDT only. */
        var emptyTabPasses: Int = 0

        /** Completed poll cycles — drives the periodic heartbeat. Poll thread only. */
        var pollCount: Long = 0

        /** Set by [com.intellij.openapi.project.ProjectManagerListener.projectClosing]. */
        @Volatile var projectClosing: Boolean = false

        /** Ambiguity grace (EDT-only): sid → epoch-ms until which the sid stays adoption-eligible
         *  after being refused ONLY because of a same-cwd contest — the refusal must be a delay,
         *  not a permanent loss of the tab from the snapshot. See bindSessions (B2). */
        val adoptGraceUntil = HashMap<String, Long>()

        /** Correlation pairing awaiting its one-poll confirmation (EDT-only): sid → (tab
         *  Content, first-observed epoch-ms). See ADOPT_CONFIRM_MS. */
        val pendingAdoptConfirm = HashMap<String, Pair<Content, Long>>()

        /** Content → epoch-ms this project first observed the tab UNBOUND (EDT-only, pruned each
         *  poll to the contents that are still present and still unbound). Stamped by the terminal
         *  ContentManager's `contentAdded` listener the instant a tab appears, with the poll's own
         *  stamp as the fallback for tabs that predate the listener. The FIFO pass's first-seen
         *  guard reads it — see FIFO_FIRST_SEEN_SLACK_MS. */
        val firstSeenUnbound = HashMap<Content, Long>()

        /** One-shot log dedup for [suppressIdeTerminalTabRestore] (the clear itself repeats). */
        @Volatile var ideTabRestoreSuppressLogged = false

        /** Removal-gate state ([RemovalGate]): what has left `present` and is not yet confirmed gone. */
        @Volatile var removalGate: RemovalGate.State = RemovalGate.State()

        /** The snapshot as this plugin last WROTE it: the authority while the file is unreadable,
         *  and the baseline that tells an outside write (the recovery command) from our own. */
        @Volatile var lastPersistedSnapshot: Set<String>? = null

        /** sid → when it appeared in the snapshot file from OUTSIDE (the recovery command). Such a
         *  sid is an explicit request: reopened once with the resurrection guards bypassed. */
        val externalRequested = ConcurrentHashMap<String, Long>()

        @Volatile var lastHeldLogged: Set<String> = emptySet()
        @Volatile var snapshotUnreadableLogged = false

        /** Removal events whose Content could not be mapped to a sid at the instant they fired
         *  (an adopted tab whose Content the event names differently, a plain shell). Paired by
         *  time with a departure the next poll sees: a removal DID happen, so that departure is
         *  "observed, liveness unknown" rather than "vanished without an event". */
        val unattributedRemovals = java.util.concurrent.ConcurrentLinkedDeque<Long>()

        /** The reworked bridge's per-tab facts as of the last binding pass — so a removal
         *  listener can still say which pty ROOT process a just-removed Content had. */
        @Volatile var lastViewInfo: Map<Content, ReworkedTerminalBridge.ViewInfo> = emptyMap()

        /** What was true at the instant a tab's Content was removed, keyed by sid; the removal
         *  gate consumes it. `widgetProcessAlive` (the shell behind the widget, via its tty
         *  connector) is the primary fact, `ptyAlive` (the startup-options pid) the fallback: true
         *  = something closed the tab under a living shell (a ×); false = the tab closed itself
         *  because its process had already died (a crash, a teardown, a distro going down);
         *  null = nothing local to ask (a remote pty). */
        val closeEvidence = ConcurrentHashMap<String, CloseEvidence>()

        /** Sids whose tab binding was re-pointed AWAY by an in-app conversation switch. They stay
         *  in [everHadWidget] (the retry-spawn guard is monotonic) but the ledger may admit them:
         *  the conversation was not closed, its tab merely hosts another one now, and the user
         *  may come back to it — a session that was worked in for hours went unrecorded because
         *  this set did not exist. Cleared again the moment the sid gains a binding. */
        val switchedAway: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Every path that binds a tab to [sid] goes through here. */
        fun markBound(sid: String) {
            everHadWidget.add(sid)
            switchedAway.remove(sid)
        }

        /** What the ledger must never admit: bound-owned sids, minus the ones switched away. */
        fun ledgerNeverAdmit(): Set<String> = everHadWidget - switchedAway

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

        /** Last shell-titled-tabs verdict logged — same dedup (the undeduped line was emitted 132
         *  identical times in a 9-minute run). Cleared when no tab is shell-titled, so the verdict
         *  logs again if it returns. */
        @Volatile var lastShellTitledLine: String? = null

        /** Last per-tab title diagnostic block logged — same dedup, one block per change. */
        @Volatile var lastTitleDiag: String? = null

        /** Last bindSessions failure message logged — same dedup. */
        @Volatile var lastBindError: String? = null

        /** Last logged poll-loop error (dedup — a recurring poll failure logs once, not per 5s). */
        @Volatile var lastPollError: String? = null

        /** Wakes the poll loop early (conflated — bursts coalesce). Poked by the terminal
         *  ContentManager listener on tab add/remove so an × is snapshotted IMMEDIATELY: the 5s
         *  cadence loses the race against an IDE close right after an × (field-tested — the ×'d
         *  tab "necroed" on restart because the frozen snapshot predated the close). */
        val poke = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

        /** The terminal ContentManager the poke listener is installed on — re-installed whenever
         *  the registered tool window (and so its manager) changes; see [TerminalTabs]. */
        /** Every ContentManager a removal listener is installed on — the tool window's own and
         *  each split pane's (a split has its own manager, and a × there fires only on it). */
        val listenedContentManagers: MutableSet<com.intellij.ui.content.ContentManager> =
            java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

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
        maybeAskOwnStartupTabs(project)

        // App-shutdown subscription (once per JVM). BOTH lifecycle hooks set the flag: appClosing
        // is published at the START of the committed exit sequence — before any project begins
        // closing, and therefore before the terminal starts killing ptys — and appWillBeClosed is
        // the late backstop. This is the earliest public quit signal; the bulk-vanish removal
        // deferral covers any poll that lands inside a teardown burst regardless.
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

        // Dispatchers.IO, not Default: the poll loop and title ticks do blocking file IO —
        // including UNC reads against \\wsl.localhost, which HANG rather than error when the 9p
        // server is unwell. Blocking IO is not cancellable, so a wedged call parks its thread;
        // on the small shared Default pool that starves the whole IDE's coroutines, on IO's
        // large pool it costs one expendable thread (plus the per-root scan breaker in pollOnce).
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        try {
            val pmListener = object : com.intellij.openapi.project.ProjectManagerListener {
                override fun projectClosing(p: com.intellij.openapi.project.Project) {
                    if (p == project) {
                        ctx(project).projectClosing = true
                        LOG.info("[ClaudeTabs] Project closing — freezing open-tabs snapshot")
                        suppressIdeTerminalTabRestore(project, ctx(project))
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
            try {
                performRestore(project)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                // Outside the loop's own catch until now: a throw here ended the coroutine before
                // its first poll, with no `Restore:` line and no poll error — the plugin looked
                // installed and did nothing for the whole IDE session.
                LOG.warn("[ClaudeTabs] Restore failed (${e.javaClass.simpleName}: ${e.message}) — polling continues", e)
            }
            val c = ctx(project)
            while (isActive) {
                try {
                    pollOnce(project)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    // WARN, deduped — a poll that dies every cycle is the plugin silently blind
                    // (entries freeze, closes go undetected) and must be visible in idea.log.
                    val msg = "${e.javaClass.simpleName}: ${e.message}"
                    if (msg != ctx(project).lastPollError) {
                        ctx(project).lastPollError = msg
                        LOG.warn("[ClaudeTabs] Poll error (recurring polls suppressed): $msg", e)
                    }
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
            var reset = 0
            for (entry in storage.activeSessions.listAll()) {
                val pid = entry.pid ?: continue
                if (verifyAlive(pid, entry.sid, entry.root)) continue
                // lastSeen is KEPT: it is the last time the session was really seen alive, and
                // the recovery command ranks by it — stamping "now" made every session that died
                // in a crash look active at the moment the IDE came back.
                storage.activeSessions.writeOrUpdate(
                    sid = entry.sid, cwd = entry.cwd, pid = null, lastSeen = entry.lastSeen, name = null,
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
        c.pollCount++

        // Step 0: self-heal ~/.claude.json (Claude's concurrent writers corrupt it under churn).
        when (val status = configGuard.check()) {
            ConfigGuard.Status.VALID, ConfigGuard.Status.SUSPECT -> {}
            ConfigGuard.Status.UNREPAIRABLE ->
                LOG.warn("[ClaudeTabs] ConfigGuard: ~/.claude.json is corrupt and no repair applies — leaving untouched")
            else ->
                LOG.warn("[ClaudeTabs] ConfigGuard: ~/.claude.json was corrupt — auto-repaired ($status)")
        }

        // Step 1: every alive Claude process IN EVERY SESSION ROOT → per-sid file. Roots = the
        // Windows ~/.claude plus each RUNNING WSL distro's (each a separate installation and
        // typically a separate ACCOUNT — see SessionRoot; identity never crosses roots). WSL cwds
        // are stored Windows-translated so all downstream cwd logic works unchanged. No
        // attachment/ancestry gating — falsified (dead chains on live tabs). Openness is a
        // present tab, computed in step 1c.
        data class Scan(
            val root: SessionRoot, val pid: Long, val sid: String,
            val cwd: String, val name: String?, val argvSid: String?, val startedAt: Long?,
            /** Launched non-interactively (`-p`/`--print`) — never a tab. Null = argv unreadable. */
            val headless: Boolean?,
        )
        val scans = mutableListOf<Scan>()
        // Roots whose sessions dir was actually LISTED this poll. Only an absence observed in one
        // of these means anything; a root that is benched, undiscovered, or whose directory could
        // not be listed says nothing about its sessions (see UnboundLedger — a stopped or wedged
        // distro must hold the snapshot, not empty it).
        val observableRootIds = mutableSetOf<String>()
        // Per-root HEALTH this poll: true = listed and probed, false = unhealthy (listing failed,
        // probe failed, benched after a throw, no longer discovered), null = unknown (benched
        // after a slow scan). Feeds the removal gate; observableRootIds is its `true` subset.
        val rootHealth = HashMap<String, Boolean?>()
        val rootHealthWhy = HashMap<String, String>()
        val roots: List<SessionRoot> =
            listOf(SessionRoot.Windows) + WslDiscovery.runningRoots() + FakeRoots.all
        for (root in roots) {
            knownRootIds.add(root.id)
            // Bench a root whose previous scan wedged or threw: \\wsl.localhost hangs rather
            // than errors when the 9p server is unwell (blocking File IO is not cancellable —
            // repeated polls would park thread after thread on the same dead share), and the
            // IDE platform hooks java.io access to wsl.localhost paths and throws UNCHECKED
            // (FileSystemNotFoundException — field-observed for a container-runtime utility
            // distro that lists as running but has no browsable filesystem).
            if ((rootScanBackoffUntil[root.id] ?: 0L) > now) {
                if (rootBenchReason[root.id] == "throw") {
                    rootHealth[root.id] = false; rootHealthWhy[root.id] = "benched after a failed scan"
                } else {
                    rootHealth[root.id] = null; rootHealthWhy[root.id] = "benched after a slow scan"
                }
                continue
            }
            val scanStart = System.currentTimeMillis()
            var listing: Array<File>? = null
            var probed = false
            try {
                listing = root.listSessionFiles()
                // Positive proof the root reports on processes, taken inside the same try and
                // timing window as the listing (a hang or throw here benches the root too).
                probed = listing != null && root.probe()
                listing?.forEach { sf ->
                    val pid = sf.nameWithoutExtension.toLongOrNull() ?: return@forEach
                    val text = try { sf.readText() } catch (_: Exception) { return@forEach }
                    val sid = ClaudeTabsHelpers.extractJsonString(text, "sessionId") ?: return@forEach
                    val rawCwd = ClaudeTabsHelpers.extractJsonString(text, "cwd") ?: return@forEach
                    if (!root.processAlive(pid)) return@forEach
                    // Recycling guard. A root that can't inspect argv (WSL /proc virtual files over
                    // the share) returns null — accepted: the session file sits in THAT root's own
                    // ~/.claude, written by its claude, and the sessionId guard applies downstream.
                    // BLANK info (Windows can report a live process with empty command AND command
                    // line, e.g. across access-token boundaries) is the same "unknown" case — a
                    // node-wrapped claude would otherwise be filtered and its session never tracked.
                    val info = root.processInfo(pid)
                        ?.takeIf { it.command.isNotBlank() || it.commandLine.isNotBlank() }
                    if (info != null && !SessionsDirScanner.looksLikeClaude(info)) return@forEach
                    // nameSource:"derived" marks a CLI placeholder (cwd basename + suffix), not a real
                    // conversation topic — caching it as the entry's name produced useless tab titles.
                    val claudeName =
                        if (ClaudeTabsHelpers.extractJsonString(text, "nameSource") == "derived") null
                        else ClaudeTabsHelpers.prettifySessionName(ClaudeTabsHelpers.extractJsonString(text, "name"))
                    // The sid this process was LAUNCHED with — its argv never changes, so it still names
                    // the tab the plugin bound at birth even after an in-app conversation switch.
                    val argvSid = OwnedTerminalSpawner.sessionIdFromCommandLine(info?.commandLine)
                    // The CLI stamps its own start time into the file — the freshness anchor that
                    // works identically for every root (no process API needed).
                    val startedAt = ClaudeTabsHelpers.extractJsonLong(text, "startedAt")
                    // Recycled-pid guard: a marker older than the process it names belongs to an
                    // earlier process with the same pid (see ClaudeTabsHelpers.markerIsStale) — not
                    // a session, and never evidence of an in-app switch.
                    if (ClaudeTabsHelpers.markerIsStale(startedAt, root.processStartEpochMs(pid))) {
                        if (staleMarkerLogged.add("${root.id}:$pid:$sid")) {
                            LOG.info("[ClaudeTabs][scan] stale session marker ignored: root ${root.id} pid $pid names a session that started before the process did (recycled pid)")
                        }
                        return@forEach
                    }
                    scans.add(
                        Scan(
                            root, pid, sid, root.comparableCwd(rawCwd), claudeName, argvSid, startedAt,
                            headless = ClaudeTabsHelpers.isHeadlessArgs(info?.args),
                        ),
                    )
                }
            } catch (t: Throwable) {
                // One bad root must NEVER abort the poll: before this catch existed, the utility
                // distro's throw killed every poll mid-scan — entries froze (pids never updated,
                // new sessions never tracked) while the loop looked alive. Bench it and move on.
                rootScanBackoffUntil[root.id] = System.currentTimeMillis() + ROOT_SCAN_BACKOFF_MS
                rootBenchReason[root.id] = "throw"
                rootHealth[root.id] = false; rootHealthWhy[root.id] = "scan threw"
                LOG.warn(
                    "[ClaudeTabs][wsl] root ${root.id} scan failed " +
                        "(${t.javaClass.simpleName}: ${t.message}) — benched for ${ROOT_SCAN_BACKOFF_MS / 1000}s",
                )
                continue
            }
            val scanTook = System.currentTimeMillis() - scanStart
            if (root !is SessionRoot.Windows && scanTook > ROOT_SCAN_SLOW_MS) {
                rootScanBackoffUntil[root.id] = System.currentTimeMillis() + ROOT_SCAN_BACKOFF_MS
                rootBenchReason[root.id] = "slow"
                LOG.warn(
                    "[ClaudeTabs][wsl] root ${root.id} scan took ${scanTook}ms — " +
                        "benched for ${ROOT_SCAN_BACKOFF_MS / 1000}s",
                )
            }
            // null = the directory could not be listed (missing, unreachable). That is NOT an
            // empty root: treating it as one made every session in a stopped distro look dead.
            // Decided AFTER the slow-scan check so a share that blocks for a minute and then
            // returns null is still benched instead of parking a thread every poll.
            rootHealth[root.id] = probed
            rootHealthWhy[root.id] = when {
                listing == null -> "sessions directory not listable"
                !probed -> "process probe failed"
                else -> "listed and probed"
            }
            if (probed) observableRootIds.add(root.id)
        }
        // A root this JVM has seen that is no longer discovered (a distro that went down) is
        // UNHEALTHY: its sessions' tabs closing now are the outage, not the user.
        val discovered = roots.map { it.id }.toSet()
        for (id in knownRootIds) if (id !in discovered) { rootHealth[id] = false; rootHealthWhy[id] = "no longer discovered" }
        for ((id, h) in rootHealth) {
            val label = when (h) { true -> "healthy"; false -> "UNHEALTHY"; null -> "unknown" } + " (${rootHealthWhy[id]})"
            if (lastRootHealthLogged.put(id, label) != label) LOG.info("[ClaudeTabs][root] $id $label")
        }
        val adoptableFreshSids = mutableSetOf<String>()
        val sidRootIds = mutableMapOf<String, String>()
        // Every session alive this poll, in any root and any cwd, with what the ledger needs to
        // admit it. Step 2's survivors are added below — a root that could not be scanned may still
        // answer for one pid.
        val aliveInfo = mutableMapOf<String, UnboundLedger.Alive>()
        for ((sid, group) in scans.groupBy { it.sid }) {
            val s = group.first()
            try {
                // Every open project runs this for every alive session every poll; the rewrites
                // raced the bound arm's reads. Write when something changed or ~30 s have passed.
                val mark = lastEntryWrite[sid]
                if (mark == null || now - mark.at >= ENTRY_WRITE_MIN_INTERVAL_MS ||
                    mark.pid != s.pid || mark.cwd != s.cwd || mark.name != s.name || mark.startedAt != s.startedAt
                ) {
                    storage.activeSessions.writeOrUpdate(sid, s.cwd, s.pid, now, s.name, root = s.root.id, startedAt = s.startedAt)
                    lastEntryWrite[sid] = EntryWriteMark(now, s.pid, s.cwd, s.name, s.startedAt)
                }
                sessionCwd[sid] = s.cwd
                sessionRoot[sid] = s.root.id
                seenSids.add(sid)
                sidToPid[sid] = s.pid
                sidToCwd[sid] = s.cwd
                sidRootIds[sid] = s.root.id
                if (s.headless == true) c.headlessSids.add(sid)
                aliveInfo[sid] = UnboundLedger.Alive(s.root.id, s.startedAt, s.headless)
                c.everSeenAlive.add(sid)
                evictionTracker.recordAlive(sid)
                // Adoption-freshness (see ADOPT_FRESH_WINDOW_MS): correlation passes may only pair
                // this sid with a tab while the session is minutes old. Primary anchor: the CLI's
                // own `startedAt` stamp (field-verified present on every root's session files in
                // practice); then the root's process start time. If BOTH are unknown the age is
                // unknown, and unknown age must fail CLOSED — [isAdoptablyFresh] already does, and
                // anchoring the clock at "when this JVM first saw it" defeated exactly that: a
                // stale orphan looked seconds old and was adopted onto a fresh tab.
                val startMs = s.startedAt ?: s.root.processStartEpochMs(s.pid)
                if (ClaudeTabsHelpers.isAdoptablyFresh(startMs, now, ADOPT_FRESH_WINDOW_MS)) {
                    adoptableFreshSids.add(sid)
                }
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
        val freshSids = adoptableFreshSids.toSet()
        val sidRoots = sidRootIds.toMap()
        // Snapshot: the poll thread keeps mutating sidToPid in step 2, after this hand-off.
        val sidPids = sidToPid.toMap()
        ApplicationManager.getApplication().invokeLater {
            bindSessions(project, aliveByCwd, argvFlips.toMap(), freshSids, sidRoots, sidPids)
        }

        // Step 1c: record left-to-right order + recompute openness.
        ApplicationManager.getApplication().invokeLater { recordTabOrder(project) }

        // Step 2: walk active-sessions/. pid=null → retry-spawn candidate (never evicted); dead
        // recorded pid → demote to restore-pending after K strikes (process death is never a close).
        val unconfirmed = mutableListOf<ActiveSessionsStore.Entry>()
        for (entry in storage.activeSessions.listAll()) {
            if (entry.sid in seenSids) continue
            val recordedPid = entry.pid
            if (recordedPid == null) { unconfirmed.add(entry); continue }
            if (verifyAlive(recordedPid, entry.sid, entry.root)) {
                sessionCwd[entry.sid] = entry.cwd
                sessionRoot[entry.sid] = entry.root
                evictionTracker.recordAlive(entry.sid)
                c.everSeenAlive.add(entry.sid)
                sidToPid[entry.sid] = recordedPid
                sidToCwd[entry.sid] = entry.cwd
                aliveInfo[entry.sid] = UnboundLedger.Alive(
                    entry.root, entry.startedAt, headless = if (entry.sid in c.headlessSids) true else null,
                )
            } else if (evictionTracker.recordDead(entry.sid)) {
                storage.activeSessions.writeOrUpdate(entry.sid, entry.cwd, pid = null, lastSeen = now)
                evictionTracker.forget(entry.sid)
                LOG.info("[ClaudeTabs] sid=${entry.sid} process gone (cwd=${entry.cwd}) — demoted to restore-pending")
            }
        }

        // Step 2b: persist the open-tabs snapshot for THIS project (frozen during teardown).
        // appShuttingDown (set on appClosing, which precedes all project teardown at quit) and
        // projectClosing cover quit and window-close; the terminal's pty kills self-close agent
        // tabs exactly like × clicks, so any poll that lands inside a removal burst before either
        // flag flips is caught by the removal gate instead (dead shells at removal = suspects).
        val teardown = c.projectClosing || appShuttingDown ||
            try { project.isDisposed } catch (_: Throwable) { false }
        if (teardown) {
            val why = "projectClosing=${c.projectClosing} appShuttingDown=$appShuttingDown"
            if (c.lastTeardownWhy != why) {
                c.lastTeardownWhy = why
                LOG.info("[ClaudeTabs][snapshot] teardown detected ($why) — snapshot FROZEN, keeping last durable write")
            }
            // Repeated on every teardown poll: the terminal may rewrite its persisted tab list
            // during its own shutdown bookkeeping, and the LAST write must be the empty one.
            suppressIdeTerminalTabRestore(project, c)
        } else if (!project.basePath.isNullOrBlank()) {
            val hash = projectHash(project)
            val basePath = project.basePath!!
            // Arm 1 — bound: sessions a present tab is bound to.
            // Cache first: a sid's cwd never changes, and the entry file may be mid-rewrite by
            // another project's poll — a null read here dropped bound tabs out of the snapshot.
            val bound = c.presentSids.filter { sid ->
                val cwd = sessionCwd[sid] ?: storage.activeSessions.read(sid)?.let { e ->
                    sessionCwd[sid] = e.cwd; sessionRoot[sid] = e.root; e.cwd
                }
                cwd != null && claimsCwd(cwd, project)
            }.toSet()
            // Arm 2 — unbound: sessions alive in this project that no tab binding accounts for.
            // A binding answers "which tab is this session in", which renaming needs and restore
            // does not: restore only needs to know the session was open here. Gating the snapshot
            // on a binding dropped every hand-opened tab adoption could not pair — with a dozen tabs
            // in one project directory, that is nearly all of them (field-observed twice: 4 of 17
            // tabs restored). The ledger keeps them, evicts on an attributed close, and hands a sid
            // to the bound arm for good the moment it gains a widget.
            val unboundTabDrop = c.unboundDrops.getAndSet(0)
            val openBases = openProjectBases()
            // Both claim rules: claimsCwd is what restore and retry-spawn honour (it also arbitrates
            // against project-index.json), claimsExclusively adds "the most specific OPEN project
            // wins" for nested windows. A sid only one of them accepts would be a permanent
            // snapshot member nothing ever restores.
            val aliveClaimed = aliveInfo.filterKeys { sid ->
                sidToCwd[sid]?.let { cwd ->
                    claimsCwd(cwd, project) && ClaudeTabsHelpers.claimsExclusively(cwd, basePath, openBases)
                } == true
            }
            val ledger = UnboundLedger.step(
                prev = c.unboundLedger,
                aliveClaimed = aliveClaimed,
                aliveAnywhere = aliveInfo.keys,
                observableRoots = observableRootIds,
                neverAdmit = c.ledgerNeverAdmit(),
                unboundTabDrop = unboundTabDrop,
                now = now,
                admitMs = UNBOUND_ADMIT_MS,
                attributionMs = UNBOUND_ATTRIBUTION_MS,
                // Only a RECENT count caps anything: if the tab enumeration has been failing, a
                // frozen number must not keep shedding sessions against tabs it can no longer see.
                unboundTabCount = if (now - c.tabCountsObservedAt <= 6 * POLL_INTERVAL_MS) c.unboundContentCount else null,
            )
            c.unboundLedger = ledger.state
            if (ledger.shedByCap.isNotEmpty()) {
                LOG.info("[ClaudeTabs][snapshot] ${ledger.shedByCap.size} dead session(s) with no tab left to be in — dropped: ${ledger.shedByCap}")
            }
            if (ledger.evictedByClose.isNotEmpty()) {
                LOG.info("[ClaudeTabs][snapshot] ${ledger.evictedByClose.size} unbound session(s) left with their tab: ${ledger.evictedByClose}")
            }
            if (ledger.purged.isNotEmpty()) {
                LOG.info("[ClaudeTabs][snapshot] ${ledger.purged.size} session(s) gained a tab binding — bound arm owns them now: ${ledger.purged}")
            }
            if (ledger.members != c.lastUnboundLogged) {
                c.lastUnboundLogged = ledger.members
                LOG.info(
                    "[ClaudeTabs][snapshot] ${ledger.members.size} live session(s) with no tab binding counted as open" +
                        (if (ledger.members.isEmpty()) "" else ": ${ledger.members}"),
                )
            }
            val present = bound + ledger.members
            // Warmup stickiness: a just-spawned tab isn't "present" for a poll or two (the
            // widget→Content link is async). Keep prev-snapshot sids spawned within SPAWN_WARMUP_MS
            // that aren't present yet. Can't re-admit a zombie: a zombie carries no recent spawn stamp.
            // The previous snapshot: the file when it can be read, else the copy this plugin last
            // wrote. A file that exists but cannot be read THIS instant (a concurrent rewrite) must
            // not become an empty `prev` — that would drop every held sid — so that poll writes
            // nothing. A missing file (never written, or cleared from outside) starts from empty.
            val fileExists = storage.openTabsFileExists(hash)
            val fileNow = if (fileExists) storage.loadOpenTabs(hash) else null
            val remembered = c.lastPersistedSnapshot
            val unreadable = fileExists && fileNow == null
            if (!fileExists) c.lastPersistedSnapshot = null
            val prev: Set<String> = fileNow ?: remembered ?: emptySet()
            // Sids that appeared in the file without this plugin writing them are an explicit
            // outside request (the recovery command): reopened once, resurrection guards bypassed.
            if (fileNow != null && remembered != null) {
                val external = fileNow - remembered - present
                if (external.isNotEmpty()) {
                    external.forEach { c.externalRequested[it] = now }
                    LOG.info("[ClaudeTabs][snapshot] $hash ${external.size} sid(s) added from outside — reopening: $external")
                }
            }
            c.externalRequested.entries.removeIf { now - it.value > EXTERNAL_REQUEST_TTL_MS }
            // Warmup keeps just-spawned-but-not-yet-present sids; but never a sid that already
            // materialized then went absent (that's a real × — see computeOpenSnapshot). An
            // outside-requested sid counts as present until it is spawned or expires.
            val snapshot = ClaudeTabsHelpers.computeOpenSnapshot(
                prev = prev,
                present = present,
                materializedSids = c.materializedSids,
                lastSpawnAttempt = c.restoreSpawnLastAttempt,
                now = now,
                warmupMs = SPAWN_WARMUP_MS,
            ) + (c.externalRequested.keys intersect prev)
            // The removal gate: a departure leaves the snapshot only once confirmed (absent for
            // two polls, its process seen dead on a healthy root, no outage in between; a burst on
            // the root waits for the root to settle) — unless the removal itself carried evidence
            // (the tab's shell alive = a ×, dead = it closed itself). See RemovalGate.
            c.closeEvidence.entries.removeIf { now - it.value.at > CLOSE_EVIDENCE_TTL_MS }
            val evidence = HashMap<String, Boolean?>(c.closeEvidence.mapValues { it.value.widgetProcessAlive ?: it.value.ptyAlive })
            // A departure the events could not name is still a departure the events SAW, when a
            // removal fired in the same window: pair each such departure with one unattributed
            // removal (oldest first). Nothing to pair with means nothing fired — a suspect.
            c.unattributedRemovals.removeIf { now - it > UNATTRIBUTED_REMOVAL_MS }
            for (sid in prev - snapshot - c.removalGate.pending.keys) {
                if (sid in evidence) continue
                if (c.unattributedRemovals.pollFirst() != null) evidence[sid] = null
            }
            val gate = RemovalGate.step(
                prev = c.removalGate,
                snapshot = prev,
                computed = snapshot,
                aliveAnywhere = aliveInfo.keys,
                rootHealth = rootHealth,
                rootOf = { sid ->
                    sessionRoot[sid] ?: storage.activeSessions.read(sid)?.let { e ->
                        sessionCwd[sid] = e.cwd; sessionRoot[sid] = e.root; e.root
                    }
                },
                rootFacts = removalRootFacts.toMap(),
                now = now,
                singleConfirmMs = REMOVAL_SINGLE_CONFIRM_MS,
                bulkWindowMs = REMOVAL_BULK_WINDOW_MS,
                bulkSettleMs = REMOVAL_BULK_SETTLE_MS,
                closeEvidence = evidence,
                // The ledger's own rulings: an attributed × of an unbound tab, a dead member with
                // no tab left to be in. Those tabs were never bound, so no removal event can name
                // their sid — without this they would read as "vanished without an event".
                decidedElsewhere = ledger.evictedByClose + ledger.shedByCap,
            )
            c.removalGate = gate.state
            // Evidence is consumed the moment the gate has recorded the departure (or the sid is
            // present again — a moved tab); anything left is stale within the TTL above.
            // "Present again" is believed only from a presence pass a full poll interval newer than
            // the event: the pass right after a × can still list the tab (one enumeration source
            // lags the content manager by a moment), and pruning on it threw the evidence away
            // before the departure was ever seen — the × then read as "vanished without an event".
            val presenceAt = c.presentSidsAt
            c.closeEvidence.entries.removeIf { (sid, ev) ->
                sid in gate.state.pending || sid in gate.committed ||
                    (sid in present && presenceAt > ev.at + POLL_INTERVAL_MS)
            }
            RemovalGate.merge(removalRootFacts, gate.rootFacts, now, REMOVAL_BULK_WINDOW_MS, REMOVAL_BULK_SETTLE_MS)
            val persisted = gate.persisted
            val removed = gate.committed
            if (unreadable) {
                if (!c.snapshotUnreadableLogged) {
                    c.snapshotUnreadableLogged = true
                    LOG.info("[ClaudeTabs][snapshot] $hash file unreadable this poll — keeping the in-memory snapshot, nothing written")
                }
            } else {
                try {
                    storage.saveOpenTabs(hash, persisted)
                    c.lastPersistedSnapshot = persisted
                    c.snapshotUnreadableLogged = false
                    if (persisted != prev) {
                        val added = persisted - prev
                        LOG.info(
                            "[ClaudeTabs][snapshot] $hash ${prev.size}→${persisted.size} open" +
                                (if (added.isNotEmpty()) " added=$added" else "") +
                                (if (removed.isNotEmpty()) " removed=$removed" else ""),
                        )
                    }
                } catch (e: Exception) {
                    LOG.warn("[ClaudeTabs] open-tabs snapshot write failed: ${e.message}")
                }
            }
            if (gate.held != c.lastHeldLogged) {
                c.lastHeldLogged = gate.held
                if (gate.held.isNotEmpty()) {
                    val suspects = gate.state.pending.count { it.value.crashSuspect }
                    LOG.info(
                        "[ClaudeTabs][snapshot] $hash ${gate.held.size} departed tab(s) HELD pending confirmation" +
                            " (crash suspects: $suspects): ${gate.held}",
                    )
                } else {
                    LOG.info("[ClaudeTabs][snapshot] $hash nothing held")
                }
            }
            if (c.pollCount % HEARTBEAT_EVERY_POLLS == 1L) {
                LOG.info(
                    "[ClaudeTabs][heartbeat] poll=${c.pollCount} roots=${roots.size} observable=${observableRootIds.size} " +
                        "tabs=${c.contentCount} bound=${bound.size} unbound=${ledger.members.size} snapshot=${persisted.size} held=${gate.held.size}",
                )
            }
        }

        // Step 4: retry-spawn for unconfirmed (pid=null) entries in the snapshot. Snapshot-gated
        // exactly like performRestore — a seed the user didn't have open is never re-spawned.
        val basePath = project.basePath
        val retrySnapshot = if (!basePath.isNullOrBlank()) storage.loadOpenTabs(projectHash(project)) ?: emptySet() else emptySet()
        if (!basePath.isNullOrBlank() && unconfirmed.isNotEmpty()) {
            for (entry in unconfirmed) {
                if (entry.sid !in retrySnapshot) continue
                // An outside request (the recovery command) is the user asking for THIS session
                // back; the guards exist to stop the plugin resurrecting one on its own.
                val requested = c.externalRequested.containsKey(entry.sid)
                if (!requested && RestoreGuard.blocksRetrySpawn(
                        entry.sid,
                        userClosed = emptySet(),
                        everHadWidget = c.everHadWidget,
                        everSeenAlive = c.everSeenAlive,
                        pendingClose = emptySet(),
                        spawnedWidgets = c.spawnedWidgets.keys,
                    )
                ) continue
                if (!claimsCwd(entry.cwd, project)) continue
                if (!transcriptExistsForEntry(entry)) continue
                val attempts = c.restoreSpawnAttempts[entry.sid] ?: 0
                if (attempts >= MAX_SPAWN_ATTEMPTS) continue
                val last = c.restoreSpawnLastAttempt[entry.sid]
                if (last != null && (now - last) < RESPAWN_COOLDOWN_MS) continue
                c.restoreSpawnAttempts[entry.sid] = attempts + 1
                val displayName = TitleModel.resolveDisplayName(entry.userName, null, entry.name)
                // Resolved OFF the EDT — see performRestore.
                val resumeCommand = SessionRoot.resumeCommandFor(entry.root, entry.sid)
                resumeSpawnMutex.withLock {
                    withContext(Dispatchers.Main) {
                        if (!project.isDisposed) {
                            spawnOwnedTab(
                                project, entry.sid, entry.cwd, displayName, OwnedTerminalSpawner.Mode.RESUME,
                                command = resumeCommand,
                            )
                        }
                    }
                    delay(RESTORE_STAGGER_MS)
                    awaitConfigSettled()
                }
                c.externalRequested.remove(entry.sid)
                LOG.info("[ClaudeTabs] Retry-spawn sid=${entry.sid} cwd=${entry.cwd} attempt=${attempts + 1}/$MAX_SPAWN_ATTEMPTS" + (if (requested) " (requested from outside)" else ""))
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
     *  (A2) **Exact adoption from birth-time / OS facts.** Before any heuristic runs: a tab whose
     *      LAUNCH ARGV names the session, and a session whose PROCESS DESCENDS from a tab's pty
     *      root. Both ignore the shell-title veto and the freshness gate — see the block comment at
     *      the passes themselves for why that is not a loosening.
     *
     *  (B) **Adopt hand-opened tabs.** A tab the user opened by hand (bypassing the action) has no
     *      birth binding. Correlate unowned present tabs to unowned alive sessions strictly 1:1 by
     *      cwd ([OwnedTerminalSpawner.pairUniqueByKey]) — ambiguity binds nothing, so a session can
     *      never attach to the wrong tab (and an × can never kill the wrong Claude). A tab's cwd
     *      comes from its classic widget, or — for a reworked tab, which has no widget at all —
     *      from [ReworkedTerminalBridge.viewInfoByContent] (live shell-integration cwd, else the
     *      tab's LAUNCH directory); without that second source a hand-opened reworked tab was
     *      invisible to this whole pass.
     *
     *      Shell-titledness is a hard VETO over the HEURISTIC passes: a tab that still affirmatively
     *      looks like a plain shell is removed from their candidate pools, so it can neither receive
     *      a session nor block another tab from receiving one. A title carrying claude's own glyph
     *      (`✳ <topic>`, braille busy frames) or leading with "claude" rescues a tab from the
     *      generic "Local" defaultTitle ([ClaudeTabsHelpers.looksLikeClaudeTitle]) — but note that
     *      on a REWORKED tab that rescue effectively never fires: the CLI's own terminal title is
     *      written to `applicationTitle` only when the advanced setting
     *      `terminal.show.application.title` is enabled, and it is OFF by default with no per-tab
     *      opt-in (verified at runtime: a tab hosting the CLI reported `application=null` for its
     *      whole life). So the rescue is a classic-tab affordance, and a hand-opened reworked tab
     *      running the CLI reaches the snapshot through the exact passes (A2) — not through titles.
     *
     * @param aliveByCwd sid → cwd for sessions seen alive this poll that belong to this project
     * @param freshSids sids whose process started within [ADOPT_FRESH_WINDOW_MS] — the ONLY sids
     *   the correlation passes ((A) minted-sid readback candidates, (B2) hand-open 1:1 and
     *   last-resort) may pair with a tab. A stale orphan (hand-resumed hours ago, running
     *   outside the IDE, or wearing an unbindable shell-titled tab) must never latch onto a
     *   fresh tab — field-observed stealing a new tab's identity, name, and animation. Exact
     *   passes (argv re-point, injected-sid FIFO) carry positive identity and are not gated.
     */
    private fun bindSessions(
        project: Project,
        aliveByCwd: Map<String, String>,
        argvFlips: Map<String, String> = emptyMap(),
        freshSids: Set<String> = emptySet(),
        sidRoots: Map<String, String> = emptyMap(),
        /** sid → the OS pid of its claude process, for the sessions seen alive this poll. Read ONLY
         *  by the pid-ancestry pass, and there only for Windows-root sessions (a WSL pid belongs to
         *  a different pid namespace — see [OwnedTerminalSpawner.pairByPidAncestry]). */
        sidPids: Map<String, Long> = emptyMap(),
    ) {
        if (project.isDisposed) return
        val c = ctx(project)
        try {
            val tw = TerminalTabs.toolWindow(project) ?: return
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
                    c.markBound(uuid)
                    c.pendingNew.remove(uuid)
                    continue
                }
                val boundSids = c.contentToSid.values.toHashSet().apply { addAll(c.spawnedWidgets.keys) }
                val pendCwd = ClaudeTabsHelpers.normalizeCwd(pending.cwd)
                // freshSids gate: the readback target is a JUST-SPAWNED claude — a stale unowned
                // session in the same cwd must neither be mistaken for it nor block the count.
                // Root gate: the plugin's own spawner launches the WINDOWS claude, so the real
                // sid can only ever appear in the windows root — a foreign-root session must
                // never masquerade as a mint's readback.
                val candidates = aliveByCwd.filter { (sid, cwd) ->
                    sid !in boundSids && sid !in c.pendingNew.keys && sid in freshSids &&
                        (sidRoots[sid] ?: SessionRoot.Windows.ID) == SessionRoot.Windows.ID &&
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
                c.markBound(sid)
                widgetOfContent[content]?.let { c.spawnedWidgets[sid] = it }
                LOG.info("[ClaudeTabs] Exact adopt (sid from tab's own launch command) → sid=$sid")
            }

            // Everything the reworked frontend can tell us about its tabs in ONE walk: the live
            // TerminalTitle, the view's cwd, and the tab's birth-time launch facts (argv, pty-root
            // pid, launch cwd). A reworked tab has NO classic widget, so this is its only source
            // for any of them — read here, before the enumeration, because the cwd decides which
            // bucket each tab lands in and the launch facts drive the exact passes below.
            // NOTE on the title: `applicationTitle` does NOT follow the program's own terminal
            // title here — the reworked path writes it only when the advanced setting
            // `terminal.show.application.title` is enabled, default OFF, no per-tab opt-in. The
            // title is still read (shell-tab veto) and written (rename), but it identifies nothing.
            val bridgeInfo = ReworkedTerminalBridge.viewInfoByContent(project)
            c.lastViewInfo = bridgeInfo

            // Enumerate every UNBOUND tab ONCE, in ContentManager (= creation) order, split into
            // cwd-readable and undetermined (unreadable cwd = a full-screen pty whose shell
            // integration has gone quiet). Built before the 1:1 pass so the FIFO pass below can run
            // even when no session is "alive" yet (a freshly injected tab whose Claude hasn't
            // registered its session file).
            val tabsByCwd = HashMap<String, MutableList<Pair<Content, TerminalWidget?>>>()
            val undetermined = mutableListOf<Pair<Content, TerminalWidget?>>()
            val unboundInOrder = mutableListOf<Pair<Content, TerminalWidget?>>()
            var bridgeCwdTabs = 0
            for (content in TerminalTabs.contents(project, tw, bridgeInfo.keys)) {
                if (c.contentToSid.containsKey(content)) continue
                val w = widgetOfContent[content]
                    ?: try { TerminalToolWindowManager.findWidgetByContent(content) } catch (_: Exception) { null }
                if (w != null && c.spawnedWidgets.containsValue(w)) continue
                unboundInOrder.add(content to w)
                // First-seen stamp for the FIFO guard — a tab can only be an injection's tab if it
                // appeared after that injection.
                c.firstSeenUnbound.getOrPut(content) { now }
                // The bridge reports the tab's cwd in the RUNTIME's own form (a WSL tab reports
                // `/mnt/...`, or a Linux-native path with no Windows equivalent at all), while
                // session cwds are stored Windows-translated at scan time — so translate, and
                // treat an untranslatable path as unreadable rather than as a bucket of its own.
                val cwd = currentDirectoryOf(w)
                    ?: bridgeInfo[content]?.cwd
                        ?.let { ClaudeTabsHelpers.normalizeBridgeCwd(it) }
                        ?.also { bridgeCwdTabs++ }
                if (cwd == null) { undetermined.add(content to w); continue }
                tabsByCwd.getOrPut(ClaudeTabsHelpers.normalizeCwd(cwd)) { mutableListOf() }.add(content to w)
            }
            // Contents that vanished or got bound stop carrying a stamp.
            val unboundContents = unboundInOrder.mapTo(HashSet()) { it.first }
            c.firstSeenUnbound.keys.retainAll(unboundContents)

            // ── EXACT PASSES (A) and (B) — run BEFORE every heuristic pass ────────────────────
            // Both bind on a STORED or OPERATING-SYSTEM fact, so both deliberately ignore the two
            // gates below, which exist only to constrain GUESSING:
            //  - the SHELL-TITLE VETO. On a reworked tab the program's title is not observable at
            //    all: `applicationTitle` is written only when the advanced setting
            //    `terminal.show.application.title` is enabled, and it is OFF by default with no
            //    per-tab opt-in (runtime-verified — a tab hosting the CLI reported
            //    `application=null` its whole life). Such a tab wears "Local" forever, so the veto
            //    would refuse precisely the tabs these passes exist to rescue.
            //  - the ADOPTION-FRESHNESS gate. Age bounds the damage of a wrong CORRELATION. An argv
            //    naming the sid, or a process tree putting the session inside the tab, is equally
            //    true for a session started a second ago and one started last week — and "chats
            //    left open for weeks" is the core case of the restore contract.
            // Nothing is loosened: ambiguity still refuses, and an already-bound sid is never stolen.
            val boundNow = HashSet(c.contentToSid.values).apply { addAll(c.spawnedWidgets.keys) }

            // (A) ARGV SESSION ID. A reworked tab whose launch argv carries `--session-id <sid>`
            // IS that session — the id ClaudeAgentLaunchCustomizer injected at spawn, read back
            // through the bridge's startup options. Parsed by the SAME helper the classic-widget
            // path and the process scan use, so there is exactly one argv parser in the plugin.
            var argvBound = 0
            for ((content, w) in unboundInOrder) {
                if (c.contentToSid.containsKey(content)) continue
                val sid = OwnedTerminalSpawner.sessionIdFromCommand(bridgeInfo[content]?.shellCommand) ?: continue
                if (sid !in aliveByCwd || sid in boundNow) continue // alive, this project's, unbound
                c.contentToSid[content] = sid
                c.markBound(sid)
                boundNow.add(sid)
                w?.let { c.spawnedWidgets[sid] = it }
                argvBound++
                LOG.info("[ClaudeTabs][adopt] Argv adopt (the tab's own launch argv names the session) → sid=$sid")
            }

            // (B) PID ANCESTRY. A session whose process descends from a tab's pty root runs IN that
            // tab — which is what makes a hand-typed `claude --resume <id>` in an ordinary terminal
            // adoptable, with no cwd, title or timing signal needed. The rule (hop budget, cycle
            // safety, ambiguity refusal) and the CROSS-PID-NAMESPACE hazard behind the Windows-root
            // gate live in [OwnedTerminalSpawner.pairByPidAncestry].
            val ancestryTabs = unboundInOrder
                .filter { !c.contentToSid.containsKey(it.first) }
                .map { OwnedTerminalSpawner.AncestryTab(it, bridgeInfo[it.first]?.ptyRootPid) }
            val ancestrySessions = aliveByCwd.keys
                .filter { it !in boundNow }
                .map { OwnedTerminalSpawner.AncestrySession(it, sidPids[it], sidRoots[it] ?: SessionRoot.Windows.ID) }
            var ancestryBound = 0
            for ((tab, sid) in OwnedTerminalSpawner.pairByPidAncestry(ancestrySessions, ancestryTabs, ::parentPidOf)) {
                val (content, widget) = tab
                c.contentToSid[content] = sid
                c.markBound(sid)
                boundNow.add(sid)
                widget?.let { c.spawnedWidgets[sid] = it }
                ancestryBound++
                LOG.info(
                    "[ClaudeTabs][adopt] Ancestry adopt (the session's process descends from this " +
                        "tab's pty root) → sid=$sid",
                )
            }

            // Title gate — a hard VETO over every CORRELATION pass (the exact passes above already
            // ran and are exempt by construction). On the reworked terminal an unbound PLAIN SHELL
            // tab looks exactly like an unbound Claude tab to the order/count based passes, so
            // adoption could bind a session to a bash/pwsh tab the user opened alongside
            // (field-observed: a shell tab wearing a session's glyph and name — the original bug).
            // Anything claude-ish in ANY title — a leading glyph/braille busy frame or a title
            // starting with "claude" — keeps the tab eligible, as does having no readable title.
            // SCOPE OF THAT RESCUE (verified, do not re-derive): on a REWORKED tab the CLI's own
            // terminal title never reaches us, because `applicationTitle` is written only when the
            // advanced setting `terminal.show.application.title` is enabled and it is OFF by
            // default with no per-tab opt-in. A hand-opened reworked tab therefore wears "Local"
            // for life and the gate calls it a shell whatever runs inside — so the claude-ish
            // rescue effectively only fires on CLASSIC tabs. That is why the exact argv/ancestry
            // passes above run first and ignore this veto: they are the ONLY way such a tab is
            // ever adopted. The plugin-owned launch paths (the action, the taken-over button,
            // injected agent launches) never depended on titles either — they bind at birth.
            fun observableTitles(content: Content, w: TerminalWidget?): List<String?> {
                val t = bridgeInfo[content]?.title ?: try { w?.terminalTitle } catch (_: Throwable) { null }
                return listOf(
                    try { t?.userDefinedTitle } catch (_: Throwable) { null },
                    try { t?.applicationTitle } catch (_: Throwable) { null },
                    try { t?.defaultTitle } catch (_: Throwable) { null },
                    try { content.displayName } catch (_: Throwable) { null },
                )
            }
            val shellTitled = unboundInOrder
                .filter { ClaudeTabsHelpers.titleLooksLikeShellTab(observableTitles(it.first, it.second)) }
                .mapTo(HashSet()) { it.first }
            // How far the veto reaches depends on WHERE the title came from. A classic widget's tab
            // name never follows the program's OSC title, so a classic tab hosting the CLI wears a
            // shell-looking name forever — that name is NOT evidence the CLI is absent. Only the
            // reworked view's title (which the CLI rewrites within about a second of launch) carries
            // that evidence, so only bridge-read titles justify the hard veto. Classic-titled tabs
            // stay eligible for the strict one-session/one-tab by-directory match — which is exact
            // enough not to need the title signal — while remaining out of the weaker
            // order/count-based passes below.
            val authoritativeShellTitled = shellTitled.filterTo(mutableSetOf()) { bridgeInfo[it]?.title != null }
            val claudeCandidates = undetermined.filterNot { it.first in shellTitled }
            // Per-tab title forensics for every UNBOUND tab, deduped like the scan summary below.
            // The title gate is a hard veto, so when a tab the user expects to be adopted is
            // classified as a plain shell this is the only record of what the four observable title
            // slots actually held, and which source (reworked view vs classic widget) they came
            // from. Non-ASCII is escaped so a CLI glyph survives the log file's encoding — its
            // presence or absence is exactly what distinguishes "no OSC title arrived" from "the
            // title arrived and the gate still said shell".
            val titleDiag = unboundInOrder.mapIndexed { idx, (content, w) ->
                val bridgeTitle = bridgeInfo[content]?.title
                val widgetTitle = try { w?.terminalTitle } catch (_: Throwable) { null }
                val src = when {
                    bridgeTitle != null -> "bridge"
                    widgetTitle != null -> "widget"
                    else -> "none"
                }
                val slots = observableTitles(content, w)
                "[ClaudeTabs][title-diag] tab#$idx src=$src " +
                    "userDefined=${quoteForLog(slots[0])} application=${quoteForLog(slots[1])} " +
                    "default=${quoteForLog(slots[2])} displayName=${quoteForLog(slots[3])} " +
                    "shell=${content in shellTitled}"
            }
            val titleDiagJoined = titleDiag.joinToString("\n")
            if (titleDiagJoined != c.lastTitleDiag) {
                c.lastTitleDiag = titleDiagJoined
                titleDiag.forEach { LOG.info(it) }
            }
            // Deduped like the adopt-scan summary: the ~5s poll re-derives the same verdict every
            // pass, and an undeduped line emitted 132 identical times in a 9-minute run drowns the
            // log it exists to explain. Only a CHANGE in the verdict is worth a line.
            if (shellTitled.isNotEmpty()) {
                val line = "[ClaudeTabs][adopt] ${shellTitled.size} unbound tab(s) titled like plain shells — " +
                    "excluded from the order/count adoption passes; the " +
                    "${authoritativeShellTitled.size} bridge-titled of them also from the strict " +
                    "by-cwd pass"
                if (line != c.lastShellTitledLine) { c.lastShellTitledLine = line; LOG.info(line) }
            } else {
                c.lastShellTitledLine = null
            }

            // (B1) FIFO injection-order binding — the BURST case. Several agent-button tabs opened
            // together in one project all got a known --session-id injected IN ORDER, and their new
            // Contents append to the ContentManager in that same order, so pair still-unbound tabs
            // to still-unclaimed injected sids in FIFO order. Candidates are ALL unbound non-shell
            // tabs, not just the undetermined ones: an agent tab whose cwd turned out readable is
            // now drained into `tabsByCwd` above, and must not thereby fall out of the burst fix.
            // The first-seen guard inside keeps a pre-existing hand tab from stealing an injected
            // sid it cannot possibly own.
            val fifoCandidates = unboundInOrder.filter { it.first !in shellTitled }
            val fifoBound = fifoBindInjected(c, fifoCandidates, widgetOfContent, aliveByCwd, now)

            // (B2) Hand-open 1:1 adoption for anything the exact + FIFO passes didn't bind.
            // Three gates protect it:
            //  - FRESHNESS (see the freshSids param doc): the legitimate case is "user typed
            //    `claude` into a tab moments ago"; an orphan must stay unbound rather than risk
            //    pairing with the wrong tab. A sid refused only because of AMBIGUITY keeps ONE
            //    extra window of grace (below) — the refusal is a delay, not a life sentence.
            //  - PAIRING CONFIRMATION (below, at commit): a foreign-root session must not steal a
            //    tab from a Windows claude whose session file hasn't landed yet.
            //  - THE TITLE GATE: shell-titled tabs are already out of every candidate list here.
            val boundSids = c.contentToSid.values.toHashSet().apply { addAll(c.spawnedWidgets.keys) }
            val eligible = { sid: String -> sid in freshSids || (c.adoptGraceUntil[sid] ?: 0L) > now }
            val staleUnowned = aliveByCwd.keys.count { it !in boundSids && !eligible(it) }
            val unownedSessions = aliveByCwd.filterKeys { it !in boundSids && eligible(it) }
            // Ambiguity grace, BOUNDED. A poll that refuses a sid — because another session
            // contests its cwd, or because the tab side is momentarily ambiguous — must not cost it
            // its tab forever, so every still-unowned sid facing at least one plausible (unbound,
            // non-shell) tab gets one more window. The bound is that grace may only ever be
            // GRANTED to a sid that is still in freshSids: a sid eligible only VIA grace can never
            // refresh its own grace. Without that rule two long-dead sessions sharing a cwd kept
            // each other adoptable forever — re-opening exactly the stale-orphan theft the
            // freshness gate exists to prevent. Net eligibility bound: freshness + ONE window.
            val hasAdoptableTab = unboundInOrder.any {
                it.first !in shellTitled && !c.contentToSid.containsKey(it.first)
            }
            if (hasAdoptableTab) {
                for (sid in aliveByCwd.keys) {
                    if (sid in freshSids && sid !in boundSids) c.adoptGraceUntil[sid] = now + ADOPT_FRESH_WINDOW_MS
                }
            }
            c.adoptGraceUntil.keys.retainAll(aliveByCwd.keys) // dead/gone sids drop their grace
            val stillUndetermined = claudeCandidates.filter { !c.contentToSid.containsKey(it.first) }
            val pairs = HashMap<Pair<Content, TerminalWidget?>, String>()
            var adoptedCount = 0
            if (unownedSessions.isNotEmpty()) {
                val sessionsByCwd = unownedSessions.entries
                    .groupBy({ ClaudeTabsHelpers.normalizeCwd(it.value) }, { it.key })
                // Candidate tabs per cwd: still unbound AND not AUTHORITATIVELY shell-titled (see
                // the veto note above — a classic tab's name is not evidence). Pre-filtering the
                // pool is what makes the everyday shape resolve by the plain strict rule — the
                // project directory holding the Claude tab plus the plain shells the user keeps
                // open there is now one session and ONE candidate tab. It also stops a shell tab
                // from BLOCKING the last-resort pass below (a readable bash tab sharing the cwd
                // used to make the real, cwd-unreadable Claude tab unadoptable).
                val readableTabsByCwd = HashMap<String, MutableList<Pair<Content, TerminalWidget?>>>()
                for ((cwd, list) in tabsByCwd) {
                    val fresh = list.filter {
                        !c.contentToSid.containsKey(it.first) && it.first !in authoritativeShellTitled
                    }
                    if (fresh.isNotEmpty()) readableTabsByCwd[cwd] = fresh.toMutableList()
                }
                // Pair among cwd-readable tabs (strict 1:1 per cwd — ambiguity binds nothing).
                val readableByCwd = readableTabsByCwd.mapValues { it.value.toList() }
                pairs.putAll(
                    OwnedTerminalSpawner.pairUniqueByKey(
                        sessionsByKey = sessionsByCwd,
                        tabsByKey = readableByCwd,
                    ),
                )
                // Last resort: exactly ONE unowned session left, exactly ONE undetermined tab, and no
                // candidate readable tab in the session's cwd competing for it — the only possible pairing.
                val leftoverSessions = unownedSessions.keys - pairs.values.toSet()
                if (leftoverSessions.size == 1 && stillUndetermined.size == 1) {
                    val sid = leftoverSessions.first()
                    val sessCwd = ClaudeTabsHelpers.normalizeCwd(unownedSessions[sid] ?: "")
                    if (readableTabsByCwd[sessCwd].isNullOrEmpty()) {
                        pairs[stillUndetermined.first()] = sid
                        LOG.info("[ClaudeTabs] Last-resort adopt: single unowned session + single undetermined tab → sid=$sid")
                    }
                }
                // COMMIT — with a one-poll confirmation for the one pairing that could be wrong.
                // FOREIGN ROOT: a WSL/fake session (typically a DIFFERENT ACCOUNT) can correlate
                // onto a tab that in truth hosts a just-typed Windows claude whose session file
                // hasn't been written yet; committing steals the tab, strands the real session
                // outside the snapshot (silent loss on restart — contract violation), and resumes
                // the other account's conversation in its place. The SAME sid↔tab pairing must
                // therefore survive one extra poll (ADOPT_CONFIRM_MS) — time enough for the
                // competing Windows session to appear and turn the pairing into a refusal.
                // Everything else commits at once: same-root mispairing cannot cross accounts, and
                // the exact passes have already had theirs.
                for ((tab, sid) in pairs) {
                    val root = sidRoots[sid] ?: SessionRoot.Windows.ID
                    if (root != SessionRoot.Windows.ID) {
                        val pending = c.pendingAdoptConfirm[sid]
                        if (pending == null || pending.first !== tab.first) {
                            c.pendingAdoptConfirm[sid] = tab.first to now
                            LOG.info(
                                "[ClaudeTabs][adopt] sid=$sid pairing observed but not obviously safe " +
                                    "(foreign-root ($root)) — deferred one poll for confirmation",
                            )
                            continue
                        }
                        if (now - pending.second < ADOPT_CONFIRM_MS) continue
                    }
                    val (content, widget) = tab
                    c.contentToSid[content] = sid
                    c.markBound(sid)
                    c.pendingAdoptConfirm.remove(sid)
                    adoptedCount++
                    // Widget may be null for a bridged-but-widgetless tab: durability works off
                    // contentToSid alone; only title/animation needs the widget.
                    widget?.let { c.spawnedWidgets[sid] = it }
                    LOG.info("[ClaudeTabs] Hand-open 1:1 adopted tab (cwd match, root=$root) → sid=$sid")
                }
            }
            // A pairing that dissolved (ambiguity appeared, tab vanished, session died) forgets its
            // stamp. OUTSIDE the block above on purpose: a poll with no eligible unowned session at
            // all must still clear stamps, otherwise they linger holding disposed Content refs.
            c.pendingAdoptConfirm.keys.retainAll(pairs.values.toSet())
            // Always-on scan diagnostic (deduped): if adoption isn't happening, idea.log must say why.
            val summary = "[ClaudeTabs][adopt-scan] unownedSessions=${unownedSessions.size} " +
                "staleUnowned=$staleUnowned " +
                "bridgedWidgets=${widgetOfContent.size} readableTabs=${tabsByCwd.values.sumOf { it.size }} " +
                "bridgeCwd=$bridgeCwdTabs undetermined=${undetermined.size} " +
                "shellTitled=${shellTitled.size}/${authoritativeShellTitled.size} " +
                "argvBound=$argvBound ancestryBound=$ancestryBound fifoBound=$fifoBound " +
                "adopted=$adoptedCount pendingConfirm=${c.pendingAdoptConfirm.size} " +
                "fifoQueue=${c.pendingInjectedSids.size}"
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
     * Render a title slot for a log line: a missing slot stays the bare token `null`, anything else
     * is quoted so an EMPTY string (`""`) is unmistakable. Every char outside printable ASCII —
     * notably the CLI's leading status glyph and braille spinner frames — becomes `\uXXXX`, because
     * idea.log's encoding cannot be relied on to round-trip them and their presence is the whole
     * point of the diagnostic.
     */
    private fun quoteForLog(s: String?): String {
        if (s == null) return "null"
        val sb = StringBuilder(s.length + 2).append('"')
        for (ch in s) {
            when {
                ch == '"' || ch == '\\' -> sb.append('\\').append(ch)
                ch.code in 0x20..0x7E -> sb.append(ch)
                else -> sb.append("\\u").append(String.format("%04x", ch.code))
            }
        }
        return sb.append('"').toString()
    }

    /**
     * (B1) FIFO injection-order binding. Pairs still-unbound, non-shell-titled [candidates] (in
     * ContentManager order) to still-unclaimed injected sids in FIFO order — the deterministic
     * answer to the burst case the strict 1:1 rule can't touch. Order holds because ContentManager
     * order = tab creation order = injection order. Binds Content-only when the tab is widgetless
     * (reworked). Returns the number bound this pass. Heavily logged so the queue can be tracked
     * in idea.log.
     *
     * FIRST-SEEN GUARD: a tab is only paired with an injected sid when this project first observed
     * the tab unbound at or after the injection (minus [FIFO_FIRST_SEEN_SLACK_MS] of poll-cadence
     * slack). An injected tab's Content necessarily appears AFTER its injection, so an older tab
     * — a shell or claude the user opened by hand long before — can never be it. Without the
     * guard, now that a readable-cwd tab also enters this candidate list, such a tab would absorb
     * the first sid of a later burst and the real tab would be left unbound.
     */
    private fun fifoBindInjected(
        c: ProjectCtx,
        candidates: List<Pair<Content, TerminalWidget?>>,
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
        val tabs = candidates.map { it.first }.filter { !c.contentToSid.containsKey(it) }
        if (tabs.isEmpty()) {
            LOG.info("[ClaudeTabs][fifo] ${bindable.size} injected sid(s) bindable but no unbound candidate tab yet (queue=${c.pendingInjectedSids.size})")
            return 0
        }
        LOG.info("[ClaudeTabs][fifo] pairing up to ${minOf(tabs.size, bindable.size)} by injection order (unboundCandidateTabs=${tabs.size} bindableInjected=${bindable.size} queue=${c.pendingInjectedSids.size})")
        var bound2 = 0
        var skippedTooOld = 0
        var ti = 0
        var bi = 0
        while (ti < tabs.size && bi < bindable.size) {
            val content = tabs[ti]
            val inj = bindable[bi]
            // First-seen guard: a tab this project already knew before the sid was injected cannot
            // be that injection's tab. Skip the TAB (not the sid) — the sid's own tab may still be
            // further down the ContentManager order.
            val firstSeen = c.firstSeenUnbound[content] ?: now
            if (firstSeen < inj.injectedAt - FIFO_FIRST_SEEN_SLACK_MS) {
                ti++
                skippedTooOld++
                continue
            }
            c.contentToSid[content] = inj.sid
            c.markBound(inj.sid)
            val w = widgetOfContent[content]
            if (w != null) c.spawnedWidgets[inj.sid] = w
            c.pendingInjectedSids.remove(inj)
            bound2++
            LOG.info("[ClaudeTabs][fifo] bound tab#$ti → sid=${inj.sid} widget=${w != null} (was injected ${now - inj.injectedAt}ms ago)")
            ti++
            bi++
        }
        if (skippedTooOld > 0) {
            LOG.info(
                "[ClaudeTabs][fifo] $skippedTooOld candidate tab(s) skipped by the first-seen guard " +
                    "(known before the injection — cannot be an injected tab)",
            )
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
        c.markBound(newSid)
        c.switchedAway.add(oldSid)
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
        c.markBound(newSid)
        try { storage.activeSessions.delete(oldSid) } catch (_: Exception) { }
    }

    /**
     * Parent of a WINDOWS pid, or null when the OS won't say (process gone, permission, no parent).
     * The injected lookup behind the pid-ancestry pass — kept here, at the platform edge, so the
     * pairing rule itself stays a pure function ([OwnedTerminalSpawner.pairByPidAncestry]).
     * `ProcessHandle` is JDK API and reports the WINDOWS process tree only, which is exactly the
     * namespace that pass is gated to.
     */
    private fun parentPidOf(pid: Long): Long? = try {
        ProcessHandle.of(pid).flatMap { it.parent() }.map<Long> { it.pid() }.orElse(null)
    } catch (_: Throwable) { null }

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

    /** Once per ContentManager instance (identity): a re-created tool window or a new split pane
     *  brings a new manager, and a listener left on an old one hears nothing. */
    private fun installRemovalListener(c: ProjectCtx, cm: com.intellij.ui.content.ContentManager) {
        if (!c.listenedContentManagers.add(cm)) return
        cm.addContentManagerListener(object : com.intellij.ui.content.ContentManagerListener {
            override fun contentAdded(event: com.intellij.ui.content.ContentManagerEvent) {
                // Stamp the tab's first-unbound instant at the exact moment it appears, not at
                // the next poll: the FIFO first-seen guard compares that stamp against the
                // injection time, and poll-cadence quantisation left it a whole poll interval of
                // slack in which a pre-existing tab could still look "new".
                c.firstSeenUnbound.getOrPut(event.content) { System.currentTimeMillis() }
                c.poke.trySend(Unit)
            }
            override fun contentRemoved(event: com.intellij.ui.content.ContentManagerEvent) {
                recordCloseEvidence(c, event.content)
                c.poke.trySend(Unit)
            }
        })
    }

    /**
     * At the instant a tab's Content is removed: is the shell behind it still alive? LOCAL checks
     * only (`Process.isAlive` through the widget's tty connector, `ProcessHandle` for the startup
     * pid) — never the UNC share; this runs on the EDT. The earlier attempts to CLASSIFY removals
     * (1.x/2.x) were falsified and are not repeated: this reads a fact, it does not interpret the
     * event. E2E phase 6 pins the two shapes (× → alive, shell killed → dead); the gate consumes it.
     */
    private fun recordCloseEvidence(c: ProjectCtx, content: Content) {
        try {
            val widget = try { TerminalToolWindowManager.findWidgetByContent(content) } catch (_: Throwable) { null }
            // The sid: the binding first, else the widget's identity (the same fallback presence
            // uses — a Content the tool window swapped is still the same widget).
            val sid = c.contentToSid[content]
                ?: widget?.let { w -> c.spawnedWidgets.entries.firstOrNull { it.value === w }?.key }
            val pid = c.lastViewInfo[content]?.ptyRootPid
            // The startup-options pid is always a LOCAL pid (the bridge leaves it null for
            // remote/adapter ptys), so a pid the local namespace no longer knows is dead.
            val alive: Boolean? = pid?.let { p ->
                try { ProcessHandle.of(p).map { it.isAlive }.orElse(false) } catch (_: Throwable) { null }
            }
            val ownWidget = widget ?: sid?.let { c.spawnedWidgets[it] }
            val widgetAlive = ownWidget?.let { widgetProcessAlive(it) }
            val shapes = ownWidget?.let { widgetProcessShapes(it) } ?: "widget=null"
            val teardown = c.projectClosing || appShuttingDown
            // A temporary removal (the platform's own marker for a shuffle/move) and a teardown
            // are not evidence of anything: record nothing, the gate's slow rules apply.
            val temporary = try { content.getUserData(Content.TEMPORARY_REMOVED_KEY) == true } catch (_: Throwable) { false }
            if (!teardown && !temporary) {
                if (sid != null) c.closeEvidence[sid] = CloseEvidence(System.currentTimeMillis(), pid, alive, widgetAlive, teardown)
                else c.unattributedRemovals.add(System.currentTimeMillis())
            }
            LOG.info(
                "[ClaudeTabs][close-probe] sid=${sid ?: "unbound"} ptyRootPid=${if (pid == null) "absent" else "present"} " +
                    "alive=${alive ?: "unknown"} widget=${if (widget == null) "absent" else "present"} " +
                    "widgetProcess=${when (widgetAlive) { true -> "alive"; false -> "dead"; null -> "unknown" }} " +
                    "teardown=$teardown temporary=$temporary [$shapes]",
            )
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] close probe failed: ${e.message}")
        }
    }

    /**
     * Is the shell process behind [widget] alive? Read through the widget's tty connector — a
     * `ProcessTtyConnector` wraps the local `Process` the terminal started (the shell, or the
     * `wsl.exe` hosting a distro-side shell). Reflective: the connector accessor's shape has moved
     * between terminal generations, and a widget without a local process (a remote/WSL-adapter
     * pty) simply answers null. Local `Process.isAlive` only — never a share read.
     */
    /** PII-free class shapes behind the widget — which API surface this build exposes at removal time. */
    private fun widgetProcessShapes(widget: TerminalWidget): String = try {
        val connector = try {
            widget.javaClass.methods.firstOrNull { it.name == "getTtyConnector" && it.parameterCount == 0 }?.invoke(widget)
        } catch (t: Throwable) { "threw:${t.javaClass.simpleName}" }
        val accessor = try {
            widget.javaClass.methods.firstOrNull { it.name == "getTtyConnectorAccessor" && it.parameterCount == 0 }?.invoke(widget)
        } catch (t: Throwable) { "threw:${t.javaClass.simpleName}" }
        val methods = widget.javaClass.methods.map { it.name }.filter { n ->
            n.contains("Tty", true) || n.contains("Process", true) || n.contains("Session", true) || n.contains("Pid", true)
        }.distinct().sorted().joinToString(",")
        "widgetClass=${widget.javaClass.name} connector=${connector?.javaClass?.name ?: connector} " +
            "accessor=${accessor?.javaClass?.name ?: accessor} methods=$methods"
    } catch (t: Throwable) { "shapes threw ${t.javaClass.simpleName}" }

    private fun widgetProcessAlive(widget: TerminalWidget): Boolean? = try {
        val connector = try {
            widget.javaClass.methods.firstOrNull { it.name == "getTtyConnector" && it.parameterCount == 0 }?.invoke(widget)
        } catch (_: Throwable) { null } ?: try {
            widget.javaClass.methods.firstOrNull { it.name == "getTtyConnectorAccessor" && it.parameterCount == 0 }
                ?.invoke(widget)?.let { acc ->
                    acc.javaClass.methods.firstOrNull { it.name == "getTtyConnector" && it.parameterCount == 0 }?.invoke(acc)
                }
        } catch (_: Throwable) { null }
        // Unwrap proxy connectors (the terminal wraps the local one in observers/proxies that
        // expose `getConnector()`), then ask the innermost one for its Process.
        var conn: Any? = connector
        var hops = 0
        while (conn != null && hops++ < 5) {
            val getProcess = conn.javaClass.methods.firstOrNull { it.name == "getProcess" && it.parameterCount == 0 }
            if (getProcess != null) return (getProcess.invoke(conn) as? Process)?.isAlive
            val inner = conn.javaClass.methods.firstOrNull { it.name == "getConnector" && it.parameterCount == 0 }
            conn = inner?.invoke(conn)
        }
        null
    } catch (_: Throwable) { null }

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
            if (c.projectClosing || appShuttingDown) {
                if (!c.tabOrderFrozenLogged) {
                    c.tabOrderFrozenLogged = true
                    LOG.info(
                        "[ClaudeTabs] recordTabOrder FROZEN — teardown in progress; keeping " +
                            "last-known presence (${c.presentSids.size} sid(s))",
                    )
                }
                return
            }
            val tw = TerminalTabs.toolWindow(project) ?: return

            // Poke the poll loop on tab add/remove. This is NOT close detection (the
            // falsified 1.x/2.x territory was CLASSIFYING removals); it only wakes the presence
            // recompute so an × is snapshotted before a same-instant IDE close freezes the
            // snapshot. During teardown the write guard in pollOnce still freezes everything.
            // Installed per ContentManager INSTANCE, not once per project: a re-created tool
            // window brings a new manager, and a listener left on the old one hears nothing.
            val cm = try { tw.contentManagerIfCreated } catch (_: Throwable) { null }
            if (cm != null) installRemovalListener(c, cm)
            val contents = TerminalTabs.contents(project, tw)
            // Split panes keep their tabs in managers of their own; a × there fires only there.
            for (content in contents) {
                val owner = try { content.manager } catch (_: Throwable) { null }
                if (owner != null) installRemovalListener(c, owner)
            }

            val present = LinkedHashSet<String>()
            val unboundNow = HashSet<com.intellij.ui.content.Content>()
            contents.forEachIndexed { index, content ->
                val sid = c.contentToSid[content] ?: run {
                    val w = try { TerminalToolWindowManager.findWidgetByContent(content) } catch (_: Exception) { null }
                    if (w != null) c.spawnedWidgets.entries.firstOrNull { it.value === w }?.key else null
                } ?: run { unboundNow.add(content); return@forEachIndexed }
                present.add(sid)
                c.materializedSids.add(sid) // once present, warmup must never re-admit it after an ×
                val entry = storage.activeSessions.read(sid) ?: return@forEachIndexed
                if (entry.ordinal != index) {
                    storage.activeSessions.writeOrUpdate(entry.sid, entry.cwd, entry.pid, entry.lastSeen, ordinal = index)
                }
            }
            c.presentSids = present
            c.presentSidsAt = System.currentTimeMillis()
            val liveContents = contents.toHashSet()
            // Identity says WHICH unbound Contents are gone; the net decrease bounds HOW MANY were
            // closed. A re-created tool window swaps every Content object for a new one with
            // nothing closed — by identity alone that is N drops, by the net bound it is zero. An
            // empty enumeration (the window mid-re-creation) is skipped outright rather than read
            // as "everything closed". The bound errs toward missing a close (a × and a new tab in
            // the same pass cancel out; that session is then reopened once) rather than
            // manufacturing one, which would evict a session still in use.
            // An empty enumeration is believed only when it REPEATS: once is the tool window
            // mid-re-creation; twice running is the user having closed the last tab — which must
            // be seen, or that session's close is never attributed and it reopens next start.
            if (contents.isNotEmpty()) c.emptyTabPasses = 0
            if (contents.isNotEmpty() || c.lastUnboundContents.isEmpty() || ++c.emptyTabPasses >= 2) {
                val gone = c.lastUnboundContents.count { it !in liveContents }
                val netDecrease = maxOf(0, c.lastUnboundContents.size - unboundNow.size)
                val closed = minOf(gone, netDecrease)
                if (closed > 0) c.unboundDrops.addAndGet(closed)
                c.lastUnboundContents = unboundNow
                c.contentCount = contents.size
                c.unboundContentCount = unboundNow.size
                c.tabCountsObservedAt = System.currentTimeMillis()
            }
            c.contentToSid.keys.retainAll { it in liveContents }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] recordTabOrder failed: ${e.message}")
        }
    }

    /** PID + sid cross-check (handles PID reuse): `<pid>.json` exists AND its sessionId matches. */
    /**
     * Transcript-existence gate for restore, per root. Windows: strict — a missing transcript
     * means nothing to resume. WSL: checked only while the distro is running and its projects
     * dir is readable; otherwise FAIL-OPEN — a sleeping distro must never cost the user a tab
     * (the resume command itself boots it), per the restore contract. The Windows-translated
     * entry cwd misses the WSL munged-dirname fast path, but [ClaudeTabsHelpers.transcriptFile]
     * falls back to scanning every project subdir, which finds it.
     */
    private fun transcriptExistsForEntry(entry: ActiveSessionsStore.Entry): Boolean {
        if (entry.root == SessionRoot.Windows.ID) {
            return ClaudeTabsHelpers.hasTranscriptAnywhere(storage.projectsDir, entry.sid, entry.cwd)
        }
        val root = SessionRoot.byId(entry.root) ?: return true
        val readable = try { root.projectsDir.isDirectory } catch (_: Throwable) { false }
        if (!readable) return true
        return ClaudeTabsHelpers.hasTranscriptAnywhere(root.projectsDir, entry.sid, entry.cwd)
    }

    /**
     * One-time (per application) notification for the startup-tabs takeover: the behavior is ON
     * by default — it is the correct behavior for what this plugin exists to do — but disabling
     * an IDE feature deserves an explicit heads-up and a one-click opt-out. The choice persists
     * app-wide; until the user answers, the notification reappears once per IDE session.
     */
    private fun maybeAskOwnStartupTabs(project: Project) {
        try {
            val props = com.intellij.ide.util.PropertiesComponent.getInstance()
            if (props.getValue(OWN_STARTUP_TABS_CHOICE_KEY) != null) return
            if (!ownStartupTabsAskedThisSession.compareAndSet(false, true)) return
            val group = com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("Claude Terminal Tabs") ?: return
            val n = group.createNotification(
                "Claude tabs now own terminal restore",
                "The IDE terminal reopens its tabs on startup and re-runs their commands, which " +
                    "spawns duplicate empty Claude sessions next to the plugin's restored ones. " +
                    "The plugin now clears the IDE's terminal-tab persistence at shutdown, so " +
                    "startup terminals are plugin-authored only. Note: plain shell tabs are no " +
                    "longer auto-reopened either.",
                com.intellij.notification.NotificationType.INFORMATION,
            )
            n.addAction(
                com.intellij.notification.NotificationAction.createSimpleExpiring("Keep (recommended)") {
                    props.setValue(OWN_STARTUP_TABS_CHOICE_KEY, "on")
                },
            )
            n.addAction(
                com.intellij.notification.NotificationAction.createSimpleExpiring("Turn off — keep IDE tab restore") {
                    props.setValue(OWN_STARTUP_TABS_CHOICE_KEY, "off")
                    LOG.info("[ClaudeTabs] user kept the IDE's own terminal tab restore (startup-tabs takeover off)")
                },
            )
            n.notify(project)
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] startup-tabs notification failed: ${e.message}")
        }
    }

    /**
     * "Don't open any terminals the plugin didn't author": the reworked terminal unconditionally
     * reopens its own persisted tabs on project open and RE-RUNS their commands — an IDE-restored
     * claude tab spawns a brand-new EMPTY session next to the plugin's proper `--resume` restore
     * (field-observed: duplicate "✳ Claude" tabs after every restart, each a fresh conversation).
     * No platform setting gates that restore, so the persisted tab list is cleared at teardown
     * (and again after the plugin's own restore, as a belt) — startup terminals are then
     * plugin-authored only. Reflective and best-effort: the storage class is platform-internal;
     * worst case on a platform change is the old duplicate-tab behavior. The
     * `rider.claude.tabs.ownStartupTabs` registry key turns it off for users who want the IDE's
     * own shell-tab restore back.
     *
     * SELF-RESTORING BY DESIGN: this is a per-shutdown ACTION, never a persistent settings
     * mutation — uninstalling or disabling the plugin stops the action and the IDE's own tab
     * persistence resumes untouched on the next session (cost: the one transitional session
     * whose tabs the final plugin-run shutdown already cleared).
     */
    /** Does the plugin own startup terminals? Registry default ON; the user's choice from the
     *  one-time takeover notification outranks it (the registry key stays the hard override). */
    private fun ownsStartupTabs(): Boolean {
        try {
            if (!com.intellij.openapi.util.registry.Registry.`is`("rider.claude.tabs.ownStartupTabs", true)) return false
        } catch (_: Throwable) { /* registry unavailable → default ON */ }
        return try {
            com.intellij.ide.util.PropertiesComponent.getInstance().getValue(OWN_STARTUP_TABS_CHOICE_KEY) != "off"
        } catch (_: Throwable) { true }
    }

    /**
     * Close every terminal tab the IDE re-created on its own, BEFORE the plugin restores anything.
     *
     * Clearing the IDE's persisted tab list at shutdown ([suppressIdeTerminalTabRestore]) cannot be
     * relied on: the terminal rewrites that list from its in-memory tab map on EVERY tab mutation
     * — create, close, and rename, which the title enforcement here does twice a second — so the
     * clear is overwritten almost at once, and after a crash it never ran at all. Field-observed:
     * "persistence cleared" logged at shutdown, thirteen IDE-restored tabs at the next start, eight
     * of them re-running `claude --resume` for sessions the plugin then restored a second time.
     *
     * At this moment the distinction needs no guessing: nothing the plugin authored exists yet in
     * this project context, so every tab present was re-created by the IDE. They are closed through
     * the terminal's own silent-close path (no "terminate process?" prompt); if that path is
     * missing on some future platform the tabs are left alone rather than risk a wall of modals.
     */
    private suspend fun sweepIdeRestoredTabs(project: Project, c: ProjectCtx) {
        if (!ownsStartupTabs()) return
        // A plugin loaded into an already-running IDE finds LIVE tabs, not re-created ones.
        if (!IdeStartupMarker.loadedWithIde) {
            LOG.info("[ClaudeTabs] Restore: plugin was loaded into a running IDE — IDE-restored tab sweep skipped")
            return
        }
        // Once anything of ours exists the two kinds can no longer be told apart for certain.
        if (ownsAnyTab(c)) return
        // Cleared FIRST: if the tool window has not initialised yet, its restore then finds nothing.
        suppressIdeTerminalTabRestore(project, c)
        fun tabs(): List<Content> {
            val tw = TerminalTabs.toolWindow(project) ?: return emptyList()
            return TerminalTabs.contents(project, tw)
        }
        withContext(Dispatchers.Main) {
            if (project.isDisposed) return@withContext
            // The same activation the first spawn would perform — it is what triggers the IDE's own
            // (asynchronous) tab restore, so it has to happen before the sweep, not after it.
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("Terminal")?.activate(null, false, false)
        }
        // Let that restore settle: the count must hold still before it is believed.
        var last = -1
        var stableSince = System.currentTimeMillis()
        val deadline = stableSince + IDE_TAB_RESTORE_SETTLE_MAX_MS
        while (System.currentTimeMillis() < deadline) {
            val n = withContext(Dispatchers.Main) { if (project.isDisposed) 0 else tabs().size }
            val now = System.currentTimeMillis()
            val needed = if (n > 0) IDE_TAB_RESTORE_SETTLE_MS else IDE_TAB_RESTORE_ZERO_WAIT_MS
            if (n != last) { last = n; stableSince = now } else if (now - stableSince >= needed) break
            delay(300)
        }
        val closed = withContext(Dispatchers.Main) {
            // Re-checked at the moment of closing, not just on entry: the settle wait above is
            // seconds long, and a "New Claude Session" clicked during it creates a tab of OURS that
            // this sweep must not take. Once one exists the sweep stands down entirely.
            if (project.isDisposed || ownsAnyTab(c)) -1
            else tabs().count { closeContentSilently(it) }
        }
        // Always logged: a sweep that found nothing and one that stood down must both be visible.
        LOG.info(
            "[ClaudeTabs] Restore: IDE-restored tab sweep — " +
                (if (closed < 0) "stood down (a plugin tab appeared meanwhile)" else "closed $closed of $last tab(s) present"),
        )
        suppressIdeTerminalTabRestore(project, c)
    }

    /** Remove [content] through the terminal's silent-close path. False when that path is
     *  unavailable — the tab is then left open rather than closed with a confirmation prompt. */
    private fun closeContentSilently(content: Content): Boolean = try {
        val manager = content.manager
        if (manager == null) false else {
            val loader = org.jetbrains.plugins.terminal.TerminalToolWindowManager::class.java.classLoader
            val cls = Class.forName("org.jetbrains.plugins.terminal.TerminalTabCloseListener", true, loader)
            val companion = cls.getField("Companion").get(null)
            val remove: () -> Unit = { manager.removeContent(content, true) }
            companion.javaClass
                .getMethod("executeContentOperationSilently", Content::class.java, kotlin.jvm.functions.Function0::class.java)
                .invoke(companion, content, remove)
            true
        }
    } catch (t: Throwable) {
        if (silentCloseUnavailableLogged.compareAndSet(false, true)) {
            LOG.info("[ClaudeTabs] silent tab close unavailable (${t.javaClass.simpleName}) — IDE-restored tabs left open")
        }
        false
    }

    /** Does anything the plugin authored exist (or is about to) in this project? Every path that
     *  creates a tab leaves a trace in one of these before its Content can be told apart. */
    private fun ownsAnyTab(c: ProjectCtx): Boolean =
        c.spawnedWidgets.isNotEmpty() || c.contentToSid.isNotEmpty() ||
            c.pendingNew.isNotEmpty() || c.pendingInjectedSids.isNotEmpty()

    private fun suppressIdeTerminalTabRestore(project: Project, c: ProjectCtx) {
        if (!ownsStartupTabs()) return
        try {
            val loader = org.jetbrains.plugins.terminal.TerminalToolWindowManager::class.java.classLoader
            val cls = Class.forName("org.jetbrains.plugins.terminal.settings.impl.TerminalTabsStorage", true, loader)
            val inst = cls.getMethod("getInstance", Project::class.java).invoke(null, project)
            cls.getMethod("updateStoredTabs", java.util.List::class.java).invoke(inst, emptyList<Any>())
            if (!c.ideTabRestoreSuppressLogged) {
                c.ideTabRestoreSuppressLogged = true
                LOG.info("[ClaudeTabs] IDE terminal tab persistence cleared — startup tabs are plugin-authored only")
            }
        } catch (t: Throwable) {
            if (!c.ideTabRestoreSuppressLogged) {
                c.ideTabRestoreSuppressLogged = true
                LOG.info("[ClaudeTabs] could not clear IDE terminal tab persistence (${t.javaClass.simpleName}) — IDE-restored tabs may reappear")
            }
        }
    }

    private fun verifyAlive(pid: Long, expectedSid: String, rootId: String): Boolean {
        // Stopped WSL distro → root unresolvable → nothing in it is alive (correct: its
        // processes died with it; the entry demotes to restore-pending and resumes later).
        val root = SessionRoot.byId(rootId) ?: return false
        val pidFile = File(root.sessionsDir, "$pid.json")
        if (!pidFile.exists()) return false
        val text = try { pidFile.readText() } catch (_: Exception) { return false }
        if (ClaudeTabsHelpers.extractJsonString(text, "sessionId") != expectedSid) return false
        if (!root.processAlive(pid)) return false
        // A marker older than the process is a leftover on a recycled pid: that process is
        // someone else's, and this session is not alive.
        return !ClaudeTabsHelpers.markerIsStale(
            ClaudeTabsHelpers.extractJsonLong(text, "startedAt"), root.processStartEpochMs(pid),
        )
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
        val candidates = entries.filter { e ->
            claimsCwd(e.cwd, project) && e.sid in openSnapshot && transcriptExistsForEntry(e)
        }
        // A session that still LOOKS alive right after startup is usually the previous IDE's
        // process halfway through dying, not one running elsewhere: the IDE's exit does not wait
        // for its terminals' children, and a quick relaunch gets here first (E2E-observed: alive
        // three seconds in, gone moments later — and the tab was withheld for the whole session).
        // So the verdict is only believed once it has survived a grace period.
        var running = candidates.filter { looksStillRunning(it) }
        var waited = 0L
        while (running.isNotEmpty() && waited < STILL_RUNNING_GRACE_MS) {
            delay(STILL_RUNNING_RECHECK_MS)
            waited += STILL_RUNNING_RECHECK_MS
            running = running.filter { looksStillRunning(it) }
        }
        running.forEach { withholdRunning(it, c) }
        val toRestore = (candidates - running.toSet()).sortedWith(ClaudeTabsHelpers.restoreOrder())
        if (toRestore.isEmpty()) {
            LOG.info("[ClaudeTabs] Restore: nothing to spawn (snapshot=${openSnapshot.size}, entries=${entries.size})")
            suppressIdeTerminalTabRestore(project, c)
            return
        }
        sweepIdeRestoredTabs(project, c)
        LOG.info("[ClaudeTabs] Restore: spawning ${toRestore.size} owned tab(s) from a snapshot of ${openSnapshot.size}")
        spawnRestored(project, c, toRestore)
        // Belt for a missed teardown clear (crash, kill): whatever the IDE just re-persisted is
        // superseded by the plugin-authored restore above — clear it again for the NEXT startup.
        suppressIdeTerminalTabRestore(project, c)
    }

    /** Spawn one owned `claude --resume` tab per entry, serialized and staggered. */
    private suspend fun spawnRestored(project: Project, c: ProjectCtx, toRestore: List<ActiveSessionsStore.Entry>) {
        val spawned = mutableListOf<ActiveSessionsStore.Entry>()
        for (e in toRestore) {
            if (c.spawnedWidgets.containsKey(e.sid)) continue
            storage.activeSessions.bumpRestoreAttempts(e.sid)
            val displayName = TitleModel.resolveDisplayName(e.userName, null, e.name)
            // Resolved OFF the EDT: resumeCommandFor may consult WSL discovery (wsl.exe, 5s cap)
            // to recover the owning user for a legacy root id.
            val resumeCommand = SessionRoot.resumeCommandFor(e.root, e.sid)
            resumeSpawnMutex.withLock {
                withContext(Dispatchers.Main) {
                    if (project.isDisposed) return@withContext
                    c.restoreSpawnAttempts[e.sid] = (c.restoreSpawnAttempts[e.sid] ?: 0) + 1
                    spawnOwnedTab(
                        project, e.sid, e.cwd, displayName, OwnedTerminalSpawner.Mode.RESUME,
                        command = resumeCommand,
                    )
                    spawned.add(e)
                }
                if (project.isDisposed) return
                delay(RESTORE_STAGGER_MS)
                awaitConfigSettled()
            }
        }
        writeLastRestoreSnapshot(project, spawned)
    }

    /**
     * A snapshot member whose process is still running — in another window, or a plain terminal
     * — must not be resumed on top of itself. Reachable since the snapshot admits sessions with no
     * tab binding: such a session can outlive the restart this restore follows. The check is
     * pid-reuse safe ([ClaudeTabsHelpers.sessionStillRunning]); an unresolvable root means a
     * stopped distro whose processes died with it. The sid joins [ProjectCtx.everSeenAlive] so a
     * death before the first poll cannot make it a retry-spawn candidate.
     */
    private fun looksStillRunning(e: ActiveSessionsStore.Entry): Boolean {
        if (e.pid == null) return false
        val root = SessionRoot.byId(e.root) ?: return false
        return ClaudeTabsHelpers.sessionStillRunning(root, e)
    }

    /** Withhold [e] from restore — only called once it has outlived the grace period. */
    private fun withholdRunning(e: ActiveSessionsStore.Entry, c: ProjectCtx) {
        c.everSeenAlive.add(e.sid)
        LOG.info(
            "[ClaudeTabs] Restore: sid=${e.sid} pid=${e.pid} root=${e.root} is still running outside this IDE " +
                "after ${STILL_RUNNING_GRACE_MS / 1000}s — not reopened (it will be after it exits and the IDE restarts)",
        )
    }

    /** Base paths of every open project — the only bases that may out-claim this one for the
     *  unbound arm ([ClaudeTabsHelpers.claimsExclusively]); `project-index.json` is never pruned. */
    private fun openProjectBases(): List<String> = try {
        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.mapNotNull { it.basePath }
    } catch (_: Exception) {
        emptyList()
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
            deployInto(storage, rootId = null)
            stripClaudeMdSection()
            for (legacy in listOf(
                    "tab-now.js", "rename-tab.sh", "tab.sh", "tab-backup.js",
                    "session-start-hook.sh", "rename-tab.log", "hook-debug.log", "last-hook-input.log",
            )) {
                try { File(storage.stateDir, legacy).delete() } catch (_: Exception) { }
            }
            deployIntoWslRoots()
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] deployClaudeIntegration failed: ${e.message}")
        }
    }

    /**
     * Copy the helpers and commands into one `~/.claude`. [rootId] is null for the home the
     * plugin itself writes state into, and the root's own id for a WSL home — where the state
     * dir sits on the other side of the `/mnt` boundary and has to be pointed at explicitly.
     */
    private fun deployInto(target: ClaudeTabsStorage, rootId: String?) {
        val cl = javaClass.classLoader
        target.stateDir.mkdirs()
        for (name in INTEGRATION_HELPERS) {
            val inStream = cl.getResourceAsStream("claude-integration/$name") ?: continue
            inStream.use { input -> File(target.stateDir, name).outputStream().use { input.copyTo(it) } }
        }
        target.commandsDir.mkdirs()
        for (name in INTEGRATION_COMMANDS) {
            val inStream = cl.getResourceAsStream("claude-integration/$name") ?: continue
            inStream.use { input -> File(target.commandsDir, name).outputStream().use { input.copyTo(it) } }
        }
        ensureSettingsPermissions(target)
        if (rootId != null) writeStateRootPointer(target, rootId)
    }

    /**
     * A helper run from a Claude session inside a distro resolves `~` to the LINUX home, where
     * there is no plugin state — the plugin only ever runs on the Windows side. This pointer is
     * the one thing that bridges the two: the state dir in the `/mnt` form that side can open,
     * plus the root id to stamp on any session record the helper has to rebuild (a record
     * without it resumes on Windows, where the session's transcript does not exist).
     */
    private fun writeStateRootPointer(target: ClaudeTabsStorage, rootId: String) {
        val mounted = ClaudeTabsHelpers.windowsPathToWslMount(storage.stateDir.absolutePath) ?: return
        val json = """{"stateDir":"${ClaudeTabsHelpers.esc(mounted)}","host":"windows",""" +
            """"rootId":"${ClaudeTabsHelpers.esc(rootId)}"}"""
        DurableIo.writeAtomic(File(target.stateDir, "state-root.json"), json)
    }

    /**
     * Deploy into every running WSL distro's `~/.claude` as well. Sessions started inside a
     * distro are the ones a WSL shutdown kills, so `/tabs-recover` is least useful in the only
     * home where it was previously never installed. Best-effort per root: an unreachable share
     * must not fail the deployment for the others.
     */
    private fun deployIntoWslRoots() {
        for (root in WslDiscovery.runningRoots()) {
            try {
                deployInto(ClaudeTabsStorage(root.claudeHome), rootId = root.id)
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] integration deploy into ${root.id} failed: ${e.message}")
            }
        }
    }

    /** Ensure current permission entries exist in `~/.claude/settings.json`; remove legacy ones. */
    /** Keep [target]'s `settings.json` permission lines current. Every home the helpers are
     *  deployed into needs its own: a session running inside a distro reads THAT distro's
     *  settings, so permissions written only on the Windows side would prompt on every run. */
    private fun ensureSettingsPermissions(target: ClaudeTabsStorage = storage) {
        val settings = target.settingsFile
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
