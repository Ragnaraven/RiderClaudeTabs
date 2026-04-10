package com.claudetabs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Key
import com.intellij.ui.content.Content
import com.jediterm.terminal.ProcessTtyConnector
import kotlinx.coroutines.*
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.lang.reflect.Method

class ClaudeTabWatcherStartup : StartupActivity.DumbAware {

    companion object {
        private val LOG = Logger.getInstance(ClaudeTabWatcherStartup::class.java)
        private const val POLL_INTERVAL_MS = 5_000L
        private val CLAUDE_HOME = File(System.getProperty("user.home"), ".claude")
        private val SESSIONS_DIR = File(CLAUDE_HOME, "sessions")
        private val TABS_DIR = File(CLAUDE_HOME, "rider-plugin/tabs")
        private val STATE_DIR = File(CLAUDE_HOME, "rider-plugin")
        private val SHELL_PID_KEY = Key.create<Long>("claudetabs.shellPid")
        private val REWORKED_TAB_ID_KEY = Key.create<Int>("claudetabs.reworkedTabId")
        private val LAST_NAME_KEY = Key.create<String>("claudetabs.lastName")
    }

    // Cache the reworked API methods so we don't re-reflect every poll
    private var tabsManager: Any? = null
    private var renameMethod: Method? = null
    private var apiInitialized = false
    private var pollCount = 0

    override fun runActivity(project: Project) {
        LOG.info("[ClaudeTabs] Started for: ${project.name}")
        TABS_DIR.mkdirs()

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            delay(2_000) // Minimal delay — just enough for terminal tool window

            withContext(Dispatchers.Main) {
                restoreSessions(project)
            }

            while (isActive) {
                try {
                    withContext(Dispatchers.Main) {
                        processPendingRestores(project)
                        poll(project)
                    }
                } catch (e: Exception) {
                    if (pollCount % 12 == 0) LOG.warn("[ClaudeTabs] Poll: ${e.message}")
                }
                // Poll faster while restores are pending
                delay(if (pendingRestores.isNotEmpty()) 2_000L else POLL_INTERVAL_MS)
                pollCount++
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // POLL — detect Claude, apply renames, save state
    // ══════════════════════════════════════════════════════════════

    data class TabMapping(val content: Content, val shellPid: Long, val reworkedTabId: Int?)

    private fun poll(project: Project) {
        val mappings = buildTabMappings(project)
        val activeSessions = mutableListOf<SavedSession>()

        for (m in mappings) {
            val claudeProcess = findClaudeChild(m.shellPid) ?: continue
            val claudePid = claudeProcess.pid()

            val sessionFile = File(SESSIONS_DIR, "$claudePid.json")
            if (!sessionFile.exists()) continue
            val sessionText = try { sessionFile.readText() } catch (_: Exception) { continue }
            val sessionId = extractJsonString(sessionText, "sessionId") ?: continue
            val cwd = extractJsonString(sessionText, "cwd") ?: continue

            // Check for rename request
            val renameFile = File(TABS_DIR, "$sessionId.json")
            if (renameFile.exists()) {
                val name = try { extractJsonString(renameFile.readText(), "name") } catch (_: Exception) { null }
                if (name != null && name != m.content.getUserData(LAST_NAME_KEY)) {
                    renameTab(project, m, name)
                    m.content.putUserData(LAST_NAME_KEY, name)
                }
            }

            val bypass = readPermissionMode(cwd, sessionId)
            activeSessions.add(SavedSession(sessionId, cwd, m.content.displayName ?: "Claude", bypass))
        }

        saveState(project, activeSessions)
    }

    /**
     * Rename via BOTH APIs to ensure it sticks:
     * 1. content.displayName — updates the ContentManager tab
     * 2. TerminalTabsManager.renameTerminalTab() — updates the reworked terminal internal state
     */
    private fun renameTab(project: Project, m: TabMapping, name: String) {
        val oldName = m.content.displayName

        // Reworked API rename (updates internal state, prevents it overriding our name)
        if (m.reworkedTabId != null) {
            try {
                val rm = getRenameMethod(project)
                if (rm != null && tabsManager != null) {
                    invokeSuspend3(tabsManager!!, rm, m.reworkedTabId, name, true)
                }
            } catch (e: Exception) {
                LOG.warn("[ClaudeTabs] Reworked rename failed: ${e.message}")
            }
        }

        // ContentManager rename (updates the visual tab)
        m.content.displayName = name
        LOG.info("[ClaudeTabs] Renamed '$oldName' → '$name'")
    }

    // ══════════════════════════════════════════════════════════════
    // TAB MAPPING — match Content ↔ reworked tab ↔ shell PID
    // ══════════════════════════════════════════════════════════════

    private fun buildTabMappings(project: Project): List<TabMapping> {
        val result = mutableListOf<TabMapping>()
        val manager = TerminalToolWindowManager.getInstance(project)
        val toolWindow = manager.toolWindow ?: return result
        val contents = toolWindow.contentManager.contents

        // Get fresh reworked tab data
        val reworkedTabs = getReworkedTabData(project) // name → (pid, tabId)

        for (content in contents) {
            // Try cached PID first
            var shellPid = content.getUserData(SHELL_PID_KEY)
            var tabId = content.getUserData(REWORKED_TAB_ID_KEY)

            // Discover from reworked API if not cached
            if (shellPid == null) {
                val name = content.displayName ?: continue
                val data = reworkedTabs[name]
                if (data != null) {
                    shellPid = data.first
                    tabId = data.second
                    content.putUserData(SHELL_PID_KEY, shellPid)
                    content.putUserData(REWORKED_TAB_ID_KEY, tabId)
                }
            }

            if (shellPid != null) {
                result.add(TabMapping(content, shellPid, tabId))
            }
        }
        return result
    }

    data class ReworkedTabInfo(val pid: Long, val tabId: Int)

    private fun getReworkedTabData(project: Project): Map<String, Pair<Long, Int>> {
        val result = mutableMapOf<String, Pair<Long, Int>>()
        try {
            val tmCls = Class.forName("com.intellij.terminal.backend.TerminalTabsManager")
            val tm = tmCls.getMethod("getInstance", Project::class.java).invoke(null, project) ?: return result
            tabsManager = tm // cache for rename calls

            val tabs = invokeSuspend(tm,
                tmCls.methods.find { it.name == "getTerminalTabs" }!!) as? List<*> ?: return result

            val smCls = Class.forName("com.intellij.terminal.backend.TerminalSessionsManager")
            val sm = smCls.getMethod("getInstance").invoke(null) ?: return result
            val getSess = smCls.methods.find { it.name == "getSession" && it.parameterCount == 1 } ?: return result

            for (tab in tabs) {
                tab ?: continue
                try {
                    val name = tab.javaClass.getMethod("getName").invoke(tab) as? String ?: continue
                    val tabId = tab.javaClass.getMethod("getId").invoke(tab) as? Int ?: continue
                    val sid = tab.javaClass.getMethod("getSessionId").invoke(tab) ?: continue
                    val sess = getSess.invoke(sm, sid) ?: continue
                    val pid = extractPidFromSession(sess) ?: continue
                    result[name] = Pair(pid, tabId)
                } catch (_: Exception) {}
            }
        } catch (_: ClassNotFoundException) {}
        return result
    }

    private fun getRenameMethod(project: Project): Method? {
        if (renameMethod != null) return renameMethod
        try {
            val cls = Class.forName("com.intellij.terminal.backend.TerminalTabsManager")
            renameMethod = cls.methods.find { it.name == "renameTerminalTab" }
        } catch (_: Exception) {}
        return renameMethod
    }

    // ══════════════════════════════════════════════════════════════
    // SESSION SAVE / RESTORE
    // ══════════════════════════════════════════════════════════════

    data class SavedSession(
        val sessionId: String, val cwd: String,
        val tabName: String, val bypassPermissions: Boolean
    )

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
            sb.append("]")
            f.writeText(sb.toString())
        } catch (_: Exception) {}
    }

