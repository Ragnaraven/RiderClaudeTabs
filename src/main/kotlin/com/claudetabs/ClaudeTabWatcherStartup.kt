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
 *     `~/.claude/rider-plugin/active-sessions/<sid>.json`. Sessions whose death we OBSERVE get
 *     evicted after [EvictionTracker]'s K=2 strikes and prepended to
 *     `~/.claude/rider-plugin/session-backlog.json` (max 50, dedup-by-sid); deaths from a Rider
 *     restart are reconciled to restore-pending instead (see [reconcileStaleEntriesOnStartup]).
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

        /** Permission lines the plugin still maintains in `~/.claude/settings.json`. Both legacy
         *  (1.x bash scripts) and current (2.0 Node helpers) are listed so uninstall cleans both. */
        private val PERMISSION_ENTRIES = listOf(
            // legacy — removed on uninstall but never re-added
            "Bash(bash ~/.claude/rider-plugin/rename-tab.sh *)",
            "Bash(bash ~/.claude/rider-plugin/tab.sh *)",
            "Bash(node ~/.claude/rider-plugin/tab-backup.js *)",
            // current 2.0
            "Bash(node ~/.claude/rider-plugin/backup-active.js)",
            "Bash(node ~/.claude/rider-plugin/backup-active.js *)",
            "Bash(node ~/.claude/rider-plugin/current-project.js)",
            "Bash(node ~/.claude/rider-plugin/tab-name.js *)",
            "Bash(node ~/.claude/rider-plugin/tab-now.js *)",
            "Bash(node ~/.claude/rider-plugin/tab-now.js)",
        )

        /** Singleton storage helper. */
        private val storage = ClaudeTabsStorage(CLAUDE_HOME)

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

        /**
         * Removes all plugin artifacts from `~/.claude` (called on plugin uninstall).
         * Best-effort; logs but does not throw on individual failures.
         */
        @JvmStatic
        fun uninstall() {
            stripClaudeMdSection()
            val settings = File(CLAUDE_HOME, "settings.json")
            if (settings.exists()) {
                var text = settings.readText()
                for (entry in PERMISSION_ENTRIES) {
                    text = text
                        .replace("\"$entry\", ", "")
                        .replace(", \"$entry\"", "")
                        .replace("\"$entry\"", "")
                }
                settings.writeText(text)
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

        /** Map from terminal Content → sid, populated when we spawn a tab. Used by the close
         *  listener to identify which sid was in a closed tab. */
        val contentToSid = ConcurrentHashMap<Content, String>()

        /** Widgets we spawned, keyed by sid. Used by the close listener as a fallback
         *  identity lookup when contentToSid hasn't been populated yet. */
        val spawnedWidgets = ConcurrentHashMap<String, TerminalWidget>()

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

    /** True if ANY currently-open project window claims [cwd]. Used by the eviction path
     *  to distinguish "session died in front of its user" (owner window open → evict)
     *  from "session died because its window closed" (no owner → demote to
     *  restore-pending). */
    private fun anyOpenProjectClaims(cwd: String): Boolean = try {
        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.any { p ->
            !p.isDisposed && claimsCwd(cwd, p)
        }
    } catch (_: Exception) {
        // Can't enumerate projects — return false so the caller demotes to restore-pending
        // (recoverable) rather than evicting (destructive).
        false
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
        val pluginVersion = try {
            com.intellij.ide.plugins.PluginManager.getPluginByClass(javaClass)?.version ?: "unknown"
        } catch (_: Exception) { "unknown" }
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

        // Main poll loop: write per-sid files for alive Claude processes, evict dead ones,
        // mirror Claude's session names onto tabs. The first poll runs after a 3s delay so any post-restore
        // tab spawns have settled.
        scope.launch {
            delay(3_000)
            withContext(Dispatchers.Main) { performRestore(project) }
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
     *  2. Walk `active-sessions/`; verify each is still alive (PID exists, sid matches);
     *     evict via [EvictionTracker]'s K-strike policy.
     *  3. Confirm pending-close signal-2 (process dead) → mark userClosed + persist.
     */
    internal fun pollOnce(project: Project) {
        val now = System.currentTimeMillis()
        val seenSids = mutableSetOf<String>()
        val alivePidToSid = mutableMapOf<Long, String>()
        val c = ctx(project)

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
                nowAliveSids.add(entry.sid)
            } else {
                val shouldEvict = evictionTracker.recordDead(entry.sid)
                if (shouldEvict) {
                    if (anyOpenProjectClaims(entry.cwd) && evictionTracker.hasBeenSeenAlive(entry.sid)) {
                        // Owner window is open AND we actually watched this session run in this
                        // JVM — so its death happened in front of the user (they exited claude
                        // or closed the tab). Genuine eviction. The seen-alive gate stops a
                        // stale/unconfirmed pid (disk seed, manual reseed, backup-active.js
                        // write added after the once-per-JVM reconcile) from being deleted on a
                        // pid we never confirmed — those demote to restore-pending below.
                        evict(entry)
                    } else {
                        // Either no open window owns this cwd (session died because its project
                        // window closed — teardown kills child processes) OR we never confirmed
                        // this pid alive in this JVM (stale/seeded entry). Neither is user
                        // intent: demote to restore-pending so reopening the project respawns
                        // it. Preserves the cached name/userName/ordinal (writeOrUpdate keeps
                        // them when passed null).
                        storage.activeSessions.writeOrUpdate(entry.sid, entry.cwd, pid = null, lastSeen = now)
                        evictionTracker.forget(entry.sid)
                        LOG.info("[ClaudeTabs] sid=${entry.sid} unconfirmed/window-closed (cwd=${entry.cwd}) — demoted to restore-pending (not evicted)")
                    }
                }
            }
        }

        // Step 4 (post-eviction): retry-spawn for unconfirmed entries that belong to this
        // project and we haven't already attempted in this Rider session. The first spawn
        // happens via performRestore on startup; this is the safety net for spawns that
        // didn't materialise (Terminal tool window not ready, etc.). Idempotent: same sid
        // can't be re-spawned because attemptedRestoreSpawnSids dedups.
        val basePath = project.basePath
        if (!basePath.isNullOrBlank() && unconfirmedEntries.isNotEmpty()) {
            val userClosed = synchronized(c.userClosedSessions) { c.userClosedSessions.toSet() }
            for (entry in unconfirmedEntries) {
                if (entry.sid in userClosed) continue
                // Already hold a live tab for this sid → NEVER spawn a second one. The entry can
                // sit at pid=null for a while after spawn (Claude's --resume is slow to write its
                // pid file); without this guard the retry path opens a duplicate tab, and the
                // second `claude --resume` can't attach to the already-live session so Claude
                // starts a FRESH EMPTY session instead — the empty ghost tabs. If the tab is
                // later closed, the title controller prunes it from spawnedWidgets, so a genuine
                // retry (spawn that truly died) can still proceed.
                if (c.spawnedWidgets.containsKey(entry.sid)) continue
                if (!claimsCwd(entry.cwd, project)) continue
                if (!ClaudeTabsHelpers.hasTranscriptAnywhere(storage.projectsDir, entry.sid, entry.cwd)) continue
                val attempts = c.restoreSpawnAttempts[entry.sid] ?: 0
                if (attempts >= MAX_SPAWN_ATTEMPTS) continue
                val lastAttempt = c.restoreSpawnLastAttempt[entry.sid]
                if (lastAttempt != null && (now - lastAttempt) < RESPAWN_COOLDOWN_MS) continue
                c.restoreSpawnLastAttempt[entry.sid] = now
                c.restoreSpawnAttempts[entry.sid] = attempts + 1
                ApplicationManager.getApplication().invokeLater {
                    spawnRestoreTab(project, entry)
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
                LOG.info("[ClaudeTabs][close] Pending sid=$sid expired (no process-death within ${PENDING_CLOSE_EXPIRY_MS}ms) — dropping")
            }
        }
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
                        c.contentToSid[content] = sid
                        LOG.info("[ClaudeTabs] Matched user-started tab (shellPid=$shellPid) to sid=$sid")
                        break
                    }
                }
            }

            // Narrow 1:1 fallback: when the PID dig failed for the last hold-outs but there is
            // EXACTLY ONE unclaimed session for this project AND EXACTLY ONE still-unclaimed
            // Claude-looking tab, the mapping is unambiguous — link them. Guarded to a single
            // candidate on each side so we can never assign a session to the wrong tab.
            val remainingSids = unclaimed.values.filter { sid ->
                !c.spawnedWidgets.containsKey(sid) && (storage.activeSessions.read(sid)?.let { claimsCwd(it.cwd, project) } == true)
            }
            if (remainingSids.size == 1) {
                val candidates = tw.contentManager.contents.filter { content ->
                    if (c.contentToSid.containsKey(content)) return@filter false
                    val w = try { TerminalToolWindowManager.findWidgetByContent(content) } catch (_: Exception) { null } ?: return@filter false
                    if (c.spawnedWidgets.containsValue(w)) return@filter false
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
                        c.contentToSid[content] = sid
                        LOG.info("[ClaudeTabs] 1:1 fallback claimed unmatchable tab → sid=$sid (PID dig failed)")
                    }
                }
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
            if (c.spawnedWidgets.isEmpty()) return
            val tw = TerminalToolWindowManager.getInstance(project).toolWindow ?: return
            val contents = tw.contentManager.contents
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
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] recordTabOrder failed: ${e.message}")
        }
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

    // ──────────────────────────────────────────────────────────────────
    // Restore — spawn one tab per saved session
    // ──────────────────────────────────────────────────────────────────

    /**
     * On project open, scan `active-sessions/` and spawn a fresh terminal tab running
     * `claude --resume <sid>` for each entry whose cwd belongs to this project (or a
     * sibling worktree) and whose transcript still exists and which the user hasn't closed.
     */
    internal fun performRestore(project: Project) {
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
        if (toRestore.isEmpty()) {
            LOG.info("[ClaudeTabs] Restore: nothing to spawn")
            return
        }
        LOG.info("[ClaudeTabs] Restore: spawning ${toRestore.size} tab(s)")
        val now = System.currentTimeMillis()
        for (e in toRestore) {
            c.restoreSpawnLastAttempt[e.sid] = now
            c.restoreSpawnAttempts[e.sid] = (c.restoreSpawnAttempts[e.sid] ?: 0) + 1
            spawnRestoreTab(project, e)
        }
        writeLastRestoreSnapshot(project, toRestore)
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
                    // Sample shutdown state LIVE — a pre-set flag races the teardown. isExitInProgress
                    // catches IDE restart/quit (e.g. a plugin-install restart); project.isDisposed +
                    // the projectClosing flag catch this single window closing. Any of them means the
                    // removal is teardown, NOT a user clicking X on one tab.
                    val appExiting = try {
                        com.intellij.openapi.application.ex.ApplicationManagerEx.getApplicationEx().isExitInProgress
                    } catch (_: Throwable) { false }
                    val projectClosing = c.projectClosing || try { project.isDisposed } catch (_: Throwable) { false }
                    when (val d = TwoSignalCloseDetector.decideOnRemoveQuery(
                        projectClosing = projectClosing,
                        isTemporary = isTemporary,
                        sid = sid,
                        appExiting = appExiting,
                    )) {
                        TwoSignalCloseDetector.Signal1.SkipAppExiting -> { /* silent — IDE restart/quit */ }
                        TwoSignalCloseDetector.Signal1.SkipProjectClosing -> { /* silent */ }
                        TwoSignalCloseDetector.Signal1.SkipTemporary -> { /* silent — shuffle/drag/split */ }
                        TwoSignalCloseDetector.Signal1.SkipNoSid -> { /* not a tracked tab */ }
                        is TwoSignalCloseDetector.Signal1.AddToPending -> {
                            c.pendingClose[d.sid] = System.currentTimeMillis()
                            c.contentToSid.remove(content)
                            LOG.info("[ClaudeTabs][close] Signal 1 for sid=${d.sid} tab='$displayName' — awaiting signal 2")
                        }
                    }
                }
            }
            cmgr.addContentManagerListener(cmListener)
            Disposer.register(project as Disposable, Disposable {
                try { cmgr.removeContentManagerListener(cmListener) } catch (_: Exception) { }
            })
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] ContentManagerListener install failed: ${e.message}")
        }
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
        val currentEntries = setOf(
            "Bash(node ~/.claude/rider-plugin/current-project.js)",
            "Bash(node ~/.claude/rider-plugin/backup-active.js)",
            "Bash(node ~/.claude/rider-plugin/backup-active.js *)",
            "Bash(node ~/.claude/rider-plugin/tab-name.js *)",
        )
        val legacyEntries = setOf(
            "Bash(bash ~/.claude/rider-plugin/rename-tab.sh *)",
            "Bash(bash ~/.claude/rider-plugin/tab.sh *)",
            "Bash(node ~/.claude/rider-plugin/tab-backup.js *)",
            "Bash(node ~/.claude/rider-plugin/tab-now.js)",
            "Bash(node ~/.claude/rider-plugin/tab-now.js *)",
        )
        try {
            val original = if (settings.exists()) settings.readText() else "{}"
            var text = original
            // Remove legacy entries (idempotent).
            for (e in legacyEntries) {
                text = text
                    .replace("\"$e\", ", "")
                    .replace(", \"$e\"", "")
                    .replace("\"$e\"", "")
            }
            // Find or create the permissions.allow array and ensure current entries are present.
            val allowMatch = Regex(""""allow"\s*:\s*\[([^\]]*)]""").find(text)
            if (allowMatch != null) {
                val body = allowMatch.groupValues[1]
                val toAdd = currentEntries.filter { !body.contains("\"$it\"") }
                if (toAdd.isNotEmpty()) {
                    val prefix = if (body.trim().isEmpty()) "" else "$body,\n      "
                    val newBody = prefix + toAdd.joinToString(",\n      ") { "\"$it\"" }
                    text = text.replaceRange(allowMatch.range, """"allow": [$newBody]""")
                }
            } else {
                // No permissions block — leave the file alone. The user can run the plugin without it;
                // Claude will just prompt the first time the helper runs.
            }
            if (text != original) {
                settings.writeText(text)
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] settings.json update failed: ${e.message}")
        }
    }

}
