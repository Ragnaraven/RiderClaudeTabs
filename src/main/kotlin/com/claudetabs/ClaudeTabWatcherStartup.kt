package com.claudetabs

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.content.Content
import com.jediterm.terminal.ProcessTtyConnector
import kotlinx.coroutines.*
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.lang.reflect.Method
import java.nio.file.*

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

    private var tabsManager: Any? = null
    private var renameMethod: Method? = null
    private var pollCount = 0

    override fun runActivity(project: Project) {
        LOG.info("[ClaudeTabs] Started for: ${project.name}")
        TABS_DIR.mkdirs()
        deployClaudeIntegration()

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // Cancel scope when project closes
        Disposer.register(project as Disposable, Disposable {
            LOG.info("[ClaudeTabs] Project closing")
            // Save state from the last known good snapshot (saved by poll loop)
            scope.cancel()
        })

        // File watcher — reacts instantly when rename files appear
        // Runs independently so a crash here doesn't kill the poll loop
        scope.launch {
            delay(2_000)
            try {
                watchTabsDirectory(project)
            } catch (e: Exception) {
                LOG.warn("[ClaudeTabs] Watcher crashed (polling will handle renames): ${e.message}")
            }
        }

        // Main loop — restore, poll for state save, renames
        scope.launch {
            delay(2_000)
            withContext(Dispatchers.Main) { restoreSessions(project) }

            while (isActive) {
                try {
                    withContext(Dispatchers.Main) {
                        processPendingRestores(project)
                        poll(project)
                    }
                } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                    LOG.info("[ClaudeTabs] Project disposed, stopping poll")
                    break
                } catch (e: Exception) {
                    if (e.message?.contains("disposed") == true) {
                        LOG.info("[ClaudeTabs] Project disposed, stopping poll")
                        break
                    }
                    if (pollCount % 12 == 0) LOG.warn("[ClaudeTabs] Poll: ${e.message}")
                }
                delay(if (pendingRestores.isNotEmpty()) 2_000L else POLL_INTERVAL_MS)
                pollCount++
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // FILE WATCHER — instant rename on file creation
    // ══════════════════════════════════════════════════════════════

    private suspend fun watchTabsDirectory(project: Project) {
        try {
            val dir = TABS_DIR.toPath()
            val watcher = FileSystems.getDefault().newWatchService()
            dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)
            LOG.info("[ClaudeTabs] File watcher active on ${TABS_DIR.absolutePath}")

            while (currentCoroutineContext().isActive) {
                // poll with timeout so we can check cancellation
                val key = watcher.poll(2, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                for (event in key.pollEvents()) {
                    val filename = (event.context() as? Path)?.toString() ?: continue
                    if (!filename.endsWith(".json")) continue
                    val sessionId = filename.removeSuffix(".json")

                    // Small delay for file to finish writing
                    delay(100)

                    try {
                        val renameFile = File(TABS_DIR, filename)
                        if (!renameFile.exists()) continue
                        val name = extractJsonString(renameFile.readText(), "name") ?: continue

                        LOG.info("[ClaudeTabs] Watcher: rename request '$name' for session $sessionId")

                        // Reverse lookup: sessionId → Claude PID → shell PID → tab
                        withContext(Dispatchers.Main) {
                            handleInstantRename(project, sessionId, name)
                        }
                    } catch (e: Exception) {
                        LOG.warn("[ClaudeTabs] Watcher error: ${e.message}")
                    }
                }
                key.reset()
            }
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] Watcher failed, falling back to polling: ${e.message}")
        }
    }

    /**
     * Instant rename via reverse PID lookup.
     * sessionId → find Claude PID from ~/.claude/sessions/ → walk UP to shell → match tab.
     */
    private fun handleInstantRename(project: Project, sessionId: String, name: String) {
        // Step 1: Find Claude PID that has this sessionId
        val claudePid = findClaudePidForSession(sessionId) ?: run {
            LOG.info("[ClaudeTabs] No alive Claude process found for session $sessionId")
            return
        }

        // Step 2: Walk UP from Claude PID to find the shell ancestor
        val shellPid = findShellAncestor(claudePid) ?: run {
            LOG.info("[ClaudeTabs] Could not find shell ancestor for Claude PID $claudePid")
            return
        }

        // Step 3: Find the Content tab with this shell PID
        val manager = TerminalToolWindowManager.getInstance(project)
        val toolWindow = manager.toolWindow ?: return
        val contents = toolWindow.contentManager.contents

        // Check cached PIDs first
        var matchedContent: Content? = null
        for (content in contents) {
            if (content.getUserData(SHELL_PID_KEY) == shellPid) {
                matchedContent = content
                break
            }
        }

        // If no cached match, discover PIDs and try again
        if (matchedContent == null) {
            val reworkedData = getReworkedTabData(project)
            for (content in contents) {
                val contentName = content.displayName ?: continue
                val data = reworkedData[contentName]
                if (data != null) {
                    content.putUserData(SHELL_PID_KEY, data.first)
                    content.putUserData(REWORKED_TAB_ID_KEY, data.second)
                    if (data.first == shellPid) {
                        matchedContent = content
                    }
                }
            }
        }

        if (matchedContent == null) {
            LOG.info("[ClaudeTabs] No tab found for shell PID $shellPid (may not be initialized yet)")
            return
        }

        // Step 4: Rename
        val tabId = matchedContent.getUserData(REWORKED_TAB_ID_KEY)
        renameTab(project, TabMapping(matchedContent, shellPid, tabId), name)
        matchedContent.putUserData(LAST_NAME_KEY, name)
    }

    /**
     * Scan ~/.claude/sessions/ for an alive process with the given sessionId.
     */
    private fun findClaudePidForSession(sessionId: String): Long? {
        val files = SESSIONS_DIR.listFiles() ?: return null
        for (f in files) {
            if (!f.name.endsWith(".json")) continue
            try {
                val text = f.readText()
                val sid = extractJsonString(text, "sessionId") ?: continue
                if (sid != sessionId) continue
                val pid = f.nameWithoutExtension.toLongOrNull() ?: continue
                // Verify alive
                if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) {
                    return pid
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Walk UP the process tree from a Claude PID to find the shell ancestor.
     * Claude's process tree: shell (bash/pwsh) → node → claude
     * We want the shell PID (same as what the terminal tab owns).
     */
    private fun findShellAncestor(claudePid: Long): Long? {
        var current = ProcessHandle.of(claudePid).orElse(null) ?: return null
        // Walk up, keeping track of the last PID before we lose the parent
        // The shell is typically 1-3 levels up from claude
        var depth = 0
        while (depth < 5) {
            val parent = current.parent().orElse(null) ?: break
            current = parent
            depth++
            // Check if this looks like a terminal shell
            val cmd = current.info().command().orElse("")
            if (cmd.contains("bash", true) || cmd.contains("pwsh", true) ||
                cmd.contains("powershell", true) || cmd.contains("cmd.exe", true) ||
                cmd.contains("zsh", true) || cmd.contains("fish", true) ||
                cmd.contains("sh", true)
            ) {
                return current.pid()
            }
        }
        // Fallback: return the grandparent (usually the shell)
        val handle = ProcessHandle.of(claudePid).orElse(null) ?: return null
        return handle.parent().flatMap { it.parent() }.map { it.pid() }.orElse(null)
    }

    // ══════════════════════════════════════════════════════════════
    // POLL — detect Claude, apply renames (fallback), save state
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

            // Fallback rename check (watcher handles the fast path)
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

    private fun renameTab(project: Project, m: TabMapping, name: String) {
        val oldName = m.content.displayName
        if (m.reworkedTabId != null) {
            try {
                val rm = getRenameMethod(project)
                if (rm != null && tabsManager != null) {
                    invokeSuspend3(tabsManager!!, rm, m.reworkedTabId, name, true)
                }
            } catch (_: Exception) {}
        }
        m.content.displayName = name
        LOG.info("[ClaudeTabs] Renamed '$oldName' → '$name'")
    }

    // ══════════════════════════════════════════════════════════════
    // TAB MAPPING
    // ══════════════════════════════════════════════════════════════

    private fun buildTabMappings(project: Project): List<TabMapping> {
        val result = mutableListOf<TabMapping>()
        val manager = TerminalToolWindowManager.getInstance(project)
        val toolWindow = manager.toolWindow ?: return result
        val contents = toolWindow.contentManager.contents
        val reworkedTabs = getReworkedTabData(project)

        for (content in contents) {
            var shellPid = content.getUserData(SHELL_PID_KEY)
            var tabId = content.getUserData(REWORKED_TAB_ID_KEY)
            if (shellPid == null) {
                val name = content.displayName ?: continue
                val data = reworkedTabs[name]
                if (data != null) {
                    shellPid = data.first; tabId = data.second
                    content.putUserData(SHELL_PID_KEY, shellPid)
                    content.putUserData(REWORKED_TAB_ID_KEY, tabId)
                }
            }
            if (shellPid != null) result.add(TabMapping(content, shellPid, tabId))
        }
        return result
    }

    private fun getReworkedTabData(project: Project): Map<String, Pair<Long, Int>> {
        val result = mutableMapOf<String, Pair<Long, Int>>()
        try {
            val tmCls = Class.forName("com.intellij.terminal.backend.TerminalTabsManager")
            val tm = tmCls.getMethod("getInstance", Project::class.java).invoke(null, project) ?: return result
            tabsManager = tm
            val tabs = invokeSuspend(tm, tmCls.methods.find { it.name == "getTerminalTabs" }!!) as? List<*> ?: return result
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
                    extractPidFromSession(sess)?.let { result[name] = Pair(it, tabId) }
                } catch (_: Exception) {}
            }
        } catch (_: ClassNotFoundException) {}
        return result
    }

    private fun getRenameMethod(project: Project): Method? {
        if (renameMethod != null) return renameMethod
        try {
            renameMethod = Class.forName("com.intellij.terminal.backend.TerminalTabsManager")
                .methods.find { it.name == "renameTerminalTab" }
        } catch (_: Exception) {}
        return renameMethod
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
            if (pendingRestores.isNotEmpty()) LOG.info("[ClaudeTabs] ${pendingRestores.size} session(s) queued for restore")
            f.delete()
        } catch (e: Exception) { LOG.warn("[ClaudeTabs] Restore read error: ${e.message}") }
    }

    private fun processPendingRestores(project: Project) {
        if (pendingRestores.isEmpty()) return
        val mgr = TerminalToolWindowManager.getInstance(project)
        val toolWindow = mgr.toolWindow ?: return
        val cm = toolWindow.contentManager
        val reworkedData = getReworkedTabData(project)
        val restored = mutableListOf<SavedSession>()

        for (s in pendingRestores) {
            val content = cm.contents.firstOrNull { it.displayName == s.tabName }
            if (content == null) {
                try {
                    @Suppress("DEPRECATION")
                    val w = mgr.createLocalShellWidget(s.cwd, s.tabName)
                    w.executeCommand(buildResumeCmd(s))
                    LOG.info("[ClaudeTabs] Restored (new tab): ${s.tabName}")
                    restored.add(s)
                } catch (e: Exception) {
                    LOG.warn("[ClaudeTabs] Fallback restore failed: ${e.message}")
                    restored.add(s)
                }
                continue
            }
            val tabData = reworkedData[s.tabName] ?: continue
            val connector = getConnectorForPid(tabData.first, project) ?: continue
            try {
                connector.write(buildResumeCmd(s).toByteArray())
                connector.write("\r\n".toByteArray())
                LOG.info("[ClaudeTabs] Claimed tab '${s.tabName}' → resumed ${s.sessionId}")
                restored.add(s)
            } catch (e: Exception) {
                LOG.warn("[ClaudeTabs] Write to tty failed: ${e.message}")
                restored.add(s)
            }
        }
        pendingRestores.removeAll(restored)
    }

    private fun buildResumeCmd(s: SavedSession): String = buildString {
        append("claude --resume ${s.sessionId}")
        if (s.bypassPermissions || shouldAlwaysBypass()) append(" --dangerously-skip-permissions")
    }

    private fun shouldAlwaysBypass(): Boolean {
        val f = File(System.getProperty("user.home"), ".claude/settings.json")
        if (!f.exists()) return false
        return try { val t = f.readText(); t.contains("\"skipDangerousModePermissionPrompt\":true") || t.contains("\"skipDangerousModePermissionPrompt\": true") }
        catch (_: Exception) { false }
    }

    private fun getConnectorForPid(shellPid: Long, project: Project): com.jediterm.terminal.TtyConnector? {
        try {
            val smCls = Class.forName("com.intellij.terminal.backend.TerminalSessionsManager")
            val sm = smCls.getMethod("getInstance").invoke(null) ?: return null
            val getSess = smCls.methods.find { it.name == "getSession" && it.parameterCount == 1 } ?: return null
            val tmCls = Class.forName("com.intellij.terminal.backend.TerminalTabsManager")
            val tm = tmCls.getMethod("getInstance", Project::class.java).invoke(null, project) ?: return null
            val tabs = invokeSuspend(tm, tmCls.methods.find { it.name == "getTerminalTabs" }!!) as? List<*> ?: return null
            for (tab in tabs) {
                tab ?: continue
                try {
                    val sid = tab.javaClass.getMethod("getSessionId").invoke(tab) ?: continue
                    val session = getSess.invoke(sm, sid) ?: continue
                    if (extractPidFromSession(session) == shellPid) return extractConnectorFromSession(session)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractConnectorFromSession(session: Any): com.jediterm.terminal.TtyConnector? {
        val targets = mutableListOf(session)
        try { val f = session.javaClass.getDeclaredField("delegate"); f.isAccessible = true; f.get(session)?.let { targets.add(0, it) } } catch (_: Exception) {}
        for (t in targets) { try { for (field in t.javaClass.declaredFields) { if (!field.name.contains("ttyConnector", true)) continue; field.isAccessible = true; val c = field.get(t); if (c is com.jediterm.terminal.TtyConnector) return c } } catch (_: Exception) {} }
        return null
    }

    private fun readPermissionMode(cwd: String, sessionId: String): Boolean {
        val h = cwd.replace("\\", "/").replace(":/", "--").replace("/", "-")
        val f = File(File(CLAUDE_HOME, "projects/$h"), "$sessionId.jsonl")
        if (!f.exists()) return false
        return try { BufferedReader(FileReader(f)).use { r -> repeat(5) { val l = r.readLine() ?: return@use false; if (l.contains("\"type\":\"permission-mode\"") || l.contains("\"type\": \"permission-mode\"")) return@use extractJsonString(l, "permissionMode") == "bypassPermissions" }; false } } catch (_: Exception) { false }
    }

    // ══════════════════════════════════════════════════════════════
    // PID EXTRACTION + CLAUDE DETECTION
    // ══════════════════════════════════════════════════════════════

    private fun extractPidFromSession(session: Any): Long? {
        val targets = mutableListOf(session)
        try { val f = session.javaClass.getDeclaredField("delegate"); f.isAccessible = true; f.get(session)?.let { targets.add(0, it) } } catch (_: Exception) {}
        for (t in targets) { try { for (field in t.javaClass.declaredFields) { if (!field.name.contains("ttyConnector", true)) continue; field.isAccessible = true; val c = field.get(t) ?: continue; if (c is ProcessTtyConnector) return c.process.pid(); try { (c.javaClass.getMethod("getProcess").invoke(c) as? Process)?.let { return it.pid() } } catch (_: Exception) {}; for (cf in c.javaClass.declaredFields) { cf.isAccessible = true; val v = cf.get(c); if (v is ProcessTtyConnector) return v.process.pid(); if (v is Process) return v.pid() } } } catch (_: Exception) {} }
        return null
    }

    private fun findClaudeChild(pid: Long): ProcessHandle? {
        val h = ProcessHandle.of(pid).orElse(null) ?: return null; return findClaudeRec(h)
    }

    private fun findClaudeRec(h: ProcessHandle): ProcessHandle? {
        for (c in h.children().toList()) {
            val cmd = c.info().command().orElse(""); val line = c.info().commandLine().orElse("")
            if ((cmd.contains("claude", true) || line.contains("claude", true)) && (cmd.endsWith("claude") || cmd.endsWith("claude.exe") || cmd.endsWith("claude.cmd") || line.contains("@anthropic", true) || line.contains("claude-code", true) || (cmd.contains("node", true) && line.contains("claude", true)))) return c
            findClaudeRec(c)?.let { return it }
        }; return null
    }

    // ══════════════════════════════════════════════════════════════
    // AUTO-DEPLOY Claude integration files on first run
    // ══════════════════════════════════════════════════════════════

    private val CLAUDE_MD_MARKER = "<!-- rider-claude-tabs-plugin -->"

    private fun deployClaudeIntegration() {
        try {
            // 1. Deploy rename-tab.sh
            val scriptFile = File(CLAUDE_HOME, "rider-plugin/rename-tab.sh")
            deployResource("claude-integration/rename-tab.sh", scriptFile)

            // 2. Deploy /tab command
            val cmdDir = File(CLAUDE_HOME, "commands")
            cmdDir.mkdirs()
            deployResource("claude-integration/tab.md", File(cmdDir, "tab.md"))

            // 3. Add instruction to global CLAUDE.md (idempotent)
            val claudeMd = File(CLAUDE_HOME, "CLAUDE.md")
            val existing = if (claudeMd.exists()) claudeMd.readText() else ""
            if (!existing.contains(CLAUDE_MD_MARKER)) {
                val section = """
                    |
                    |$CLAUDE_MD_MARKER
                    |## Terminal Tab Naming (Rider Plugin)
                    |At the **start of every conversation**, rename your Rider terminal tab by running:
                    |```bash
                    |bash ~/.claude/rider-plugin/rename-tab.sh "Short Topic Name"
                    |```
                    |Pick a concise name (3-5 words) that describes the conversation's purpose. Update it if the topic shifts significantly. The Rider plugin auto-detects Claude sessions, applies the name, and restores sessions on Rider restart.
                    |
                    |This applies to **new chats, resumed chats** (`--resume`), **and `/resume`**. On resume, re-use the previous tab name if the topic hasn't changed.
                    |$CLAUDE_MD_MARKER
                """.trimMargin()
                claudeMd.appendText(section)
                LOG.info("[ClaudeTabs] Added instruction to CLAUDE.md")
            }

            LOG.info("[ClaudeTabs] Integration files deployed")
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] Deploy failed: ${e.message}")
        }
    }

    private fun deployResource(resourcePath: String, target: File) {
        // Always overwrite to keep in sync with plugin version
        try {
            val stream = javaClass.classLoader.getResourceAsStream(resourcePath) ?: return
            target.parentFile?.mkdirs()
            target.writeBytes(stream.readBytes())
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════
    // UTILITIES
    // ══════════════════════════════════════════════════════════════

    private fun invokeSuspend(target: Any, method: Method): Any? = runBlocking {
        val d = CompletableDeferred<Any?>(); val cont = object : kotlin.coroutines.Continuation<Any?> { override val context = kotlin.coroutines.EmptyCoroutineContext; override fun resumeWith(r: Result<Any?>) { d.complete(r.getOrNull()) } }
        val r = method.invoke(target, cont); if (r == kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) d.await() else r
    }

    private fun invokeSuspend3(target: Any, method: Method, a1: Any, a2: Any, a3: Any): Any? = runBlocking {
        val d = CompletableDeferred<Any?>(); val cont = object : kotlin.coroutines.Continuation<Any?> { override val context = kotlin.coroutines.EmptyCoroutineContext; override fun resumeWith(r: Result<Any?>) { d.complete(r.getOrNull()) } }
        val r = method.invoke(target, a1, a2, a3, cont); if (r == kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) d.await() else r
    }

    private fun extractJsonString(json: String, key: String): String? {
        val m = Regex(""""$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(json) ?: return null
        return m.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"")
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
