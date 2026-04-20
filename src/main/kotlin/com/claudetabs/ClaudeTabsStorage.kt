package com.claudetabs

import java.io.File

/**
 * All filesystem reads/writes the plugin performs, centralised into one object that can
 * operate on any root directory (production uses `~/.claude`, tests use a temp dir).
 *
 * Nothing here touches the IntelliJ platform — this is intentionally split from
 * [ClaudeTabWatcherStartup] so it can be exercised by headless integration tests.
 *
 * File layout under [claudeHome]:
 * ```
 * CLAUDE.md                               # plugin injects a section between markers
 * settings.json                           # plugin adds a permission entry
 * commands/tab.md, tabs-*.md              # (deployed by plugin at runtime, not here)
 * rider-plugin/
 *   rename-tab.sh, session-start-hook.sh  # shell integration scripts
 *   tabs/<sessionId>.json                 # rename directives (scripts → plugin)
 *   session-map/<TERM_SESSION_ID>         # per-tab session ID mapping
 *   restore-<projectHash>.json            # auto-restore target
 *   snapshots/<projectHash>-<ts>.json     # rolling backups
 *   history.json                          # closed/backed-up sessions
 *   config.json                           # user-overridable settings
 * ```
 */
internal class ClaudeTabsStorage(private val claudeHome: File) {

    val stateDir = File(claudeHome, "rider-plugin")
    val tabsDir = File(stateDir, "tabs")
    val sessionMapDir = File(stateDir, "session-map")
    val snapshotsDir = File(stateDir, "snapshots")
    val sessionsDir = File(claudeHome, "sessions")
    val historyFile = File(stateDir, "history.json")
    val configFile = File(stateDir, "config.json")
    val claudeMdFile = File(claudeHome, "CLAUDE.md")
    val settingsFile = File(claudeHome, "settings.json")
    val commandsDir = File(claudeHome, "commands")

    fun restoreFile(projectHash: String): File = File(stateDir, "restore-$projectHash.json")

    // ══════════════════════════════════════════════════════════════
    // HISTORY
    // ══════════════════════════════════════════════════════════════

    /**
     * Append (or update) a history entry for [session]. Entries older than [maxAgeMs] are pruned.
     *
     * Thread-safe via [historyLock]. If the write fails the exception is rethrown so callers
     * can log (the production caller catches and logs at DEBUG).
     */
    private val historyLock = Any()

    fun appendToHistory(session: SavedSession, now: Long = System.currentTimeMillis(), maxAgeMs: Long) =
        synchronized(historyLock) {
            val entries = loadHistoryRaw().toMutableList()
            entries.removeAll { ClaudeTabsHelpers.extractJsonString(it, "sessionId") == session.sessionId }

            val entry = buildString {
                append("{\"sessionId\":\"${ClaudeTabsHelpers.esc(session.sessionId)}\"")
                append(",\"cwd\":\"${ClaudeTabsHelpers.esc(session.cwd)}\"")
                append(",\"tabName\":\"${ClaudeTabsHelpers.esc(session.tabName)}\"")
                append(",\"bypassPermissions\":${session.bypassPermissions}")
                append(",\"closedAt\":$now}")
            }
            entries.add(entry)

            val cutoff = now - maxAgeMs
            val pruned = entries.filter { raw ->
                val ts = Regex(""""closedAt":(\d+)""").find(raw)?.groupValues?.get(1)?.toLongOrNull()
                ts != null && ts > cutoff
            }

            historyFile.parentFile?.mkdirs()
            historyFile.writeText(pruned.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { "  $it" })
        }

