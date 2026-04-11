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
    }

    private var pollCount = 0
    private val renamedSessions = mutableSetOf<String>()
    private val pendingRestores = mutableListOf<SavedSession>()

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
            try { watchTabsDirectory(project) } catch (_: Exception) {}
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

        // Step 1: Get frontend views (name → TerminalView + Content) for visual rename
        val frontendViews = mutableMapOf<String, Pair<Any, Content?>>() // name → (view, content)
        try {
            val feMgrCls = Class.forName("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager")
            val feMgr = feMgrCls.getMethod("getInstance", Project::class.java).invoke(null, project)
            val feTabs = feMgr?.javaClass?.getMethod("getTabs")?.invoke(feMgr) as? List<*>
            LOG.info("[ClaudeTabs] STEP 1: Frontend has ${feTabs?.size ?: 0} tabs")

            feTabs?.forEach { feTab ->
                feTab ?: return@forEach
                try {
                    val content = feTab.javaClass.getMethod("getContent").invoke(feTab) as? Content
                    val view = feTab.javaClass.getMethod("getView").invoke(feTab) ?: return@forEach
                    val name = content?.displayName ?: try {
                        val title = view.javaClass.getMethod("getTitle").invoke(view)
                        title?.javaClass?.getMethod("buildTitle")?.invoke(title) as? String ?: "Local"
                    } catch (_: Exception) { "Local" }
                    frontendViews[name] = Pair(view, content)
                } catch (_: Exception) {}
            }
            LOG.info("[ClaudeTabs] STEP 2: Frontend views: ${frontendViews.keys}")
        } catch (_: ClassNotFoundException) {}
        catch (_: Exception) {}

        // Step 2: Get backend tabs (name → PID + session) for process detection
        try {
            val tmCls = Class.forName("com.intellij.terminal.backend.TerminalTabsManager")
            val tm = tmCls.getMethod("getInstance", Project::class.java).invoke(null, project)
            val tabs = tm?.let { invokeSuspend(it, tmCls.methods.find { m -> m.name == "getTerminalTabs" }!!) as? List<*> }
            LOG.info("[ClaudeTabs] STEP 3: Backend has ${tabs?.size ?: 0} tabs")

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

                    // Merge: find matching frontend view by name
                    val fe = frontendViews[name]
                    val view = fe?.first
                    val content = fe?.second
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
            LOG.info("[ClaudeTabs] STEP 3a: Backend all names: $backendNames")
            LOG.info("[ClaudeTabs] STEP 3b: Backend with PIDs: $backendWithPids")
            if (backendNoSession.isNotEmpty()) LOG.info("[ClaudeTabs] STEP 3c: Backend no session/pid: $backendNoSession")
        } catch (_: ClassNotFoundException) {}
        catch (_: Exception) {}

        LOG.info("[ClaudeTabs] STEP 4: Total: ${result.size} → ${result.map { "'${it.tabName}'→PID${it.pid}" }}")
        return result
    }

    /**
     * Recursively walk a Swing component tree to find TerminalWidget instances
     * that aren't already in our result list. This catches split panel terminals.
     */
    private fun findTerminalWidgetsInTree(
        component: java.awt.Component?,
        contents: Array<Content>,
        result: MutableList<TabInfo>,
        foundPids: MutableSet<Long>
    ) {
        if (component == null) return

        // Check if this component IS a TerminalWidget
        if (component is TerminalWidget) {
            try {
                val tty = component.ttyConnectorAccessor.ttyConnector
                if (tty is ProcessTtyConnector) {
                    val pid = tty.process.pid()
                    if (pid !in foundPids) {
                        // Find or create a Content association
                        val content = contents.firstOrNull { result.none { r -> r.content == it } }
                        if (content != null) {
                            result.add(TabInfo(content, component, pid))
                            foundPids.add(pid)
                            if (pollCount % 6 == 0) LOG.info("[ClaudeTabs] Tree-found widget PID=$pid")
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Check if component has TerminalWidget as a data key (TERMINAL_WIDGET_DATA_KEY)
        try {
            val dataKey = Class.forName("org.jetbrains.plugins.terminal.ui.TerminalContainer")
                .getField("TERMINAL_WIDGET_DATA_KEY").get(null)
            if (component is com.intellij.openapi.actionSystem.DataProvider) {
                val widget = component.getData(dataKey.toString())
                if (widget is TerminalWidget) {
                    val tty = widget.ttyConnectorAccessor.ttyConnector
                    if (tty is ProcessTtyConnector) {
                        val pid = tty.process.pid()
                        if (pid !in foundPids) {
                            val content = contents.firstOrNull { result.none { r -> r.content == it } }
                            if (content != null) {
                                result.add(TabInfo(content, widget, pid))
                                foundPids.add(pid)
                                if (pollCount % 6 == 0) LOG.info("[ClaudeTabs] DataKey-found widget PID=$pid")
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Recurse into children
        if (component is java.awt.Container) {
            for (child in component.components) {
                findTerminalWidgetsInTree(child, contents, result, foundPids)
            }
        }
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

    private fun renameTab(tab: TabInfo, name: String) {
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

        // Path 3: Stable TerminalWidget API (classic terminal)
        tab.widget?.terminalTitle?.change { userDefinedTitle = name }
    }

    /**
     * Extract shell PID from a frontend TerminalView.
     * The view's startupOptions or backing session has the ttyConnector.
     */
    private fun extractPidFromView(view: Any): Long? {
        // Try to get the session from the view via sessionState or similar
        // The TerminalView doesn't directly expose ttyConnector, but the
        // backing BackendTerminalSession (accessible via the session system) does.

        // Strategy: get all alive session PIDs and match by checking the view's
        // component tree for any process info
        // Actually, let's try getting it from the backend session system
        try {
            val smCls = Class.forName("com.intellij.terminal.backend.TerminalSessionsManager")
            val sm = smCls.getMethod("getInstance").invoke(null) ?: return null

            // The SessionsManager has a sessionsMap — reflect into it
            for (f in sm.javaClass.declaredFields) {
                if (f.name.contains("session", true) && f.type.name.contains("Map")) {
                    f.isAccessible = true
                    val map = f.get(sm) as? Map<*, *> ?: continue
                    // Each entry: TerminalSessionId → BackendTerminalSession
                    for ((_, session) in map) {
                        if (session == null) continue
                        // Check if this session's coroutineScope matches the view's
                        // This is a heuristic — match by checking if the PID is unique
                        val pid = extractPidFromSession(session)
                        if (pid != null) {
                            // We can't directly match view to session without more info
                            // Return PIDs that we'll match externally
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Simpler approach: traverse the view's component to find something with PID
        try {
            val component = view.javaClass.getMethod("getComponent").invoke(view) as? java.awt.Component
            return findPidInComponentTree(component)
        } catch (_: Exception) {}

        return null
    }

    private fun findPidInComponentTree(component: java.awt.Component?): Long? {
        if (component == null) return null
        // Check if this component is or contains a TerminalWidget
        if (component is TerminalWidget) {
            try {
                val tty = component.ttyConnectorAccessor.ttyConnector
                if (tty is ProcessTtyConnector) return tty.process.pid()
            } catch (_: Exception) {}
        }
        // Check for any object with getTtyConnector/getTtyConnectorAccessor
        try {
            for (m in component.javaClass.methods) {
                if (m.name == "getTtyConnectorAccessor" && m.parameterCount == 0) {
                    val accessor = m.invoke(component) ?: continue
                    val tty = accessor.javaClass.getMethod("getTtyConnector").invoke(accessor)
                    if (tty is ProcessTtyConnector) return tty.process.pid()
                }
                if (m.name == "getTtyConnector" && m.parameterCount == 0) {
                    val tty = m.invoke(component)
                    if (tty is ProcessTtyConnector) return tty.process.pid()
                }
            }
        } catch (_: Exception) {}
        // Recurse
        if (component is java.awt.Container) {
            for (child in component.components) {
                val pid = findPidInComponentTree(child)
                if (pid != null) return pid
            }
        }
        return null
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
                val sessionId = filename.removeSuffix(".json")
                delay(100)
                try {
                    val f = File(TABS_DIR, filename)
                    if (!f.exists()) continue
                    val name = extractJsonString(f.readText(), "name") ?: continue
                    LOG.info("[ClaudeTabs] Watcher: '$name' for $sessionId")
                    withContext(Dispatchers.Main) { handleRename(project, sessionId, name) }
                } catch (e: Exception) {
                    LOG.warn("[ClaudeTabs] Watcher: ${e.message}")
                }
            }
            key.reset()
        }
    }

    private fun handleRename(project: Project, sessionId: String, name: String) {
        val claudePid = findClaudePidForSession(sessionId)
        if (claudePid == null) { LOG.info("[ClaudeTabs] RENAME: no alive Claude for session $sessionId"); return }
        val shellPid = findShellAncestor(claudePid)
        if (shellPid == null) { LOG.info("[ClaudeTabs] RENAME: no shell ancestor for Claude PID $claudePid"); return }
        LOG.info("[ClaudeTabs] RENAME: session=$sessionId → Claude PID=$claudePid → shell PID=$shellPid")

        val tabs = getAllTabs(project)
        val match = tabs.find { it.pid == shellPid }
        if (match != null) {
            LOG.info("[ClaudeTabs] RENAME: '${match.tabName}' → '$name'")
            renameTab(match, name)
            renamedSessions.add(sessionId)
        } else {
            LOG.info("[ClaudeTabs] RENAME: FAILED — shell PID $shellPid not in tabs: ${tabs.map { it.pid }}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // POLL — fallback rename + state save
    // ══════════════════════════════════════════════════════════════

    private fun poll(project: Project) {
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

            // Fallback rename
            if (sessionId !in renamedSessions) {
                val renameFile = File(TABS_DIR, "$sessionId.json")
                if (renameFile.exists()) {
                    val name = try { extractJsonString(renameFile.readText(), "name") } catch (_: Exception) { null }
                    if (name != null) {
                        LOG.info("[ClaudeTabs] POLL RENAME: '${tab.tabName}' → '$name'")
                        renameTab(tab, name)
                        renamedSessions.add(sessionId)
                    }
                }
            }

            val title = tab.widget?.terminalTitle?.buildTitle() ?: tab.tabName ?: "Claude"
            val bypass = readPermissionMode(cwd, sessionId)
            activeSessions.add(SavedSession(sessionId, cwd, title, bypass))
        }

        if (claudeSessions.isNotEmpty()) {
            LOG.info("[ClaudeTabs] STEP 6: Claude sessions found: $claudeSessions")
        }
        LOG.info("[ClaudeTabs] STEP 7: Saving ${activeSessions.size} active session(s)")
        saveState(project, activeSessions)
    }

    // ══════════════════════════════════════════════════════════════
    // SESSION SAVE / RESTORE
    // ══════════════════════════════════════════════════════════════

    data class SavedSession(val sessionId: String, val cwd: String, val tabName: String, val bypassPermissions: Boolean)

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
        } catch (_: Exception) {}
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
            // Find a matching tab: by name first, then any idle tab
            var match = tabs.find { it.tabName == s.tabName && it.pid !in usedPids }
            if (match == null) {
                match = tabs.find { findClaudeChild(it.pid) == null && it.pid !in usedPids }
            }

            if (match != null) {
                val cmd = buildResumeCmd(s)
                renameTab(match, s.tabName)

                // Send command via TerminalView.sendText or widget API or tty fallback
                val view = match.reworkedSession
                val w = match.widget
                if (view != null) {
                    try {
                        val sendText = view.javaClass.getMethod("sendText", String::class.java)
                        sendText.invoke(view, cmd + "\n")
                        LOG.info("[ClaudeTabs] Sent command via TerminalView.sendText")
                    } catch (_: Exception) {
                        // Fallback: write to ttyConnector
                        try {
                            val connector = extractConnectorFromSession(view)
                            connector?.write(cmd.toByteArray())
                            connector?.write("\r\n".toByteArray())
                        } catch (_: Exception) {}
                    }
                } else if (w != null) {
                    ApplicationManager.getApplication().invokeLater {
                        w.sendCommandToExecute(cmd)
                    }
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
                val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
                if (project != null) getStateFile(project).delete()
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

    private fun findShellAncestor(claudePid: Long): Long? {
        var current = ProcessHandle.of(claudePid).orElse(null) ?: return null
        for (i in 0 until 5) {
            val parent = current.parent().orElse(null) ?: break
            current = parent
            val cmd = current.info().command().orElse("")
            if (cmd.contains("bash", true) || cmd.contains("pwsh", true) ||
                cmd.contains("powershell", true) || cmd.contains("cmd.exe", true) ||
                cmd.contains("zsh", true) || cmd.contains("fish", true) || cmd.contains("sh", true)
            ) return current.pid()
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

    private val CLAUDE_MD_MARKER = "<!-- rider-claude-tabs-plugin -->"
    private val PERMISSION_ENTRY = "Bash(bash ~/.claude/rider-plugin/rename-tab.sh *)"

    private fun deployClaudeIntegration() {
        try {
            deployResource("claude-integration/rename-tab.sh", File(CLAUDE_HOME, "rider-plugin/rename-tab.sh"))
            File(CLAUDE_HOME, "commands").mkdirs()
            deployResource("claude-integration/tab.md", File(CLAUDE_HOME, "commands/tab.md"))

            val claudeMd = File(CLAUDE_HOME, "CLAUDE.md")
            val existing = if (claudeMd.exists()) claudeMd.readText() else ""
            if (!existing.contains(CLAUDE_MD_MARKER)) {
                claudeMd.appendText("\n$CLAUDE_MD_MARKER\n## Terminal Tab Naming (Rider Plugin)\nAt the **start of every conversation**, rename your Rider terminal tab by running:\n```bash\nbash ~/.claude/rider-plugin/rename-tab.sh \"Short Topic Name\"\n```\nPick a concise name (3-5 words) that describes the conversation's purpose. Update it if the topic shifts significantly.\n\nThis applies to **new chats, resumed chats** (`--resume`), **and `/resume`**. On resume, re-use the previous tab name if the topic hasn't changed.\n$CLAUDE_MD_MARKER\n")
            }

            addPermission()
        } catch (_: Exception) {}
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
                // permissions exists but no allow
            } else {
                sf.writeText(text.trimEnd().removeSuffix("}") + ",\n  \"permissions\": {\n    \"allow\": [\"$PERMISSION_ENTRY\"]\n  }\n}")
            }
        } catch (_: Exception) {}
    }

    private fun deployResource(path: String, target: File) {
        try { javaClass.classLoader.getResourceAsStream(path)?.let { target.parentFile?.mkdirs(); target.writeBytes(it.readBytes()) } } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════
    // UTILITIES
    // ══════════════════════════════════════════════════════════════

    private fun extractJsonString(json: String, key: String): String? {
        val m = Regex(""""$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(json) ?: return null
        return m.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"")
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
