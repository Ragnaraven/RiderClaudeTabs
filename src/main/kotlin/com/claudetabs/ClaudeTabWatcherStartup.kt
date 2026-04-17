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
import com.jediterm.terminal.ProcessTtyConnector
import kotlinx.coroutines.*
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.nio.file.*

/**
 * Entry point for the Claude Terminal Tab Namer plugin.
 *
 * This is a JetBrains IntelliJ Platform post-startup activity. When a project opens, it:
 *
 *  1. **Deploys** its bash integration (`rename-tab.sh`, `session-start-hook.sh`), slash commands,
 *     a CLAUDE.md section, and permission/settings entries into `~/.claude/`.
 *  2. **Watches** `~/.claude/rider-plugin/tabs/` for `{sessionId}.json` rename files written by the
 *     bash scripts when the user runs `/tab` or any other command that names a terminal tab.
 *  3. **Polls** the terminal tool window every [POLL_INTERVAL_MS] to:
 *     - Match Claude Code processes to their terminal tab (by walking each tab's PID tree).
 *     - Apply any pending renames.
 *     - Save the set of named tabs to a per-project restore file.
 *     - Detect closed sessions and append them to `history.json` for `/tabs-history`.
 *  4. **Restores** saved tabs after a Rider restart — typing `claude --resume <id>` into each
 *     matching idle terminal.
 *
 * ### Design notes
 *
 * - **Reflection-heavy**: navigates IntelliJ's reworked (and classic) terminal internals because
 *   the public API doesn't expose what's needed. Fallback paths are graceful — see [renameTab].
 * - **Race-condition free**: tab identification uses JetBrains' `TERM_SESSION_ID` env var, which
 *   is unique per terminal tab. The `session-start-hook.sh` writes the mapping
 *   `TERM_SESSION_ID → Claude session ID` per-tab, so no shared FIFO queue is needed.
 * - **Manual-rename priority**: if the user renames a tab themselves, [lastAppliedName] lets us
 *   detect it and back off — we won't overwrite their choice.
 *
 * See `plugin.xml` for Marketplace metadata and `README.md` for user-facing docs.
 */
class ClaudeTabWatcherStartup : StartupActivity.DumbAware {

    companion object {
        private val LOG = Logger.getInstance(ClaudeTabWatcherStartup::class.java)

        /** Poll cadence for detecting rename files and session state changes. */
        private const val POLL_INTERVAL_MS = 5_000L

        /** Root of Claude Code's user data (scripts, sessions, commands live under this). */
        private val CLAUDE_HOME = File(System.getProperty("user.home"), ".claude")

        /** Where Claude Code writes `{PID}.json` session files. Read-only for the plugin. */
        private val SESSIONS_DIR = File(CLAUDE_HOME, "sessions")

        /** Where bash scripts drop `{sessionId}.json` rename directives for the plugin to pick up. */
        private val TABS_DIR = File(CLAUDE_HOME, "rider-plugin/tabs")

        /** Where per-project restore files (`restore-<projectPath>.json`) and `history.json` live. */
        private val STATE_DIR = File(CLAUDE_HOME, "rider-plugin")

        /** Markers wrapping the plugin's section of `~/.claude/CLAUDE.md` so it can be replaced cleanly. */
        private const val CLAUDE_MD_MARKER = "<!-- rider-claude-tabs-plugin -->"

        /** Permission line inserted into `~/.claude/settings.json` so Claude can run our rename script. */
        private const val PERMISSION_ENTRY = "Bash(bash ~/.claude/rider-plugin/rename-tab.sh *)"

        /** Long-term session history — one JSON entry per closed/backed-up session. */
        private val HISTORY_FILE = File(CLAUDE_HOME, "rider-plugin/history.json")

        /** Rotating snapshots of restore-*.json — one per successful non-empty save. */
        private val SNAPSHOTS_DIR = File(CLAUDE_HOME, "rider-plugin/snapshots")

        /**
         * User-overridable config file. Read once at startup (see [loadConfig]). Defaults
         * below are used when the file is missing or a field is malformed. Users can create
         * or edit this file to change retention policies without recompiling the plugin.
         */
        private val CONFIG_FILE = File(CLAUDE_HOME, "rider-plugin/config.json")

        // ── Live config (loaded from CONFIG_FILE; defaults apply if not overridden). ──

        /** History entries older than this are pruned on every append. Default: 90 days. */
        var historyMaxAgeMs: Long = 90L * 24 * 60 * 60 * 1000
            private set

        /** How many recent snapshots to retain per project. Default: 10. */
        var snapshotKeepCount: Int = 10
            private set

        /**
         * Load [CONFIG_FILE] and apply any recognised fields, falling back to defaults for
         * anything missing or malformed. Accepted fields:
         *   - `historyMaxAgeDays` — integer (converted to ms internally)
         *   - `snapshotKeepCount` — integer
         */
        private fun loadConfig() {
            if (!CONFIG_FILE.exists()) return
            try {
                val text = CONFIG_FILE.readText()
                Regex(""""historyMaxAgeDays"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let {
                    if (it > 0) historyMaxAgeMs = it * 24 * 60 * 60 * 1000
                }
                Regex(""""snapshotKeepCount"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    if (it >= 0) snapshotKeepCount = it
                }
                LOG.info("[ClaudeTabs] Config loaded: historyMaxAgeDays=${historyMaxAgeMs / (24*60*60*1000)}, snapshotKeepCount=$snapshotKeepCount")
            } catch (e: Exception) {
                LOG.warn("[ClaudeTabs] Config load failed (using defaults): ${e.message}")
            }
        }

        /** Write a commented template config.json if the file doesn't exist yet. */
        private fun maybeWriteConfigTemplate() {
            if (CONFIG_FILE.exists()) return
            try {
                CONFIG_FILE.parentFile?.mkdirs()
                CONFIG_FILE.writeText(
                    """{
  "_comment": "Claude Terminal Tab Namer — edit values and restart Rider to apply.",
  "historyMaxAgeDays": 90,
  "snapshotKeepCount": 10
}
"""
                )
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] Config template write failed: ${e.message}")
            }
        }

