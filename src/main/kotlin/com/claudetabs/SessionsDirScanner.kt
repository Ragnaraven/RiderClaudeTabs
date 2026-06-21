package com.claudetabs

/**
 * Helper bits for scanning `~/.claude/sessions/<pid>.json` and classifying which entries
 * are real Claude processes vs PID-recycled hosts.
 *
 * In 2.0 the orchestration scan moved inline into [ClaudeTabWatcherStartup.pollOnce] —
 * this file now exists only to host the [ProcessInfo] data class and [looksLikeClaude]
 * heuristic, which the pollOnce code (and tests) reference.
 */
internal object SessionsDirScanner {

    /** Subset of `ProcessHandle.Info` we care about. */
    data class ProcessInfo(val command: String, val commandLine: String)

    /**
     * Recycling guard: given a [ProcessInfo], does this look like a Claude process?
     *
     * The check is intentionally loose — `claude.exe`, the `node` wrapper invoking
     * `@anthropic`, and the `.cmd` shim all count.
     */
    fun looksLikeClaude(info: ProcessInfo): Boolean =
        info.command.endsWith("claude") ||
            info.command.endsWith("claude.exe") ||
            info.command.endsWith("claude.cmd") ||
            info.commandLine.contains("@anthropic", true) ||
            info.commandLine.contains("claude-code", true) ||
            (info.command.contains("node", true) && info.commandLine.contains("claude", true))
}
