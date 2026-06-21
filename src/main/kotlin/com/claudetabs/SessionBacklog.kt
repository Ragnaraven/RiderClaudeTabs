package com.claudetabs

import java.io.File

/**
 * Global, bounded history of evicted sessions at `<file>` (typically
 * `~/.claude/rider-plugin/session-backlog.json`).
 *
 * Shape: a JSON array, newest first, max [MAX_ENTRIES] entries. On eviction the entry is
 * prepended after de-duplicating by sid (so resurrect→evict-again only ever appears once,
 * with the latest evictedAt timestamp).
 *
 * Per-entry shape:
 * ```
 * { "sid": "...", "cwd": "D:\\Dev\\Foo", "name": null, "evictedAt": 1780640000000 }
 * ```
 *
 * Atomic tmp + rename writes; multi-writer races are benign (one writer wins, the other's
 * deletion still goes through in [ActiveSessionsStore] independently).
 */
internal class SessionBacklog(val file: File) {

    data class Entry(
        val sid: String,
        val cwd: String,
        val name: String?,
        val evictedAt: Long,
        val userName: String? = null,
    )

    fun list(): List<Entry> {
        if (!file.exists()) return emptyList()
        val text = try { file.readText() } catch (_: Exception) { return emptyList() }
        return parse(text)
    }

    /** Prepend [entry], dedup any prior occurrence of [entry.sid], trim to [MAX_ENTRIES].
     *
     *  Name inheritance: if [entry] arrives without a name but a prior occurrence of the
     *  same sid had one, keep the old name. A resurrect→evict-again cycle can lose the
     *  cached name in `active-sessions/` (e.g. resumed but killed before Claude re-wrote
     *  its topic name) — the backlog must never trade a known name for null. */
    @Synchronized
    fun prepend(entry: Entry) {
        val current = list().toMutableList()
        val prior = current.firstOrNull { it.sid == entry.sid }
        current.removeAll { it.sid == entry.sid }
        var resolved = entry
        if (resolved.name == null && prior?.name != null) resolved = resolved.copy(name = prior.name)
        if (resolved.userName == null && prior?.userName != null) resolved = resolved.copy(userName = prior.userName)
        current.add(0, resolved)
        val trimmed = if (current.size > MAX_ENTRIES) current.subList(0, MAX_ENTRIES) else current
        file.parentFile?.mkdirs()
        atomicWrite(file, serialise(trimmed))
    }

    fun serialise(entries: List<Entry>): String {
        if (entries.isEmpty()) return "[]"
        return entries.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { e ->
            "  {\"sid\":\"${ClaudeTabsHelpers.esc(e.sid)}\"," +
                "\"cwd\":\"${ClaudeTabsHelpers.esc(e.cwd)}\"," +
                "\"name\":${e.name?.let { "\"${ClaudeTabsHelpers.esc(it)}\"" } ?: "null"}," +
                "\"userName\":${e.userName?.let { "\"${ClaudeTabsHelpers.esc(it)}\"" } ?: "null"}," +
                "\"evictedAt\":${e.evictedAt}}"
        }
    }

    fun parse(text: String): List<Entry> {
        val t = text.trim()
        if (t.isEmpty() || t == "[]") return emptyList()
        return Regex("""\{[^}]+\}""").findAll(t).mapNotNull { m ->
            val o = m.value
            val sid = ClaudeTabsHelpers.extractJsonString(o, "sid") ?: return@mapNotNull null
            val cwd = ClaudeTabsHelpers.extractJsonString(o, "cwd") ?: return@mapNotNull null
            // `name` is current; `metaName` is the pre-2.0 key — read both.
            val name = ClaudeTabsHelpers.extractJsonString(o, "name")
                ?: ClaudeTabsHelpers.extractJsonString(o, "metaName")
            val userName = ClaudeTabsHelpers.extractJsonString(o, "userName")
            val evictedAt = Regex(""""evictedAt"\s*:\s*(\d+)""").find(o)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            Entry(sid, cwd, name, evictedAt, userName)
        }.toList()
    }

    private fun atomicWrite(target: File, content: String) = DurableIo.writeAtomic(target, content)

    companion object {
        const val MAX_ENTRIES = 100
    }
}
