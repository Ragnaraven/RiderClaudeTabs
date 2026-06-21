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
        val entry = Entry(sid, cwd, pid, lastSeen, resolvedName, resolvedUserName, resolvedOrdinal)
        atomicWrite(fileFor(sid), serialise(entry))
    }

    /** Delete the per-sid file. Idempotent — non-existent file is not an error. */
    fun delete(sid: String): Boolean {
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
        return Entry(sid, cwd, pid, lastSeen, name, userName, ordinal)
    }

    private fun atomicWrite(target: File, content: String) = DurableIo.writeAtomic(target, content)
}
