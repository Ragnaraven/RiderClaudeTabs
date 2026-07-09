package com.claudetabs

import java.io.File

/**
 * Self-healing guard for Claude Code's own global config (`~/.claude.json`).
 *
 * Claude Code performs non-atomic read-modify-write cycles on that file from EVERY running
 * claude process — at startup, during a session, and at exit. Under process churn (many
 * sessions starting or exiting near-simultaneously) two writers interleave and the file is
 * left syntactically invalid; the observed signature is a complete JSON document followed by
 * trailing garbage from the longer loser of the race (e.g. a stray `}`). Once corrupt, every
 * new claude launch aborts with a "Configuration error" prompt, which breaks restore
 * entirely.
 *
 * The plugin cannot make claude's own writes atomic, so this guard makes corruption
 * self-healing instead:
 *
 *  1. Every poll, [check] validates the file with a strict JSON scan (no parse tree, no
 *     external deps). While it is VALID, a copy is mirrored to [lastGoodFile] (atomic +
 *     fsynced via [DurableIo]) so a known-good snapshot always exists.
 *  2. An invalid read must persist for [STRIKES_TO_REPAIR] consecutive checks before any
 *     repair — a writer caught mid-write settles within milliseconds, and one strike would
 *     race it. Repair is only ever attempted on STABLE corruption.
 *  3. Repair prefers the least destructive option: if the text is a valid document followed
 *     by trailing garbage, the garbage is stripped and the NEWEST data survives. Only when
 *     the document itself is broken does the guard fall back to restoring [lastGoodFile].
 *     If neither works, the file is left untouched — the guard never destroys data.
 *
 * All methods are synchronized: project windows share one JVM and each window's poll calls
 * [check], so strikes and the last-good mirror must not race.
 */
