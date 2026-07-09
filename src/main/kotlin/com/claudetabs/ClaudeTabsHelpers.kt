package com.claudetabs

/**
 * Pure helper functions extracted from [ClaudeTabWatcherStartup] so they can be unit-tested
 * without needing an IntelliJ [com.intellij.openapi.project.Project] instance or a running IDE.
 *
 * Nothing in here touches the filesystem, threads, reflection, or the IntelliJ platform.
 */
internal object ClaudeTabsHelpers {

    // ══════════════════════════════════════════════════════════════
    // JSON HELPERS
    // ══════════════════════════════════════════════════════════════

    /**
     * Extract a string-valued field from [json] by [key], handling standard JSON string escapes.
     * Returns null if the key is missing or malformed.
     *
     * Intentionally hand-rolled (instead of Gson/Jackson) to keep the plugin zero-deps.
     * Only supports flat objects — entries in the plugin's files never have nested objects.
     */
    fun extractJsonString(json: String, key: String): String? {
        val m = Regex(""""$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(json) ?: return null
        return m.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"")
    }

    /** Escape backslashes and double-quotes for embedding into a JSON string literal. */
    fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    // ══════════════════════════════════════════════════════════════
    // TAB NAME HELPERS
    // ══════════════════════════════════════════════════════════════

    /**
     * True if [name] looks like a default JetBrains terminal tab name ("Local", "Local (2)",
     * "bash", "pwsh", etc.). A generic title is safe to overwrite with Claude's session name;
     * anything else is treated as user-chosen and left alone.
     */
    fun isGenericTabName(name: String): Boolean {
        val n = name.trim()
        return n == "Local" || n.matches(Regex("Local \\(\\d+\\)")) ||
            n == "bash" || n == "pwsh" || n == "PowerShell" || n == "cmd" ||
            n.matches(Regex("bash \\(\\d+\\)")) || n.matches(Regex("pwsh \\(\\d+\\)"))
    }

    /**
     * Convert Claude's auto-generated session-name slug (the `name` field in
     * `~/.claude/sessions/<pid>.json`, e.g. `fix-auth-token-rotation`) into a
     * human-friendly tab title (`Fix Auth Token Rotation`). Returns null for
     * null/blank input so callers can skip cleanly.
     */
    fun prettifySessionName(slug: String?): String? {
        if (slug.isNullOrBlank()) return null
        return slug.trim()
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
            .takeIf { it.isNotBlank() }
    }

    // ══════════════════════════════════════════════════════════════
    // CONFIG PARSING
    // ══════════════════════════════════════════════════════════════

    /** Parsed config values with defaults already applied. */
    data class Config(
        val backlogMaxEntries: Int,
        val deadStrikesNeeded: Int,
    ) {
        companion object {
            /** Defaults: 100 backlog entries, 2 dead strikes before eviction. */
            val DEFAULT = Config(100, 2)
        }
    }

    /**
     * Parse a config.json blob leniently — any missing or malformed field falls back to the
     * corresponding default from [Config.DEFAULT]. Unknown fields are ignored.
     */
    fun parseConfig(text: String?): Config {
        var backlog = Config.DEFAULT.backlogMaxEntries
        var strikes = Config.DEFAULT.deadStrikesNeeded
        if (text.isNullOrBlank()) return Config(backlog, strikes)

        try {
            Regex(""""backlogMaxEntries"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                if (it > 0) backlog = it
            }
            Regex(""""deadStrikesNeeded"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                if (it > 0) strikes = it
            }
        } catch (_: Exception) { /* fall through to defaults */ }

        return Config(backlog, strikes)
    }

    // ══════════════════════════════════════════════════════════════
    // SHELL DETECTION
    // ══════════════════════════════════════════════════════════════

    /** Process names recognised as terminal shells. */
    private val SHELL_NAMES = setOf(
        "bash", "bash.exe", "sh", "sh.exe", "zsh", "fish",
        "pwsh", "pwsh.exe", "powershell", "powershell.exe", "cmd.exe"
    )

    /**
     * True if [cmd] (absolute or relative path) ends in a known shell executable name.
     * Case-insensitive; handles both `/` and `\` as path separators.
     */
    fun isShellCommand(cmd: String): Boolean {
        val name = cmd.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return name in SHELL_NAMES
    }

    // ══════════════════════════════════════════════════════════════
    // PROJECT HASH
    // ══════════════════════════════════════════════════════════════

    /**
     * Derive a stable filesystem-safe identifier from a project base path.
     * Used as the suffix of per-project state files (`user-closed-<hash>.json`, etc.).
     */
    fun projectHashForPath(basePath: String?): String =
        (basePath ?: "default").replace("\\", "/").replace(":/", "--").replace("/", "-")

    /**
     * True if [cwd] is the project base path, a descendant of it, OR a sibling worktree.
     *
     * Why sibling worktrees matter: a common workflow is `cd ../MyApp-feature-branch &&
     * claude --resume <sid>` from inside the main `MyApp` Rider window. The Claude process
     * cwd is `D:\Dev\MyApp-feature-branch` (NOT a subpath of `D:\Dev\MyApp`), but the tab
     * is hosted in MyApp's Rider window and conceptually belongs to MyApp. We accept any
     * cwd of the form `<projectBase>-<suffix>` as a sibling worktree.
     *
     * Both paths are normalised: backslashes → forward slashes, trailing slashes stripped,
     * lowercased. The strict-descendant check uses a `/` boundary so `/repos/MyApp` does
     * NOT accidentally match `/repos/MyApp-mobile` as a descendant — but the worktree clause
     * DOES accept it (which is correct: `MyApp-mobile` IS a sibling-worktree-style sibling
     * of `MyApp` from the user's perspective).
     */
    fun isCwdUnderProject(cwd: String?, projectBasePath: String?): Boolean {
        if (cwd.isNullOrBlank()) return false
        // Defensive: if we can't resolve the project base path (rare — only happens for
        // detached/default Rider projects), don't filter.
        if (projectBasePath.isNullOrBlank()) return true
        val n1 = cwd.replace("\\", "/").trimEnd('/').lowercase()
        val n2 = projectBasePath.replace("\\", "/").trimEnd('/').lowercase()
        if (n1 == n2) return true
        if (n1.startsWith("$n2/")) return true
        // Sibling-worktree tolerance: `<n2>-<suffix>` and `<n2>-<suffix>/...` both qualify.
        if (n1.startsWith("$n2-")) return true
        return false
    }

    // ══════════════════════════════════════════════════════════════
    // TRANSCRIPT LOOKUP
    // ══════════════════════════════════════════════════════════════

    /**
     * True if a transcript file `<sessionId>.jsonl` exists anywhere under [projectsDir]
     * (which is `~/.claude/projects/` in production).
     *
     * Fast path: check the cwd-derived subdir (`<projectsDir>/<encoded-cwd>/<sessionId>.jsonl`)
     * since that's where Claude writes for fresh sessions.
     *
     * Fallback: scan all immediate subdirs. Needed when a session originally started in
     * cwd A is later resumed by sid from cwd B — Claude keeps appending to the original
     * transcript path (under A's encoded dir), not the one derived from B. Without the
     * fallback, every cross-cwd resume (most commonly: resuming a session inside a git
     * worktree shell) was rejected.
     *
     * The cwd → subdir encoding matches Claude's: backslash → forward-slash, then `:/` →
     * `--`, then `/` → `-`.
     */
    fun hasTranscriptAnywhere(projectsDir: java.io.File, sessionId: String, cwd: String?): Boolean =
        transcriptFile(projectsDir, sessionId, cwd) != null

    /**
     * Resolve the transcript file `<sessionId>.jsonl` for [sessionId], or null if none exists.
     * Same lookup order as [hasTranscriptAnywhere] (cwd-derived subdir fast path, then a scan of
     * every immediate subdir for the cross-cwd resume case).
     */
    fun transcriptFile(projectsDir: java.io.File, sessionId: String, cwd: String?): java.io.File? {
        if (sessionId.isBlank()) return null
        if (!cwd.isNullOrBlank()) {
            val h = cwd.replace("\\", "/").replace(":/", "--").replace("/", "-")
            val f = java.io.File(java.io.File(projectsDir, h), "$sessionId.jsonl")
            if (f.exists()) return f
        }
        val dirs = projectsDir.listFiles { f -> f.isDirectory } ?: return null
        for (d in dirs) {
            val f = java.io.File(d, "$sessionId.jsonl")
            if (f.exists()) return f
        }
        return null
    }

    // ══════════════════════════════════════════════════════════════
    // ARGV PARSING — canonical session id from --resume flag
    // ══════════════════════════════════════════════════════════════

    private val UUID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /**
     * Parse a Claude process's argv and return the UUID following `--resume` / `-r` /
     * `--resume=<uuid>`, or null if no resume flag is present (fresh session) or the
     * value isn't a UUID.
     */
    fun extractResumeIdFromArgs(args: Array<String>?): String? {
        if (args == null) return null
        for (i in args.indices) {
            val a = args[i]
            if (a == "--resume" || a == "-r") {
                return args.getOrNull(i + 1)?.takeIf { it.matches(UUID_REGEX) }
            }
            if (a.startsWith("--resume=")) {
                return a.substringAfter('=').takeIf { it.matches(UUID_REGEX) }
            }
        }
        return null
    }
}
