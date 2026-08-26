package com.claudetabs

/**
 * Pure, IDE-free logic for the owned-terminal identity model (v3).
 *
 * The v3 principle: the plugin OWNS every Claude tab's widget AND knows its session id at the
 * instant of birth, so `widget ↔ Content ↔ sid` is a stored fact, never a re-derived guess.
 * Two launch shapes carry the id on the command line:
 *
 *  - NEW    → `claude --session-id <uuid>` — the plugin mints a fresh v4 UUID, so the id is
 *             known before Claude even starts. A collision only errors, and a freshly-minted
 *             UUID never collides, so this is safe.
 *  - RESUME → `claude --resume <sid>` — restore of a session whose id we already hold.
 *
 * Everything reflective/ancestry/title-heuristic from the pre-v3 identity layer is deleted; the
 * only correlation that survives (for tabs the user opened by hand, bypassing the plugin's
 * "New Claude Session" action) is [pairUniqueByKey] — a strict, unambiguous 1:1 bind by cwd.
 */
internal object OwnedTerminalSpawner {

    enum class Mode { NEW, RESUME }

    /**
     * The executable token that starts every Windows-root launch. Normally the bare `claude` on
     * the shell's PATH; overridable via `-Dclaudetabs.claudeCommand=<absolute path>` so the E2E
     * harness can pin its stub CLI without depending on the sandbox shell's PATH. Never set in
     * production. Quoted when it contains whitespace, the same way the WSL launcher quotes.
     * Read per call (not cached) so a test can set and restore the property around one assertion.
     */
    private fun claudeExecutable(): String {
        val override = System.getProperty("claudetabs.claudeCommand")?.takeIf { it.isNotBlank() }
            ?: return "claude"
        return if (override.any { it.isWhitespace() }) "\"$override\"" else override
    }

    /** The exact command the plugin sends into an owned terminal it just created. Pure so the
     *  contract ("new assigns the id, resume reuses it, nothing else") is unit-pinned. */
    fun launchCommand(sid: String, mode: Mode): String = when (mode) {
        Mode.NEW -> "${claudeExecutable()} --session-id $sid"
        Mode.RESUME -> "${claudeExecutable()} --resume $sid"
    }

    private val UUID_V4 =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")

    /** True if [s] is a canonical random (v4) UUID — the shape the plugin mints for new sessions. */
    fun isMintedUuid(s: String): Boolean = s.matches(UUID_V4)

    /** Executable basenames that count as a Claude CLI launch when they are the terminal's own
     *  process (the shape the terminal's AI-agents button uses: claude IS the pty root command). */
    private val CLAUDE_EXECUTABLES = setOf("claude", "claude.exe", "claude.cmd", "claude.ps1")

    /** True if [command] (a terminal's argv) launches the Claude CLI directly. */
    fun isDirectClaudeCommand(command: List<String>): Boolean {
        val exe = command.firstOrNull() ?: return false
        val basename = exe.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return basename in CLAUDE_EXECUTABLES
    }

    /** Flags after which injecting `--session-id` would be wrong: the launch already pins a
     *  session (or continues one), so the command must be left untouched. */
    private val SESSION_PINNING_FLAGS = setOf("--session-id", "--resume", "-r", "--continue", "-c")

    /** True if [command] already pins/continues a session — never inject into these. */
    fun alreadyPinsSession(command: List<String>): Boolean =
        command.any { it in SESSION_PINNING_FLAGS || it.startsWith("--session-id=") || it.startsWith("--resume=") }

    /**
     * Extract the session id a terminal's own argv carries (`--session-id <sid>` /
     * `--resume <sid>`, `=`-form included). This is the readback half of command-line injection:
     * a tab whose shell command names a sid IS that session — a birth-time fact, not a guess.
     * Returns null when the argv doesn't carry a UUID-valued session flag.
     */
    fun sessionIdFromCommand(command: List<String>?): String? {
        command ?: return null
        for ((i, arg) in command.withIndex()) {
            val candidate = when {
                arg == "--session-id" || arg == "--resume" || arg == "-r" -> command.getOrNull(i + 1)
                arg.startsWith("--session-id=") -> arg.substringAfter('=')
                arg.startsWith("--resume=") -> arg.substringAfter('=')
                else -> null
            } ?: continue
            if (isMintedUuid(candidate)) return candidate
        }
        return null
    }

    /**
     * Session id carried by a raw command-line STRING — the `ProcessHandle.info().commandLine()`
     * form used by the poll's process scan. A claude process's argv permanently carries the sid it
     * was LAUNCHED with, even after the user switches conversations inside it (in-app resume), so
     * this is the anchor that ties a live pid back to the tab the plugin bound at birth.
     * Tokenised on whitespace: the session flags and their UUID values never contain spaces, so
     * quoting/spaces in the executable path cannot break the flag scan.
     */
    fun sessionIdFromCommandLine(line: String?): String? {
        if (line.isNullOrBlank()) return null
        return sessionIdFromCommand(line.trim().split(Regex("\\s+")))
    }

    /**
     * Strict unambiguous 1:1 pairing used to adopt a HAND-OPENED tab (one the plugin didn't
     * spawn) into a session. Both sides are grouped by a shared key (the normalised cwd). A key
     * is bound ONLY when it has exactly one session AND exactly one tab — any ambiguity (0 or
     * 2+ on either side) binds nothing, so a session can never be attached to the wrong tab.
     *
     * @param sessionsByKey key → session ids observed for that key
     * @param tabsByKey     key → tab handles observed for that key
     * @return tab handle → session id, for unambiguous keys only
     */
    fun <T> pairUniqueByKey(
        sessionsByKey: Map<String, List<String>>,
        tabsByKey: Map<String, List<T>>,
    ): Map<T, String> {
        val out = HashMap<T, String>()
        for ((key, sids) in sessionsByKey) {
            val tabs = tabsByKey[key] ?: continue
            if (sids.size == 1 && tabs.size == 1) out[tabs[0]] = sids[0]
        }
        return out
    }

