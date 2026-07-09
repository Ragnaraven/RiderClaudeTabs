package com.claudetabs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.terminal.ui.TerminalWidget
import com.jediterm.terminal.ProcessTtyConnector

/**
 * Matches terminal tabs to the Claude sessions running inside them, so Claude's
 * auto-generated session name can be applied to ANY tab — including ones the user opened
 * manually and typed `claude` into (not just tabs the plugin spawned).
 *
 * Two halves:
 *
 *  1. [extractPidFromWidget] — reflection dig for a widget's shell process PID. Ported from
 *     the 1.x codebase (it survived three IDE versions of terminal rework there). Reflection
 *     is unavoidable: Rider 2026.1's reworked terminal doesn't expose the tty process via
 *     public API. All failures degrade to null → that tab just doesn't get auto-named.
 *
 *  2. [isHostedBy] — pure ancestry check: does walking up from a Claude PID's parent chain
 *     reach the tab's shell PID? Typical chain: `claude.exe → pwsh.exe (the tab's shell)`,
 *     1 hop. Wrappers (`claude.cmd → node → pwsh`) add hops, so we allow up to [MAX_HOPS].
 *     Injected parentOf lambda keeps this unit-testable.
 */
internal object TabSessionMatcher {

    private val LOG = Logger.getInstance(TabSessionMatcher::class.java)

    const val MAX_HOPS = 8

