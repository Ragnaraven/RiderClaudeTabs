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
        if (!tmp.renameTo(target)) {
            // Windows can't rename onto an existing file — delete then retry.
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("atomic rename failed for ${target.absolutePath}")
            }
        }
    }
}