    // ──────────────────────────────────────────────────────────────────
    // Pid-ancestry pairing — the EXACT adoption of a hand-typed `claude`
    // ──────────────────────────────────────────────────────────────────

    /** How far up the OS process tree [pairByPidAncestry] walks. A hand-typed `claude` sits one or
     *  two hops under its tab's shell (shell → node/npm wrapper → claude); a handful of hops covers
     *  every wrapper shape while keeping the walk cheap and bounded. */
    const val ANCESTRY_MAX_HOPS = 6

    /** An alive, unbound session as the ancestry pass sees it. [rootId] is the session's SESSION
     *  ROOT id (`"windows"`, `"wsl:<distro>:<user>"`, …) — see the cross-namespace hazard in
     *  [pairByPidAncestry]. A null [rootId] is the pre-3.1 persisted shape and means Windows. */
    data class AncestrySession(val sid: String, val pid: Long?, val rootId: String?)

    /** An unbound terminal tab as the ancestry pass sees it: an opaque [handle] (the caller's
     *  Content/widget pair) plus the OS pid of its pty ROOT process, null when unknown (a remote
     *  or WSL-adapter tab never reports one). */
    data class AncestryTab<T>(val handle: T, val ptyRootPid: Long?)

    /**
     * Bind sessions to tabs by OS PROCESS ANCESTRY: a session whose process descends from a tab's
     * pty root IS running in that tab. That is an operating-system fact, not a correlation, which
     * is what makes a hand-typed `claude --resume <id>` in an ordinary terminal tab adoptable —
     * the CLI is a child of that tab's shell.
     *
     * Rules:
     *  - The ancestor set of a session starts at its OWN pid (a tab whose pty root IS the CLI is
     *    the same fact, one hop shorter) and walks up via [parentOf] for at most [maxHops] hops.
     *  - Repeating a pid ends the walk, so a cycle or a self-parent from a hostile/odd [parentOf]
     *    terminates instead of spinning.
     *  - AMBIGUITY REFUSES, as everywhere else in this codebase: a session matching two or more
     *    tabs binds nothing, and a tab claimed by two or more sessions binds nothing.
     *
     * **CROSS-NAMESPACE HAZARD — why the root gate is not optional.** A pid is only meaningful
     * inside its own pid namespace. A WSL session's pid is a LINUX pid from the distro's namespace
     * and can collide numerically with an unrelated Windows pty-root pid; binding on that collision
     * would attach one Anthropic ACCOUNT's session to another root's tab (identity must never cross
     * roots — see SessionRoot). So only sessions belonging to the Windows root are ever walked. The
     * tab side is naturally safe too (a WSL/remote tab reports no pty-root pid), but that is a
     * second line of defence, never the gate.
     *
     * @param parentOf pid → parent pid, or null when unknown/absent (injected so the whole rule is
     *   testable without touching the real process tree)
     * @return tab handle → session id, for unambiguous matches only
     */
    fun <T> pairByPidAncestry(
        sessions: List<AncestrySession>,
        tabs: List<AncestryTab<T>>,
        parentOf: (Long) -> Long?,
        maxHops: Int = ANCESTRY_MAX_HOPS,
    ): Map<T, String> {
        val tabsByPid = HashMap<Long, MutableList<AncestryTab<T>>>()
        for (t in tabs) tabsByPid.getOrPut(t.ptyRootPid ?: continue) { mutableListOf() }.add(t)
        if (tabsByPid.isEmpty()) return emptyMap()
        // sid → the single tab its ancestry points at (sessions matching 0 or 2+ tabs are dropped).
        val claimBySid = LinkedHashMap<String, T>()
        for (s in sessions) {
            val pid = s.pid ?: continue
            if (s.rootId != null && s.rootId != WINDOWS_ROOT_ID) continue
            val matches = ancestorPids(pid, parentOf, maxHops).flatMap { tabsByPid[it].orEmpty() }
            if (matches.size == 1) claimBySid[s.sid] = matches.single().handle
        }
        val claimCount = claimBySid.values.groupingBy { it }.eachCount()
        val out = HashMap<T, String>()
        for ((sid, handle) in claimBySid) if (claimCount[handle] == 1) out[handle] = sid
        return out
    }

    /** Windows [SessionRoot.Windows.ID], inlined as a `const` so this file stays IDE-free. */
    private const val WINDOWS_ROOT_ID = SessionRoot.Windows.ID

    /** [start] plus up to [maxHops] of its ancestors. Terminates on an unknown parent, on the hop
     *  budget, or on a repeated pid (cycle / self-parent), and never throws: a [parentOf] that
     *  blows up simply ends the walk. */
    private fun ancestorPids(start: Long, parentOf: (Long) -> Long?, maxHops: Int): Set<Long> {
        val seen = LinkedHashSet<Long>()
        seen.add(start)
        var cur = start
        var hops = 0
        while (hops < maxHops) {
            val parent = try { parentOf(cur) } catch (_: Throwable) { null } ?: break
            if (!seen.add(parent)) break
            cur = parent
            hops++
        }
        return seen
    }
}
