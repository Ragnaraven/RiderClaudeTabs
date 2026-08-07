package com.claudetabs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content
import java.io.File

/**
 * Reads Content↔session-id bindings for **reworked-terminal** tabs — the ones the IDE terminal's
 * built-in AI-agents launcher creates (its "Claude Code" title-bar button + agent dropdown).
 *
 * ## Why this exists
 *
 * Those tabs launch the Claude CLI directly as the tab's pty process, but they are NOT classic
 * [org.jetbrains.plugins.terminal.TerminalToolWindowManager] widgets: they never appear in
 * `getTerminalWidgets()` and `findWidgetByContent` returns null for them (field-verified on
 * 2026.2). So the classic Content→widget bridge — and any `getShellCommand()` read that hangs off
 * it — can never see them, and the tabs were lost on restart.
 *
 * The reworked terminal has its own public project service,
 * `com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager`, whose `getTabs()`
 * exposes, per tab, its [Content] and a `TerminalView` carrying the launch command
 * (`getStartupOptionsDeferred().getShellCommand()`) and pid. This bridge reaches that service by
 * reflection so a hard module dependency can never fail the plugin's load: every failure path
 * degrades to "no reworked tabs found", leaving the classic path untouched.
 *
 * ## How a tab yields its sid (birth-time facts, never a heuristic)
 *
 *  1. The tab's own shell command carries `--session-id <uuid>` — the id
 *     [ClaudeTabWatcherStartup.injectSessionIdIfClaudeLaunch] injected at spawn (confirmed to be
 *     the id Claude then persists under). Read straight back from the argv.
 *  2. Fallback for a tab launched before injection existed (or with injection disabled): the
 *     view's pid → `~/.claude/sessions/<pid>.json` → `sessionId`.
 */
internal object ReworkedTerminalBridge {

    private val LOG = Logger.getInstance(ReworkedTerminalBridge::class.java)

    private const val TABS_MANAGER_FQN =
        "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager"

    private val CLAUDE_SESSIONS_DIR = File(System.getProperty("user.home"), ".claude/sessions")

    /** Resolved once we find a classloader that can see the frontend module. */
    @Volatile private var managerClass: Class<*>? = null
    @Volatile private var loggedUnavailable = false
    @Volatile private var loggedFirstTabs = false

