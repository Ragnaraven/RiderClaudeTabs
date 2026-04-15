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

class ClaudeTabWatcherStartup : StartupActivity.DumbAware {

    companion object {
        private val LOG = Logger.getInstance(ClaudeTabWatcherStartup::class.java)
        private const val POLL_INTERVAL_MS = 5_000L
        private val CLAUDE_HOME = File(System.getProperty("user.home"), ".claude")
        private val SESSIONS_DIR = File(CLAUDE_HOME, "sessions")
        private val TABS_DIR = File(CLAUDE_HOME, "rider-plugin/tabs")
        private val STATE_DIR = File(CLAUDE_HOME, "rider-plugin")
        private const val CLAUDE_MD_MARKER = "<!-- rider-claude-tabs-plugin -->"
        private const val PERMISSION_ENTRY = "Bash(bash ~/.claude/rider-plugin/rename-tab.sh *)"
        private val HISTORY_FILE = File(CLAUDE_HOME, "rider-plugin/history.json")
        private const val HISTORY_MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000 // 90 days

        /**
         * Removes all plugin artifacts from ~/.claude.
         * Called on plugin uninstall or via clear-tabs command.
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
            File(CLAUDE_HOME, "commands/clear-tabs.md").delete()
            File(CLAUDE_HOME, "commands/restore-tabs.md").delete()
            File(CLAUDE_HOME, "commands/tab-history.md").delete()
        }
    }

    private var pollCount = 0
    private val renamedSessions = mutableSetOf<String>()
    private val lastAppliedName = mutableMapOf<String, String>() // sessionId → name the plugin set
    private val pendingRestores = mutableListOf<SavedSession>()
    private val previousActive = mutableMapOf<String, SavedSession>() // sessionId → last known state

    override fun runActivity(project: Project) {
        LOG.info("[ClaudeTabs] Started for: ${project.name}")
        TABS_DIR.mkdirs()
        deployClaudeIntegration()

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        Disposer.register(project as Disposable, Disposable {
            LOG.info("[ClaudeTabs] Project closing")
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

    data class TabInfo(
        val content: Content?,              // null for reworked API tabs (split panels)
        val widget: TerminalWidget?,        // null when using reworked API
        val pid: Long,
        val reworkedSession: Any? = null,   // reworked session for PID/command access
        val reworkedTabId: Int? = null,      // for renameTerminalTab()
        val tabName: String = ""            // current tab name
    )

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
                } catch (_: Exception) {}
            }
            if (pollCount % 12 == 0) LOG.info("[ClaudeTabs] STEP 2: Frontend tabs: ${frontendTabs.size}, names: ${frontendTabs.map { it.content?.displayName ?: "?" }}")
        } catch (_: ClassNotFoundException) {}
        catch (_: Exception) {}

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
                } catch (_: Exception) {}
            }
            if (pollCount % 12 == 0) {
                LOG.info("[ClaudeTabs] STEP 3a: Backend all names: $backendNames")
                LOG.info("[ClaudeTabs] STEP 3b: Backend with PIDs: $backendWithPids")
                if (backendNoSession.isNotEmpty()) LOG.info("[ClaudeTabs] STEP 3c: Backend no session/pid: $backendNoSession")
            }
        } catch (_: ClassNotFoundException) {}
        catch (_: Exception) {}

        if (pollCount % 12 == 0) LOG.info("[ClaudeTabs] STEP 4: Total: ${result.size} → ${result.map { "'${it.tabName}'→PID${it.pid}" }}")
        return result
    }

    // Reworked API helpers
    private fun extractPidFromSession(session: Any): Long? {
        val targets = mutableListOf(session)
        try { val f = session.javaClass.getDeclaredField("delegate"); f.isAccessible = true; f.get(session)?.let { targets.add(0, it) } } catch (_: Exception) {}
        for (t in targets) {
            try {
                for (field in t.javaClass.declaredFields) {
                    if (!field.name.contains("ttyConnector", true)) continue
                    field.isAccessible = true
                    val c = field.get(t) ?: continue
                    if (c is ProcessTtyConnector) return c.process.pid()
                    try { (c.javaClass.getMethod("getProcess").invoke(c) as? Process)?.let { return it.pid() } } catch (_: Exception) {}
                    for (cf in c.javaClass.declaredFields) { cf.isAccessible = true; val v = cf.get(c); if (v is ProcessTtyConnector) return v.process.pid(); if (v is Process) return v.pid() }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun extractConnectorFromSession(session: Any): com.jediterm.terminal.TtyConnector? {
        val targets = mutableListOf(session)
        try { val f = session.javaClass.getDeclaredField("delegate"); f.isAccessible = true; f.get(session)?.let { targets.add(0, it) } } catch (_: Exception) {}
        for (t in targets) {
            try {
                for (field in t.javaClass.declaredFields) {
                    if (!field.name.contains("ttyConnector", true)) continue
                    field.isAccessible = true
                    val c = field.get(t)
                    if (c is com.jediterm.terminal.TtyConnector) return c
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun invokeSuspend(target: Any, method: java.lang.reflect.Method): Any? = kotlinx.coroutines.runBlocking {
        val d = CompletableDeferred<Any?>()
        val cont = object : kotlin.coroutines.Continuation<Any?> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(r: Result<Any?>) { d.complete(r.getOrNull()) }
        }
        val r = method.invoke(target, cont)
        if (r == kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) d.await() else r
    }

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
                    } catch (_: Exception) {}
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
            } catch (_: Exception) {}
        }

        // Path 4: Stable TerminalWidget API (classic terminal)
        tab.widget?.terminalTitle?.change { userDefinedTitle = name }
    }

    // ══════════════════════════════════════════════════════════════
    // FILE WATCHER — instant rename
    // ══════════════════════════════════════════════════════════════

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
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] TERMSESS: ${e.message}")
        }
        LOG.info("[ClaudeTabs] TERMSESS: no tab found for TERM_SESSION_ID=$termSessionId")
    }

    private fun handleRename(project: Project, sessionId: String, name: String) {
        // Direct match: find the tab whose Claude child has this session ID
        val tabs = getAllTabs(project)

        for (tab in tabs) {
            val claudeProcess = findClaudeChild(tab.pid) ?: continue
            val sf = File(SESSIONS_DIR, "${claudeProcess.pid()}.json")
            if (!sf.exists()) continue
            val tabSessionId = try { extractJsonString(sf.readText(), "sessionId") } catch (_: Exception) { null }

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
            } catch (_: Exception) {}
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

    data class SavedSession(val sessionId: String, val cwd: String, val tabName: String, val bypassPermissions: Boolean)

    private fun appendToHistory(session: SavedSession) {
        try {
            val now = System.currentTimeMillis()
            val entries = loadHistory().toMutableList()

            // Don't duplicate — update if same sessionId exists
            entries.removeAll { extractJsonString(it, "sessionId") == session.sessionId }

            val entry = "{\"sessionId\":\"${esc(session.sessionId)}\",\"cwd\":\"${esc(session.cwd)}\",\"tabName\":\"${esc(session.tabName)}\",\"bypassPermissions\":${session.bypassPermissions},\"closedAt\":$now}"
            entries.add(entry)

            // Prune entries older than 90 days
            val cutoff = now - HISTORY_MAX_AGE_MS
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
        } catch (e: Exception) { LOG.debug("[ClaudeTabs] History write failed: ${e.message}") }
    }

    private fun loadHistory(): List<String> {
        if (!HISTORY_FILE.exists()) return emptyList()
        return try {
            Regex("""\{[^}]+\}""").findAll(HISTORY_FILE.readText()).map { it.value }.toList()
        } catch (_: Exception) { emptyList() }
    }

    private fun getStateFile(project: Project): File {
        val h = (project.basePath ?: "default").replace("\\", "/").replace(":/", "--").replace("/", "-")
        return File(STATE_DIR, "restore-$h.json")
    }

    private fun saveState(project: Project, sessions: List<SavedSession>) {
        val f = getStateFile(project)
        try {
            if (sessions.isEmpty()) { f.delete(); return }
            val sb = StringBuilder("[\n")
            sessions.forEachIndexed { i, s ->
                sb.append("  {\"sessionId\":\"${esc(s.sessionId)}\",\"cwd\":\"${esc(s.cwd)}\",\"tabName\":\"${esc(s.tabName)}\",\"bypassPermissions\":${s.bypassPermissions}}")
                if (i < sessions.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("]"); f.writeText(sb.toString())
        } catch (e: Exception) { LOG.debug("[ClaudeTabs] Save state failed: ${e.message}") }
    }

    private fun loadRestoreFile(project: Project) {
        val f = getStateFile(project)
        if (!f.exists()) return
        try {
            val json = f.readText().trim()
            if (json.isEmpty() || json == "[]") return
            for (m in Regex("""\{[^}]+\}""").findAll(json)) {
                val o = m.value
                pendingRestores.add(SavedSession(
                    extractJsonString(o, "sessionId") ?: continue,
                    extractJsonString(o, "cwd") ?: continue,
                    extractJsonString(o, "tabName") ?: continue,
                    o.contains("\"bypassPermissions\":true")
                ))
            }
            if (pendingRestores.isNotEmpty()) LOG.info("[ClaudeTabs] ${pendingRestores.size} session(s) to restore")
            // Don't delete yet — delete after all restores complete
        } catch (_: Exception) {}
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
                    // Try createSendTextBuilder first (cleanest API)
                    try {
                        val builder = view.javaClass.getMethod("createSendTextBuilder").invoke(view)
                        val shouldExec = builder.javaClass.getMethod("shouldExecute").invoke(builder)
                        shouldExec.javaClass.getMethod("send", String::class.java).invoke(shouldExec, cmd)
                        LOG.info("[ClaudeTabs] Sent via createSendTextBuilder")
                        sent = true
                    } catch (_: Exception) {}

                    // Fallback: sendText with newline
                    if (!sent) {
                        try {
                            view.javaClass.getMethod("sendText", String::class.java).invoke(view, cmd + "\n")
                            LOG.info("[ClaudeTabs] Sent via sendText")
                            sent = true
                        } catch (_: Exception) {}
                    }

                    // Last resort: tty connector
                    if (!sent) {
                        try {
                            val connector = extractConnectorFromSession(view)
                            connector?.write(cmd.toByteArray())
                            connector?.write("\r\n".toByteArray())
                            sent = true
                        } catch (_: Exception) {}
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
            } catch (_: Exception) {}
        }
    }

    private fun buildResumeCmd(s: SavedSession): String = buildString {
        append("claude --resume ${s.sessionId}")
        if (s.bypassPermissions || shouldAlwaysBypass()) append(" --dangerously-skip-permissions")
    }

    private fun shouldAlwaysBypass(): Boolean {
        val f = File(CLAUDE_HOME, "settings.json")
        if (!f.exists()) return false
        return try { f.readText().let { it.contains("\"skipDangerousModePermissionPrompt\":true") || it.contains("\"skipDangerousModePermissionPrompt\": true") } }
        catch (_: Exception) { false }
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
        } catch (_: Exception) { false }
    }

    // ══════════════════════════════════════════════════════════════
    // CLAUDE DETECTION
    // ══════════════════════════════════════════════════════════════

    private fun findClaudePidForSession(sessionId: String): Long? {
        for (f in SESSIONS_DIR.listFiles() ?: emptyArray()) {
            if (!f.name.endsWith(".json")) continue
            try {
                if (extractJsonString(f.readText(), "sessionId") != sessionId) continue
                val pid = f.nameWithoutExtension.toLongOrNull() ?: continue
                if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) return pid
            } catch (_: Exception) {}
        }
        return null
    }

    private val SHELL_NAMES = setOf(
        "bash", "bash.exe", "sh", "sh.exe", "zsh", "fish",
        "pwsh", "pwsh.exe", "powershell", "powershell.exe", "cmd.exe"
    )

    private fun isShellCommand(cmd: String): Boolean {
        val name = cmd.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return name in SHELL_NAMES
    }

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

    private fun findClaudeChild(pid: Long): ProcessHandle? {
        val h = ProcessHandle.of(pid).orElse(null) ?: return null
        return findClaudeRec(h)
    }

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

    private fun deployClaudeIntegration() {
        try {
            deployResource("claude-integration/rename-tab.sh", File(CLAUDE_HOME, "rider-plugin/rename-tab.sh"))
            deployResource("claude-integration/session-start-hook.sh", File(CLAUDE_HOME, "rider-plugin/session-start-hook.sh"))
            File(CLAUDE_HOME, "commands").mkdirs()
            deployResource("claude-integration/tab.md", File(CLAUDE_HOME, "commands/tab.md"))
            deployResource("claude-integration/clear-tabs.md", File(CLAUDE_HOME, "commands/clear-tabs.md"))
            deployResource("claude-integration/restore-tabs.md", File(CLAUDE_HOME, "commands/restore-tabs.md"))
            deployResource("claude-integration/tab-history.md", File(CLAUDE_HOME, "commands/tab-history.md"))

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

            val hookJson = """
                "hooks": {
                    "SessionStart": [
                      {
                        "hooks": [
                          {
                            "type": "command",
                            "command": "bash ~/.claude/rider-plugin/session-start-hook.sh",
                            "timeout": 5
                          }
                        ]
                      }
                    ]
                  }
            """.trimIndent()

            if (!text.contains("\"hooks\"")) {
                sf.writeText(text.trimEnd().removeSuffix("}") + ",\n  $hookJson\n}")
                LOG.info("[ClaudeTabs] Added SessionStart hook")
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
