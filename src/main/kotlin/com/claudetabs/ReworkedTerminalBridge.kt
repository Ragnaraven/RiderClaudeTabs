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
 *
 * ## Verified facts about the 2026.1 reworked view — do not re-derive
 *
 *  - **The startup-options `Deferred` DOES complete** (on the tab's first state event), and its
 *    `TerminalStartupOptions` then yields `getShellCommand()` (launch argv), `getPid()` (the OS
 *    pid of the pty ROOT process — null for remote/WSL-adapter processes) and
 *    `getWorkingDirectory()` (the launch cwd, non-null by construction). The long-standing
 *    "it never completes" belief was a REFLECTION BUG in [completedValue]: `getCompleted` was
 *    resolved off `target.javaClass`, which at runtime is the package-private
 *    `kotlinx.coroutines.CompletableDeferredImpl`, so the call threw `IllegalAccessException` and
 *    the blanket catch reported "not completed". Resolving it off the PUBLIC
 *    `kotlinx.coroutines.Deferred` interface works. (`isCompleted`/`isCancelled` always worked —
 *    they resolve to the public `JobSupport`.)
 *  - **A program's terminal title is NOT an identification signal here.** `TerminalTitle`'s
 *    `applicationTitle` is only ever written when the advanced setting
 *    `terminal.show.application.title` is ENABLED, and its default is **false**; unlike the classic
 *    path there is no per-tab opt-in. Runtime proof: a tab hosting the CLI showed
 *    `application=null` for its whole life. Titles are still READ (for the shell-tab veto) and
 *    WRITTEN (the rename path), but nothing may conclude "the CLI is/isn't here" from them.
 *  - **`getCurrentDirectory()` exists but returns null indefinitely** for a tab with no shell
 *    integration (it is fed by shell-integration events, or by a heuristic that only fires once
 *    the user presses Enter). `getWorkingDirectoryFlow()` does NOT exist on 2026.1 (2026.2+ only).
 *    So the startup options' working directory is the cwd source that actually answers.
 *
 * Everything reworked is `@ApiStatus.Experimental`, so EVERY access here stays reflective (no
 * compile-time reference) — that is also what keeps the Marketplace verifier clean.
 */
internal object ReworkedTerminalBridge {

    private val LOG = Logger.getInstance(ReworkedTerminalBridge::class.java)

    private const val TABS_MANAGER_FQN =
        "com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager"

    private val CLAUDE_SESSIONS_DIR = File(ClaudeHome.dir, "sessions")

    /** Resolved once we find a classloader that can see the frontend module. */
    @Volatile private var managerClass: Class<*>? = null
    @Volatile private var loggedUnavailable = false

    /** Last bridge-scan summary emitted — dedup so the ~5s poll logs only on change. */
    @Volatile private var lastScanSummary: String? = null

    /** Last per-tab cwd/view diagnostic block emitted — dedup so the ~5s poll logs only on change. */
    @Volatile private var lastCwdDiag: String? = null

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
            var startupReadable = 0
            var argvSids = 0
            var ptyPids = 0
            for (tab in tabs) {
                tab ?: continue
                val content = invokeNoArg(tab, "getContent") as? Content ?: continue
                val view = invokeNoArg(tab, "getView") ?: continue
                viewsSeen++
                val startup = startupFactsOf(view)
                if (startup.readable) startupReadable++
                if (OwnedTerminalSpawner.sessionIdFromCommand(startup.shellCommand) != null) argvSids++
                if (startup.pid != null) ptyPids++
                val sid = sidFromStartup(startup) ?: continue
                sidsRead++
                out.add(content to sid)
            }
            // Deduped on CHANGE (not one-shot): startupReadable flipping 0→N is precisely the
            // signal that the getCompleted fix took, and a one-shot line at IDE start — before any
            // tab exists — could never show it. Counts only; no paths, no argv.
            val scan = "[ClaudeTabs] Reworked bridge scan: ${tabs.size} tab(s), $viewsSeen with a view, " +
                "$startupReadable with readable startup options, $argvSids with an argv session id, " +
                "$ptyPids with a pty-root pid, $sidsRead carried a readable Claude sid"
            if (scan != lastScanSummary) { lastScanSummary = scan; LOG.info(scan) }
            out
        } catch (e: Throwable) {
            LOG.debug("[ClaudeTabs] reworked bridge scan failed: ${e.message}")
            emptyList()
        }
    }

    /** Everything one reworked tab's view can tell us in a single walk: its live title, its
     *  working directory, and the launch (startup-options) facts. EVERY field may be null — the
     *  getter is absent on this build, the reflective call degraded, or the backend hasn't
     *  reported yet — and every consumer treats null as "unknown", never as a fact. */
    data class ViewInfo(
        val title: com.intellij.terminal.TerminalTitle?,
        val cwd: String?,
        /** Runtime class of the view object — which API surface this build actually exposes. */
        val viewClass: String? = null,
        /** ASCII, PII-free account of every reflective step the cwd read took (see [cwdOfView]):
         *  which getter was tried and whether it was absent / threw / returned null / returned a
         *  value, and which source finally won. The one record of WHY a tab's cwd is unknown. */
        val cwdReason: String? = null,
        /** The tab's LAUNCH argv (`TerminalStartupOptions.getShellCommand()`). A birth-time fact:
         *  when it carries `--session-id <uuid>` the tab IS that session, with nothing to guess. */
        val shellCommand: List<String>? = null,
        /** OS pid of the tab's pty ROOT process (`TerminalStartupOptions.getPid()`). Null for
         *  remote/WSL-adapter processes — i.e. a non-null value is always a WINDOWS pid here. */
        val ptyRootPid: Long? = null,
        /** The cwd the tab was LAUNCHED in (`TerminalStartupOptions.getWorkingDirectory()`).
         *  Non-null by construction once the startup options are readable, which is why it is the
         *  cwd that actually answers when live shell integration stays quiet. */
        val startupCwd: String? = null,
    )

    /** The three startup-options facts, each independently degradable to null. */
    private data class StartupFacts(
        val shellCommand: List<String>? = null,
        val pid: Long? = null,
        val workingDirectory: String? = null,
    ) {
        val readable: Boolean get() = shellCommand != null || pid != null || workingDirectory != null
    }

    /**
     * Map each reworked tab's [Content] to its [ViewInfo] in ONE `getTabs()` walk.
     *
     * **Title** is a [com.intellij.terminal.TerminalTitle] — the SAME type the classic
     * `TerminalWidget.getTerminalTitle()` exposes, so the caller can drive it with the identical
     * `title.change { userDefinedTitle = … }` path — `getView().getTitle()` is a plain getter. This
     * is how a widget-less reworked (agent-button) tab gets renamed / titled. Reading it is not the
     * same as trusting it: the CLI's own terminal title never reaches `applicationTitle` here (see
     * the class KDoc), so a title identifies nothing on a reworked tab.
     *
     * **Cwd** matters because a hand-opened reworked tab has NO classic widget at all — the
     * container registry and `findWidgetByContent` both come up empty — so the view is the only
     * cwd source there is. Without it every such tab was "undetermined" and the strict 1:1
     * by-cwd adoption pass could never see it, which is why a tab in which the user typed
     * `claude` was never adopted. Preference: the LIVE `getCurrentDirectory()` when it is non-blank
     * (it follows a `cd`), else the startup options' LAUNCH directory — which is what actually
     * answers, since the live getter stays null for the whole life of a tab with no shell
     * integration. May be a remote (WSL-side) path — harmless: it simply won't match a project cwd.
     *
     * **Startup facts** (argv / pty-root pid / launch cwd) are the birth-time identity the exact
     * adoption passes run on: `--session-id` in the argv, and the pid whose process-tree children
     * include a hand-typed `claude`. EDT only.
     */
    fun viewInfoByContent(project: Project): Map<Content, ViewInfo> {
        val mgrClass = resolveManagerClass(project) ?: return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            val manager = project.getService(mgrClass as Class<Any>) ?: return emptyMap()
            val tabs = mgrClass.getMethod("getTabs").invoke(manager) as? List<*> ?: return emptyMap()
            val out = HashMap<Content, ViewInfo>()
            val diag = ArrayList<String>()
            var idx = -1
            for (tab in tabs) {
                tab ?: continue
                idx++
                val content = invokeNoArg(tab, "getContent") as? Content ?: continue
                val view = invokeNoArg(tab, "getView") ?: continue
                val title = (try { invokeNoArg(view, "getTitle") } catch (_: Throwable) { null })
                    as? com.intellij.terminal.TerminalTitle
                val viewClass = try { view.javaClass.name } catch (_: Throwable) { null }
                val startup = startupFactsOf(view)
                val (cwd, reason) = cwdOfView(view, startup.workingDirectory)
                out[content] = ViewInfo(
                    title = title,
                    cwd = cwd,
                    viewClass = viewClass,
                    cwdReason = reason,
                    shellCommand = startup.shellCommand,
                    ptyRootPid = startup.pid,
                    startupCwd = startup.workingDirectory,
                )
                // PII-free by construction: shapes and presence only — never the cwd string, never
                // an argv token. `argvSid` says whether the argv carried a session id, which is the
                // one thing about it worth knowing at a glance.
                diag.add(
                    "[ClaudeTabs][cwd-diag] tab#$idx view=${viewClass ?: "?"} " +
                        "cwd=${if (cwd == null) "null" else "<readable>"} $reason " +
                        "startupOpts=${if (startup.readable) "readable" else "dark"} " +
                        "argv=${startup.shellCommand?.let { "${it.size}tok" } ?: "absent"} " +
                        "argvSid=${OwnedTerminalSpawner.sessionIdFromCommand(startup.shellCommand) != null} " +
                        "ptyPid=${startup.pid ?: "absent"} " +
                        "startupCwd=${if (startup.workingDirectory == null) "absent" else "present"}",
                )
            }
            // Deduped exactly like the adopt-scan summary: the poll re-runs this walk every few
            // seconds, so only a CHANGE in the reflective picture is worth a line.
            val joined = diag.joinToString("\n")
            if (joined != lastCwdDiag) {
                lastCwdDiag = joined
                diag.forEach { LOG.info(it) }
            }
            out
        } catch (e: Throwable) {
            LOG.debug("[ClaudeTabs] reworked viewInfoByContent failed: ${e.message}")
            emptyMap()
        }
    }

    /** Titles only — the shape [TitleController] wants. Delegates to [viewInfoByContent]. */
    fun titlesByContent(project: Project): Map<Content, com.intellij.terminal.TerminalTitle> {
        val out = HashMap<Content, com.intellij.terminal.TerminalTitle>()
        for ((content, info) in viewInfoByContent(project)) info.title?.let { out[content] = it }
        return out
    }

    /**
     * A reworked view's working directory, or null — LIVE first, LAUNCH directory as the fallback.
     *
     *  1. `getCurrentDirectory(): String?` — a plain synchronous getter in 2026.1. It follows a
     *     `cd`, so it wins when it answers; but it is fed by shell integration, and for a tab with
     *     none (a pty running the CLI directly) it stays null for the tab's whole life.
     *  2. `getWorkingDirectoryFlow(): StateFlow<Path?>` → `getValue()` → `toString()` — the 2026.2+
     *     replacement (absent on 2026.1; the getter above is deprecated there and may eventually go).
     *  3. [startupCwd] — `TerminalStartupOptions.getWorkingDirectory()`, the directory the tab was
     *     LAUNCHED in. Non-null by construction once the startup options are readable, so this is
     *     the source that actually answers on 2026.1. It cannot follow a later `cd`; that is an
     *     accepted trade — a stale-by-cd cwd simply fails to match a project and adopts nothing,
     *     whereas no cwd at all made every reworked tab invisible to the strict by-cwd pass.
     *
     * Every reflective step gets its own catch: an absent method, a different return type, or a
     * flow that hasn't received its first value all degrade to the next source.
     *
     * Returns the cwd (or null) together with an ASCII, PII-free trace of what each step did —
     * including which source WON — so a dark cwd can be told apart from a missing API without a
     * debugger attached.
     */
    private fun cwdOfView(view: Any, startupCwd: String?): Pair<String?, String> {
        val trace = StringBuilder()
        fun fallback(): Pair<String?, String> {
            val s = startupCwd?.takeIf { it.isNotBlank() }
                ?: return null to trace.append(" src=none").toString()
            return s to trace.append(" src=startupOptions").toString()
        }
        try {
            val v = invokeNoArg(view, "getCurrentDirectory")
            when {
                v == null -> trace.append("getCurrentDirectory=null")
                v !is String -> trace.append("getCurrentDirectory=nonString(${v.javaClass.simpleName})")
                v.isBlank() -> trace.append("getCurrentDirectory=blank")
                else -> return v to trace.append("getCurrentDirectory=ok src=live").toString()
            }
        } catch (e: Throwable) {
            trace.append("getCurrentDirectory=").append(failLabel(e))
        }
        val flow = try {
            invokeNoArg(view, "getWorkingDirectoryFlow")
        } catch (e: Throwable) {
            trace.append(" workingDirectoryFlow=").append(failLabel(e))
            null
        }
        if (flow == null) {
            if (!trace.contains("workingDirectoryFlow=")) trace.append(" workingDirectoryFlow=null")
            return fallback()
        }
        val path = try {
            invokeNoArg(flow, "getValue")
        } catch (e: Throwable) {
            trace.append(" flowValue=").append(failLabel(e))
            return fallback()
        }
        if (path == null) { trace.append(" flowValue=null"); return fallback() }
        val s = try { path.toString() } catch (e: Throwable) {
            trace.append(" flowValue=toString-").append(failLabel(e))
            return fallback()
        }
        if (s.isBlank()) { trace.append(" flowValue=blank"); return fallback() }
        return s to trace.append(" flowValue=ok src=live").toString()
    }

    /**
     * The tab's birth-time launch facts, read from the (now readable — see the class KDoc)
     * startup-options `Deferred`: argv, pty-root pid, launch cwd. Each field is read in its own
     * try/catch, so one absent getter never costs the others; an unreadable Deferred yields an
     * all-null [StartupFacts], which is exactly how every consumer behaved before this existed.
     */
    private fun startupFactsOf(view: Any): StartupFacts {
        val deferred = try { invokeNoArg(view, "getStartupOptionsDeferred") } catch (_: Throwable) { null }
        val opts = completedValue(deferred) ?: return StartupFacts()
        @Suppress("UNCHECKED_CAST")
        val command = try { invokeNoArg(opts, "getShellCommand") as? List<String> } catch (_: Throwable) { null }
        val pid = try { invokeNoArg(opts, "getPid") as? Long } catch (_: Throwable) { null }
        val cwd = try { (invokeNoArg(opts, "getWorkingDirectory") as? String)?.takeIf { it.isNotBlank() } }
            catch (_: Throwable) { null }
        return StartupFacts(command, pid, cwd)
    }

    /** How a reflective call failed, in one ASCII token: the method simply isn't on this build, or
     *  the call itself blew up (unwrapping the reflection wrapper to the real cause). */
    private fun failLabel(e: Throwable): String = when (e) {
        is NoSuchMethodException -> "absent"
        is java.lang.reflect.InvocationTargetException ->
            "threw:" + (e.cause ?: e).javaClass.simpleName
        else -> "threw:" + e.javaClass.simpleName
    }

    /**
     * A reworked view's Claude session id: from its launch command first, then its pid.
     *
     * Emits a bounded, PII-free `[sid-diag]` breakdown (shapes only — token count, whether a session
     * flag is present, pid presence, whether the pid's session file exists) for the first few
     * failures, so we can see WHY a tab yields no sid without dumping paths/args.
     */
    private fun sidFromStartup(startup: StartupFacts): String? {
        if (!startup.readable) { sidDiag("startupOptions unreadable (deferred not completed or all getters dark)"); return null }
        val command = startup.shellCommand
        OwnedTerminalSpawner.sessionIdFromCommand(command)?.let { return it }
        val pid = startup.pid
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

    /** Resolved `getCompleted` accessor, cached after the first success (cleared if it ever
     *  fails, so a differently-loaded Deferred re-resolves instead of going permanently dark). */
    @Volatile private var getCompletedMethod: java.lang.reflect.Method? = null

    /**
     * Read a kotlinx `Deferred`'s value if it has completed normally; null otherwise.
     *
     * THE BUG THIS FIXES (cost three releases of wrong conclusions — see the class KDoc): the
     * getter must be resolved off the PUBLIC `kotlinx.coroutines.Deferred` interface. Resolving it
     * off `deferred.javaClass` finds the declaration on the package-private
     * `kotlinx.coroutines.CompletableDeferredImpl`, whose `invoke` throws `IllegalAccessException`
     * — swallowed by the catch and misreported as "the future never completes". `isCompleted` /
     * `isCancelled` are unaffected: they resolve to the public `JobSupport`.
     *
     * The interface is looked up through the DEFERRED's own classloader first (the reworked
     * frontend is a separate content module — see [resolveManagerClass] — and a Method from a
     * foreign loader's copy of the interface would not apply to this instance), then through the
     * plugin's, and finally the impl declaration forced open with `isAccessible`. Any failure
     * still degrades to null.
     */
    private fun completedValue(deferred: Any?): Any? {
        deferred ?: return null
        return try {
            if (invokeNoArg(deferred, "isCompleted") != true) return null
            if (invokeNoArg(deferred, "isCancelled") == true) return null
            getCompletedMethod?.let { cached ->
                try { return cached.invoke(deferred) } catch (_: Throwable) { getCompletedMethod = null }
            }
            for (m in completedGetterCandidates(deferred)) {
                try {
                    val v = m.invoke(deferred)
                    getCompletedMethod = m
                    return v
                } catch (_: Throwable) { /* next candidate */ }
            }
            null
        } catch (_: Throwable) { null }
    }

    /** Every way to reach `Deferred.getCompleted` for [target], best first (see [completedValue]). */
    private fun completedGetterCandidates(target: Any): List<java.lang.reflect.Method> {
        val out = ArrayList<java.lang.reflect.Method>(3)
        val loaders = LinkedHashSet<ClassLoader>()
        try { target.javaClass.classLoader?.let { loaders.add(it) } } catch (_: Throwable) { }
        try { ReworkedTerminalBridge::class.java.classLoader?.let { loaders.add(it) } } catch (_: Throwable) { }
        for (loader in loaders) {
            try {
                out.add(Class.forName("kotlinx.coroutines.Deferred", false, loader).getMethod("getCompleted"))
            } catch (_: Throwable) { /* next loader */ }
        }
        try {
            val m = target.javaClass.getMethod("getCompleted")
            try { m.isAccessible = true } catch (_: Throwable) { /* module-closed: try it anyway */ }
            out.add(m)
        } catch (_: Throwable) { }
        return out
    }

    /**
     * The [Content] of every tab the terminal FRONTEND lists, in its order — the tabs the user
     * actually sees, independent of any ToolWindow reference. Cheap: one `getTabs()` walk, no
     * view/title/startup reads, no logging. [TerminalTabs] unions this into every enumeration so a
     * tool window whose cached reference went stale can never hide a tab (see its KDoc).
     */
    fun tabContents(project: Project): List<Content> {
        val mgrClass = resolveManagerClass(project) ?: return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            val manager = project.getService(mgrClass as Class<Any>) ?: return emptyList()
            val tabs = mgrClass.getMethod("getTabs").invoke(manager) as? List<*> ?: return emptyList()
            tabs.mapNotNull { tab -> tab?.let { invokeNoArg(it, "getContent") as? Content } }
        } catch (e: Throwable) {
            LOG.debug("[ClaudeTabs] reworked bridge tab list failed: ${e.message}")
            emptyList()
        }
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
        // 2. Terminal plugin main loader (older/monolith packagings). Read off a class we already
        //    link against — this IS the terminal plugin's main classloader, identical to what a
        //    plugin-descriptor lookup would return, without touching the plugin-manager APIs
        //    (internal in 2026.2+).
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