internal class ConfigGuard(
    /** Claude Code's global config file (`~/.claude.json`). */
    private val configFile: File,
    /** Plugin-owned mirror of the last VALID config content. */
    private val lastGoodFile: File,
) {

    companion object {
        /** Consecutive invalid checks required before repair — see class doc for why not 1. */
        const val STRIKES_TO_REPAIR = 2

        /**
         * True when [text] is exactly one valid JSON value (any type) plus surrounding
         * whitespace. Strict single-pass scan; no exceptions, no allocation beyond the scan.
         */
        fun isValidJson(text: String): Boolean {
            val end = scanValue(text, skipWs(text, 0))
            return end > 0 && skipWs(text, end) == text.length
        }

        /**
         * If [text] is a valid JSON value followed by non-whitespace trailing garbage —
         * the interleaved-writer signature — return just the valid prefix. Null when the
         * document itself is broken (or when there is nothing to strip), meaning this
         * repair does not apply.
         */
        fun stripTrailingGarbage(text: String): String? {
            val end = scanValue(text, skipWs(text, 0))
            if (end <= 0) return null
            val rest = skipWs(text, end)
            if (rest == text.length) return null // already valid — nothing to strip
            return text.substring(0, end)
        }

        // ── Minimal strict JSON scanner ─────────────────────────────────
        // Returns the index just past the value starting at [i], or -1 if invalid.

        private fun skipWs(s: String, i: Int): Int {
            var p = i
            while (p < s.length && (s[p] == ' ' || s[p] == '\t' || s[p] == '\n' || s[p] == '\r')) p++
            return p
        }

        private fun scanValue(s: String, i: Int): Int {
            if (i >= s.length) return -1
            return when (s[i]) {
                '{' -> scanObject(s, i)
                '[' -> scanArray(s, i)
                '"' -> scanString(s, i)
                't' -> scanLiteral(s, i, "true")
                'f' -> scanLiteral(s, i, "false")
                'n' -> scanLiteral(s, i, "null")
                else -> scanNumber(s, i)
            }
        }

        private fun scanObject(s: String, i: Int): Int {
            var p = skipWs(s, i + 1)
            if (p < s.length && s[p] == '}') return p + 1
            while (true) {
                p = scanString(s, p); if (p < 0) return -1
                p = skipWs(s, p)
                if (p >= s.length || s[p] != ':') return -1
                p = scanValue(s, skipWs(s, p + 1)); if (p < 0) return -1
                p = skipWs(s, p)
                if (p >= s.length) return -1
                when (s[p]) {
                    '}' -> return p + 1
                    ',' -> p = skipWs(s, p + 1)
                    else -> return -1
                }
            }
        }

        private fun scanArray(s: String, i: Int): Int {
            var p = skipWs(s, i + 1)
            if (p < s.length && s[p] == ']') return p + 1
            while (true) {
                p = scanValue(s, p); if (p < 0) return -1
                p = skipWs(s, p)
                if (p >= s.length) return -1
                when (s[p]) {
                    ']' -> return p + 1
                    ',' -> p = skipWs(s, p + 1)
                    else -> return -1
                }
            }
        }

        private fun scanString(s: String, i: Int): Int {
            if (i >= s.length || s[i] != '"') return -1
            var p = i + 1
            while (p < s.length) {
                when (s[p]) {
                    '"' -> return p + 1
                    '\\' -> {
                        if (p + 1 >= s.length) return -1
                        when (s[p + 1]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> p += 2
                            'u' -> {
                                if (p + 5 >= s.length) return -1
                                for (k in p + 2..p + 5) if (!isHex(s[k])) return -1
                                p += 6
                            }
                            else -> return -1
                        }
                    }
                    else -> {
                        if (s[p].code < 0x20) return -1 // raw control char — invalid in JSON
                        p++
                    }
                }
            }
            return -1
        }

        private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        private fun scanLiteral(s: String, i: Int, lit: String): Int =
            if (s.startsWith(lit, i)) i + lit.length else -1

        private fun scanNumber(s: String, i: Int): Int {
            var p = i
            if (p < s.length && s[p] == '-') p++
            val intStart = p
            if (p < s.length && s[p] == '0') p++
            else { while (p < s.length && s[p] in '0'..'9') p++ }
            if (p == intStart) return -1
            if (p < s.length && s[p] == '.') {
                p++
                val fracStart = p
                while (p < s.length && s[p] in '0'..'9') p++
                if (p == fracStart) return -1
            }
            if (p < s.length && (s[p] == 'e' || s[p] == 'E')) {
                p++
                if (p < s.length && (s[p] == '+' || s[p] == '-')) p++
                val expStart = p
                while (p < s.length && s[p] in '0'..'9') p++
                if (p == expStart) return -1
            }
            return p
        }
    }

    /** Outcome of one [check] pass, for logging and tests. */
    enum class Status {
        /** File valid (or absent) — snapshot refreshed if content changed. */
        VALID,
        /** File invalid but strikes not yet reached — waiting out a possible mid-write. */
        SUSPECT,
        /** Repaired by stripping trailing garbage off a valid document (newest data kept). */
        REPAIRED_STRIPPED,
        /** Document itself broken — restored from the last-good mirror. */
        REPAIRED_RESTORED,
        /** Stable corruption but no repair applies and no usable mirror — left untouched. */
        UNREPAIRABLE,
    }

    private var strikes = 0
    private var lastGoodMirrorHash = 0

    /**
     * Validate the config once; repair only stable corruption. Call from the poll loop.
     * All IO failures degrade to no-op — the guard must never be the thing that breaks.
     */
    @Synchronized
    fun check(): Status {
        val text = try {
            if (!configFile.exists()) return Status.VALID.also { strikes = 0 }
            configFile.readText()
        } catch (_: Exception) { return Status.SUSPECT } // unreadable ≠ corrupt; don't strike

        if (isValidJson(text)) {
            strikes = 0
            mirror(text)
            return Status.VALID
        }

        strikes++
        if (strikes < STRIKES_TO_REPAIR) return Status.SUSPECT

        // Stable corruption. Re-read so the repair source is the newest bytes, not the
        // snapshot from the top of this check.
        strikes = 0
        val current = try { configFile.readText() } catch (_: Exception) { return Status.SUSPECT }
        if (isValidJson(current)) { mirror(current); return Status.VALID } // healed itself

        stripTrailingGarbage(current)?.let { stripped ->
            return try {
                DurableIo.writeAtomic(configFile, stripped)
                mirror(stripped)
                Status.REPAIRED_STRIPPED
            } catch (_: Exception) { Status.UNREPAIRABLE }
        }

        val lastGood = try {
            if (lastGoodFile.exists()) lastGoodFile.readText() else null
        } catch (_: Exception) { null }
        if (lastGood != null && isValidJson(lastGood)) {
            return try {
                DurableIo.writeAtomic(configFile, lastGood)
                Status.REPAIRED_RESTORED
            } catch (_: Exception) { Status.UNREPAIRABLE }
        }
        return Status.UNREPAIRABLE
    }

    /** Refresh the last-good mirror, skipping the write when content is unchanged. */
    private fun mirror(text: String) {
        val hash = text.hashCode()
        if (hash == lastGoodMirrorHash && lastGoodFile.exists()) return
        try {
            DurableIo.writeAtomic(lastGoodFile, text)
            lastGoodMirrorHash = hash
        } catch (_: Exception) { /* best effort — next valid poll retries */ }
    }
}
