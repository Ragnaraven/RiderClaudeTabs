package com.claudetabs

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Crash-durable atomic file write — the single write primitive for every plugin state file.
 *
 * The plugin's three stores ([ActiveSessionsStore], [SessionBacklog], [ClaudeTabsStorage])
 * previously each did `tmp.writeText(content)` then `tmp.renameTo(target)`. That pattern is
 * atomic against a *clean* crash, but NOT against a hard power/GPU crash: the rename's
 * directory-entry update can reach disk while the temp file's DATA blocks are still only in
 * the OS page cache. After reboot the target name then points at unwritten blocks — a
 * zero/space-filled file. That is exactly how a crash wiped the saved sessions: the
 * `active-sessions` JSON files came back as whitespace, so startup reconcile couldn't
 * parse them, so nothing was restored.
 *
 * [writeAtomic] closes that hole: it fsyncs the temp file (forcing data + length to stable
 * storage) BEFORE the rename. After the rename the target always references fully-written
 * bytes, even if the machine dies in the very next instant.
 */
internal object DurableIo {

    /**
     * Write [content] to [target] atomically and durably: write to a sibling temp file,
     * fsync it, then rename over [target]. Creates the parent directory if needed.
     *
     * @throws IOException if the rename can't be completed.
     */
    fun writeAtomic(target: File, content: String) {
        val parent = target.parentFile
        parent?.mkdirs()
        val tmp = File(parent, "${target.name}.tmp.${System.nanoTime()}")
        FileOutputStream(tmp).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            // The critical line: force the bytes to disk before the rename commits. Best-effort
            // on exotic filesystems that don't support fsync (the rename is still atomic there).
            try {
                fos.fd.sync()
            } catch (_: java.io.SyncFailedException) { /* FS without fsync — rename still atomic */ }
        }
        // ATOMIC_MOVE replaces an existing target in one step (MoveFileEx with
        // MOVEFILE_REPLACE_EXISTING on Windows). The old delete-then-rename left a window with NO
        // file at all, and a reader landing in it saw the record as missing — a bound tab then
        // dropped out of the snapshot for a poll. A reader holding the file open can still make
        // the move fail with AccessDenied for an instant, so retry briefly before giving up.
        var lastError: Throwable? = null
        repeat(ATOMIC_MOVE_ATTEMPTS) { attempt ->
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                return
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                // Different volume or a filesystem without atomic replace: the legacy path.
                if (!tmp.renameTo(target)) {
                    target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        throw IOException("atomic rename failed for ${target.absolutePath}", e)
                    }
                }
                return
            } catch (e: IOException) {
                lastError = e
                if (attempt < ATOMIC_MOVE_ATTEMPTS - 1) Thread.sleep(ATOMIC_MOVE_RETRY_MS)
            }
        }
        tmp.delete()
        throw IOException("atomic replace failed for ${target.absolutePath}", lastError)
    }

    private const val ATOMIC_MOVE_ATTEMPTS = 5
    private const val ATOMIC_MOVE_RETRY_MS = 10L

    /** Read a whole file through NIO, which opens with FILE_SHARE_DELETE on Windows — so a
     *  concurrent [writeAtomic] replacing the file neither fails nor blocks on this reader. */
    fun readText(file: File): String = java.nio.file.Files.readString(file.toPath())
}