        /**
         * Removes all plugin artifacts from ~/.claude.
         * Called on plugin uninstall or via /tabs-clear command.
         */
        @JvmStatic
        fun uninstall() {
            // 1. Remove CLAUDE.md section
            val claudeMd = File(CLAUDE_HOME, "CLAUDE.md")
            if (claudeMd.exists()) {
                val text = claudeMd.readText()
                if (text.contains(CLAUDE_MD_MARKER)) {
                    val pattern = Regex("\n?${Regex.escape(CLAUDE_MD_MARKER)}.*?${Regex.escape(CLAUDE_MD_MARKER)}\n?", RegexOption.DOT_MATCHES_ALL)
                    claudeMd.writeText(text.replace(pattern, "\n").trim() + "\n")
                }
            }

            // 2. Remove permission entry from settings.json
            val settings = File(CLAUDE_HOME, "settings.json")
            if (settings.exists()) {
                var text = settings.readText()
                text = text.replace("\"$PERMISSION_ENTRY\", ", "")
                    .replace(", \"$PERMISSION_ENTRY\"", "")
                    .replace("\"$PERMISSION_ENTRY\"", "")
                settings.writeText(text)
            }

            // 3. Remove deployed scripts and data
            File(CLAUDE_HOME, "rider-plugin").deleteRecursively()
            File(CLAUDE_HOME, "commands/tab.md").delete()
            File(CLAUDE_HOME, "commands/tabs-clear.md").delete()
            File(CLAUDE_HOME, "commands/tabs-restore.md").delete()
            File(CLAUDE_HOME, "commands/tabs-history.md").delete()
            File(CLAUDE_HOME, "commands/tabs-backup.md").delete()
            File(CLAUDE_HOME, "commands/tabs-status.md").delete()
            // Legacy command filenames (pre-rename)
            File(CLAUDE_HOME, "commands/clear-tabs.md").delete()
            File(CLAUDE_HOME, "commands/restore-tabs.md").delete()
            File(CLAUDE_HOME, "commands/tab-history.md").delete()
            File(CLAUDE_HOME, "commands/backup-tabs.md").delete()
        }
    }

    private var pollCount = 0
    private val renamedSessions = mutableSetOf<String>()
    /** Last name the plugin itself applied to each session. If the current tab name diverges from
     * this, we infer the user manually renamed the tab and back off — see [poll]. */
    private val lastAppliedName = mutableMapOf<String, String>()

    /** Sessions loaded from the project's restore file, waiting for an idle tab to be restored into. */
    private val pendingRestores = mutableListOf<SavedSession>()

    /** Sessions we've seen active at least once this run. Used to detect closures and write history. */
    private val previousActive = mutableMapOf<String, SavedSession>()

    /**
     * IntelliJ entry point. Fires once per project open. Starts two coroutines:
     *  - a [java.nio.file.WatchService] on the tabs dir for instant renames
     *  - a main poll loop that does rename fallback + state save + history tracking
     *
     * The project [Disposable] ensures both coroutines shut down on project close, and any
     * still-active sessions get written to history for later browsing.
     */
    override fun runActivity(project: Project) {
        LOG.info("[ClaudeTabs] Started for: ${project.name}")
        TABS_DIR.mkdirs()
        maybeWriteConfigTemplate()
        loadConfig()
        deployClaudeIntegration()

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        Disposer.register(project as Disposable, Disposable {
            LOG.info("[ClaudeTabs] Project closing — saving ${previousActive.size} session(s) to history")
            for ((_, session) in previousActive) {
                appendToHistory(session)
            }
            previousActive.clear()
            scope.cancel()
        })

        // File watcher for instant renames
        scope.launch {
            delay(2_000)
            try { watchTabsDirectory(project) } catch (e: Exception) { LOG.debug("[ClaudeTabs] Watcher failed: ${e.message}") }
        }

        // Main poll loop
        scope.launch {
            delay(3_000)

            // Load restore file
            withContext(Dispatchers.Main) { loadRestoreFile(project) }

            val startupTime = System.currentTimeMillis()
            while (isActive) {
                try {
                    withContext(Dispatchers.Main) {
                        processPendingRestores(project)
                        poll(project)
                    }
                } catch (_: ProcessCanceledException) { break }
                catch (e: Exception) {
                    if (e.message?.contains("disposed") == true) break
                    if (pollCount % 12 == 0) LOG.warn("[ClaudeTabs] Poll: ${e.message}")
                }
                val inBurst = System.currentTimeMillis() - startupTime < 60_000
                delay(if (inBurst || pendingRestores.isNotEmpty()) 2_000L else POLL_INTERVAL_MS)
                pollCount++
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // TERMINAL TAB ACCESS — stable API, all panels
    // ══════════════════════════════════════════════════════════════

    /**
     * A unified view of a single terminal tab across IntelliJ's two terminal backends:
     *  - **Classic** terminal (`TerminalWidget` via `TerminalToolWindowManager`) — [widget] set, [content] set.
     *  - **Reworked** terminal (2024.3+ split-panel aware) — [reworkedSession] + [reworkedTabId] set.
     *
     * Exactly one of the two paths will be populated, depending on which backend is active for
     * this particular tab. [pid] is the shell process PID (PowerShell / bash / cmd) at the root
     * of the tab — we walk its children with [findClaudeChild] to find the Claude process.
     */
    data class TabInfo(
        val content: Content?,              // null for reworked API tabs (split panels)
        val widget: TerminalWidget?,        // null when using reworked API
        val pid: Long,
        val reworkedSession: Any? = null,   // reworked session for PID/command access
        val reworkedTabId: Int? = null,     // for renameTerminalTab()
        val tabName: String = ""            // current tab name
    )

    /**
     * Enumerate every terminal tab in the project's terminal tool window.
     *
     * Uses reflection on both the reworked and classic terminal APIs; tabs from both paths are
     * merged into a single list of [TabInfo] entries with their PIDs resolved. Silent on API drift
     * (see `LOG.debug` messages) so one backend missing doesn't block the other.
     */
    private fun getAllTabs(project: Project): List<TabInfo> {
        val result = mutableListOf<TabInfo>()

        // Step 1: Get frontend views — store view + content per tab
        data class FrontendEntry(val view: Any, val content: Content?)
        val frontendTabs = mutableListOf<FrontendEntry>()
        try {
            val feMgrCls = Class.forName("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager")
            val feMgr = feMgrCls.getMethod("getInstance", Project::class.java).invoke(null, project)
            val feTabs = feMgr?.javaClass?.getMethod("getTabs")?.invoke(feMgr) as? List<*>
            if (pollCount % 12 == 0) LOG.info("[ClaudeTabs] STEP 1: Frontend has ${feTabs?.size ?: 0} tabs")

            feTabs?.forEach { feTab ->
                feTab ?: return@forEach
                try {
                    val content = feTab.javaClass.getMethod("getContent").invoke(feTab) as? Content
                    val view = feTab.javaClass.getMethod("getView").invoke(feTab) ?: return@forEach
                    frontendTabs.add(FrontendEntry(view, content))
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] frontend tab access failed: ${e.message}")
                }
            }
            if (pollCount % 12 == 0) LOG.info("[ClaudeTabs] STEP 2: Frontend tabs: ${frontendTabs.size}, names: ${frontendTabs.map { it.content?.displayName ?: "?" }}")
        } catch (_: ClassNotFoundException) {
            // Older IntelliJ — reworked terminal frontend not available. Falls back to classic paths.
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] TerminalToolWindowTabsManager unavailable: ${e.message}")
        }

        // Step 2: Get backend tabs (name → PID + session) for process detection
        try {
            val tmCls = Class.forName("com.intellij.terminal.backend.TerminalTabsManager")
            val tm = tmCls.getMethod("getInstance", Project::class.java).invoke(null, project)
            val tabs = tm?.let { invokeSuspend(it, tmCls.methods.find { m -> m.name == "getTerminalTabs" }!!) as? List<*> }
            if (pollCount % 12 == 0) LOG.info("[ClaudeTabs] STEP 3: Backend has ${tabs?.size ?: 0} tabs")

            val smCls = Class.forName("com.intellij.terminal.backend.TerminalSessionsManager")
            val sm = smCls.getMethod("getInstance").invoke(null)
            val getSess = sm?.let { smCls.methods.find { m -> m.name == "getSession" && m.parameterCount == 1 } }

            val backendNames = mutableListOf<String>()
            val backendWithPids = mutableListOf<String>()
            val backendNoSession = mutableListOf<String>()

            tabs?.forEach { tab ->
                tab ?: return@forEach
                try {
                    val name = tab.javaClass.getMethod("getName").invoke(tab) as? String ?: return@forEach
                    val tabId = tab.javaClass.getMethod("getId").invoke(tab) as? Int ?: return@forEach
                    backendNames.add(name)

                    val sessIdObj = tab.javaClass.getMethod("getSessionId").invoke(tab)
                    if (sessIdObj == null) { backendNoSession.add(name); return@forEach }
                    val session = getSess?.invoke(sm, sessIdObj)
                    if (session == null) { backendNoSession.add("$name(no-sess)"); return@forEach }
                    val pid = extractPidFromSession(session)
                    if (pid == null) { backendNoSession.add("$name(no-pid)"); return@forEach }

                    backendWithPids.add("$name→PID$pid")

                    // Merge: match to frontend by index (both APIs return tabs in same order)
                    val backendIdx = backendNames.size - 1
                    val fe = frontendTabs.getOrNull(backendIdx)
                    val view = fe?.view
                    val content = fe?.content
                    val hasFrontend = fe != null

                    result.add(TabInfo(
                        content = content,
                        widget = null,
                        pid = pid,
                        reworkedSession = view ?: session,
                        reworkedTabId = tabId,
                        tabName = name
                    ))

                    if (!hasFrontend) {
                        LOG.info("[ClaudeTabs] Backend tab '$name' has NO frontend view match!")
                    }
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] backend tab access failed: ${e.message}")
                }
            }
            if (pollCount % 12 == 0) {
                LOG.info("[ClaudeTabs] STEP 3a: Backend all names: $backendNames")
                LOG.info("[ClaudeTabs] STEP 3b: Backend with PIDs: $backendWithPids")
                if (backendNoSession.isNotEmpty()) LOG.info("[ClaudeTabs] STEP 3c: Backend no session/pid: $backendNoSession")
            }
        } catch (_: ClassNotFoundException) {
            // Older IntelliJ — reworked terminal backend not available. Falls back to classic paths.
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] TerminalTabsManager unavailable: ${e.message}")
        }

        if (pollCount % 12 == 0) LOG.info("[ClaudeTabs] STEP 4: Total: ${result.size} → ${result.map { "'${it.tabName}'→PID${it.pid}" }}")
        return result
    }

    // ══════════════════════════════════════════════════════════════
    // REWORKED API REFLECTION HELPERS
    // ══════════════════════════════════════════════════════════════
    // These navigate private fields of IntelliJ's "reworked" terminal
    // classes (public API doesn't expose what we need). The many inner
    // try/catches are intentional: each iteration is a best-effort probe
    // and failing one field is expected — we silently try the next.

    /**
     * Walk the session object (and its `delegate`, if any) looking for a
     * `ttyConnector` field, then unwrap a [ProcessTtyConnector] to get
     * the underlying Windows/Unix PID.
     * Returns null if no connector/process is accessible.
     */
    private fun extractPidFromSession(session: Any): Long? {
        val targets = mutableListOf(session)
        try {
            val f = session.javaClass.getDeclaredField("delegate"); f.isAccessible = true
            f.get(session)?.let { targets.add(0, it) }
        } catch (_: Exception) { /* no delegate — fine */ }

        for (t in targets) {
            try {
                for (field in t.javaClass.declaredFields) {
                    if (!field.name.contains("ttyConnector", true)) continue
                    field.isAccessible = true
                    val c = field.get(t) ?: continue
                    if (c is ProcessTtyConnector) return c.process.pid()
                    try {
                        (c.javaClass.getMethod("getProcess").invoke(c) as? Process)?.let { return it.pid() }
                    } catch (_: Exception) { /* no getProcess — try fields */ }
                    for (cf in c.javaClass.declaredFields) {
                        cf.isAccessible = true
                        val v = cf.get(c)
                        if (v is ProcessTtyConnector) return v.process.pid()
                        if (v is Process) return v.pid()
                    }
                }
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] extractPidFromSession probe failed: ${e.message}")
            }
        }
        return null
    }

    /**
     * Sibling of [extractPidFromSession] for getting the raw [com.jediterm.terminal.TtyConnector]
     * when we need to send commands (e.g. restore flow) rather than just read the PID.
     */
    private fun extractConnectorFromSession(session: Any): com.jediterm.terminal.TtyConnector? {
        val targets = mutableListOf(session)
        try {
            val f = session.javaClass.getDeclaredField("delegate"); f.isAccessible = true
            f.get(session)?.let { targets.add(0, it) }
        } catch (_: Exception) { /* no delegate — fine */ }

        for (t in targets) {
            try {
                for (field in t.javaClass.declaredFields) {
                    if (!field.name.contains("ttyConnector", true)) continue
                    field.isAccessible = true
                    val c = field.get(t)
                    if (c is com.jediterm.terminal.TtyConnector) return c
                }
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] extractConnectorFromSession probe failed: ${e.message}")
            }
        }
        return null
    }

    /**
     * Call a Kotlin `suspend` function via reflection by constructing an explicit [kotlin.coroutines.Continuation]
     * and blocking on its completion. Needed because many of IntelliJ's internal methods are suspend
     * functions exposed only via `Method.invoke`.
     */
    private fun invokeSuspend(target: Any, method: java.lang.reflect.Method): Any? = kotlinx.coroutines.runBlocking {
        val d = CompletableDeferred<Any?>()
        val cont = object : kotlin.coroutines.Continuation<Any?> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(r: Result<Any?>) { d.complete(r.getOrNull()) }
        }
        val r = method.invoke(target, cont)
        if (r == kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) d.await() else r
    }

    /**
     * Apply [name] to the given [tab] across every known rename path so whichever API is actually
     * wired up on this IntelliJ version takes effect:
     *
     *  1. **TerminalTitle** field mutation (reworked terminal, 2024.3+) — sets `userDefinedTitle` and
     *     fires the change notification so the UI repaints immediately.
     *  2. **Content.displayName** — classic tab label in the tool window.
     *  3. **TerminalTabsManager.renameTerminalTab()** — reworked backend; persists across split panels.
     *  4. **TerminalWidget.terminalTitle.change { }** — classic widget API.
     *
     * All four are attempted; individual failures are logged at DEBUG and don't abort the others.
     */
    private fun renameTab(project: Project, tab: TabInfo, name: String) {
        // Path 1: Frontend TerminalView.title (primary — updates UI directly)
        val view = tab.reworkedSession
        if (view != null) {
            try {
                val title = view.javaClass.getMethod("getTitle").invoke(view)
                if (title != null) {
                    // Set userDefinedTitle field directly
                    for (f in title.javaClass.declaredFields) {
                        if (f.name.contains("userDefinedTitle", true)) {
                            f.isAccessible = true
                            f.set(title, name)
                            // Fire change notification
                            for (m in title.javaClass.declaredMethods) {
                                if (m.name.contains("fireTitleChanged") && m.parameterCount == 0) {
                                    m.isAccessible = true; m.invoke(title); break
                                }
                            }
                            LOG.info("[ClaudeTabs] Renamed via TerminalTitle field")
                            break
                        }
                    }
                    // Also try change() method via reflection
                    try {
                        val changeMethod = title.javaClass.methods.find { it.name == "change" }
                        if (changeMethod != null) {
                            // Can't easily create kotlin Function1, so skip this path
                        }
                    } catch (e: Exception) {
                        LOG.debug("[ClaudeTabs] title.change() probe failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                LOG.warn("[ClaudeTabs] TerminalTitle rename failed: ${e.message}")
            }
        }

        // Path 2: Content.displayName (visual tab label)
        tab.content?.displayName = name

        // Path 3: Backend renameTerminalTab (updates backend name for save/restore)
        if (tab.reworkedTabId != null) {
            try {
                val tmCls = Class.forName("com.intellij.terminal.backend.TerminalTabsManager")
                val tm = tmCls.getMethod("getInstance", Project::class.java).invoke(null, project)
                val renameMethod = tmCls.methods.find { it.name == "renameTerminalTab" }
                if (tm != null && renameMethod != null) {
                    val d = CompletableDeferred<Any?>()
                    val cont = object : kotlin.coroutines.Continuation<Any?> {
                        override val context = kotlin.coroutines.EmptyCoroutineContext
                        override fun resumeWith(r: Result<Any?>) { d.complete(r.getOrNull()) }
                    }
                    renameMethod.invoke(tm, tab.reworkedTabId, name, true, cont)
                    LOG.info("[ClaudeTabs] Renamed via backend API: tabId=${tab.reworkedTabId}")
                }
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] Backend renameTerminalTab failed (falling back to frontend path): ${e.message}")
            }
        }

        // Path 4: Stable TerminalWidget API (classic terminal)
        tab.widget?.terminalTitle?.change { userDefinedTitle = name }
    }

    // ══════════════════════════════════════════════════════════════
    // FILE WATCHER — instant rename
    // ══════════════════════════════════════════════════════════════

    /**
     * Watch [TABS_DIR] with Java NIO's [java.nio.file.WatchService] and route new rename files
     * to their handler immediately, rather than waiting for the next poll.
     *
     * File-name conventions:
     *  - `termsess-{TERM_SESSION_ID}.json` — legacy format — routed to [handleTermSessionRename]
     *  - `pid-{scriptPid}.json` — legacy format — routed to [handlePidRename]
     *  - `{sessionId}.json` — primary format — routed to [handleRename]
     *
     * Runs for the lifetime of the project's coroutine scope.
     */
    private suspend fun watchTabsDirectory(project: Project) {
        val watcher = FileSystems.getDefault().newWatchService()
        TABS_DIR.toPath().register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)
        LOG.info("[ClaudeTabs] Watcher active")

        while (currentCoroutineContext().isActive) {
            val key = watcher.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: continue
            for (event in key.pollEvents()) {
                val filename = (event.context() as? Path)?.toString() ?: continue
                if (!filename.endsWith(".json")) continue
                delay(100)
                try {
                    val f = File(TABS_DIR, filename)
                    if (!f.exists()) continue
                    val text = f.readText()
                    val name = extractJsonString(text, "name") ?: continue

                    if (filename.startsWith("termsess-")) {
                        // TERM_SESSION_ID-keyed file: match JetBrains terminal session → tab
                        val termSessionId = filename.removePrefix("termsess-").removeSuffix(".json")
                        LOG.info("[ClaudeTabs] Watcher: termsess-rename '$name' for TERM_SESSION_ID=$termSessionId")
                        withContext(Dispatchers.Main) { handleTermSessionRename(project, termSessionId, name) }
                        f.delete()
                    } else if (filename.startsWith("pid-")) {
                        // PID-keyed file: walk up from script PID to find shell → tab
                        val scriptPid = filename.removePrefix("pid-").removeSuffix(".json").toLongOrNull() ?: continue
                        LOG.info("[ClaudeTabs] Watcher: PID-rename '$name' from script PID $scriptPid")
                        withContext(Dispatchers.Main) { handlePidRename(project, scriptPid, name) }
                        f.delete()
                    } else {
                        // Session-keyed file: use session ID to find Claude → shell → tab
                        val sessionId = filename.removeSuffix(".json")
                        LOG.info("[ClaudeTabs] Watcher: session-rename '$name' for $sessionId")
                        withContext(Dispatchers.Main) { handleRename(project, sessionId, name) }
                    }
                } catch (e: Exception) {
                    LOG.warn("[ClaudeTabs] Watcher: ${e.message}")
                }
            }
            key.reset()
        }
    }

    /**
     * Handle PID-keyed rename: walk up from the bash script's PID to find the
     * terminal shell, then match to a tab.
     */
    private fun handlePidRename(project: Project, scriptPid: Long, name: String) {
        // Walk up from script PID: bash(script) → node(claude) → ... → shell(terminal)
        val shellPid = findShellAncestor(scriptPid)
        if (shellPid == null) {
            LOG.info("[ClaudeTabs] PID-RENAME: no shell ancestor for script PID $scriptPid")
            return
        }
        LOG.info("[ClaudeTabs] PID-RENAME: script PID $scriptPid → shell PID $shellPid")

        val tabs = getAllTabs(project)
        val match = tabs.find { it.pid == shellPid }
        if (match != null) {
            LOG.info("[ClaudeTabs] PID-RENAME: '${match.tabName}' → '$name'")
            renameTab(project, match, name)
            renamedSessions.add("pid-$scriptPid")
            lastAppliedName["pid-$scriptPid"] = name
        } else {
            LOG.info("[ClaudeTabs] PID-RENAME: FAILED — shell PID $shellPid not in tabs: ${tabs.map { it.pid }}")
        }
    }

    /**
     * Handle TERM_SESSION_ID-keyed rename: match the JetBrains terminal session ID
     * to a backend tab, then rename. This is race-condition free because each terminal
     * tab has a unique, stable TERM_SESSION_ID env var that propagates to all subprocesses.
     */
    private fun handleTermSessionRename(project: Project, termSessionId: String, name: String) {
        try {
            val tmCls = Class.forName("com.intellij.terminal.backend.TerminalTabsManager")
            val tm = tmCls.getMethod("getInstance", Project::class.java).invoke(null, project)
            val backendTabs = tm?.let { invokeSuspend(it, tmCls.methods.find { m -> m.name == "getTerminalTabs" }!!) as? List<*> }
            val allTabs = getAllTabs(project)

            backendTabs?.forEachIndexed { index, tab ->
                tab ?: return@forEachIndexed
                try {
                    val sessIdObj = tab.javaClass.getMethod("getSessionId").invoke(tab) ?: return@forEachIndexed
                    val sessIdStr = sessIdObj.toString()
                    LOG.info("[ClaudeTabs] TERMSESS: Tab $index sessId='$sessIdStr' vs target='$termSessionId'")

                    // Match: toString() may return raw UUID or wrapped like TerminalSessionId(uuid)
                    if (sessIdStr == termSessionId || sessIdStr.contains(termSessionId) || termSessionId.contains(sessIdStr)) {
                        val tabInfo = allTabs.getOrNull(index)
                        if (tabInfo != null) {
                            LOG.info("[ClaudeTabs] TERMSESS: MATCH tab $index '${tabInfo.tabName}' → '$name'")
                            renameTab(project, tabInfo, name)
                            renamedSessions.add("termsess-$termSessionId")
                            lastAppliedName["termsess-$termSessionId"] = name

                            // Also track the Claude session ID for save/restore
                            val claudeProcess = findClaudeChild(tabInfo.pid)
                            if (claudeProcess != null) {
                                val sf = File(SESSIONS_DIR, "${claudeProcess.pid()}.json")
                                if (sf.exists()) {
                                    val claudeSessionId = try { extractJsonString(sf.readText(), "sessionId") } catch (_: Exception) { null }
                                    if (claudeSessionId != null) {
                                        renamedSessions.add(claudeSessionId)
                                        lastAppliedName[claudeSessionId] = name
                                    }
                                }
                            }
                            return
                        }
                    }
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] TERMSESS: per-tab probe failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] TERMSESS: ${e.message}")
        }
        LOG.info("[ClaudeTabs] TERMSESS: no tab found for TERM_SESSION_ID=$termSessionId")
    }

    /**
     * Handle session-ID-keyed rename — the primary path.
     *
     * For each terminal tab, walks the process tree to find a Claude child whose session file
     * contains the target [sessionId]; if found, renames that tab.
     */
    private fun handleRename(project: Project, sessionId: String, name: String) {
        // Direct match: find the tab whose Claude child has this session ID
        val tabs = getAllTabs(project)

        for (tab in tabs) {
            val claudeProcess = findClaudeChild(tab.pid) ?: continue
            val sf = File(SESSIONS_DIR, "${claudeProcess.pid()}.json")
            if (!sf.exists()) continue
            val tabSessionId = try {
                extractJsonString(sf.readText(), "sessionId")
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] session file read failed (${sf.name}): ${e.message}")
                null
            }

            if (tabSessionId == sessionId) {
                LOG.info("[ClaudeTabs] RENAME: '${tab.tabName}' → '$name' (session $sessionId matched tab PID ${tab.pid})")
                renameTab(project, tab, name)
                renamedSessions.add(sessionId)
                lastAppliedName[sessionId] = name
                return
            }
        }

        LOG.info("[ClaudeTabs] RENAME: no tab found for session $sessionId")
    }

    // ══════════════════════════════════════════════════════════════
    // POLL — fallback rename + state save
    // ══════════════════════════════════════════════════════════════

    /**
     * Main periodic loop. On each tick:
     *
     *  1. Cleans up any `termsess-*.json` files the watcher missed.
     *  2. Detects **manual renames** — user-edited tab names get preserved and we stop rewriting them.
     *  3. Applies **fallback renames** — any pending `{sessionId}.json` that wasn't picked up by the watcher.
     *  4. Updates the per-project **restore file** (`restore-<projectPath>.json`) with currently named tabs.
     *  5. Detects **closed sessions** (present last tick, gone now) and appends them to history.json.
     *
     * Called every [POLL_INTERVAL_MS] (or faster during the 60s startup burst).
     */
    private fun poll(project: Project) {
        // Poll fallback: process any unhandled termsess-*.json files
        TABS_DIR.listFiles()?.filter { it.name.startsWith("termsess-") && it.name.endsWith(".json") }?.forEach { f ->
            try {
                val termSessionId = f.name.removePrefix("termsess-").removeSuffix(".json")
                if ("termsess-$termSessionId" !in renamedSessions) {
                    val name = extractJsonString(f.readText(), "name") ?: return@forEach
                    LOG.info("[ClaudeTabs] POLL: processing termsess file $termSessionId")
                    handleTermSessionRename(project, termSessionId, name)
                    f.delete()
                }
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] termsess file processing failed for ${f.name}: ${e.message}")
            }
        }

        val tabs = getAllTabs(project)
        val activeSessions = mutableListOf<SavedSession>()
        val claudeSessions = mutableListOf<String>()

        for (tab in tabs) {
            val claudeProcess = findClaudeChild(tab.pid) ?: continue
            val claudePid = claudeProcess.pid()

            val sf = File(SESSIONS_DIR, "$claudePid.json")
            if (!sf.exists()) continue
            val st = try { sf.readText() } catch (_: Exception) { continue }
            val sessionId = extractJsonString(st, "sessionId") ?: continue
            val cwd = extractJsonString(st, "cwd") ?: continue

            claudeSessions.add("'${tab.tabName}'→session:${sessionId.take(8)}")

            // Detect manual renames: if user changed the name from what we last set,
            // respect their choice and don't overwrite
            if (sessionId in renamedSessions) {
                val lastSet = lastAppliedName[sessionId]
                val currentName = tab.tabName ?: ""
                if (lastSet != null && currentName != lastSet && !isGenericTabName(currentName)) {
                    LOG.info("[ClaudeTabs] Manual rename detected: plugin set '$lastSet', now '$currentName' — respecting user choice")
                    lastAppliedName[sessionId] = currentName  // track the manual name
                    // Delete the rename file so we don't try again
                    File(TABS_DIR, "$sessionId.json").delete()
                }
            }

            // Fallback rename
            if (sessionId !in renamedSessions) {
                val renameFile = File(TABS_DIR, "$sessionId.json")
                if (renameFile.exists()) {
                    val name = try { extractJsonString(renameFile.readText(), "name") } catch (_: Exception) { null }
                    if (name != null) {
                        LOG.info("[ClaudeTabs] POLL RENAME: '${tab.tabName}' → '$name'")
                        renameTab(project, tab, name)
                        renamedSessions.add(sessionId)
                        lastAppliedName[sessionId] = name
                    }
                }
            }

            val title = tab.widget?.terminalTitle?.buildTitle() ?: tab.tabName ?: "Claude"

            // Never save generic/unnamed tabs — only save tabs that were explicitly renamed
            if (isGenericTabName(title)) continue

            val bypass = readPermissionMode(cwd, sessionId)
            activeSessions.add(SavedSession(sessionId, cwd, title, bypass))
        }

        if (pollCount % 12 == 0) {
            if (claudeSessions.isNotEmpty()) LOG.info("[ClaudeTabs] STEP 6: Claude sessions found: $claudeSessions")
            LOG.info("[ClaudeTabs] STEP 7: Saving ${activeSessions.size} active session(s)")
        }

        // Detect closed sessions and write to history
        val currentIds = activeSessions.map { it.sessionId }.toSet()
        for ((id, session) in previousActive) {
            if (id !in currentIds) {
                appendToHistory(session)
                LOG.info("[ClaudeTabs] Session closed, saved to history: '${session.tabName}'")
            }
        }
        previousActive.clear()
        for (s in activeSessions) previousActive[s.sessionId] = s

        saveState(project, activeSessions)
    }

    // ══════════════════════════════════════════════════════════════
    // SESSION SAVE / RESTORE
    // ══════════════════════════════════════════════════════════════

    /** A Claude Code session that has been (or currently is) associated with a named terminal tab. */
    data class SavedSession(val sessionId: String, val cwd: String, val tabName: String, val bypassPermissions: Boolean)

    /** Lock object guarding all reads/writes to [HISTORY_FILE]. */
    private val historyLock = Any()

    /**
     * Append (or update) a session entry in history.json.
     *
     * Called when a tab closes (or when the IDE is shutting down) to preserve the session so the user
     * can browse/resume it later via `/tabs-history`. Entries older than [historyMaxAgeMs] are pruned.
     *
     * Thread-safe: wrapped in [historyLock] because the poll loop, file watcher, and project-close
     * disposable can all call this concurrently.
     */
    private fun appendToHistory(session: SavedSession) = synchronized(historyLock) {
        try {
            val now = System.currentTimeMillis()
            val entries = loadHistory().toMutableList()

            // Don't duplicate — replace any existing entry for the same sessionId.
            entries.removeAll { extractJsonString(it, "sessionId") == session.sessionId }

            val entry = "{\"sessionId\":\"${esc(session.sessionId)}\",\"cwd\":\"${esc(session.cwd)}\",\"tabName\":\"${esc(session.tabName)}\",\"bypassPermissions\":${session.bypassPermissions},\"closedAt\":$now}"
            entries.add(entry)

            // Prune entries older than configured retention window.
            val cutoff = now - historyMaxAgeMs
            val pruned = entries.filter { raw ->
                val ts = Regex(""""closedAt":(\d+)""").find(raw)?.groupValues?.get(1)?.toLongOrNull()
                ts != null && ts > cutoff
            }

            HISTORY_FILE.parentFile?.mkdirs()
            val sb = StringBuilder("[\n")
            pruned.forEachIndexed { i, e ->
                sb.append("  $e")
                if (i < pruned.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("]")
            HISTORY_FILE.writeText(sb.toString())
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] History write failed: ${e.message}")
        }
    }

    /**
     * Read the history file as a list of raw JSON entry strings.
     *
     * Uses a simple non-greedy regex rather than a full JSON parser because entries are flat
     * (no nested objects). See [appendToHistory] for the matching writer.
     */
    private fun loadHistory(): List<String> = synchronized(historyLock) {
        if (!HISTORY_FILE.exists()) return@synchronized emptyList()
        try {
            Regex("""\{[^}]+\}""").findAll(HISTORY_FILE.readText()).map { it.value }.toList()
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] History read failed: ${e.message}")
            emptyList()
        }
    }

    /** Stable hash of the project path, used as the suffix for restore/snapshot file names. */
    private fun projectHash(project: Project): String =
        (project.basePath ?: "default").replace("\\", "/").replace(":/", "--").replace("/", "-")

    private fun getStateFile(project: Project): File = File(STATE_DIR, "restore-${projectHash(project)}.json")

    /**
     * List this project's snapshot files in the snapshots dir, newest first (by filename —
     * filenames are `<projectHash>-<timestampMs>.json` so lexical order = chronological order).
     */
    private fun listSnapshots(project: Project): List<File> {
        val prefix = "${projectHash(project)}-"
        return SNAPSHOTS_DIR.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /**
     * Write a timestamped snapshot of [content] (already-serialised JSON array) to the snapshots
     * dir, then prune older snapshots beyond [snapshotKeepCount]. Silently best-effort — if this
     * fails the user still has the live restore file and history.json.
     */
    private fun writeSnapshot(project: Project, content: String) {
        if (snapshotKeepCount <= 0) return  // user disabled snapshots entirely
        try {
            SNAPSHOTS_DIR.mkdirs()
            val file = File(SNAPSHOTS_DIR, "${projectHash(project)}-${System.currentTimeMillis()}.json")
            file.writeText(content)

            // Prune older snapshots beyond the retention window.
            val existing = listSnapshots(project)
            if (existing.size > snapshotKeepCount) {
                existing.drop(snapshotKeepCount).forEach { old ->
                    try { old.delete() } catch (_: Exception) { /* best effort */ }
                }
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] Snapshot write failed: ${e.message}")
        }
    }

    /**
     * Write [sessions] to the project's restore file, overwriting the previous content.
     *
     * Important: if [sessions] is empty AND there are still [pendingRestores] waiting to be
     * placed, we leave the file alone. Otherwise the restore file can get wiped on the first
     * poll after startup — before terminal tabs have spawned Claude processes — destroying
     * the saved state we just loaded a second ago.
     */
    private fun saveState(project: Project, sessions: List<SavedSession>) {
        val f = getStateFile(project)
        try {
            if (sessions.isEmpty()) {
                if (pendingRestores.isNotEmpty()) {
                    // Restore hasn't finished yet — don't wipe the file we're still consuming.
                    return
                }
                f.delete(); return
            }
            val sb = StringBuilder("[\n")
            sessions.forEachIndexed { i, s ->
                sb.append("  {\"sessionId\":\"${esc(s.sessionId)}\",\"cwd\":\"${esc(s.cwd)}\",\"tabName\":\"${esc(s.tabName)}\",\"bypassPermissions\":${s.bypassPermissions}}")
                if (i < sessions.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("]")
            val content = sb.toString()
            f.writeText(content)

            // Capture a timestamped snapshot so a later wipe (crash mid-save, poll races, etc.)
            // can't lose the last known-good state.
            writeSnapshot(project, content)
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] Save state failed: ${e.message}")
        }
    }

    /**
     * Load the project's restore file into [pendingRestores]. If the live file is missing,
     * empty, or an empty array, fall back to the most recent non-empty snapshot from
     * [SNAPSHOTS_DIR]. This protects against:
     *   - Previous poll wiping the file (pre-fix bug).
     *   - Crashes during save that leave an empty or truncated file.
     *   - Accidental deletion by the user or external tools.
     */
    private fun loadRestoreFile(project: Project) {
        val sources = mutableListOf<File>().apply {
            val live = getStateFile(project)
            if (live.exists()) add(live)
            addAll(listSnapshots(project))  // newest → oldest
        }

        for ((index, source) in sources.withIndex()) {
            try {
                val json = source.readText().trim()
                if (json.isEmpty() || json == "[]") continue

                val loadedBefore = pendingRestores.size
                for (m in Regex("""\{[^}]+\}""").findAll(json)) {
                    val o = m.value
                    pendingRestores.add(SavedSession(
                        extractJsonString(o, "sessionId") ?: continue,
                        extractJsonString(o, "cwd") ?: continue,
                        extractJsonString(o, "tabName") ?: continue,
                        o.contains("\"bypassPermissions\":true")
                    ))
                }

                if (pendingRestores.size > loadedBefore) {
                    val provenance = if (index == 0) "live restore file" else "snapshot (${source.name})"
                    LOG.info("[ClaudeTabs] ${pendingRestores.size} session(s) to restore from $provenance")
                    // Don't delete yet — delete after all restores complete
                    return
                }
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] Restore source ${source.name} parse failed (trying next): ${e.message}")
            }
        }

        if (sources.isNotEmpty()) {
            LOG.info("[ClaudeTabs] No non-empty restore source found (${sources.size} candidates checked)")
        }
    }

    private fun processPendingRestores(project: Project) {
        if (pendingRestores.isEmpty()) return

        val tabs = getAllTabs(project)
        if (tabs.isEmpty()) {
            LOG.info("[ClaudeTabs] Restore waiting — no tabs with PIDs yet")
            return
        }

        val restored = mutableListOf<SavedSession>()
        val usedPids = mutableSetOf<Long>()

        for (s in pendingRestores) {
            // ONLY match by exact saved tab name — never grab generic "Local" tabs
            val match = tabs.find { it.tabName == s.tabName && it.pid !in usedPids }

            if (match != null) {
                val cmd = buildResumeCmd(s)
                renameTab(project, match, s.tabName)

                // Send command via createSendTextBuilder (proper API, no garbled text)
                val view = match.reworkedSession
                val w = match.widget
                var sent = false

                if (view != null) {
                    // Try createSendTextBuilder first (cleanest API — IntelliJ 2024.3+).
                    try {
                        val builder = view.javaClass.getMethod("createSendTextBuilder").invoke(view)
                        val shouldExec = builder.javaClass.getMethod("shouldExecute").invoke(builder)
                        shouldExec.javaClass.getMethod("send", String::class.java).invoke(shouldExec, cmd)
                        LOG.info("[ClaudeTabs] Sent via createSendTextBuilder")
                        sent = true
                    } catch (e: Exception) {
                        LOG.debug("[ClaudeTabs] createSendTextBuilder unavailable: ${e.message}")
                    }

                    // Fallback: sendText with newline (older API).
                    if (!sent) {
                        try {
                            view.javaClass.getMethod("sendText", String::class.java).invoke(view, cmd + "\n")
                            LOG.info("[ClaudeTabs] Sent via sendText")
                            sent = true
                        } catch (e: Exception) {
                            LOG.debug("[ClaudeTabs] sendText unavailable: ${e.message}")
                        }
                    }

                    // Last resort: write straight to the tty connector.
                    if (!sent) {
                        try {
                            val connector = extractConnectorFromSession(view)
                            connector?.write(cmd.toByteArray())
                            connector?.write("\r\n".toByteArray())
                            sent = true
                        } catch (e: Exception) {
                            LOG.debug("[ClaudeTabs] tty-connector write failed: ${e.message}")
                        }
                    }
                } else if (w != null) {
                    ApplicationManager.getApplication().invokeLater {
                        w.sendCommandToExecute(cmd)
                    }
                    sent = true
                }

                usedPids.add(match.pid)
                LOG.info("[ClaudeTabs] Restored '${s.tabName}' in '${match.tabName}' → ${s.sessionId}")
                restored.add(s)
            } else {
                LOG.info("[ClaudeTabs] Restore pending '${s.tabName}' — no idle tab available")
            }
        }
        pendingRestores.removeAll(restored)

        // Delete restore file only when ALL sessions have been restored
        if (pendingRestores.isEmpty() && restored.isNotEmpty()) {
            try {
                getStateFile(project).delete()
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] Failed to delete restore file after full restore: ${e.message}")
            }
        }
    }

    private fun buildResumeCmd(s: SavedSession): String = buildString {
        append("claude --resume ${s.sessionId}")
        if (s.bypassPermissions || shouldAlwaysBypass()) append(" --dangerously-skip-permissions")
    }

    private fun shouldAlwaysBypass(): Boolean {
        val f = File(CLAUDE_HOME, "settings.json")
        if (!f.exists()) return false
        return try {
            f.readText().let { it.contains("\"skipDangerousModePermissionPrompt\":true") || it.contains("\"skipDangerousModePermissionPrompt\": true") }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] settings.json read failed: ${e.message}")
            false
        }
    }

    private fun readPermissionMode(cwd: String, sessionId: String): Boolean {
        val h = cwd.replace("\\", "/").replace(":/", "--").replace("/", "-")
        val f = File(File(CLAUDE_HOME, "projects/$h"), "$sessionId.jsonl")
        if (!f.exists()) return false
        return try {
            BufferedReader(FileReader(f)).use { r ->
                repeat(5) {
                    val l = r.readLine() ?: return@use false
                    if (l.contains("\"permission-mode\"")) return@use extractJsonString(l, "permissionMode") == "bypassPermissions"
                }; false
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] session jsonl read failed: ${e.message}")
            false
        }
    }

    // ══════════════════════════════════════════════════════════════
    // CLAUDE DETECTION
    // ══════════════════════════════════════════════════════════════

    /**
     * Find the currently-alive Claude process PID that owns the given [sessionId],
     * by scanning the JSON files in `~/.claude/sessions/`. Returns null if no match
     * or the process has exited.
     */
    private fun findClaudePidForSession(sessionId: String): Long? {
        for (f in SESSIONS_DIR.listFiles() ?: emptyArray()) {
            if (!f.name.endsWith(".json")) continue
            try {
                if (extractJsonString(f.readText(), "sessionId") != sessionId) continue
                val pid = f.nameWithoutExtension.toLongOrNull() ?: continue
                if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) return pid
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] session lookup error for ${f.name}: ${e.message}")
            }
        }
        return null
    }

    /** Process names recognised as terminal shells (used by [findShellAncestor]). */
    private val SHELL_NAMES = setOf(
        "bash", "bash.exe", "sh", "sh.exe", "zsh", "fish",
        "pwsh", "pwsh.exe", "powershell", "powershell.exe", "cmd.exe"
    )

    /** @return true if [cmd] ends in a known shell executable name (any OS). */
    private fun isShellCommand(cmd: String): Boolean {
        val name = cmd.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return name in SHELL_NAMES
    }

    /**
     * Walk up at most 5 levels from [claudePid] looking for the terminal shell process that hosts
     * the Claude instance. Used by the PID-rename flow when the script writes its own PID and we
     * need to map back to a specific tab.
     */
    private fun findShellAncestor(claudePid: Long): Long? {
        var current = ProcessHandle.of(claudePid).orElse(null) ?: return null
        for (i in 0 until 5) {
            val parent = current.parent().orElse(null) ?: break
            current = parent
            val cmd = current.info().command().orElse("")
            if (isShellCommand(cmd)) return current.pid()
        }
        return ProcessHandle.of(claudePid).flatMap { it.parent() }.flatMap { it.parent() }.map { it.pid() }.orElse(null)
    }

    /**
     * Starting from the shell PID [pid] (the process hosting a terminal tab), search the full
     * descendant tree for a running Claude Code CLI process. Returns null if nothing matches.
     */
    private fun findClaudeChild(pid: Long): ProcessHandle? {
        val h = ProcessHandle.of(pid).orElse(null) ?: return null
        return findClaudeRec(h)
    }

    /** Recursive worker for [findClaudeChild]. Matches `claude[.exe|.cmd]` or `node` + `claude` args. */
    private fun findClaudeRec(h: ProcessHandle): ProcessHandle? {
        for (c in h.children().toList()) {
            val cmd = c.info().command().orElse(""); val line = c.info().commandLine().orElse("")
            if ((cmd.contains("claude", true) || line.contains("claude", true)) &&
                (cmd.endsWith("claude") || cmd.endsWith("claude.exe") || cmd.endsWith("claude.cmd") ||
                        line.contains("@anthropic", true) || line.contains("claude-code", true) ||
                        (cmd.contains("node", true) && line.contains("claude", true)))) return c
            findClaudeRec(c)?.let { return it }
        }
        return null
    }

    // ══════════════════════════════════════════════════════════════
    // AUTO-DEPLOY
    // ══════════════════════════════════════════════════════════════

    // CLAUDE_MD_MARKER and PERMISSION_ENTRY are in the companion object

    /**
     * Installs (and updates) the plugin's bash integration into the user's `~/.claude/` directory.
     * Safe to call on every startup — it's idempotent:
     *  - Files are overwritten from JAR resources (so script updates ship with plugin updates).
     *  - CLAUDE.md section is replaced between its markers (so instruction text stays current).
     *  - Permissions & hooks are only added if missing.
     *  - Old-named command files (pre-rename) are cleaned up.
     *
     * The complementary [uninstall] method lives in the companion object.
     */
    private fun deployClaudeIntegration() {
        try {
            deployResource("claude-integration/rename-tab.sh", File(CLAUDE_HOME, "rider-plugin/rename-tab.sh"))
            deployResource("claude-integration/session-start-hook.sh", File(CLAUDE_HOME, "rider-plugin/session-start-hook.sh"))
            File(CLAUDE_HOME, "commands").mkdirs()
            deployResource("claude-integration/tab.md", File(CLAUDE_HOME, "commands/tab.md"))
            deployResource("claude-integration/tabs-clear.md", File(CLAUDE_HOME, "commands/tabs-clear.md"))
            deployResource("claude-integration/tabs-restore.md", File(CLAUDE_HOME, "commands/tabs-restore.md"))
            deployResource("claude-integration/tabs-history.md", File(CLAUDE_HOME, "commands/tabs-history.md"))
            deployResource("claude-integration/tabs-backup.md", File(CLAUDE_HOME, "commands/tabs-backup.md"))
            deployResource("claude-integration/tabs-status.md", File(CLAUDE_HOME, "commands/tabs-status.md"))
            // Cleanup old command filenames (pre-rename)
            File(CLAUDE_HOME, "commands/clear-tabs.md").delete()
            File(CLAUDE_HOME, "commands/restore-tabs.md").delete()
            File(CLAUDE_HOME, "commands/tab-history.md").delete()
            File(CLAUDE_HOME, "commands/backup-tabs.md").delete()

            val claudeMd = File(CLAUDE_HOME, "CLAUDE.md")
            val existing = if (claudeMd.exists()) claudeMd.readText() else ""
            val claudeMdBlock = """
$CLAUDE_MD_MARKER
## Terminal Tab Naming (Rider Plugin)
At the **start of every conversation**, rename your Rider terminal tab by running:
```bash
bash ~/.claude/rider-plugin/rename-tab.sh "Short Topic Name"
```

**Naming priority:**
1. If the user provides a name — in their first message, via `/tab`, or by saying "name it X", "call this X", "name this X tab" — use their **exact words** as the tab name. "name this left tab" means the name IS "left tab". Never reinterpret the user's words as a description; take them literally as the desired name.
2. Otherwise, pick a concise name (3-5 words) that describes the conversation's purpose.
3. Update it if the topic shifts significantly.

This applies to **new chats, resumed chats** (`--resume`), **and `/resume`**. On resume, re-use the previous tab name if the topic hasn't changed.
$CLAUDE_MD_MARKER
""".trimStart()
            if (existing.contains(CLAUDE_MD_MARKER)) {
                // Replace existing section with latest version
                val pattern = Regex("$CLAUDE_MD_MARKER.*?$CLAUDE_MD_MARKER", RegexOption.DOT_MATCHES_ALL)
                val updated = existing.replace(pattern, claudeMdBlock.trim())
                if (updated != existing) {
                    claudeMd.writeText(updated)
                    LOG.info("[ClaudeTabs] Updated CLAUDE.md section")
                }
            } else {
                // First install — append
                claudeMd.appendText("\n$claudeMdBlock")
                LOG.info("[ClaudeTabs] Added CLAUDE.md section")
            }

            addPermission()
            addSessionStartHook()
        } catch (e: Exception) { LOG.warn("[ClaudeTabs] Deploy failed: ${e.message}") }
    }

    private val HOOK_MARKER = "session-start-hook.sh"
    private val HOOK_MARKER_LEGACY = "active-sessions"

    private fun addSessionStartHook() {
        val sf = File(CLAUDE_HOME, "settings.json")
        if (!sf.exists()) return
        try {
            val text = sf.readText()
            if (text.contains(HOOK_MARKER) || text.contains(HOOK_MARKER_LEGACY)) return

            val hookEntry = """
                      {
                        "hooks": [
                          {
                            "type": "command",
                            "command": "bash ~/.claude/rider-plugin/session-start-hook.sh",
                            "timeout": 5
                          }
                        ]
                      }
            """.trimIndent()

            if (!text.contains("\"hooks\"")) {
                // No hooks section at all — add the entire block
                val hookJson = "\"hooks\": {\n    \"SessionStart\": [\n      $hookEntry\n    ]\n  }"
                sf.writeText(text.trimEnd().removeSuffix("}") + ",\n  $hookJson\n}")
                LOG.info("[ClaudeTabs] Added hooks section with SessionStart hook")
            } else if (!text.contains("\"SessionStart\"")) {
                // Has hooks but no SessionStart — add SessionStart array
                sf.writeText(text.replace(Regex(""""hooks"\s*:\s*\{"""), "\"hooks\": {\n    \"SessionStart\": [\n      $hookEntry\n    ],"))
                LOG.info("[ClaudeTabs] Added SessionStart hook to existing hooks")
            } else {
                // Has SessionStart but our hook isn't in it — append to the array
                sf.writeText(text.replace(Regex(""""SessionStart"\s*:\s*\["""), "\"SessionStart\": [\n      $hookEntry,"))
                LOG.info("[ClaudeTabs] Appended hook to existing SessionStart array")
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] Hook install failed: ${e.message}")
        }
    }

    private fun addPermission() {
        val sf = File(CLAUDE_HOME, "settings.json")
        if (!sf.exists()) return
        try {
            val text = sf.readText()
            if (text.contains(PERMISSION_ENTRY)) return
            if (text.contains("\"allow\"")) {
                sf.writeText(text.replace(Regex(""""allow"\s*:\s*\["""), "\"allow\": [\"$PERMISSION_ENTRY\", "))
            } else if (text.contains("\"permissions\"")) {
                sf.writeText(text.replace(Regex(""""permissions"\s*:\s*\{"""), "\"permissions\": {\n    \"allow\": [\"$PERMISSION_ENTRY\"],"))
            } else {
                sf.writeText(text.trimEnd().removeSuffix("}") + ",\n  \"permissions\": {\n    \"allow\": [\"$PERMISSION_ENTRY\"]\n  }\n}")
            }
        } catch (e: Exception) { LOG.debug("[ClaudeTabs] Permission install failed: ${e.message}") }
    }

    private fun deployResource(path: String, target: File) {
        try { javaClass.classLoader.getResourceAsStream(path)?.let { target.parentFile?.mkdirs(); target.writeBytes(it.readBytes()) } } catch (e: Exception) { LOG.debug("[ClaudeTabs] Deploy resource failed: $path — ${e.message}") }
    }

    // ══════════════════════════════════════════════════════════════
    // UTILITIES
    // ══════════════════════════════════════════════════════════════

    private fun extractJsonString(json: String, key: String): String? {
        val m = Regex(""""$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(json) ?: return null
        return m.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"")
    }

    /** Tabs with generic names should never be saved for restore or grabbed for restore */
    private fun isGenericTabName(name: String): Boolean {
        val n = name.trim()
        return n == "Local" || n.matches(Regex("Local \\(\\d+\\)")) ||
            n == "bash" || n == "pwsh" || n == "PowerShell" || n == "cmd" ||
            n.matches(Regex("bash \\(\\d+\\)")) || n.matches(Regex("pwsh \\(\\d+\\)"))
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
