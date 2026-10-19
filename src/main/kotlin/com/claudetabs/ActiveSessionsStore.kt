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
        /** Which [SessionRoot] this session lives in ("windows" or "wsl:<distro>") — a separate
         *  Claude installation and typically a separate ACCOUNT. Liveness checks and the resume
         *  launcher are derived from it; identity never crosses roots. Files written by pre-3.1
         *  builds have no field → "windows". */
        val root: String = SessionRoot.Windows.ID,
        /** When THIS session's CLI process started — the `startedAt` stamp the CLI writes into
         *  its own `sessions/<pid>.json`, copied onto the record so it outlives the process.
         *  Orders restore for entries that never had a tab position (see
         *  [ClaudeTabsHelpers.restoreOrder]); without it those reopen in sid order, i.e. shuffled.
         *  Null on files written by pre-3.3 builds and whenever the stamp was never observed. */
        val startedAt: Long? = null,
    )

    /** Serialises read-modify-write cycles within this JVM: the poll loop and the title
     *  controller both update the same per-sid files concurrently. */
    private val rmwLock = Any()

    fun fileFor(sid: String): File = File(dir, "$sid.json")

    /** A record parsed from a file as it was at (mtime, length); re-read only when either moves.
     *  Every poll of every open project lists ALL records (hundreds, most untouched for weeks);
     *  before this they were read and parsed in full each time. */
    private data class Cached(val mtime: Long, val length: Long, val entry: Entry?)
    private val parsed = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    fun listAll(): List<Entry> {
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        val live = HashSet<String>(files.size * 2)
        val out = ArrayList<Entry>(files.size)
        for (f in files) {
            live.add(f.name)
            cachedParse(f)?.let { out.add(it) }
        }
        parsed.keys.retainAll(live)
        return out
    }

    fun read(sid: String): Entry? = cachedParse(fileFor(sid))

    private fun cachedParse(f: File): Entry? {
        // Two stats instead of an open/read/parse when the file is unchanged. A missing file
        // stats as (0, 0) and reads as null; the next write moves both and invalidates it.
        val mtime = f.lastModified()
        val length = f.length()
        parsed[f.name]?.let { if (it.mtime == mtime && it.length == length) return it.entry }
        val entry = try { parse(DurableIo.readText(f)) } catch (_: Exception) { null }
        parsed[f.name] = Cached(mtime, length, entry)
        return entry
    }

    /** Write or update the per-sid file. A non-null optional field ([name], [userName],
     *  [ordinal], [root], [startedAt]) overwrites; null preserves whatever was on disk (so a
     *  caller that only knows pid/lastSeen can refresh them without clobbering the rest). */
    fun writeOrUpdate(
        sid: String,
        cwd: String,
        pid: Long?,
        lastSeen: Long,
        name: String? = null,
        userName: String? = null,
        ordinal: Int? = null,
        root: String? = null,
        startedAt: Long? = null,
    ) = synchronized(rmwLock) {
        dir.mkdirs()
        val existing = read(sid)
        val resolvedName = name ?: existing?.name
        val resolvedUserName = userName ?: existing?.userName
        val resolvedOrdinal = ordinal ?: existing?.ordinal
        // A live pid means the session came alive — its restore-generation counter starts over.
        val resolvedAttempts = if (pid != null) 0 else existing?.restoreAttempts ?: 0
        // Null preserves the recorded root (an update without root knowledge must never flip a
        // WSL session back to windows — that would resume it under the wrong account).
        val resolvedRoot = root ?: existing?.root ?: SessionRoot.Windows.ID
        // A refresh that doesn't know the start stamp (most of them — only the scan reads it)
        // must keep the recorded one, or restore order collapses back to sid order.
        val resolvedStartedAt = startedAt ?: existing?.startedAt
        val entry = Entry(
            sid, cwd, pid, lastSeen, resolvedName, resolvedUserName, resolvedOrdinal,
            resolvedAttempts, resolvedRoot, resolvedStartedAt,
        )
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
        append(",\"root\":\"").append(ClaudeTabsHelpers.esc(e.root)).append("\"")
        append(",\"startedAt\":").append(e.startedAt?.toString() ?: "null")
        append("}")
    }

    fun parse(text: String): Entry? {
        val sid = ClaudeTabsHelpers.extractJsonString(text, "sid") ?: return null
        val cwd = ClaudeTabsHelpers.extractJsonString(text, "cwd") ?: return null
        val pid = ClaudeTabsHelpers.extractJsonLong(text, "pid")
        val lastSeen = ClaudeTabsHelpers.extractJsonLong(text, "lastSeen") ?: 0L
        // `name` is current; `metaName` is the pre-2.0 key — read both so files written by
        // earlier builds (including migration seeds) keep their labels.
        val name = ClaudeTabsHelpers.extractJsonString(text, "name")
            ?: ClaudeTabsHelpers.extractJsonString(text, "metaName")
        val userName = ClaudeTabsHelpers.extractJsonString(text, "userName")
        val ordinal = ClaudeTabsHelpers.extractJsonLong(text, "ordinal")?.toInt()
        // Missing on files written by older builds → 0 (backward compatible).
        val restoreAttempts = ClaudeTabsHelpers.extractJsonLong(text, "restoreAttempts")?.toInt() ?: 0
        // Missing on pre-3.1 files → windows (the only root that existed).
        val root = ClaudeTabsHelpers.extractJsonString(text, "root") ?: SessionRoot.Windows.ID
        // Missing on pre-3.3 files (and written as JSON null when never observed) → null, which
        // restore ordering reads as "unknown start time", not as epoch 0.
        val startedAt = ClaudeTabsHelpers.extractJsonLong(text, "startedAt")
        return Entry(sid, cwd, pid, lastSeen, name, userName, ordinal, restoreAttempts, root, startedAt)
    }

    private fun atomicWrite(target: File, content: String) = DurableIo.writeAtomic(target, content)
}
