package com.claudetabs

import java.io.File

/**
 * Filesystem layout owner. Composes [ActiveSessionsStore] and [SessionBacklog] and provides
 * the per-project user-closed store + one-time migration from 1.x's per-project restore files.
 *
 * On-disk layout under [claudeHome]:
 * ```
 * rider-plugin/
 *   active-sessions/<sid>.json    # ActiveSessionsStore — one per alive session
 *   session-backlog.json          # SessionBacklog — global, max 50, prepended on eviction
 *   user-closed-<hash>.json       # per-project persistent set of sids the user closed
 *   config.json                   # backlogMaxEntries, deadStrikesNeeded
 * ```
 *
 * 1.x → 2.0 migration is handled by [migrateLegacyRestoreFiles] which runs once on first
 * 2.0 launch in any project. It reads every existing `restore-<hash>.json`, seeds
 * `active-sessions/` from it (with `pid=null` so the first poll either confirms or
 * starts the dead-strike counter), and renames the legacy files to `.pre-2.0` siblings.
 */
internal class ClaudeTabsStorage(private val claudeHome: File) {

    val stateDir = File(claudeHome, "rider-plugin")
    val activeSessionsDir = File(stateDir, "active-sessions")
    val backlogFile = File(stateDir, "session-backlog.json")
    val sessionsDir = File(claudeHome, "sessions")
    val configFile = File(stateDir, "config.json")
    val projectsDir = File(claudeHome, "projects")
    val claudeMdFile = File(claudeHome, "CLAUDE.md")
    val settingsFile = File(claudeHome, "settings.json")
    val commandsDir = File(claudeHome, "commands")

    val activeSessions = ActiveSessionsStore(activeSessionsDir)
    val backlog = SessionBacklog(backlogFile)

    fun userClosedFile(projectHash: String): File = File(stateDir, "user-closed-$projectHash.json")

    // ══════════════════════════════════════════════════════════════
    // USER-CLOSED STORE — per-project persistent set of sids the user closed
    // ══════════════════════════════════════════════════════════════

    private val userClosedLock = Any()

