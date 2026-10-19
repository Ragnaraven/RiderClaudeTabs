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

    /**
     * Subset of `ProcessHandle.Info` we care about.
     *
     * [args] is the process's TOKENIZED argv when the root can produce it (a Linux root splits
     * `/proc/<pid>/cmdline` on NUL); null when it cannot — `ProcessHandle.Info` hands back an
     * already-joined command line, and the original token boundaries are unrecoverable from it.
     * Null therefore means UNKNOWN, never "no arguments": a whole-token question such as
     * [ClaudeTabsHelpers.isHeadlessArgs] must answer "unknown" rather than fall back to scanning
     * [commandLine] for a substring, where a path or a prompt can spell a flag it never passed.
     */
    data class ProcessInfo(val command: String, val commandLine: String, val args: List<String>? = null)

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