    fun loadHistoryRaw(): List<String> = synchronized(historyLock) {
        if (!historyFile.exists()) return@synchronized emptyList()
        try {
            Regex("""\{[^}]+\}""").findAll(historyFile.readText()).map { it.value }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // RESTORE FILE + SNAPSHOTS
    // ══════════════════════════════════════════════════════════════

    data class SavedSession(val sessionId: String, val cwd: String, val tabName: String, val bypassPermissions: Boolean)

    /** Serialise [sessions] to a JSON array string (matches what saveState writes). */
    fun serialiseSessions(sessions: List<SavedSession>): String {
        if (sessions.isEmpty()) return "[]"
        return sessions.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { s ->
            "  {\"sessionId\":\"${ClaudeTabsHelpers.esc(s.sessionId)}\"," +
                "\"cwd\":\"${ClaudeTabsHelpers.esc(s.cwd)}\"," +
                "\"tabName\":\"${ClaudeTabsHelpers.esc(s.tabName)}\"," +
                "\"bypassPermissions\":${s.bypassPermissions}}"
        }
    }

    /** Parse a saved-sessions JSON string back into the record list. */
    fun parseSessions(json: String): List<SavedSession> {
        val text = json.trim()
        if (text.isEmpty() || text == "[]") return emptyList()
        return Regex("""\{[^}]+\}""").findAll(text).mapNotNull { m ->
            val o = m.value
            val sid = ClaudeTabsHelpers.extractJsonString(o, "sessionId") ?: return@mapNotNull null
            val cwd = ClaudeTabsHelpers.extractJsonString(o, "cwd") ?: return@mapNotNull null
            val name = ClaudeTabsHelpers.extractJsonString(o, "tabName") ?: return@mapNotNull null
            SavedSession(sid, cwd, name, o.contains("\"bypassPermissions\":true"))
        }.toList()
    }

    /**
     * Write [sessions] to the project's restore file + a rotating snapshot.
     * Returns the file content that was written (or null if nothing was written because
     * [sessions] is empty and [pendingRestoresNonEmpty] is true).
     */
    fun saveState(projectHash: String, sessions: List<SavedSession>, pendingRestoresNonEmpty: Boolean, keepCount: Int, now: Long = System.currentTimeMillis()): String? {
        val f = restoreFile(projectHash)
        if (sessions.isEmpty()) {
            if (pendingRestoresNonEmpty) return null  // don't wipe during active restore
            f.delete()
            return null
        }
        val content = serialiseSessions(sessions)
        f.parentFile?.mkdirs()
        f.writeText(content)
        writeSnapshot(projectHash, content, keepCount, now)
        return content
    }

    /** Write a timestamped snapshot and prune older ones beyond [keepCount]. */
    fun writeSnapshot(projectHash: String, content: String, keepCount: Int, now: Long = System.currentTimeMillis()) {
        if (keepCount <= 0) return
        snapshotsDir.mkdirs()
        File(snapshotsDir, "$projectHash-$now.json").writeText(content)

        val existing = listSnapshots(projectHash)
        if (existing.size > keepCount) {
            existing.drop(keepCount).forEach { old ->
                try { old.delete() } catch (_: Exception) { /* best effort */ }
            }
        }
    }

    /** List snapshots for [projectHash], newest first. */
    fun listSnapshots(projectHash: String): List<File> {
        val prefix = "$projectHash-"
        return snapshotsDir.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /**
     * Load the restore file (or fall back to the newest non-empty snapshot if the live file
     * is missing, empty, or unparseable). Returns the list of sessions to restore plus the
     * source file for logging.
     */
    data class LoadResult(val sessions: List<SavedSession>, val source: File?)
    fun loadRestoreWithFallback(projectHash: String): LoadResult {
        val sources = mutableListOf<File>().apply {
            val live = restoreFile(projectHash)
            if (live.exists()) add(live)
            addAll(listSnapshots(projectHash))
        }
        for (src in sources) {
            try {
                val parsed = parseSessions(src.readText())
                if (parsed.isNotEmpty()) return LoadResult(parsed, src)
            } catch (_: Exception) { /* try next */ }
        }
        return LoadResult(emptyList(), null)
    }
}