    /** Load the set of sids the user has explicitly closed in [projectHash]. Returns
     *  emptySet on missing/empty/unreadable file. */
    fun loadUserClosed(projectHash: String): Set<String> = synchronized(userClosedLock) {
        val f = userClosedFile(projectHash)
        if (!f.exists()) return@synchronized emptySet()
        val text = try { f.readText() } catch (_: Exception) { return@synchronized emptySet() }
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == "[]") return@synchronized emptySet()
        Regex("\"([^\"]+)\"").findAll(trimmed).map { it.groupValues[1] }.toSet()
    }

    /** Persist [closed] for [projectHash] via atomic write. */
    fun saveUserClosed(projectHash: String, closed: Set<String>) = synchronized(userClosedLock) {
        val f = userClosedFile(projectHash)
        f.parentFile?.mkdirs()
        val content = if (closed.isEmpty()) "[]" else closed.joinToString(
            prefix = "[\n",
            postfix = "\n]",
            separator = ",\n",
        ) { "  \"${ClaudeTabsHelpers.esc(it)}\"" }
        writeAtomic(f, content)
    }

    /** Add [sid] to the persistent user-closed set for [projectHash]. Idempotent. */
    fun addUserClosed(projectHash: String, sid: String): Boolean = synchronized(userClosedLock) {
        val current = loadUserClosed(projectHash)
        if (sid in current) return@synchronized false
        saveUserClosed(projectHash, current + sid)
        true
    }

    /** Prune entries whose sid no longer satisfies [sidStillExists]. */
    fun pruneUserClosed(projectHash: String, sidStillExists: (sid: String) -> Boolean): Int =
        synchronized(userClosedLock) {
            val current = loadUserClosed(projectHash)
            val kept = current.filter(sidStillExists).toSet()
            if (kept.size == current.size) return@synchronized 0
            saveUserClosed(projectHash, kept)
            current.size - kept.size
        }

    // ══════════════════════════════════════════════════════════════
    // ATOMIC WRITE
    // ══════════════════════════════════════════════════════════════

    /** Write [content] to [target] durably (fsync + atomic rename) so even a hard crash
     *  mid-write can't leave the target partially written or zero-filled. */
    internal fun writeAtomic(target: File, content: String) = DurableIo.writeAtomic(target, content)

    // ══════════════════════════════════════════════════════════════
    // 1.x → 2.0 MIGRATION
    // ══════════════════════════════════════════════════════════════

    /**
     * One-time migration that runs on the first 2.0 launch in any project. Idempotent —
     * safe to call repeatedly; if `active-sessions/` already exists with files, the
     * migration is skipped.
     *
     * Seeds `active-sessions/` from any existing `restore-<hash>.json` files in [stateDir].
     * The migrated entries have `pid=null` so the first poll either confirms (matches an
     * alive `<pid>.json`) and stamps the real pid, or marks dead and starts the dead-strike
     * counter. Stale migration entries get cleaned within ~10s of the first poll.
     *
     * After seeding, the legacy `restore-<hash>.json` files are renamed to `.pre-2.0`
     * siblings (kept on disk for manual recovery but never read by 2.0). Likewise
     * `names.json` is renamed; its data is lost since the restore file's `tabName` field
     * is more recent.
     *
     * Returns the number of per-sid files seeded.
     */
    fun migrateLegacyRestoreFiles(now: Long = System.currentTimeMillis()): Int {
        if (!stateDir.exists()) return 0
        // Skip if already migrated (active-sessions/ has any non-empty content)
        if (activeSessionsDir.exists() && (activeSessionsDir.listFiles()?.isNotEmpty() == true)) {
            return 0
        }
        activeSessionsDir.mkdirs()

        var seeded = 0
        val restoreFiles = stateDir.listFiles { f ->
            f.isFile && f.name.startsWith("restore-") && f.name.endsWith(".json")
        } ?: emptyArray()

        for (rf in restoreFiles) {
            val text = try { rf.readText() } catch (_: Exception) { continue }
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == "[]") continue

            // Parse the legacy {sessionId, cwd, tabName, bypassPermissions} entries.
            for (m in Regex("""\{[^}]+\}""").findAll(text)) {
                val o = m.value
                val sid = ClaudeTabsHelpers.extractJsonString(o, "sessionId") ?: continue
                val cwd = ClaudeTabsHelpers.extractJsonString(o, "cwd") ?: continue
                val tabName = ClaudeTabsHelpers.extractJsonString(o, "tabName")
                if (activeSessions.read(sid) != null) continue
                activeSessions.writeOrUpdate(
                    sid = sid,
                    cwd = cwd,
                    pid = null,
                    lastSeen = now,
                    name = tabName?.takeIf { it.isNotBlank() && it != "Local" && it != "Claude" },
                )
                seeded++
            }

            // Rename legacy file to .pre-2.0 sibling.
            val rotated = File(rf.parentFile, "${rf.name}.pre-2.0")
            try {
                if (rotated.exists()) rotated.delete()
                rf.renameTo(rotated)
            } catch (_: Exception) { /* best effort */ }
        }

        // Rename names.json to .pre-2.0 (data not merged — restore file's tabName is more recent)
        val namesFile = File(stateDir, "names.json")
        if (namesFile.exists()) {
            try {
                val rotated = File(stateDir, "names.json.pre-2.0")
                if (rotated.exists()) rotated.delete()
                namesFile.renameTo(rotated)
            } catch (_: Exception) { /* best effort */ }
        }

        // Best-effort cleanup of obsolete dirs (idempotent — non-existent is fine).
        listOf("session-map", "session-queue", "tabs", "snapshots", "backups").forEach { name ->
            val d = File(stateDir, name)
            if (d.exists()) {
                try { d.deleteRecursively() } catch (_: Exception) { /* best effort */ }
            }
        }

        // Best-effort cleanup of obsolete bundled scripts (plugin no longer owns these,
        // but the JS files the active skills use stay).
        listOf("rename-tab.sh", "session-start-hook.sh", "tab.sh", "tab-backup.js").forEach { name ->
            val f = File(stateDir, name)
            if (f.exists()) {
                try { f.delete() } catch (_: Exception) { /* best effort */ }
            }
        }

        return seeded
    }
}