    /**
     * Shell PID of the process backing [widget], or null if the reworked terminal hides it.
     *
     * Strategy:
     *  1. Public-ish `getTtyConnector()` getter via reflection (cross-version safe).
     *  2. Field walk over the widget's class hierarchy for anything named `ttyConnector*`,
     *     unwrapping a [ProcessTtyConnector] or a `getProcess()` method.
     */
    fun extractPidFromWidget(widget: TerminalWidget?): Long? {
        widget ?: return null

        // 0. Most stable: the PUBLIC ShellTerminalWidget.getProcessTtyConnector() (and the
        //    older getTtyConnector()). These are public API, not private fields, so they
        //    survive terminal reworks. Try them by name on whatever widget we hold.
        for (method in listOf("getProcessTtyConnector", "getTtyConnector")) {
            try {
                val getter = widget.javaClass.methods.find { it.name == method && it.parameterCount == 0 } ?: continue
                val connector = getter.invoke(widget) ?: continue
                if (connector is ProcessTtyConnector) return connector.process.pid()
                try {
                    (connector.javaClass.getMethod("getProcess").invoke(connector) as? Process)?.let { return it.pid() }
                } catch (_: Exception) { /* try next */ }
            } catch (e: Exception) {
                LOG.debug("[ClaudeTabs] extractPidFromWidget $method probe failed: ${e.message}")
            }
        }

        var cls: Class<*>? = widget.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (!field.name.contains("ttyConnector", true)) continue
                try {
                    field.isAccessible = true
                    val c = field.get(widget) ?: continue
                    if (c is ProcessTtyConnector) return c.process.pid()
                    try {
                        (c.javaClass.getMethod("getProcess").invoke(c) as? Process)?.let { return it.pid() }
                    } catch (_: Exception) { /* try nested fields */ }
                    for (cf in c.javaClass.declaredFields) {
                        cf.isAccessible = true
                        val v = cf.get(c)
                        if (v is ProcessTtyConnector) return v.process.pid()
                        if (v is Process) return v.pid()
                    }
                } catch (e: Exception) {
                    LOG.debug("[ClaudeTabs] extractPidFromWidget field probe failed for ${field.name}: ${e.message}")
                }
            }
            cls = cls.superclass
        }

        // Last resort: bounded recursive walk of the widget's object graph for ANY
        // ProcessTtyConnector / Process. The reworked terminal reaches the tty process via
        // differently-named fields across versions; this finds it without hard-coding names.
        // Safe: a wrong PID can't cause a wrong tab→session match — isHostedBy verifies the
        // process ancestry. Bounded by depth + a visited set so it can't loop or go wide.
        return deepFindProcessPid(widget, depth = 5, visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap()))
    }

    /** Recursively search [root]'s fields (up to [depth] levels) for a [ProcessTtyConnector]
     *  or [Process], returning its PID. Skips JDK/primitive leaves and already-visited objects. */
    private fun deepFindProcessPid(root: Any?, depth: Int, visited: MutableSet<Any>): Long? {
        if (root == null || depth < 0 || !visited.add(root)) return null
        if (root is ProcessTtyConnector) return try { root.process.pid() } catch (_: Throwable) { null }
        if (root is Process) return try { root.pid() } catch (_: Throwable) { null }
        val pkg = root.javaClass.name
        // Don't descend into JDK containers/strings/boxed types — process objects live in
        // IntelliJ/JediTerm classes, and walking java.* graphs is slow and loop-prone.
        if (pkg.startsWith("java.") || pkg.startsWith("kotlin.") || pkg.startsWith("javax.")) return null
        var cls: Class<*>? = root.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                val v = try { field.isAccessible = true; field.get(root) } catch (_: Throwable) { continue }
                deepFindProcessPid(v, depth - 1, visited)?.let { return it }
            }
            cls = cls.superclass
        }
        return null
    }

    /**
     * The tab's current working directory via the PUBLIC `ShellTerminalWidget.getCurrentDirectory()`
     * accessor, or null if unavailable. Used to claim a tab by cwd when the PID dig fails — a
     * stable, reflection-light bridge (public method, not a private field).
     */
    fun extractCwdFromWidget(widget: TerminalWidget?): String? {
        widget ?: return null
        return try {
            val getter = widget.javaClass.methods.find { it.name == "getCurrentDirectory" && it.parameterCount == 0 }
            (getter?.invoke(widget) as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    /** Normalise a path for cwd comparison: backslashes → '/', trailing '/' stripped, lowercased. */
    fun normalizeCwd(path: String): String = path.replace("\\", "/").trimEnd('/').lowercase()

    /**
     * True if walking the parent chain from [claudePid] reaches [shellPid] within
     * [maxHops] hops. [parentOf] returns a PID's parent or null; tests inject a fake,
     * production uses [osParentOf].
     */
    fun isHostedBy(
        claudePid: Long,
        shellPid: Long,
        parentOf: (Long) -> Long?,
        maxHops: Int = MAX_HOPS,
    ): Boolean {
        var current: Long? = claudePid
        for (i in 0 until maxHops) {
            current = current?.let(parentOf) ?: return false
            if (current == shellPid) return true
        }
        return false
    }

    /** Production parentOf via ProcessHandle. */
    fun osParentOf(pid: Long): Long? = try {
        ProcessHandle.of(pid).orElse(null)?.parent()?.orElse(null)?.pid()
    } catch (_: Exception) {
        null
    }

    /**
     * Title-handshake claim (pure core). The /tab script pokes `✳ <name>` into ITS OWN tab's
     * tty (OSC title escape) right after persisting the session's `userName`. A pty is private
     * to one tab, so an unclaimed tab whose displayed title strips to exactly one unclaimed
     * session's userName IS that session's tab — no process or cwd introspection needed, which
     * is what makes this work on user-opened tabs where every reflection dig fails.
     *
     * Claims only unambiguous pairs: the userName must be unique among [sidToUserName] AND
     * exactly one tab in [tabTitles] must show it. Returns tab index → sid.
     */
    fun matchTitlesToSessions(
        sidToUserName: Map<String, String?>,
        tabTitles: List<String?>,
    ): Map<Int, String> {
        val tabsByName = HashMap<String, MutableList<Int>>()
        tabTitles.forEachIndexed { i, raw ->
            val stripped = raw?.let { TitleModel.stripGlyph(it) }?.trim()
            if (!stripped.isNullOrEmpty()) tabsByName.getOrPut(stripped) { mutableListOf() }.add(i)
        }
        val sidsByName = sidToUserName.entries
            .filter { !it.value.isNullOrBlank() }
            .groupBy({ it.value!!.trim() }, { it.key })
        val claims = HashMap<Int, String>()
        for ((name, sids) in sidsByName) {
            val tabs = tabsByName[name] ?: continue
            if (sids.size == 1 && tabs.size == 1) claims[tabs[0]] = sids[0]
        }
        return claims
    }

    /**
     * Resolve the single session whose display name equals [vanishedTitle] — used to identify,
     * at the instant a tab closes, WHICH session's tab it was, so the close can be persisted
     * immediately (before a later poll, so it survives an instant Rider quit).
     *
     * [candidates] maps sid → its display name (userName, else prettified topic name). Matches by
     * exact trimmed equality. Returns the sid ONLY when exactly one candidate matches — an
     * ambiguous or absent match returns null and the caller falls back to vanish-attribution.
     */
    fun resolveUniqueByDisplayName(candidates: Map<String, String?>, vanishedTitle: String): String? {
        val target = vanishedTitle.trim()
        if (target.isEmpty()) return null
        val matches = candidates.entries.filter { (_, name) -> !name.isNullOrBlank() && name.trim() == target }
        return if (matches.size == 1) matches[0].key else null
    }
}
