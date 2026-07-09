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
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry point for the Claude Terminal Tab Persistence plugin (2.1).
 *
 * Three jobs:
 *
 *  1. **Track alive Claude sessions.** Every [POLL_INTERVAL_MS] scan `~/.claude/sessions/<pid>.json`
 *     (Claude's own per-process metadata) and write one file per sid into
 *     `~/.claude/rider-plugin/active-sessions/<sid>.json`. Process death is NEVER treated as a
 *     user action: after [EvictionTracker]'s K=2 strikes a dead entry is DEMOTED to
 *     restore-pending (pid=null), preserving its names, so reloads and crashes are survivable.
 *     The only path that evicts (to `session-backlog.json`) is a confirmed two-signal user close.
 *
 *  2. **Auto-restore on project open.** Scan `active-sessions/`, filter to "cwd belongs to this
 *     project (worktree-tolerant + sibling-project arbitration via [claimsCwd])", spawn one
 *     fresh terminal tab per match and run `claude --resume <sid>` in it.
 *
 *  3. **Own tab titles + thinking animation.** [TitleController] enforces `✳ <name>` on
 *     every tab hosting a Claude session (plugin-spawned and user-opened, matched via
 *     [matchUnclaimedTabs]) and cycles the glyph while the session is busy. Display name:
 *     explicit user name (IDE rename / /tab skill) > Claude's live topic name > cached
 *     topic > "Claude". User renames are adopted and persisted, never fought.
 *
 * Close-detection (two-signal: contentRemoveQuery + Claude-process-dead) is unchanged from
 * 1.0.17 and persists user-closed sids per-project so they don't auto-resurrect.
 */
class ClaudeTabWatcherStartup : StartupActivity.DumbAware {

    companion object {
        private val LOG = Logger.getInstance(ClaudeTabWatcherStartup::class.java)

        /** Poll cadence. */
        private const val POLL_INTERVAL_MS = 5_000L

        /** Per-sid cooldown between retry-spawn attempts when a restore-tab spawn doesn't
         *  produce an alive Claude process within the cooldown window. 60s is long enough
         *  for Claude's `--resume` startup (Node init + transcript replay) on a slow disk,
         *  short enough that a genuinely failed spawn gets re-attempted promptly. */
        private const val RESPAWN_COOLDOWN_MS = 60_000L

        /** Hard cap on spawn attempts per sid per Rider session. Each retry opens a NEW
         *  terminal tab; without a cap, a resume that consistently fails (deleted worktree,
         *  corrupted transcript, claude binary error) would spawn an endless stream of
         *  "Local" tabs at the cooldown cadence. After the cap, the pid=null entry just
         *  sits in active-sessions/ (never evicted) for manual recovery. */
        private const val MAX_SPAWN_ATTEMPTS = 3

        /** Pending-close entries are dropped after this long without an observed process-death. */
        const val PENDING_CLOSE_EXPIRY_MS = 30_000L

        /** Debounce for the event-driven close pass. `contentRemoved` fires on the reworked terminal
         *  (even when `contentRemoveQuery` does not), once per removed tab. We coalesce a burst — a
         *  shutdown removes every tab at once — and run the close-detection pass this long after the
         *  LAST removal. Long enough to swallow a shutdown burst into one "dropped to zero → ignore",
         *  short enough that a single close is caught in well under a second instead of waiting for
         *  the 5s poll. This is what closes the "X a tab then quit Rider" race down to ~400ms. */
        private const val CLOSE_DEBOUNCE_MS = 400

        /** Base delay after spawning a restore tab before the next, giving the just-launched
         *  `claude --resume` a moment to BEGIN its startup write of `~/.claude.json`. This alone
         *  is not enough (Claude's write isn't concurrency-atomic and can take longer), so it is
         *  paired with [awaitConfigSettled], which then waits until that write actually finishes
         *  before the next resume launches. Together they serialize the writes — the real fix for
         *  the thundering-herd corruption of `~/.claude.json`. The plugin only READS that file's
         *  size/mtime to detect settling; it never writes it. */
        private const val RESTORE_STAGGER_MS = 300L

        /** Hard cap on how long [awaitConfigSettled] waits for `~/.claude.json` to stop changing,
         *  so a slow or hung Claude start can never stall restore. */
        private const val CONFIG_SETTLE_CAP_MS = 5000L

        // NOTE: there is intentionally no restore staleness window, herd cap, or ghost-decay cap.
        // The restore contract (project memory `tab-restore-contract`) forbids age/count-based
        // retirement — a tab reopens unless the user X-closed it, full stop. Config-churn safety
        // comes from serialized spawns (below), not from capping how many tabs restore.

        /** JVM-GLOBAL serialization of every `claude --resume` spawn. The stagger+settle inside
         *  one project's restore loop is not enough on its own: multiple project windows restore
         *  concurrently, and the poll's retry-spawn path fires outside any loop — without a shared
         *  lock, two windows can both observe a "settled" `~/.claude.json` in the same instant and
         *  spawn simultaneously, resurrecting the thundering-herd corruption of Claude's config.
         *  Every spawn path (performRestore AND retry-spawn) takes this mutex around
         *  spawn → stagger → settle, so at most one resuming Claude is mid-startup-write at a time. */
        private val resumeSpawnMutex = Mutex()

        /** `~/.claude.json` — Claude Code's own config. The plugin never writes it; restore only
         *  reads its size+mtime to know when a resuming Claude has finished updating it. */
        private val CLAUDE_GLOBAL_CONFIG = File(System.getProperty("user.home"), ".claude.json")

        /** Root of Claude Code's user data. */
        private val CLAUDE_HOME = File(System.getProperty("user.home"), ".claude")

        /** Markers wrapping the plugin's section of `~/.claude/CLAUDE.md`. 1.x wrote a
         *  tab-naming instruction block between these; 2.0 never writes one and strips any
         *  leftover block on every deploy (and on uninstall). */
        private const val CLAUDE_MD_MARKER = "<!-- rider-claude-tabs-plugin -->"

        /** Remove the plugin's marker-wrapped section from `~/.claude/CLAUDE.md` if present.
         *  Idempotent; no-op when the file or marker is absent. */
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

        /** Every permission line any build of the plugin has ever written — uninstall removes
         *  all of them. Derived from [SettingsPermissions]'s single-source lists. */
        private val PERMISSION_ENTRIES =
            SettingsPermissions.CURRENT_ENTRIES + SettingsPermissions.LEGACY_ENTRIES

        /** Singleton storage helper. */
        private val storage = ClaudeTabsStorage(CLAUDE_HOME)

        /** Self-healing guard for `~/.claude.json` (see [ConfigGuard]). JVM-global like the
         *  spawn mutex: every window's poll calls it, strikes must be shared. */
        private val configGuard = ConfigGuard(
            configFile = CLAUDE_GLOBAL_CONFIG,
            lastGoodFile = File(storage.stateDir, "claude-json.last-good"),
        )

        /** When this IDE process started. The user-closed self-heal only trusts a claude process
         *  as evidence of "the user re-opened/resumed this session" when it STARTED after the IDE
         *  did — an orphan left alive by a 1.x close (which never killed the process) predates the
         *  IDE start and must not erase a deliberate user-closed record. */
        private val IDE_START_MS: Long =
            java.lang.management.ManagementFactory.getRuntimeMXBean().startTime

        /** Cross-window in-memory eviction tracker. Per-window strike counts; shared so two
         *  Rider windows polling the same sids don't both have to confirm independently
         *  (race-safe — both might reach the threshold at the same poll, both call evict —
         *  delete is idempotent, backlog prepend dedups). */
        private val evictionTracker = EvictionTracker()

        /** Per-project state. Keyed by project.locationHash so multiple projects don't
         *  clobber each other. */
        private val projectCtx = ConcurrentHashMap<String, ProjectCtx>()

        /** One-shot guard for [reconcileStaleEntriesOnStartup] — all project windows share
         *  this JVM, only the first to start runs the reconcile. */
        private val reconciledThisJvm = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Plugin version, for the diagnostic startup log. Kept as a constant so we don't reach
         *  for the internal `PluginManager.getPluginByClass` API just to print it. */
        private const val PLUGIN_VERSION = "2.1.0"

        /** Set true once the IDE begins shutting down (quit OR restart), via the public
         *  [com.intellij.ide.AppLifecycleListener] message-bus topic. Terminal close-detection
         *  reads this to tell an IDE shutdown (tear down every tab) from the user closing one
         *  tab — replacing the internal `Application.isExitInProgress()` query. */
        @Volatile private var appClosing = false
        private val appClosingSubscribed = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Removes all plugin artifacts from `~/.claude` (called on plugin uninstall).
         * Best-effort; logs but does not throw on individual failures.
         */
        @JvmStatic
        fun uninstall() {
            stripClaudeMdSection()
            val settings = File(CLAUDE_HOME, "settings.json")
            if (settings.exists()) {
                val text = settings.readText()
                // Remove every plugin entry via a clean array rebuild — same robust path as
                // ensureSettingsPermissions, so uninstall can't leave a dangling comma either.
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
        /** Sids the user explicitly closed in this project. Hydrated from
         *  `user-closed-<hash>.json` on startup; mutated by close detection. */
        val userClosedSessions = mutableSetOf<String>()

        /** Sids the close listener has queued for signal-2 (process-dead) verification.
         *  Value is the timestamp the signal-1 fired, used for [PENDING_CLOSE_EXPIRY_MS]. */
        val pendingClose = ConcurrentHashMap<String, Long>()

        /** Content → sid for closes that passed signal 1 but whose removal hasn't COMMITTED yet.
         *  `contentRemoveQuery` is a veto-able question — Rider's own "terminate running
         *  process?" dialog (or any other listener) can cancel it, and killing on the question
         *  would destroy a session whose close the user then cancels. So signal 1 only records
         *  intent here; the actual process kill fires from `contentRemoved` (the post-commit
         *  event), on a pooled thread. Vetoed closes simply never reach `contentRemoved`;
         *  their entries are dropped when the matching pendingClose expires. */
        val pendingKill = ConcurrentHashMap<Content, String>()

        /** Map from terminal Content → sid, populated when we spawn a tab. Used by the close
         *  listener to identify which sid was in a closed tab. */
        val contentToSid = ConcurrentHashMap<Content, String>()

        /** Widgets we spawned, keyed by sid. Used by the close listener as a fallback
         *  identity lookup when contentToSid hasn't been populated yet. */
        val spawnedWidgets = ConcurrentHashMap<String, TerminalWidget>()

        /** Every sid that has held a live tab at any point THIS Rider session (spawned or
         *  claimed). Retry-spawn consults this: a sid that once had a tab and no longer does was
         *  CLOSED or crashed — never resurrect it mid-session. Only sids that NEVER materialised a
         *  tab (a genuine startup-spawn failure) are eligible for retry. This is the guard that
         *  stops a deliberately-closed tab from coming back a minute later. */
        val everHadWidget: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** Every sid the poll has observed ALIVE at any point THIS Rider session — populated from
         *  the sessions-dir alive scan, BEFORE and independent of any widget claim. This is the
         *  broader sibling of [everHadWidget]: a user-opened tab the plugin tracks but never managed
         *  to claim a widget for (PID dig failed, ambiguous cwd) is in here but NOT in everHadWidget.
         *  Retry-spawn consults it so that "was alive, now dead" — a close or crash — is never
         *  mistaken for a startup spawn that silently failed. Without this, an unclaimed user tab
         *  resurrects ~1 min after the user closes it (it demotes to pid=null, looks like a failed
         *  seed, and retry-spawn resumes it). everHadWidget alone can't cover it. */
        val everSeenAlive: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** Timestamps of tab closes we could NOT attribute to a sid (unclaimed tab — its X
         *  reached contentRemoveQuery with no session id). The poll ties each token to a
         *  project-owned session whose process has since vanished (see [TwoSignalCloseDetector.attributeCloses]).
         *  This is what makes an X on a "Local"/unclaimed tab still stick. Guarded by its own monitor. */
        val unattributedCloses: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())

        /** Project-owned sids the poll verified ALIVE on its PREVIOUS iteration. Diffed against
         *  the current alive set to find vanishers for unattributed-close attribution. */
        @Volatile var lastAliveProjectSids: Set<String> = emptySet()

        /** Count of Claude-looking terminal tabs seen on the PREVIOUS poll. A DROP (while the
         *  window/app is not tearing down) is the poll-based close signal — the reworked terminal
         *  may never deliver contentRemoveQuery, so a vanished tab is detected by counting instead.
         *  -1 = not yet sampled. */
        @Volatile var lastClaudeTabCount: Int = -1

        /** Stripped display titles of the Claude tabs present on the PREVIOUS poll. When the count
         *  drops by one, the title that disappeared identifies the closed session by name — letting
         *  the close be persisted (userClosed) IMMEDIATELY, instead of waiting for a later poll to
         *  watch the process die. That closes the "X a tab then quit Rider within 5s → the close is
         *  lost and the tab reopens" race. */
        @Volatile var prevClaudeTitles: Set<String> = emptySet()

        /** Set to true by [com.intellij.openapi.project.ProjectManagerListener.projectClosing].
         *  When true, close events are project teardown, not user intent. */
        @Volatile var projectClosing: Boolean = false

        /** Sid → lastAttemptMs for restore-tab spawns we've fired in THIS Rider session.
         *  Lets pollOnce retry pid=null entries whose first spawn silently failed (Terminal
         *  tool window not ready at startup, widget creation threw, etc.), with a 60s cooldown
         *  per sid so we don't hammer createShellWidget on a Claude that's slow to start.
         *  Once the alive Claude actually appears, step 1's writeOrUpdate stamps the real
         *  pid and the entry is no longer pid=null, so the retry branch skips it. */
        val restoreSpawnLastAttempt = ConcurrentHashMap<String, Long>()

        /** Sid → attempt count, capped at [MAX_SPAWN_ATTEMPTS]. Each attempt opens a new
         *  terminal tab; the cap stops a consistently-failing resume from spawning an
         *  endless stream of tabs. */
        val restoreSpawnAttempts = ConcurrentHashMap<String, Int>()

        /** Sid → the exact title string [TitleController] last wrote. Lets the controller
         *  distinguish "still what we set" from "user renamed" (which it ADOPTS as the
         *  persistent userName, keeping the glyph + animation around the user's text). */
        val lastAppliedTitle = ConcurrentHashMap<String, String>()

        val startupAt: Long = System.currentTimeMillis()
    }

    private fun ctx(project: Project): ProjectCtx =
        projectCtx.getOrPut(project.locationHash) { ProjectCtx() }

    private fun projectHash(project: Project): String =
        ClaudeTabsHelpers.projectHashForPath(project.basePath)

    // ──────────────────────────────────────────────────────────────────
    // Cwd ownership arbitration
    // ──────────────────────────────────────────────────────────────────

    /**
     * True if THIS project's window should claim a session whose cwd is [cwd].
     *
     * Base rule: [ClaudeTabsHelpers.isCwdUnderProject] (exact, descendant, or
     * `<base>-<suffix>` sibling-worktree).
     *
     * Arbitration on top: the sibling-worktree clause is deliberately loose — it also
     * matches *sibling projects* (`MyApp` vs `MyApp-mobile`), which have their own Rider
     * windows and must NOT be claimed by the parent. So a dash-suffix match is rejected
     * when a MORE SPECIFIC project base is known for that cwd, where "known" means:
     *  - any currently-open Rider project, or
     *  - any project recorded in `project-index.json` (projects the plugin has seen open
     *    before — covers the "sibling project's window isn't open right now" case, so its
     *    sessions wait for it rather than leaking into the parent window).
     *
     * A genuine git worktree (`MyApp-feature-branch`) is never opened as its own Rider
     * project, so no more-specific base exists, and the parent window claims it. Exact
     * and descendant matches are never rejected — only the sibling clause arbitrates.
     */
    private fun claimsCwd(cwd: String, project: Project): Boolean {
        val basePath = project.basePath ?: return false
        if (!ClaudeTabsHelpers.isCwdUnderProject(cwd, basePath)) return false

        val norm = { p: String -> p.replace("\\", "/").trimEnd('/').lowercase() }
        val nCwd = norm(cwd)
        val nBase = norm(basePath)
        // Exact or descendant — always ours.
        if (nCwd == nBase || nCwd.startsWith("$nBase/")) return true

        // Sibling-dash clause matched. Check for a more specific known project base.
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
            // A more specific base owns this cwd → not ours.
            if (other.length > nBase.length && (nCwd == other || nCwd.startsWith("$other/"))) {
                return false
            }
        }
        return true
    }

    /** Upsert this project into `project-index.json` so [claimsCwd] arbitration works
     *  even when this project's window is closed later. */
    private fun updateProjectIndex(project: Project) {
        val basePath = project.basePath ?: return
        try {
            val indexFile = File(storage.stateDir, "project-index.json")
            val hash = projectHash(project)
            val name = ClaudeTabsHelpers.esc(project.name)
            val basePathEsc = ClaudeTabsHelpers.esc(basePath.replace("\\", "/"))
            val entry = """{"hash":"${ClaudeTabsHelpers.esc(hash)}","basePath":"$basePathEsc","name":"$name"}"""
            val existing = if (indexFile.exists()) indexFile.readText() else ""
            if (existing.contains("\"hash\":\"${ClaudeTabsHelpers.esc(hash)}\"")) return // already indexed
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
        val pluginVersion = PLUGIN_VERSION
        val ideInfo = try {
            val app = com.intellij.openapi.application.ApplicationInfo.getInstance()
            "${app.versionName} ${app.fullVersion} (build ${app.build.asString()})"
        } catch (_: Exception) { "unknown" }
        LOG.info("[ClaudeTabs] ════════════════════════════════════════════════════════")
        LOG.info("[ClaudeTabs] Started for: ${project.name}")
        LOG.info("[ClaudeTabs] Plugin version: $pluginVersion")
        LOG.info("[ClaudeTabs] IDE: $ideInfo")
        LOG.info("[ClaudeTabs] Project base path: ${project.basePath}")
        if (AiAgentsDetector.isActive(project)) {
            LOG.info("[ClaudeTabs] JetBrains AI Assistant / Claude Agent host detected — this plugin manages " +
                "terminal-launched Claude CLI sessions only.")
        }
        LOG.info("[ClaudeTabs] ════════════════════════════════════════════════════════")

        // One-time 1.x → 2.0 migration. Idempotent.
        try {
            val seeded = storage.migrateLegacyRestoreFiles()
            if (seeded > 0) LOG.info("[ClaudeTabs] Migration: seeded $seeded session(s) from legacy restore-*.json")
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] Migration failed: ${e.message}")
        }

        deployClaudeIntegration()
        updateProjectIndex(project)
        reconcileStaleEntriesOnStartup()

        // One-time app-shutdown subscription. appWillBeClosed fires (for both quit and restart)
        // before project teardown, so terminal close-detection can suppress the tab-removal
        // burst of a shutdown instead of mis-recording it as user tab-closes.
        if (appClosingSubscribed.compareAndSet(false, true)) {
            try {
                val app = ApplicationManager.getApplication()
                app.messageBus.connect(app).subscribe(
                    com.intellij.ide.AppLifecycleListener.TOPIC,
                    object : com.intellij.ide.AppLifecycleListener {
                        override fun appWillBeClosed(isRestart: Boolean) { appClosing = true }
                    },
                )
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] AppLifecycleListener subscribe failed: ${e.message}")
            }
        }

        // Hydrate userClosed from disk so a tab the user X-ed before a crash doesn't auto-resurrect.
        try {
            val persisted = storage.loadUserClosed(projectHash(project))
            if (persisted.isNotEmpty()) {
                val c = ctx(project)
                synchronized(c.userClosedSessions) { c.userClosedSessions.addAll(persisted) }
                LOG.info("[ClaudeTabs] Loaded ${persisted.size} persisted user-closed sid(s)")
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] user-closed load failed: ${e.message}")
        }

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // Project-closing listener so subsequent close events are not treated as user-intent.
        try {
            val pmListener = object : com.intellij.openapi.project.ProjectManagerListener {
                override fun projectClosing(p: com.intellij.openapi.project.Project) {
                    if (p == project) {
                        ctx(project).projectClosing = true
                        LOG.info("[ClaudeTabs] Project closing — suppressing user-close tracking")
                    }
                }
            }
            com.intellij.openapi.project.ProjectManager.getInstance().addProjectManagerListener(project, pmListener)
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] ProjectManagerListener install failed: ${e.message}")
        }

        // Two-signal close detection (signal 1 = contentRemoveQuery + not TEMPORARY_REMOVED;
        // signal 2 = Claude process dead at next poll). User-closed only fires on BOTH.
        installCloseListener(project)

        Disposer.register(project as Disposable, Disposable {
            val c = ctx(project)
            c.projectClosing = true
            LOG.info("[ClaudeTabs] Project closed — dropping ProjectCtx")
            projectCtx.remove(project.locationHash)
            scope.cancel()
        })

        // Title ownership: enforce `✳ <name>` + busy animation on every Claude tab.
        // Lifecycle rides the scope cancel above.
        TitleController(project, storage, ctx(project)).start(scope)

        // Main poll loop: write per-sid files for alive Claude processes, demote dead ones to
        // restore-pending, mirror Claude's session names onto tabs, confirm pending closes. The
        // first poll runs after a 3s delay so any post-restore tab spawns have settled.
        scope.launch {
            delay(3_000)
            // performRestore runs on the background dispatcher (file IO off-EDT) and hops to the
            // EDT itself for each staggered spawn.
            performRestore(project)
            while (isActive) {
                try {
                    pollOnce(project)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] Poll error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Startup reconcile — observed vs unobserved deaths
    // ──────────────────────────────────────────────────────────────────

    /**
     * Convert stale per-sid entries into restore candidates. Runs ONCE per Rider JVM, before
     * any poll loop starts.
     *
     * The eviction model's contract is "evict sessions whose death we OBSERVED" — the user
     * typed exit, killed Claude, closed the tab. But when Rider itself restarts, every Claude
     * process dies with it while no plugin was watching. On the next start, those entries
     * have recorded pids that are dead — indistinguishable, at poll time, from observed
     * deaths. Without this reconcile, the first poll evicts the entire saved state to the
     * backlog BEFORE the per-project restore loops get a chance to spawn resumes (the exact
     * failure: one window's poll evicted 17 entries two seconds before another window's
     * restore ran).
     *
     * The reconcile rewrites every dead-pid entry with `pid=null` — the "unconfirmed /
     * restore-pending" shape, which the eviction path never touches and the restore +
     * retry-spawn paths actively try to resume. After this, any entry with a non-null pid
     * was stamped by a poll in THIS JVM (we observed it alive), so a later death really is
     * an observed death and eviction applies.
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
                    sid = entry.sid,
                    cwd = entry.cwd,
                    pid = null,
                    lastSeen = now,
                    name = null, // preserves existing cached name
                )
                reset++
            }
            if (reset > 0) {
                LOG.info("[ClaudeTabs] Startup reconcile: $reset stale entr(ies) reset to restore-pending (unobserved deaths from previous Rider session)")
            }
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] Startup reconcile failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Poll loop — the core of the new design
    // ──────────────────────────────────────────────────────────────────

    /**
     * Single iteration:
     *  1. Scan `~/.claude/sessions/<pid>.json`; write `active-sessions/<sid>.json` for each.
     *  2. Walk `active-sessions/`; verify each is still alive (PID exists, sid matches); after
     *     [EvictionTracker]'s K strikes a dead entry is DEMOTED to restore-pending (never evicted
     *     — only a confirmed user close evicts).
     *  3. Confirm pending-close signal-2 (process dead) → mark userClosed + evict to backlog.
     */
    internal suspend fun pollOnce(project: Project) {
        val now = System.currentTimeMillis()
        val seenSids = mutableSetOf<String>()
        val alivePidToSid = mutableMapOf<Long, String>()
        /** sid → alive pid for every session this poll verified, feeding step 3b's
         *  process-start-time gate. */
        val sidToPid = mutableMapOf<String, Long>()
        val c = ctx(project)

        // Step 0: self-heal `~/.claude.json`. Claude's own concurrent writers corrupt it under
        // process churn (a resume herd, a mass tab close), and once corrupt every new claude
        // launch dies at a "Configuration error" prompt — including our restores. The guard
        // validates each poll, mirrors the last VALID content, and repairs only corruption
        // that survives two consecutive polls (never racing a writer mid-flight).
        when (val status = configGuard.check()) {
            ConfigGuard.Status.VALID, ConfigGuard.Status.SUSPECT -> {}
            ConfigGuard.Status.UNREPAIRABLE ->
                LOG.warn("[ClaudeTabs] ConfigGuard: ~/.claude.json is corrupt and no repair applies — leaving untouched")
            else ->
                LOG.warn("[ClaudeTabs] ConfigGuard: ~/.claude.json was corrupt — auto-repaired ($status)")
        }

        // Step 1: write per-sid for every alive Claude process.
        val sessionsDir = storage.sessionsDir
        sessionsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { sf ->
            val pid = sf.nameWithoutExtension.toLongOrNull() ?: return@forEach
            val text = try { sf.readText() } catch (_: Exception) { return@forEach }
            val sid = ClaudeTabsHelpers.extractJsonString(text, "sessionId") ?: return@forEach
            val cwd = ClaudeTabsHelpers.extractJsonString(text, "cwd") ?: return@forEach
            // Liveness check: PID alive AND looks like a Claude process (recycling guard).
            val ph = ProcessHandle.of(pid).orElse(null) ?: return@forEach
            if (!ph.isAlive) return@forEach
            val info = ph.info()
            val cmd = info.command().orElse("")
            val cmdLine = info.commandLine().orElse("")
            val procInfo = SessionsDirScanner.ProcessInfo(cmd, cmdLine)
            if (!SessionsDirScanner.looksLikeClaude(procInfo)) return@forEach
            // Claude's own auto-generated session topic name ("fix-auth-token-rotation").
            // This is the single source of truth for tab labels — no model instruction,
            // no helper script, just Claude's metadata mirrored onto the tab.
            val claudeName = ClaudeTabsHelpers.prettifySessionName(
                ClaudeTabsHelpers.extractJsonString(text, "name")
            )
            try {
                storage.activeSessions.writeOrUpdate(
                    sid = sid, cwd = cwd, pid = pid, lastSeen = now,
                    name = claudeName, // null → preserve existing cached name
                )
                seenSids.add(sid)
                alivePidToSid[pid] = sid
                sidToPid[sid] = pid
                c.everSeenAlive.add(sid)
                evictionTracker.recordAlive(sid)
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] writeOrUpdate failed for sid=$sid: ${e.message}")
            }
        }

        // Step 1b: claim tabs we don't yet hold a widget for — user-opened terminals where
        // someone typed `claude` (or `claude --resume`) by hand. Matching is by process
        // ancestry: the tab's shell PID must be an ancestor of the Claude PID. Matched
        // widgets land in ctx.spawnedWidgets, so naming and close detection treat them
        // exactly like plugin-spawned tabs from then on.
        if (alivePidToSid.isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                matchUnclaimedTabs(project, alivePidToSid.toMap())
            }
        }

        // Step 1c: record each tracked tab's current left-to-right position so a later
        // restore can rebuild the same order. Continuously refreshed, so re-arranging tabs
        // sticks across the next crash/restart.
        ApplicationManager.getApplication().invokeLater {
            recordTabOrder(project)
        }

        // Step 2: walk active-sessions/. Three cases:
        //   - Already touched in step 1 → alive.
        //   - pid=null (migration seed / not-yet-confirmed) → NEVER evict. Sit idle until
        //     spawn step 4 below succeeds or user manually clears via /tabs-clear.
        //   - pid set + verification fails → dead-strike (K=2).
        val activeEntries = storage.activeSessions.listAll()
        val nowAliveSids = mutableSetOf<String>().apply { addAll(seenSids) }
        val unconfirmedEntries = mutableListOf<ActiveSessionsStore.Entry>()
        for (entry in activeEntries) {
            if (entry.sid in seenSids) continue
            val recordedPid = entry.pid
            if (recordedPid == null) {
                // Unconfirmed (typical migration shape). Eligible for retry-spawn in step 4.
                unconfirmedEntries.add(entry)
                continue
            }
            val live = verifyAlive(recordedPid, entry.sid)
            if (live) {
                evictionTracker.recordAlive(entry.sid)
                c.everSeenAlive.add(entry.sid)
                sidToPid[entry.sid] = recordedPid
                nowAliveSids.add(entry.sid)
            } else {
                val shouldDemote = evictionTracker.recordDead(entry.sid)
                if (shouldDemote) {
                    // PROCESS DEATH IS NEVER A USER ACTION. It happens on every window reload,
                    // IDE restart, and crash — none of which mean "the user closed this tab".
                    // So the poll NEVER evicts: it only demotes to restore-pending (pid=null),
                    // preserving cached name/userName/ordinal, so reopening the project respawns
                    // it. The ONLY path that removes a session is the two-signal close detector
                    // (a real user gesture on the tab's X, confirmed by the process then dying).
                    // This is what makes reloads and crashes survivable.
                    storage.activeSessions.writeOrUpdate(entry.sid, entry.cwd, pid = null, lastSeen = now)
                    evictionTracker.forget(entry.sid)
                    LOG.info("[ClaudeTabs] sid=${entry.sid} process gone (cwd=${entry.cwd}) — demoted to restore-pending (never evicted on death)")
                }
            }
        }

        // Step 3b: self-heal user-closed. A session observed ALIVE this poll that is still in
        // the user-closed set was re-opened by the user (`claude --resume`) — clear the flag
        // (memory + disk) so it auto-restores from now on. Without this, a once-closed sid stays
        // suppressed forever and the user must keep resuming it by hand every restart.
        //
        // GATE: only a process that STARTED after this IDE did counts as re-open evidence. A
        // 1.x-era close never killed the claude process; such an orphan survives into the next
        // IDE session still 'alive', and healing on it would erase a deliberate close and
        // resurrect the tab. A genuine resume always launches a fresh process (start time >
        // IDE start); an orphan predates it. Unknown start time → conservative, no heal.
        if (nowAliveSids.isNotEmpty()) {
            val reopened = synchronized(c.userClosedSessions) {
                val hit = nowAliveSids.filter {
                    it in c.userClosedSessions && startedAfterIdeStart(sidToPid[it])
                }
                c.userClosedSessions.removeAll(hit.toSet())
                hit
            }
            for (sid in reopened) {
                try { storage.removeUserClosed(projectHash(project), sid) } catch (_: Exception) { }
                LOG.info("[ClaudeTabs] sid=$sid observed alive again — cleared user-closed (re-opened/resumed)")
            }
        }

        // Step 4: retry-spawn for unconfirmed (pid=null) entries that belong to this project.
        // The first spawn happens via performRestore on startup; this is the safety net for
        // spawns that didn't materialise (Terminal tool window not ready, etc.). Double-spawn
        // protection is [RestoreGuard.blocksRetrySpawn] (userClosed / everHadWidget /
        // everSeenAlive / pendingClose / spawnedWidgets) plus the per-sid attempt cap and
        // [RESPAWN_COOLDOWN_MS].
        val basePath = project.basePath
        if (!basePath.isNullOrBlank() && unconfirmedEntries.isNotEmpty()) {
            val userClosed = synchronized(c.userClosedSessions) { c.userClosedSessions.toSet() }
            for (entry in unconfirmedEntries) {
                // Resurrection guard (pure, unit-tested in [RestoreGuard]). Retry-spawn exists ONLY
                // to recover a restore SEED whose startup spawn silently failed — never to bring
                // back a session the user closed or that crashed. A sid that was user-closed,
                // mid-close, already has a live tab, ever held a widget, OR was ever seen alive this
                // session is NOT a failed seed and must be skipped. everSeenAlive is the piece that
                // covers a user-opened tab we never claimed a widget for — without it that tab
                // demotes to pid=null and resurrects ~1 min after the user closes it.
                if (RestoreGuard.blocksRetrySpawn(
                        entry.sid,
                        userClosed = userClosed,
                        everHadWidget = c.everHadWidget,
                        everSeenAlive = c.everSeenAlive,
                        pendingClose = c.pendingClose.keys,
                        spawnedWidgets = c.spawnedWidgets.keys,
                    )
                ) continue
                if (!claimsCwd(entry.cwd, project)) continue
                if (!ClaudeTabsHelpers.hasTranscriptAnywhere(storage.projectsDir, entry.sid, entry.cwd)) continue
                val attempts = c.restoreSpawnAttempts[entry.sid] ?: 0
                if (attempts >= MAX_SPAWN_ATTEMPTS) continue
                val lastAttempt = c.restoreSpawnLastAttempt[entry.sid]
                if (lastAttempt != null && (now - lastAttempt) < RESPAWN_COOLDOWN_MS) continue
                c.restoreSpawnLastAttempt[entry.sid] = now
                c.restoreSpawnAttempts[entry.sid] = attempts + 1
                // Same serialization as performRestore: this spawn launches a `claude --resume`
                // whose startup write of ~/.claude.json must not overlap any other resume's.
                resumeSpawnMutex.withLock {
                    withContext(Dispatchers.Main) {
                        if (!project.isDisposed) spawnRestoreTab(project, entry)
                    }
                    delay(RESTORE_STAGGER_MS)
                    awaitConfigSettled()
                }
                LOG.info("[ClaudeTabs] Retry-spawn (pid=null entry) sid=${entry.sid} cwd=${entry.cwd} attempt=${attempts + 1}/$MAX_SPAWN_ATTEMPTS")
                if (attempts + 1 >= MAX_SPAWN_ATTEMPTS) {
                    LOG.warn("[ClaudeTabs] sid=${entry.sid} reached spawn-attempt cap — leaving entry for manual recovery (claude --resume ${entry.sid})")
                }
            }
        }

        // Step 3: confirm pending-close signal-2.
        if (c.pendingClose.isNotEmpty()) {
            val confirmResult = TwoSignalCloseDetector.confirmPending(
                pendingClose = c.pendingClose.toMap(),
                aliveSids = nowAliveSids,
                now = now,
            )
            for (sid in confirmResult.confirmed) {
                c.pendingClose.remove(sid)
                synchronized(c.userClosedSessions) { c.userClosedSessions.add(sid) }
                try { storage.addUserClosed(projectHash(project), sid) } catch (_: Exception) { }
                // Evict NOW (not via dead-strikes later) so the backlog entry carries the
                // names — an accidental close stays recoverable via /tabs-history.
                try { storage.activeSessions.read(sid)?.let { evict(it) } } catch (_: Exception) { }
                // Drop per-tab state so the title controller stops tracking the widget.
                c.spawnedWidgets.remove(sid)
                c.lastAppliedTitle.remove(sid)
                c.contentToSid.entries.removeIf { it.value == sid }
                LOG.info("[ClaudeTabs][close] Signal 2 confirmed for sid=$sid — recorded user-closed, moved to backlog")
            }
            for (sid in confirmResult.expired) {
                c.pendingClose.remove(sid)
                // A vetoed close never reaches contentRemoved — drop its stale kill-intent too.
                c.pendingKill.entries.removeIf { it.value == sid }
                LOG.info("[ClaudeTabs][close] Pending sid=$sid expired (no process-death within ${PENDING_CLOSE_EXPIRY_MS}ms) — dropping")
            }
        }

        // Step 3c: attribute UNCLAIMED-tab closes (the Rule 2 fix). An X on a tab we never claimed
        // reaches the listener with no sid and only records a timestamp token. Here we tie each
        // pending token to a project-owned session that was alive last poll and is gone now — the
        // vanisher IS the closed tab. Crash-safe: a crash fires no token, and more-vanished-than-
        // tokens attributes nothing (see TwoSignalCloseDetector.attributeCloses).
        val cwdBySid = activeEntries.associate { it.sid to it.cwd }
        val aliveProjectSids = nowAliveSids.filter { sid ->
            cwdBySid[sid]?.let { claimsCwd(it, project) } == true
        }.toSet()
        val pendingTokens = synchronized(c.unattributedCloses) { c.unattributedCloses.toList() }
        if (pendingTokens.isNotEmpty()) {
            val vanished = c.lastAliveProjectSids.filter { sid ->
                sid !in aliveProjectSids &&
                    (storage.activeSessions.read(sid)?.let { claimsCwd(it.cwd, project) } == true)
            }
            LOG.info("[ClaudeTabs][close] Attribution check: ${pendingTokens.size} unattributed close token(s), " +
                "prevAliveProject=${c.lastAliveProjectSids.size}, nowAliveProject=${aliveProjectSids.size}, " +
                "vanished=${vanished.map { it.take(8) }}")
            val attribution = TwoSignalCloseDetector.attributeCloses(
                unattributedCloses = pendingTokens,
                vanished = vanished,
                now = now,
                expiryMs = PENDING_CLOSE_EXPIRY_MS,
            )
            synchronized(c.unattributedCloses) {
                c.unattributedCloses.clear()
                c.unattributedCloses.addAll(attribution.remainingCloses)
            }
            if (attribution.closedSids.isEmpty() && vanished.isNotEmpty()) {
                LOG.info("[ClaudeTabs][close] Attribution held off — ${vanished.size} vanished but only " +
                    "${pendingTokens.size} close token(s); a crash may have coincided, not attributing (protects Rule 4)")
            }
            for (sid in attribution.closedSids) {
                synchronized(c.userClosedSessions) { c.userClosedSessions.add(sid) }
                try { storage.addUserClosed(projectHash(project), sid) } catch (_: Exception) { }
                try { storage.activeSessions.read(sid)?.let { evict(it) } } catch (_: Exception) { }
                c.spawnedWidgets.remove(sid)
                c.lastAppliedTitle.remove(sid)
                c.contentToSid.entries.removeIf { it.value == sid }
                evictionTracker.forget(sid)
                LOG.info("[ClaudeTabs][close] Attributed unclaimed-tab close → sid=$sid — recorded user-closed, evicted to backlog (it will NOT reopen)")
            }
        } else {
            // Prune any expired tokens even when nothing vanished, so they don't linger.
            synchronized(c.unattributedCloses) {
                val kept = c.unattributedCloses.filter { now - it <= PENDING_CLOSE_EXPIRY_MS }
                if (kept.size != c.unattributedCloses.size) {
                    c.unattributedCloses.clear(); c.unattributedCloses.addAll(kept)
                }
            }
        }
        // Remember this poll's alive project set for next poll's vanish diff.
        c.lastAliveProjectSids = aliveProjectSids

        LOG.debug("[ClaudeTabs] poll done: aliveSids=${nowAliveSids.size} aliveProject=${aliveProjectSids.size} " +
            "pendingClose=${c.pendingClose.size} unattributedCloses=${c.unattributedCloses.size}")
    }

    /**
     * Find terminal tabs hosting Claude sessions we don't yet hold a widget reference for
     * (user-opened terminals where `claude` was typed by hand) and claim them.
     *
     * Must run on EDT (ContentManager access). All reflective PID extraction is inside
     * [TabSessionMatcher] with null-on-failure semantics — an unmatchable tab just stays
     * unnamed, nothing else is affected.
     */
    private fun matchUnclaimedTabs(project: Project, alivePidToSid: Map<Long, String>) {
        try {
            if (project.isDisposed) return
            val c = ctx(project)
            val unclaimed = alivePidToSid.filterValues { sid -> !c.spawnedWidgets.containsKey(sid) }
            if (unclaimed.isEmpty()) return
            LOG.debug("[ClaudeTabs][claim] ${unclaimed.size} alive session(s) need a widget: ${unclaimed.values.map { it.take(8) }}")
            val tw = TerminalToolWindowManager.getInstance(project).toolWindow ?: return
            // Tabs with no sid yet AND whose widget we don't already hold — the claim candidates.
            val unclaimedContents = tw.contentManager.contents.filter { content ->
                !c.contentToSid.containsKey(content)
            }
            for (content in unclaimedContents) {
                val widget = try { TerminalToolWindowManager.findWidgetByContent(content) } catch (_: Exception) { null } ?: continue
                if (c.spawnedWidgets.containsValue(widget)) continue
                val shellPid = TabSessionMatcher.extractPidFromWidget(widget) ?: continue
                for ((claudePid, sid) in unclaimed) {
                    if (c.spawnedWidgets.containsKey(sid)) continue // claimed earlier in this loop
                    if (TabSessionMatcher.isHostedBy(claudePid, shellPid, TabSessionMatcher::osParentOf)) {
                        c.spawnedWidgets[sid] = widget
                        c.everHadWidget.add(sid)
                        c.contentToSid[content] = sid
                        LOG.info("[ClaudeTabs] Matched user-started tab (shellPid=$shellPid) to sid=$sid")
                        break
                    }
                }
            }

            // Pass 1.5: /tab title handshake. tab-name.js pokes `✳ <name>` into its own tab's
            // tty right after persisting userName, so a tab showing exactly one unclaimed
            // session's userName is that session's tab — the escape can only have travelled
            // through that tab's pty. Works when both the PID and cwd digs fail.
            run {
                val unclaimedNow = unclaimed.values.filter { !c.spawnedWidgets.containsKey(it) }
                if (unclaimedNow.isEmpty()) return@run
                val sidUserNames = unclaimedNow.associateWith { sid -> storage.activeSessions.read(sid)?.userName }
                if (sidUserNames.values.all { it.isNullOrBlank() }) return@run
                val tabCandidates = tw.contentManager.contents.mapNotNull { content ->
                    if (c.contentToSid.containsKey(content)) return@mapNotNull null
                    val w = try { TerminalToolWindowManager.findWidgetByContent(content) } catch (_: Exception) { null }
                        ?: return@mapNotNull null
                    if (c.spawnedWidgets.containsValue(w)) return@mapNotNull null
                    val title = try {
                        w.terminalTitle.let { it.userDefinedTitle ?: it.applicationTitle }
                    } catch (_: Throwable) { null }
                    Triple(content, w, title)
                }
                val claims = TabSessionMatcher.matchTitlesToSessions(sidUserNames, tabCandidates.map { it.third })
                for ((tabIdx, sid) in claims) {
                    val (content, widget, title) = tabCandidates[tabIdx]
                    c.spawnedWidgets[sid] = widget
                    c.everHadWidget.add(sid)
                    c.contentToSid[content] = sid
                    LOG.info("[ClaudeTabs] Title handshake claimed tab (title=$title) → sid=$sid")
                }
            }

            // Pass 2: cwd-based claim. The PID dig can fail on the reworked terminal; the PUBLIC
            // getCurrentDirectory() accessor is more reliable. Claim only when a cwd has EXACTLY
            // ONE still-unclaimed alive Claude session AND EXACTLY ONE still-unclaimed tab — then
            // the mapping is unambiguous and a stray plain-shell tab in the same folder can't be
            // mis-grabbed (two unclaimed tabs → skip).
            val sessionsByCwd = unclaimed.values
                .filter { !c.spawnedWidgets.containsKey(it) }
                .mapNotNull { sid -> storage.activeSessions.read(sid)?.let { sid to TabSessionMatcher.normalizeCwd(it.cwd) } }
                .groupBy({ it.second }, { it.first })
            if (sessionsByCwd.isNotEmpty()) {
                val tabsByCwd = HashMap<String, MutableList<Pair<Content, TerminalWidget>>>()
                // The 1:1 claim is only unambiguous if we could read EVERY unclaimed tab's cwd.
                // If any extraction failed, the invisible tab might be the session's real tab and
                // the one visible tab a plain shell in the same folder — claiming it would attach
                // the session to the wrong tab (and closing that shell would then kill the real
                // Claude). Asymmetric extraction failure is realistic: cwd probing is best-effort
                // on the reworked terminal, which is why this pass exists at all.
                var unreadableTabs = 0
                for (content in tw.contentManager.contents) {
                    if (c.contentToSid.containsKey(content)) continue
                    val widget = try { TerminalToolWindowManager.findWidgetByContent(content) } catch (_: Exception) { null }
                    if (widget == null) { unreadableTabs++; continue }
                    if (c.spawnedWidgets.containsValue(widget)) continue
                    val tabCwd = TabSessionMatcher.extractCwdFromWidget(widget)?.let { TabSessionMatcher.normalizeCwd(it) }
                    if (tabCwd == null) { unreadableTabs++; continue }
                    tabsByCwd.getOrPut(tabCwd) { mutableListOf() }.add(content to widget)
                }
                if (unreadableTabs == 0) {
                    for ((cwd, sids) in sessionsByCwd) {
                        val tabs = tabsByCwd[cwd] ?: continue
                        if (sids.size == 1 && tabs.size == 1 && !c.spawnedWidgets.containsKey(sids[0])) {
                            val sid = sids[0]
                            val (content, widget) = tabs[0]
                            c.spawnedWidgets[sid] = widget
                            c.everHadWidget.add(sid)
                            c.contentToSid[content] = sid
                            LOG.info("[ClaudeTabs] cwd 1:1 claimed unclaimed tab (cwd=$cwd) → sid=$sid")
                        }
                    }
                } else {
                    LOG.debug("[ClaudeTabs] cwd 1:1 pass skipped — $unreadableTabs unreadable tab(s) make the mapping ambiguous")
                }
            }

            // Pass 3 — narrow 1:1 fallback: when both digs failed for the last hold-outs but there
            // is EXACTLY ONE unclaimed session for this project AND EXACTLY ONE still-unclaimed
            // Claude-looking tab, the mapping is unambiguous — link them. Guarded to a single
            // candidate on each side so we can never assign a session to the wrong tab.
            val remainingSids = unclaimed.values.filter { sid ->
                !c.spawnedWidgets.containsKey(sid) && (storage.activeSessions.read(sid)?.let { claimsCwd(it.cwd, project) } == true)
            }
            if (remainingSids.size == 1) {
                // In-IDE proof: if the session's process ancestry reaches THIS IDE process, the
                // session definitively lives in one of this window's tabs — so a plain "Local"
                // title is no longer disqualifying (a hand-resumed chat keeps the shell's default
                // title when the terminal swallows Claude's title escapes). Without the proof,
                // keep the strict Claude-looking filter: the session might be in an external
                // terminal, and grabbing a shell tab for it would hijack the wrong tab.
                val claudePid = unclaimed.entries.firstOrNull { it.value == remainingSids[0] }?.key
                val inThisIde = claudePid != null && TabSessionMatcher.isHostedBy(
                    claudePid, ProcessHandle.current().pid(), TabSessionMatcher::osParentOf,
                )
                val candidates = tw.contentManager.contents.filter { content ->
                    if (c.contentToSid.containsKey(content)) return@filter false
                    val w = try { TerminalToolWindowManager.findWidgetByContent(content) } catch (_: Exception) { null } ?: return@filter false
                    if (c.spawnedWidgets.containsValue(w)) return@filter false
                    if (inThisIde) return@filter true
                    val title = try {
                        w.terminalTitle.userDefinedTitle ?: w.terminalTitle.applicationTitle
                    } catch (_: Throwable) { null } ?: ""
                    // A Claude tab shows our "✳ …", or Claude's own "Claude"/"Claude Code" — never a
                    // plain shell ("Local"/"pwsh"), which the generic-name check rejects.
                    TitleModel.isOurFormat(title) || title.contains("Claude", ignoreCase = true)
                }
                if (candidates.size == 1) {
                    val content = candidates[0]
                    val widget = TerminalToolWindowManager.findWidgetByContent(content)
                    if (widget != null) {
                        val sid = remainingSids[0]
                        c.spawnedWidgets[sid] = widget
                        c.everHadWidget.add(sid)
                        c.contentToSid[content] = sid
                        LOG.info("[ClaudeTabs] 1:1 fallback claimed unmatchable tab → sid=$sid (PID dig failed)")
                    }
                }
            }

            // Post-pass diagnostics: any session STILL without a widget can't have its X detected
            // by the sid path — its close relies on the unattributed-close attribution (step 3c).
            val stillUnclaimed = unclaimed.values.filter { !c.spawnedWidgets.containsKey(it) }
            if (stillUnclaimed.isNotEmpty()) {
                LOG.debug("[ClaudeTabs][claim] ${stillUnclaimed.size} session(s) STILL unclaimed after all passes " +
                    "(all reflection digs failed): ${stillUnclaimed.map { it.take(8) }} — their X-close will rely on vanish-attribution")
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] matchUnclaimedTabs failed: ${e.message}")
        }
    }

    /**
     * Record the left-to-right position of every tracked Claude tab into its per-sid entry's
     * `ordinal`, so [performRestore] can rebuild the same order after a crash/restart. Runs on
     * EDT (ContentManager access). Cheap: only writes when a tab's position actually changed.
     */
    private fun recordTabOrder(project: Project) {
        try {
            if (project.isDisposed) return
            val c = ctx(project)
            val tw = TerminalToolWindowManager.getInstance(project).toolWindow ?: return
            val contents = tw.contentManager.contents

            // Record ordinals for tabs we can map to a sid (drives same-order restore).
            if (c.spawnedWidgets.isNotEmpty()) {
                contents.forEachIndexed { index, content ->
                    val sid = c.contentToSid[content] ?: run {
                        val w = try { TerminalToolWindowManager.findWidgetByContent(content) } catch (_: Exception) { null }
                        if (w != null) c.spawnedWidgets.entries.firstOrNull { it.value === w }?.key else null
                    } ?: return@forEachIndexed
                    val entry = storage.activeSessions.read(sid) ?: return@forEachIndexed
                    if (entry.ordinal != index) {
                        storage.activeSessions.writeOrUpdate(
                            entry.sid, entry.cwd, entry.pid, entry.lastSeen, ordinal = index,
                        )
                    }
                }
            }

            // Poll-based close detection (event-independent — the reworked terminal may never fire
            // contentRemoveQuery). Count Claude-looking tabs and remember their stripped titles; a DROP
            // while NOT tearing down means the user closed a tab. Claude-looking = we already map it,
            // OR its title is our "✳ …" format, OR it contains "Claude" — so a plain shell close makes
            // no spurious signal.
            val presentTitles = mutableSetOf<String>()
            var claudeTabs = 0
            for (content in contents) {
                val tracked = c.contentToSid.containsKey(content)
                val w = try { TerminalToolWindowManager.findWidgetByContent(content) } catch (_: Exception) { null }
                val rawTitle = try {
                    w?.terminalTitle?.let { it.userDefinedTitle ?: it.applicationTitle }
                } catch (_: Throwable) { null } ?: ""
                if (!tracked && !TitleModel.isOurFormat(rawTitle) && !rawTitle.contains("Claude", ignoreCase = true)) continue
                claudeTabs++
                val stripped = TitleModel.stripGlyph(rawTitle).trim()
                if (stripped.isNotEmpty()) presentTitles.add(stripped)
            }
            val prev = c.lastClaudeTabCount
            val teardown = c.projectClosing || appClosing || try { project.isDisposed } catch (_: Throwable) { false }
            LOG.debug("[ClaudeTabs][tabs] project=${project.name} totalTabs=${contents.size} claudeTabs=$claudeTabs " +
                "(prev=$prev) teardown=$teardown titles=$presentTitles")
            val drop = if (prev >= 0) prev - claudeTabs else 0
            when {
                prev < 0 || drop <= 0 -> { /* first sample, or count rose/steady — nothing closed */ }

                // Genuine single-tab close: the count fell by exactly ONE and OTHER Claude tabs
                // remain. A user closes tabs one at a time, so this is the only shape that is
                // unambiguously a user close.
                drop == 1 && claudeTabs > 0 && !teardown -> {
                    val vanishedTitles = c.prevClaudeTitles - presentTitles
                    // Immediate resolution: if the ONE title that disappeared uniquely names a
                    // session, persist the close NOW (userClosed + kill + evict). This survives an
                    // instant Rider quit — the fix for "X a tab then close Rider within 5s → the
                    // close was lost". If the title is unresolvable (unnamed tab / ambiguous), fall
                    // back to a token that the next poll's vanish-attribution resolves.
                    val sid = if (vanishedTitles.size == 1)
                        resolveClosedSidByTitle(project, vanishedTitles.first()) else null
                    if (sid != null) {
                        recordUserClose(project, sid, "title '${vanishedTitles.first()}'")
                    } else {
                        c.unattributedCloses.add(System.currentTimeMillis())
                        LOG.info("[ClaudeTabs][close] Single close $prev→$claudeTabs, title unresolved (vanished=$vanishedTitles) — " +
                            "recorded token for vanish-attribution (pending ${c.unattributedCloses.size})")
                    }
                }

                // Everything else — a drop to ZERO, a multi-tab drop, or any drop while teardown is
                // flagged — is a shutdown / window-close / tool-window teardown / transient glitch, NOT
                // the user closing individual tabs. Recording tokens here is the Rule 4 hazard that
                // false-closed sessions on restart. Record NOTHING, and CLEAR any pending tokens so a
                // slow shutdown's later process deaths can't be matched to stale tokens.
                else -> {
                    val reason = when {
                        teardown -> "teardown flag set"
                        claudeTabs == 0 -> "dropped to ZERO (all tabs gone at once = shutdown/tool-window close)"
                        else -> "multi-tab drop ($drop at once — not one-at-a-time user closes)"
                    }
                    synchronized(c.unattributedCloses) {
                        val had = c.unattributedCloses.size
                        c.unattributedCloses.clear()
                        LOG.info("[ClaudeTabs][close] Claude tab count dropped $prev→$claudeTabs — IGNORED ($reason); " +
                            "no tokens recorded, cleared $had pending token(s) (protects Rule 4: restart/crash must not close tabs)")
                    }
                }
            }
            c.lastClaudeTabCount = claudeTabs
            c.prevClaudeTitles = presentTitles
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] recordTabOrder failed: ${e.message}")
        }
    }

    /** True when [pid]'s process started after this IDE did — the evidence bar for treating an
     *  alive process as a deliberate user re-open in step 3b. Unknown/missing → false (no heal). */
    private fun startedAfterIdeStart(pid: Long?): Boolean {
        if (pid == null) return false
        return try {
            val start = ProcessHandle.of(pid).orElse(null)?.info()?.startInstant()?.orElse(null)
                ?: return false
            start.toEpochMilli() >= IDE_START_MS
        } catch (_: Exception) { false }
    }

    /** PID + sid cross-check. Returns true only if `<pid>.json` still exists AND its
     *  sessionId matches [expectedSid]. Handles the PID-reuse case. */
    private fun verifyAlive(pid: Long, expectedSid: String): Boolean {
        val pidFile = File(storage.sessionsDir, "$pid.json")
        if (!pidFile.exists()) return false
        val text = try { pidFile.readText() } catch (_: Exception) { return false }
        val actualSid = ClaudeTabsHelpers.extractJsonString(text, "sessionId") ?: return false
        if (actualSid != expectedSid) return false
        // Process actually alive at OS level too.
        return try {
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        } catch (_: Exception) { false }
    }

    /** Evict [entry]: delete the per-sid file and prepend to the backlog. */
    private fun evict(entry: ActiveSessionsStore.Entry) {
        try {
            storage.activeSessions.delete(entry.sid)
            evictionTracker.forget(entry.sid)
            storage.backlog.prepend(
                SessionBacklog.Entry(
                    sid = entry.sid,
                    cwd = entry.cwd,
                    name = entry.name,
                    evictedAt = System.currentTimeMillis(),
                    userName = entry.userName,
                )
            )
            LOG.info("[ClaudeTabs] Evicted sid=${entry.sid} (cwd=${entry.cwd}) → backlog")
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] evict failed for sid=${entry.sid}: ${e.message}")
        }
    }

    /**
     * Persist a confirmed user close for [sid] IMMEDIATELY and durably: record userClosed (memory +
     * disk), evict to the backlog, drop per-tab tracking, and kill the (possibly orphaned) process
     * so it can't be re-detected alive and un-closed by the step-3b self-heal. Because the disk
     * write happens here — not on a later poll — the close survives an instant Rider quit. Used by
     * the immediate title-based close path; the process kill runs off the EDT.
     */
    private fun recordUserClose(project: Project, sid: String, reason: String) {
        val c = ctx(project)
        synchronized(c.userClosedSessions) { c.userClosedSessions.add(sid) }
        try { storage.addUserClosed(projectHash(project), sid) } catch (_: Exception) { }
        try { storage.activeSessions.read(sid)?.let { evict(it) } } catch (_: Exception) { }
        c.spawnedWidgets.remove(sid)
        c.lastAppliedTitle.remove(sid)
        c.contentToSid.entries.removeIf { it.value == sid }
        c.pendingClose.remove(sid)
        evictionTracker.forget(sid)
        ApplicationManager.getApplication().executeOnPooledThread { killClaudeProcessTree(sid) }
        LOG.info("[ClaudeTabs][close] User close recorded ($reason) → sid=$sid — userClosed persisted + evicted + process killed (durable, will NOT reopen)")
    }

    /** Map the [vanishedTitle] of a just-closed tab back to the single session it named, among the
     *  sessions this project owns. Display name = explicit userName, else the prettified topic name.
     *  Null when unnamed or ambiguous (caller falls back to vanish-attribution). */
    private fun resolveClosedSidByTitle(project: Project, vanishedTitle: String): String? {
        val candidates = storage.activeSessions.listAll()
            .filter { claimsCwd(it.cwd, project) }
            .associate { it.sid to (it.userName?.takeIf { n -> n.isNotBlank() } ?: it.name) }
        return TabSessionMatcher.resolveUniqueByDisplayName(candidates, vanishedTitle)
    }

    // ──────────────────────────────────────────────────────────────────
    // Restore — spawn one tab per saved session
    // ──────────────────────────────────────────────────────────────────

    /**
     * On project open, scan `active-sessions/` and spawn a fresh terminal tab running
     * `claude --resume <sid>` for each entry whose cwd belongs to this project (or a
     * sibling worktree) and whose transcript still exists and which the user hasn't closed.
     */
    internal suspend fun performRestore(project: Project) {
        val basePath = project.basePath
        if (basePath.isNullOrBlank()) return
        val c = ctx(project)
        val userClosed = synchronized(c.userClosedSessions) { c.userClosedSessions.toSet() }
        val entries = storage.activeSessions.listAll()
        val toRestore = entries.filter { e ->
            claimsCwd(e.cwd, project)
                && e.sid !in userClosed
                && ClaudeTabsHelpers.hasTranscriptAnywhere(storage.projectsDir, e.sid, e.cwd)
        }
            // Spawn in saved left-to-right order so the user's arrangement survives restart;
            // entries with no recorded position (ordinal=null) go last, then by sid for a
            // stable deterministic order.
            .sortedWith(compareBy({ it.ordinal ?: Int.MAX_VALUE }, { it.sid }))
        // RESTORE CONTRACT (see project memory `tab-restore-contract`): a tab reopens 100% of
        // the time unless the user pressed its X. Age and count are IRRELEVANT — a chat left open
        // for weeks is the core use case, not an outlier. So there is deliberately NO staleness
        // window and NO herd cap here: both would retire genuinely-open tabs and violate the
        // contract. Accumulation is prevented at the SOURCE (reliable X-close detection evicts;
        // nothing else does), not by gating restore. The ~/.claude.json thundering-herd risk is
        // handled by serialized spawns below (resumeSpawnMutex + awaitConfigSettled), not by capping.
        if (toRestore.isEmpty()) {
            LOG.info("[ClaudeTabs] Restore: nothing to spawn")
            return
        }
        LOG.info("[ClaudeTabs] Restore: spawning ${toRestore.size} tab(s), staggered ${RESTORE_STAGGER_MS}ms apart")
        val spawned = mutableListOf<ActiveSessionsStore.Entry>()
        // Serialize the spawns: each opens a `claude --resume` that writes Claude's shared
        // ~/.claude.json on startup — a non-atomic write that concurrent starts corrupt. The
        // JVM-global mutex serializes across project WINDOWS too; within the lock, spawn on the
        // EDT, give Claude a beat to start its write, then wait for the file to settle.
        for (e in toRestore) {
            // No ghost-decay retirement: the `hasTranscriptAnywhere` filter above already excludes
            // seeds that literally cannot resume (no transcript). Everything reaching here HAS a
            // transcript, so a resume that keeps failing is environmental (e.g. ~/.claude.json
            // corruption, which ConfigGuard heals) — NOT a closed tab. Per the restore contract we
            // keep restoring it every launch until it comes alive; only a user X-close evicts.
            // restoreAttempts is still tracked (reset on alive) for diagnostics only.
            storage.activeSessions.bumpRestoreAttempts(e.sid)
            resumeSpawnMutex.withLock {
                withContext(Dispatchers.Main) {
                    if (project.isDisposed) return@withContext
                    c.restoreSpawnLastAttempt[e.sid] = System.currentTimeMillis()
                    c.restoreSpawnAttempts[e.sid] = (c.restoreSpawnAttempts[e.sid] ?: 0) + 1
                    spawnRestoreTab(project, e)
                    spawned.add(e)
                }
                if (project.isDisposed) return
                delay(RESTORE_STAGGER_MS)   // let this Claude begin its ~/.claude.json write…
                awaitConfigSettled()        // …then wait until it's done before releasing the lock.
            }
        }
        writeLastRestoreSnapshot(project, spawned)
    }

    /**
     * Suspend until `~/.claude.json` has stopped changing — i.e. the resuming Claude has finished
     * its startup write — so the NEXT `claude --resume` doesn't race it. Reads only the file's
     * mtime+size (never writes). Returns as soon as the signature holds steady for a short window,
     * or after [CONFIG_SETTLE_CAP_MS] regardless, so a slow/hung start can't stall restore. This is
     * what turns the stagger into true serialization and stops the thundering-herd corruption.
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
                if (now - stableSince >= 350L) return   // unchanged for 350ms → write finished
            } else {
                lastSig = sig
                stableSince = 0L
            }
            delay(120L)
        }
    }

    /** Spawn a fresh terminal tab via the public createShellWidget API — the same call the
     *  official Claude Code plugin's toolbar button uses — and send the resume command.
     *
     *  The tab is created with the composed `✳ <name>` title (cached user name > cached
     *  topic > "Claude") so it NEVER shows "Local", and recorded in
     *  [ProjectCtx.lastAppliedTitle]; from then on [TitleController] enforces/updates it. */
    private fun spawnRestoreTab(project: Project, e: ActiveSessionsStore.Entry) {
        try {
            val initialTitle = TitleModel.compose(
                TitleModel.resolveDisplayName(e.userName, null, e.name),
                busy = false, frameIndex = 0,
            )
            val mgr = TerminalToolWindowManager.getInstance(project)
            val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Terminal")
            tw?.activate(null, false, false)
            val widget = mgr.createShellWidget(
                e.cwd,        // workingDirectory
                initialTitle, // never null — "Local" must not appear even for one frame
                // requestFocus=false — restoring N tabs must not steal focus.
                false,
                // deferSessionStartUntilUiShown=false — start eagerly so claude launches and
                // the poll loop can confirm its PID; deferred tabs would sit unstarted in the
                // background (pid stays null) and trip the retry-spawn path into duplicates.
                false,
            )
            val c = ctx(project)
            c.lastAppliedTitle[e.sid] = initialTitle
            c.spawnedWidgets[e.sid] = widget
            c.everHadWidget.add(e.sid)
            try {
                // The widget's content gets created asynchronously; capture-on-EDT later.
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val content = findContentForWidget(project, widget)
                        if (content != null) c.contentToSid[content] = e.sid
                    } catch (_: Exception) { /* best effort */ }
                }
            } catch (_: Exception) { }
            val cmd = buildString {
                append("claude --resume ${e.sid}")
            }
            ApplicationManager.getApplication().invokeLater {
                try {
                    widget.sendCommandToExecute(cmd)
                } catch (ex: Exception) {
                    LOG.warn("[ClaudeTabs] sendCommandToExecute failed for sid=${e.sid}: ${ex.message}")
                }
            }
            LOG.info("[ClaudeTabs] Spawned tab for sid=${e.sid} cwd=${e.cwd}")
        } catch (ex: Throwable) {
            LOG.warn("[ClaudeTabs] spawnRestoreTab failed for sid=${e.sid}: ${ex.message}")
        }
    }

    /** Walk the terminal tool window's ContentManager looking for the Content backing [widget]. */
    private fun findContentForWidget(project: Project, widget: TerminalWidget): Content? {
        val tw = TerminalToolWindowManager.getInstance(project).toolWindow ?: return null
        val cm = tw.contentManager
        for (c in cm.contents) {
            try {
                val w = TerminalToolWindowManager.findWidgetByContent(c)
                if (w === widget) return c
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    /** Drop a snapshot to `last-restore.json` so `/tabs-status` can show what was restored. */
    private fun writeLastRestoreSnapshot(project: Project, restored: List<ActiveSessionsStore.Entry>) {
        try {
            val out = File(storage.stateDir, "last-restore.json")
            out.parentFile?.mkdirs()
            val sessions = restored.joinToString(",") { e ->
                val sid = ClaudeTabsHelpers.esc(e.sid)
                val cwd = ClaudeTabsHelpers.esc(e.cwd)
                """{"sid":"$sid","cwd":"$cwd"}"""
            }
            val projectName = ClaudeTabsHelpers.esc(project.name)
            val json = """{"restoredAt":${System.currentTimeMillis()},"projectName":"$projectName","count":${restored.size},"sessions":[$sessions]}"""
            out.writeText(json)
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] last-restore.json write failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Close detection (two-signal)
    // ──────────────────────────────────────────────────────────────────

    private fun installCloseListener(project: Project) {
        try {
            val tw = TerminalToolWindowManager.getInstance(project).toolWindow
            val cmgr = tw?.contentManager ?: return
            // Debounced, EDT-threaded trigger for the close-detection pass. `contentRemoved` fires
            // per removed tab (even when contentRemoveQuery doesn't); each firing reschedules this,
            // so a shutdown burst coalesces into ONE pass ~CLOSE_DEBOUNCE_MS after the last removal
            // (which sees "dropped to zero → ignore"), while a lone close runs sub-second.
            val closeDebounce = com.intellij.util.Alarm(
                com.intellij.util.Alarm.ThreadToUse.SWING_THREAD, project as Disposable,
            )
            val cmListener = object : com.intellij.ui.content.ContentManagerListener {
                override fun contentRemoveQuery(event: com.intellij.ui.content.ContentManagerEvent) {
                    val c = ctx(project)
                    val content = event.content
                    val displayName = try { content.displayName ?: "?" } catch (_: Exception) { "?" }
                    val isTemporary = try {
                        content.getUserData(Content.TEMPORARY_REMOVED_KEY) == true
                    } catch (_: Throwable) { false }
                    val capturedWidget = try {
                        TerminalToolWindowManager.findWidgetByContent(content)
                    } catch (_: Exception) { null }
                    val widgetSid = if (capturedWidget != null) {
                        c.spawnedWidgets.entries.firstOrNull { (_, w) -> w === capturedWidget }?.key
                    } else null
                    val sid = c.contentToSid[content] ?: widgetSid
                    // appClosing is set by AppLifecycleListener.appWillBeClosed at the very start
                    // of an IDE quit/restart (e.g. a plugin-install restart). project.isDisposed +
                    // the projectClosing flag catch this single window closing. Any of them means
                    // the removal is teardown, NOT a user clicking X on one tab.
                    val appExiting = appClosing
                    val projectClosing = c.projectClosing || try { project.isDisposed } catch (_: Throwable) { false }
                    LOG.info("[ClaudeTabs][close] contentRemoveQuery FIRED tab='$displayName' " +
                        "isTemporary=$isTemporary appExiting=$appExiting projectClosing=$projectClosing " +
                        "widgetFound=${capturedWidget != null} sid=${sid?.take(8) ?: "null"}")
                    when (val d = TwoSignalCloseDetector.decideOnRemoveQuery(
                        projectClosing = projectClosing,
                        isTemporary = isTemporary,
                        sid = sid,
                        appExiting = appExiting,
                    )) {
                        TwoSignalCloseDetector.Signal1.SkipAppExiting ->
                            LOG.info("[ClaudeTabs][close] SKIP (appExiting) tab='$displayName' — IDE restart/quit")
                        TwoSignalCloseDetector.Signal1.SkipProjectClosing ->
                            LOG.info("[ClaudeTabs][close] SKIP (projectClosing) tab='$displayName' — window teardown")
                        TwoSignalCloseDetector.Signal1.SkipTemporary ->
                            LOG.info("[ClaudeTabs][close] SKIP (temporary) tab='$displayName' — drag/split/reorder, not a close")
                        TwoSignalCloseDetector.Signal1.SkipNoSid -> {
                            // A genuine single-tab close (not teardown/drag) that we could NOT map to a
                            // session — an unclaimed "Local"/resume tab on the reworked terminal. Record a
                            // close token; the poll attributes it to whichever project session vanishes next
                            // (see step 3c). Without this, an X on an unclaimed tab is silently lost and the
                            // session wrongly reopens.
                            c.unattributedCloses.add(System.currentTimeMillis())
                            LOG.info("[ClaudeTabs][close] Signal 1 for UNCLAIMED tab='$displayName' — recorded unattributed close, awaiting a vanishing session")
                        }
                        is TwoSignalCloseDetector.Signal1.AddToPending -> {
                            c.pendingClose[d.sid] = System.currentTimeMillis()
                            c.contentToSid.remove(content)
                            // Record intent only — contentRemoveQuery is a veto-able QUESTION
                            // (Rider's own "terminate?" dialog can cancel the close). The kill
                            // fires from contentRemoved below, once the removal has COMMITTED.
                            c.pendingKill[content] = d.sid
                            LOG.info("[ClaudeTabs][close] Signal 1 for sid=${d.sid} tab='$displayName' — awaiting removal commit + signal 2")
                        }
                    }
                }

                override fun contentRemoved(event: com.intellij.ui.content.ContentManagerEvent) {
                    // The removal actually happened (not vetoed) — NOW kill the tab's claude so it
                    // can't orphan and resurrect, and so signal 2 (process dead) confirms reliably.
                    // Pooled thread: the kill does file IO + a process-tree walk that must not
                    // block the EDT mid-close.
                    val c = ctx(project)
                    val displayName = try { event.content.displayName ?: "?" } catch (_: Exception) { "?" }
                    LOG.info("[ClaudeTabs][close] contentRemoved FIRED tab='$displayName' pendingKill=${c.pendingKill.containsKey(event.content)}")
                    // Event-driven close detection: run the close pass shortly after the removal
                    // settles. Reschedule on every removal so a shutdown burst collapses to a single
                    // "dropped to zero → ignore" pass, while a single close is resolved in <0.5s —
                    // no longer dependent on catching the 5s poll between the X and a quick quit.
                    if (!project.isDisposed) {
                        closeDebounce.cancelAllRequests()
                        closeDebounce.addRequest({ recordTabOrder(project) }, CLOSE_DEBOUNCE_MS)
                    }
                    val sid = c.pendingKill.remove(event.content) ?: return
                    ApplicationManager.getApplication().executeOnPooledThread {
                        killClaudeProcessTree(sid)
                    }
                }
            }
            cmgr.addContentManagerListener(cmListener)
            LOG.info("[ClaudeTabs][close] Close listener installed on terminal ContentManager (${cmgr.contentCount} content(s) present)")
            Disposer.register(project as Disposable, Disposable {
                try { cmgr.removeContentManagerListener(cmListener) } catch (_: Exception) { }
            })
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] ContentManagerListener install failed: ${e.message}")
        }
    }

    /**
     * Kill the `claude` process tree backing [sid] — called only after a user close has COMMITTED
     * (`contentRemoved` following a signal-1 `contentRemoveQuery`), on a pooled thread. Closing a
     * Claude tab in the reworked terminal does NOT reliably kill the child `claude` process (it
     * orphans: stays alive, keeps its `sessions/<pid>.json`, so the plugin keeps tracking it and
     * resurrects it on the next restart — the "wrong old tabs came back" bug). Killing it here
     * makes the close deterministic AND lets the two-signal detector's signal 2 (process-now-dead)
     * confirm reliably instead of waiting forever on an orphan.
     *
     * Safety rails, in order:
     *  - pid resolution falls back to scanning the sessions dir's per-pid files for the sid when
     *    the tracked entry has pid=null — a tab closed seconds after `claude --resume` spawned it
     *    hasn't had its pid stamped by the poll yet, but Claude has already written its own pid file;
     *  - [verifyAlive] cross-checks `sessions/<pid>.json` sid before anything destructive, so a
     *    RECYCLED OS pid (stale entry after a crash) can never get an innocent process killed;
     *  - teardown (IDE quit/restart, project close, tab drag/split) never reaches this — filtered
     *    before [TwoSignalCloseDetector.Signal1.AddToPending] — so a reload/crash never kills;
     *  - the transcript is untouched, so `claude --resume <sid>` always recovers the session.
     */
    private fun killClaudeProcessTree(sid: String) {
        val pid = try { storage.activeSessions.read(sid)?.pid } catch (_: Exception) { null }
            ?: findSessionPidBySid(sid)
            ?: return
        // Identity check before anything destructive: <pid>.json must still name THIS sid.
        if (!verifyAlive(pid, sid)) return
        val handle = ProcessHandle.of(pid).orElse(null) ?: return
        try {
            // Children first (subagent node procs, shells), then claude itself, so nothing reparents
            // away and survives. destroyForcibly: claude can ignore a polite terminate.
            handle.descendants().forEach { try { it.destroyForcibly() } catch (_: Throwable) { } }
            handle.destroyForcibly()
            LOG.info("[ClaudeTabs][close] Killed claude process tree pid=$pid for user-closed sid=$sid")
        } catch (e: Throwable) {
            LOG.debug("[ClaudeTabs] killClaudeProcessTree pid=$pid failed: ${e.message}")
        }
    }

    /** Find the pid whose `sessions/<pid>.json` names [sid] — Claude's own registration, present
     *  within moments of launch, long before the poll stamps the tracked entry's pid. */
    private fun findSessionPidBySid(sid: String): Long? {
        val files = storage.sessionsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return null
        for (f in files) {
            val pid = f.nameWithoutExtension.toLongOrNull() ?: continue
            val text = try { f.readText() } catch (_: Exception) { continue }
            if (ClaudeTabsHelpers.extractJsonString(text, "sessionId") == sid) return pid
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────────
    // Integration deployment — install Node helpers + skill files
    // ──────────────────────────────────────────────────────────────────

    /**
     * Copy the bundled `claude-integration/` resources to `~/.claude/` so the Node helpers
     * (used by the /tabs-* skills) and the skill .md files are present.
     *
     * Idempotent: overwrites existing copies so a plugin update refreshes them. Markdown
     * skills + Node helpers only — no shell scripts in 2.0.
     */
    private fun deployClaudeIntegration() {
        try {
            val cl = javaClass.classLoader
            val resourceNames = listOf(
                "current-project.js",
                "backup-active.js",
                "tab-name.js",
            )
            storage.stateDir.mkdirs()
            for (name in resourceNames) {
                val inStream = cl.getResourceAsStream("claude-integration/$name") ?: continue
                val target = File(storage.stateDir, name)
                inStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            }
            // Clean up every 1.x artifact so nothing keeps auto-instructing the model:
            //  - the CLAUDE.md tab-naming block (told Claude to run rename-tab.sh each chat)
            //  - the 1.x helper scripts and their logs
            // (The /tab skill itself is back in 2.0 — user-invoked only, no standing rule.)
            stripClaudeMdSection()
            for (legacy in listOf(
                    "tab-now.js", "rename-tab.sh", "tab.sh", "tab-backup.js",
                    "session-start-hook.sh", "rename-tab.log", "hook-debug.log",
                    "last-hook-input.log",
            )) {
                try { File(storage.stateDir, legacy).delete() } catch (_: Exception) { }
            }
            val skillNames = listOf(
                "tab.md",
                "tabs-backup.md",
                "tabs-restore.md",
                "tabs-history.md",
                "tabs-status.md",
                "tabs-clear.md",
            )
            storage.commandsDir.mkdirs()
            for (name in skillNames) {
                val inStream = cl.getResourceAsStream("claude-integration/$name") ?: continue
                val target = File(storage.commandsDir, name)
                inStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            }
            ensureSettingsPermissions()
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] deployClaudeIntegration failed: ${e.message}")
        }
    }

    /** Make sure the current 2.0 permission entries exist in `~/.claude/settings.json`.
     *  Also removes legacy 1.x entries (rename-tab.sh, tab.sh, tab-backup.js) idempotently. */
    private fun ensureSettingsPermissions() {
        val settings = storage.settingsFile
        val currentEntries = SettingsPermissions.CURRENT_ENTRIES
        val legacyEntries = SettingsPermissions.LEGACY_ENTRIES.toSet()
        try {
            // No file yet → leave it alone (Claude will prompt the first time a helper runs).
            if (!settings.exists()) return
            val original = settings.readText()
            // Drop legacy entries + ensure current ones via a clean array rebuild. Returns null when
            // there's no allow array or nothing needs changing — and never leaves a dangling comma
            // (it also repairs a file an older build's string-surgery removal already broke).
            val updated = SettingsPermissions.rewriteAllowArray(
                original, remove = legacyEntries, add = currentEntries.toList(),
            ) ?: return
            if (updated != original) settings.writeText(updated)
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] settings.json update failed: ${e.message}")
        }
    }

}
