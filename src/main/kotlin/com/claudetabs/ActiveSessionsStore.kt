package com.claudetabs

import java.io.File

/**
 * One JSON file per alive Claude session at `<activeSessionsDir>/<sid>.json`.
 *
 * Replaces the per-project `restore-<projectHash>.json` model from 1.x. Each Rider window
 * writes per-sid based on what its poll sees; writes are idempotent (same content from any
 * writer); race-safe via atomic tmp + rename. Filtering by "which sessions belong to which
 * project" is a read-time concern only.
 *
 * `name` is a cache of Claude's OWN auto-generated session topic name (the `name` field
 * Claude writes into `~/.claude/sessions/<pid>.json`), prettified. It is refreshed every
 * poll while the session is alive. `userName` is an EXPLICIT name chosen by the user (tab
 * rename in the IDE or the /tab skill) — it outranks `name` for display and is never
 * touched by the topic-mirroring path.
 *
 * On-disk shape per file:
 * ```
 * { "sid": "...", "cwd": "D:\\Dev\\Foo", "pid": 12345, "lastSeen": 1780640000000,
 *   "name": null, "userName": null }
 * ```
 */
internal class ActiveSessionsStore(val dir: File) {

    data class Entry(
        val sid: String,
        val cwd: String,
        val pid: Long?,
        val lastSeen: Long,
        val name: String?,
        val userName: String? = null,
        /** The tab's left-to-right position in the terminal tool window the last time the
         *  poll observed it. Drives same-order restore so the user doesn't have to re-arrange
         *  15 tabs after every crash/restart. Null = position unknown (restored last). */
        val ordinal: Int? = null,
        /** How many consecutive restores have attempted this entry WITHOUT the session ever
         *  being observed alive since. Bumped by the restore loop before each spawn; reset to 0
         *  by [writeOrUpdate] whenever a live pid is recorded. Entries that hit the restore
         *  loop's generation cap are retired to the backlog instead of resurrected forever. */
        val restoreAttempts: Int = 0,
    )

    /** Serialises read-modify-write cycles within this JVM: the poll loop and the title
     *  controller both update the same per-sid files concurrently. */
    private val rmwLock = Any()

    fun fileFor(sid: String): File = File(dir, "$sid.json")

    fun listAll(): List<Entry> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f -> try { parse(f.readText()) } catch (_: Exception) { null } }
            ?: emptyList()
    }

    fun read(sid: String): Entry? {
        val f = fileFor(sid)
        if (!f.exists()) return null
        return try { parse(f.readText()) } catch (_: Exception) { null }
    }

    /** Write or update the per-sid file. A non-null [name]/[userName] overwrites; null
     *  preserves whatever was on disk (so callers without name knowledge can refresh
     *  pid/lastSeen without clobbering cached names). */
    fun writeOrUpdate(
        sid: String,
        cwd: String,
        pid: Long?,
        lastSeen: Long,
        name: String? = null,
        userName: String? = null,
        ordinal: Int? = null,
    ) = synchronized(rmwLock) {
        dir.mkdirs()
        val existing = read(sid)
        val resolvedName = name ?: existing?.name
        val resolvedUserName = userName ?: existing?.userName
        val resolvedOrdinal = ordinal ?: existing?.ordinal
        // A live pid means the session came alive — its restore-generation counter starts over.
        val resolvedAttempts = if (pid != null) 0 else existing?.restoreAttempts ?: 0
        val entry = Entry(sid, cwd, pid, lastSeen, resolvedName, resolvedUserName, resolvedOrdinal, resolvedAttempts)
        atomicWrite(fileFor(sid), serialise(entry))
    }

    /**
     * Locked update of ONLY the display-name fields. Unlike [writeOrUpdate] it can never touch
     * pid/lastSeen (so a racing name-capture can't resurrect a pid the poll just demoted) and it
     * NO-OPS when the entry no longer exists (so it can't recreate a file the close path just
     * evicted). This is the only write the title controller is allowed to make.
     */
    fun updateName(sid: String, name: String? = null, userName: String? = null): Boolean =
        synchronized(rmwLock) {
            val existing = read(sid) ?: return false
            val entry = existing.copy(
                name = name ?: existing.name,
                userName = userName ?: existing.userName,
            )
            atomicWrite(fileFor(sid), serialise(entry))
            true
        }

    /** Locked increment of [Entry.restoreAttempts] — the restore loop's ghost-decay counter.
     *  No-op when the entry doesn't exist. */
    fun bumpRestoreAttempts(sid: String): Unit = synchronized(rmwLock) {
        val existing = read(sid) ?: return
        atomicWrite(fileFor(sid), serialise(existing.copy(restoreAttempts = existing.restoreAttempts + 1)))
    }

    /** Delete the per-sid file. Idempotent — non-existent file is not an error. Takes the RMW
     *  lock so a concurrent read-modify-write can't interleave with (and undo) the deletion. */
    fun delete(sid: String): Boolean = synchronized(rmwLock) {
        val f = fileFor(sid)
        if (!f.exists()) return false
        return try { f.delete() } catch (_: Exception) { false }
    }

    fun serialise(e: Entry): String = buildString {
        append("{\"sid\":\"").append(ClaudeTabsHelpers.esc(e.sid)).append("\"")
        append(",\"cwd\":\"").append(ClaudeTabsHelpers.esc(e.cwd)).append("\"")
        append(",\"pid\":").append(e.pid?.toString() ?: "null")
        append(",\"lastSeen\":").append(e.lastSeen)
        append(",\"name\":")
        if (e.name == null) append("null")
        else append("\"").append(ClaudeTabsHelpers.esc(e.name)).append("\"")
        append(",\"userName\":")
        if (e.userName == null) append("null")
        else append("\"").append(ClaudeTabsHelpers.esc(e.userName)).append("\"")
        append(",\"ordinal\":").append(e.ordinal?.toString() ?: "null")
        append(",\"restoreAttempts\":").append(e.restoreAttempts)
        append("}")
    }

    fun parse(text: String): Entry? {
        val sid = ClaudeTabsHelpers.extractJsonString(text, "sid") ?: return null
        val cwd = ClaudeTabsHelpers.extractJsonString(text, "cwd") ?: return null
        val pid = Regex(""""pid"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
        val lastSeen = Regex(""""lastSeen"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        // `name` is current; `metaName` is the pre-2.0 key — read both so files written by
        // earlier builds (including migration seeds) keep their labels.
        val name = ClaudeTabsHelpers.extractJsonString(text, "name")
            ?: ClaudeTabsHelpers.extractJsonString(text, "metaName")
        val userName = ClaudeTabsHelpers.extractJsonString(text, "userName")
        val ordinal = Regex(""""ordinal"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        // Missing on files written by older builds → 0 (backward compatible).
        val restoreAttempts = Regex(""""restoreAttempts"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Entry(sid, cwd, pid, lastSeen, name, userName, ordinal, restoreAttempts)
    }

    private fun atomicWrite(target: File, content: String) = DurableIo.writeAtomic(target, content)
}