    /**
     * Present reworked Claude tabs as (Content, sid). Empty when the reworked frontend isn't
     * present, no reworked tabs exist, or none carry a resolvable Claude session id. Must be
     * called on the EDT (it reads live tool-window tab state).
     */
    fun presentClaudeTabs(project: Project): List<Pair<Content, String>> {
        val mgrClass = resolveManagerClass(project) ?: return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            val manager = project.getService(mgrClass as Class<Any>) ?: return emptyList()
            val tabs = mgrClass.getMethod("getTabs").invoke(manager) as? List<*> ?: return emptyList()
            val out = ArrayList<Pair<Content, String>>()
            var viewsSeen = 0
            var sidsRead = 0
            for (tab in tabs) {
                tab ?: continue
                val content = invokeNoArg(tab, "getContent") as? Content ?: continue
                val view = invokeNoArg(tab, "getView") ?: continue
                viewsSeen++
                val sid = sidFromView(view) ?: continue
                sidsRead++
                out.add(content to sid)
            }
            if (!loggedFirstTabs) {
                loggedFirstTabs = true
                LOG.info("[ClaudeTabs] Reworked bridge scan: ${tabs.size} tab(s), $viewsSeen with a view, " +
                    "$sidsRead carried a readable Claude sid")
            }
            out
        } catch (e: Throwable) {
            LOG.debug("[ClaudeTabs] reworked bridge scan failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Map each reworked tab's [Content] to its [com.intellij.terminal.TerminalTitle] — the SAME
     * title type the classic `TerminalWidget.getTerminalTitle()` exposes, so the caller can drive it
     * with the identical `title.change { userDefinedTitle = … }` path. Unlike the sid read, this
     * needs no startup-options future (which never completes here): `getView().getTitle()` is a plain
     * getter. This is how a widget-less reworked (agent-button) tab gets renamed / titled. EDT only.
     */
    fun titlesByContent(project: Project): Map<Content, com.intellij.terminal.TerminalTitle> {
        val mgrClass = resolveManagerClass(project) ?: return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            val manager = project.getService(mgrClass as Class<Any>) ?: return emptyMap()
            val tabs = mgrClass.getMethod("getTabs").invoke(manager) as? List<*> ?: return emptyMap()
            val out = HashMap<Content, com.intellij.terminal.TerminalTitle>()
            for (tab in tabs) {
                tab ?: continue
                val content = invokeNoArg(tab, "getContent") as? Content ?: continue
                val view = invokeNoArg(tab, "getView") ?: continue
                val title = (try { invokeNoArg(view, "getTitle") } catch (_: Throwable) { null })
                    as? com.intellij.terminal.TerminalTitle ?: continue
                out[content] = title
            }
            out
        } catch (e: Throwable) {
            LOG.debug("[ClaudeTabs] reworked titlesByContent failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * A reworked view's Claude session id: from its launch command first, then its pid.
     *
     * Emits a bounded, PII-free `[sid-diag]` breakdown (shapes only — token count, whether a session
     * flag is present, pid presence, whether the pid's session file exists) for the first few
     * failures, so we can see WHY a tab yields no sid without dumping paths/args.
     */
    private fun sidFromView(view: Any): String? {
        val deferred = invokeNoArg(view, "getStartupOptionsDeferred")
        val opts = completedValue(deferred)
        if (opts == null) { sidDiag("startupOptions deferred not completed (deferred=${deferred != null})"); return null }
        @Suppress("UNCHECKED_CAST")
        val command = try { invokeNoArg(opts, "getShellCommand") as? List<String> } catch (_: Throwable) { null }
        OwnedTerminalSpawner.sessionIdFromCommand(command)?.let { return it }
        val pid = try { invokeNoArg(opts, "getPid") as? Long } catch (_: Throwable) { null }
        val fromPid = pid?.let { sidFromPidFile(it) }
        if (fromPid == null) {
            val firstIsClaude = command?.firstOrNull()?.contains("claude", ignoreCase = true) == true
            val hasFlag = command?.any { it.startsWith("--session-id") || it.startsWith("--resume") || it == "-r" || it == "-c" || it == "--continue" } == true
            val pidFile = pid?.let { File(CLAUDE_SESSIONS_DIR, "$it.json").isFile }
            sidDiag("cmd=${command?.size ?: -1}tok firstClaude=$firstIsClaude sessFlag=$hasFlag pid=$pid pidFileExists=$pidFile")
        }
        return fromPid
    }

    private val sidDiagBudget = java.util.concurrent.atomic.AtomicInteger(10)
    private fun sidDiag(msg: String) {
        if (sidDiagBudget.getAndDecrement() > 0) LOG.info("[ClaudeTabs][sid-diag] $msg")
    }

    private fun sidFromPidFile(pid: Long): String? {
        return try {
            val f = File(CLAUDE_SESSIONS_DIR, "$pid.json")
            if (!f.isFile) return null
            // Minimal, dependency-free extraction of "sessionId":"<uuid>".
            val m = Regex("\"sessionId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"").find(f.readText())
            m?.groupValues?.get(1)
        } catch (_: Throwable) { null }
    }

    /** Read a kotlinx `Deferred`'s value if it has completed normally; null otherwise. */
    private fun completedValue(deferred: Any?): Any? {
        deferred ?: return null
        return try {
            val completed = invokeNoArg(deferred, "isCompleted") as? Boolean ?: false
            val cancelled = invokeNoArg(deferred, "isCancelled") as? Boolean ?: false
            if (!completed || cancelled) return null
            invokeNoArg(deferred, "getCompleted")
        } catch (_: Throwable) { null }
    }

    private fun invokeNoArg(target: Any, method: String): Any? =
        target.javaClass.getMethod(method).invoke(target)

    /**
     * Find a classloader that can load the frontend tabs-manager class.
     *
     * `intellij.terminal.frontend` is a **separate content module** of the terminal plugin with its
     * OWN classloader. The terminal plugin's *main* classloader (which loads the classic
     * [org.jetbrains.plugins.terminal.TerminalToolWindowManager] from `terminal.jar`) and the
     * platform loader both **cannot** `Class.forName` a frontend-module class — field-verified on
     * 2026.2, where those two candidates failed and the bridge went dark ("not resolvable").
     *
     * The reliable loader is the one that already loaded a **live reworked tab's UI component**: a
     * reworked terminal Content's component IS a frontend-module class, so its classloader can
     * always see its sibling `TerminalToolWindowTabsManager`. We harvest those component loaders
     * from the Terminal tool window and try them first, then fall back to the plugin/platform
     * loaders for older packagings where the class does live on the main loader.
     */
    private fun resolveManagerClass(project: Project): Class<*>? {
        managerClass?.let { return it }
        val loaders = LinkedHashSet<ClassLoader>()
        val diag = if (!loggedUnavailable) ArrayList<String>() else null // one-shot component dump
        // 1. Loaders harvested by DEEP-WALKING every terminal tool-window tab's component tree. The
        //    Content's top component is a platform wrapper (wrong loader — this is why the shallow
        //    read saw "1 loader tried"); the real frontend TerminalView sits deeper, and ITS loader
        //    can see the sibling tabs-manager. We collect every descendant component's loader.
        try {
            val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("Terminal")
            tw?.contentManagerIfCreated?.contents?.forEach { content ->
                val top = try { content.component } catch (_: Throwable) { null }
                harvestLoadersDeep(top, loaders, diag, contentName = safeName(content))
            }
        } catch (_: Throwable) { }
        // 2. Terminal plugin main loader + classic manager's loader (older/monolith packagings).
        try {
            val pid = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                com.intellij.openapi.extensions.PluginId.getId("org.jetbrains.plugins.terminal"),
            )
            pid?.classLoader?.let { loaders.add(it) }
        } catch (_: Throwable) { }
        try {
            loaders.add(org.jetbrains.plugins.terminal.TerminalToolWindowManager::class.java.classLoader)
        } catch (_: Throwable) { }
        for (loader in loaders) {
            val cls = try { Class.forName(TABS_MANAGER_FQN, false, loader) } catch (_: Throwable) { continue }
            // IDENTITY CHECK: a component loader may load the FQN as a DIFFERENT Class than the one
            // the platform registered the service under (dual-classloader trap). getService(thatClass)
            // then returns null and the bridge silently binds nothing — field-verified in 3.0.9 ("bridge
            // active" but zero Exact-adopts). Only accept a class whose getService actually resolves.
            val svc = try {
                @Suppress("UNCHECKED_CAST")
                project.getService(cls as Class<Any>)
            } catch (_: Throwable) { null }
            if (svc == null) {
                diag?.add("forName OK but getService NULL via ${loader.javaClass.name} — wrong class identity, skipping")
                continue
            }
            managerClass = cls
            LOG.info("[ClaudeTabs] Reworked-terminal bridge active (service resolved via ${loader.javaClass.name})")
            return cls
        }
        // Not resolvable *this pass* — but a reworked tab's frontend component may simply not exist
        // yet. Log ONCE with a component dump so the exact class+loader landscape is visible; do NOT
        // cache failure (managerClass stays null → later passes retry once tabs materialize).
        if (!loggedUnavailable) {
            loggedUnavailable = true
            LOG.info("[ClaudeTabs] Reworked-terminal tabs-manager not resolvable yet (${loaders.size} loader(s) tried) — " +
                "will retry on later passes; classic adoption path active meanwhile")
            diag?.take(60)?.forEach { LOG.info("[ClaudeTabs][bridge-diag] $it") }
        }
        return null
    }

    private fun safeName(content: Content): String =
        try { content.displayName ?: content.tabName ?: "?" } catch (_: Throwable) { "?" }

    /**
     * Breadth-first walk of an AWT/Swing component tree, adding every distinct component classloader
     * to [out]. Bounded (depth + node count) so a pathological tree can't hang the poll. When [diag]
     * is non-null, records `depth · componentClass · loaderName` lines for a one-shot log dump.
     */
    private fun harvestLoadersDeep(
        root: java.awt.Component?,
        out: MutableSet<ClassLoader>,
        diag: MutableList<String>?,
        contentName: String,
    ) {
        root ?: return
        val queue = ArrayDeque<Pair<java.awt.Component, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < 4000) {
            val (comp, depth) = queue.removeFirst()
            visited++
            try {
                val cls = comp.javaClass
                cls.classLoader?.let { out.add(it) }
                if (diag != null && (depth <= 3 || cls.name.contains("terminal", ignoreCase = true))) {
                    diag.add("[$contentName] d$depth ${cls.name} :: ${cls.classLoader?.javaClass?.name ?: "boot"}")
                }
            } catch (_: Throwable) { }
            if (depth < 12 && comp is java.awt.Container) {
                try { comp.components?.forEach { child -> if (child != null) queue.add(child to depth + 1) } } catch (_: Throwable) { }
            }
        }
    }
}
