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

        try {
            val getter = widget.javaClass.methods.find { it.name == "getTtyConnector" && it.parameterCount == 0 }
            val connector = getter?.invoke(widget)
            if (connector is ProcessTtyConnector) return connector.process.pid()
            if (connector != null) {
                try {
                    (connector.javaClass.getMethod("getProcess").invoke(connector) as? Process)?.let { return it.pid() }
                } catch (_: Exception) { /* fall through to field walk */ }
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] extractPidFromWidget getTtyConnector probe failed: ${e.message}")
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
}
