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

    /** The exact command the plugin sends into an owned terminal it just created. Pure so the
     *  contract ("new assigns the id, resume reuses it, nothing else") is unit-pinned. */
    fun launchCommand(sid: String, mode: Mode): String = when (mode) {
        Mode.NEW -> "claude --session-id $sid"
        Mode.RESUME -> "claude --resume $sid"
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
}