    // Sessions pending restore — retried each poll until their tab's tty is ready
    private val pendingRestores = mutableListOf<SavedSession>()

    private fun restoreSessions(project: Project) {
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
            if (pendingRestores.isNotEmpty()) {
                LOG.info("[ClaudeTabs] ${pendingRestores.size} session(s) queued for restore")
            }
            f.delete()
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] Restore read error: ${e.message}")
        }
    }

    /**
     * Called each poll cycle. Tries to claim existing stale tabs by writing
     * the resume command into their tty. Tabs need an initialized terminal
     * session before we can write to them, so we retry until ready.
     */
    private fun processPendingRestores(project: Project) {
        if (pendingRestores.isEmpty()) return

        val mgr = TerminalToolWindowManager.getInstance(project)
        val toolWindow = mgr.toolWindow ?: return
        val cm = toolWindow.contentManager

        // Get tty connectors for all tabs
        val reworkedData = getReworkedTabData(project)

        val restored = mutableListOf<SavedSession>()

        for (s in pendingRestores) {
            // Find the stale tab with matching name
            val content = cm.contents.firstOrNull { it.displayName == s.tabName }
            if (content == null) {
                // Tab not found — create a new one as fallback
                try {
                    @Suppress("DEPRECATION")
                    val w = mgr.createLocalShellWidget(s.cwd, s.tabName)
                    w.executeCommand(buildResumeCmd(s))
                    LOG.info("[ClaudeTabs] Restored (new tab): ${s.tabName}")
                    restored.add(s)
                } catch (e: Exception) {
                    LOG.warn("[ClaudeTabs] Fallback restore failed: ${e.message}")
                    restored.add(s) // Give up on this one
                }
                continue
            }

            // Try to get the tty for this tab
            val tabData = reworkedData[s.tabName]
            if (tabData == null) {
                // Session not initialized yet — will retry next poll
                continue
            }

            val shellPid = tabData.first
            val connector = getConnectorForPid(shellPid, project, reworkedData)
            if (connector == null) {
                continue // Retry next poll
            }

            // Write the resume command directly into the existing shell.
            // Use \r\n (Windows line ending) and send command THEN enter.
            try {
                val cmd = buildResumeCmd(s)
                connector.write(cmd.toByteArray())
                connector.write("\r\n".toByteArray())
                LOG.info("[ClaudeTabs] Claimed tab '${s.tabName}' → resumed session ${s.sessionId}")
                restored.add(s)
            } catch (e: Exception) {
                LOG.warn("[ClaudeTabs] Write to tty failed: ${e.message}")
                restored.add(s) // Give up
            }
        }

        pendingRestores.removeAll(restored)
    }

    private fun buildResumeCmd(s: SavedSession): String = buildString {
        append("claude --resume ${s.sessionId}")
        // Check user's Claude settings for skipDangerousModePermissionPrompt
        if (s.bypassPermissions || shouldAlwaysBypass()) {
            append(" --dangerously-skip-permissions")
        }
    }

    private fun shouldAlwaysBypass(): Boolean {
        val settings = File(System.getProperty("user.home"), ".claude/settings.json")
        if (!settings.exists()) return false
        return try {
            settings.readText().contains("\"skipDangerousModePermissionPrompt\":true") ||
                settings.readText().contains("\"skipDangerousModePermissionPrompt\": true")
        } catch (_: Exception) { false }
    }

    /**
     * Get the TtyConnector for a shell PID by going through the reworked terminal session.
     */
    private fun getConnectorForPid(
        shellPid: Long, project: Project,
        reworkedData: Map<String, Pair<Long, Int>>
    ): com.jediterm.terminal.TtyConnector? {
        try {
            val smCls = Class.forName("com.intellij.terminal.backend.TerminalSessionsManager")
            val sm = smCls.getMethod("getInstance").invoke(null) ?: return null
            val getSess = smCls.methods.find { it.name == "getSession" && it.parameterCount == 1 }
                ?: return null

            val tmCls = Class.forName("com.intellij.terminal.backend.TerminalTabsManager")
            val tm = tmCls.getMethod("getInstance", Project::class.java).invoke(null, project)
                ?: return null
            val tabs = invokeSuspend(tm, tmCls.methods.find { it.name == "getTerminalTabs" }!!) as? List<*>
                ?: return null

            for (tab in tabs) {
                tab ?: continue
                try {
                    val sid = tab.javaClass.getMethod("getSessionId").invoke(tab) ?: continue
                    val session = getSess.invoke(sm, sid) ?: continue
                    val pid = extractPidFromSession(session)
                    if (pid == shellPid) {
                        return extractConnectorFromSession(session)
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractConnectorFromSession(session: Any): com.jediterm.terminal.TtyConnector? {
        val targets = mutableListOf(session)
        try {
            val f = session.javaClass.getDeclaredField("delegate")
            f.isAccessible = true
            f.get(session)?.let { targets.add(0, it) }
        } catch (_: Exception) {}
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

    private fun readPermissionMode(cwd: String, sessionId: String): Boolean {
        val h = cwd.replace("\\", "/").replace(":/", "--").replace("/", "-")
        val f = File(File(CLAUDE_HOME, "projects/$h"), "$sessionId.jsonl")
        if (!f.exists()) return false
        return try {
            BufferedReader(FileReader(f)).use { r ->
                repeat(5) {
                    val l = r.readLine() ?: return@use false
                    if (l.contains("\"type\":\"permission-mode\"") || l.contains("\"type\": \"permission-mode\""))
                        return@use extractJsonString(l, "permissionMode") == "bypassPermissions"
                }
                false
            }
        } catch (_: Exception) { false }
    }

    // ══════════════════════════════════════════════════════════════
    // PID EXTRACTION
    // ══════════════════════════════════════════════════════════════

    private fun extractPidFromSession(session: Any): Long? {
        val targets = mutableListOf(session)
        try {
            val f = session.javaClass.getDeclaredField("delegate")
            f.isAccessible = true
            f.get(session)?.let { targets.add(0, it) }
        } catch (_: Exception) {}
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

    // ══════════════════════════════════════════════════════════════
    // CLAUDE DETECTION
    // ══════════════════════════════════════════════════════════════

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
    // UTILITIES
    // ══════════════════════════════════════════════════════════════

    private fun invokeSuspend(target: Any, method: Method): Any? = runBlocking {
        val d = CompletableDeferred<Any?>()
        val cont = object : kotlin.coroutines.Continuation<Any?> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(r: Result<Any?>) { d.complete(r.getOrNull()) }
        }
        val r = method.invoke(target, cont)
        if (r == kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) d.await() else r
    }

    private fun invokeSuspend3(target: Any, method: Method, a1: Any, a2: Any, a3: Any): Any? = runBlocking {
        val d = CompletableDeferred<Any?>()
        val cont = object : kotlin.coroutines.Continuation<Any?> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(r: Result<Any?>) { d.complete(r.getOrNull()) }
        }
        val r = method.invoke(target, a1, a2, a3, cont)
        if (r == kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) d.await() else r
    }

    private fun extractJsonString(json: String, key: String): String? {
        val m = Regex(""""$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(json) ?: return null
        return m.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"")
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
