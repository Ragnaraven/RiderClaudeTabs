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
    // TAB NAME CLASSIFICATION
    // ══════════════════════════════════════════════════════════════

    /**
     * True if [name] looks like a default JetBrains terminal tab name ("Local", "Local (2)",
     * "bash", "pwsh", etc.). Generic names are never saved for restore or preserved across
     * resume operations — only user-chosen or Claude-assigned names are.
     */
    fun isGenericTabName(name: String): Boolean {
        val n = name.trim()
        return n == "Local" || n.matches(Regex("Local \\(\\d+\\)")) ||
            n == "bash" || n == "pwsh" || n == "PowerShell" || n == "cmd" ||
            n.matches(Regex("bash \\(\\d+\\)")) || n.matches(Regex("pwsh \\(\\d+\\)"))
    }

    /**
     * True if [newName] is close enough to [currentName] that renaming is just churn.
     *
     * Used to skip redundant renames on `claude --resume` (where Claude's CLAUDE.md
     * instruction triggers a fresh rename with a similar-but-not-identical name).
     *
     * Rules:
     *  - Current null/blank/generic → never redundant (always allow the rename).
     *  - Exact match (case-insensitive, whitespace-normalised) → redundant.
     *  - Word-set Jaccard ≥ 0.6 when both names have ≥ 2 alphanumeric tokens → redundant.
     */
    fun isRenameRedundant(currentName: String?, newName: String): Boolean {
        if (currentName.isNullOrBlank() || isGenericTabName(currentName)) return false

        val normalised: (String) -> String = { it.trim().lowercase().replace(Regex("\\s+"), " ") }
        if (normalised(currentName) == normalised(newName)) return true

        val tokens: (String) -> Set<String> = { s ->
            s.lowercase()
                .replace(Regex("[^a-z0-9 ]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 1 }
                .toSet()
        }
        val cur = tokens(currentName)
        val new = tokens(newName)
        if (cur.size < 2 || new.size < 2) return false

        val intersection = cur.intersect(new).size.toDouble()
        val union = cur.union(new).size.toDouble()
        val jaccard = if (union == 0.0) 0.0 else intersection / union
        return jaccard >= 0.6
    }

    // ══════════════════════════════════════════════════════════════
    // CONFIG PARSING
    // ══════════════════════════════════════════════════════════════

    /** Parsed config values with defaults already applied. */
    data class Config(
        val historyMaxAgeMs: Long,
        val snapshotKeepCount: Int,
    ) {
        companion object {
            /** Defaults: 90-day history, 10 snapshots. */
            val DEFAULT = Config(90L * 24 * 60 * 60 * 1000, 10)
        }
    }

    /**
     * Parse a config.json blob leniently — any missing or malformed field falls back to the
     * corresponding default from [Config.DEFAULT]. Unknown fields are ignored.
     *
     * Accepts:
     *  - `historyMaxAgeDays` (int) — converted to ms internally
     *  - `snapshotKeepCount` (int ≥ 0)
     */
    fun parseConfig(text: String?): Config {
        var history = Config.DEFAULT.historyMaxAgeMs
        var snapshots = Config.DEFAULT.snapshotKeepCount
        if (text.isNullOrBlank()) return Config(history, snapshots)

        try {
            Regex(""""historyMaxAgeDays"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let {
                if (it > 0) history = it * 24 * 60 * 60 * 1000
            }
            Regex(""""snapshotKeepCount"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                if (it >= 0) snapshots = it
            }
        } catch (_: Exception) { /* fall through to defaults */ }

        return Config(history, snapshots)
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
     * Used as the suffix of per-project state files (`restore-<hash>.json`, snapshots, etc.).
     */
    fun projectHashForPath(basePath: String?): String =
        (basePath ?: "default").replace("\\", "/").replace(":/", "--").replace("/", "-")
}
