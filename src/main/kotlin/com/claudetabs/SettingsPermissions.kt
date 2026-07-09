package com.claudetabs

/**
 * Pure logic for editing the `permissions.allow` array in `~/.claude/settings.json` — zero IDE
 * imports, fully unit-testable.
 *
 * The plugin keeps a handful of `Bash(node ~/.claude/rider-plugin/<helper>.js *)` permission lines
 * in the user's settings so its helper scripts run without a prompt. Adding/removing those lines
 * must NEVER corrupt the file.
 *
 * History: an earlier build removed an entry with naive string replacement tuned for a single-line
 * `"a", "b"` array (`text.replace("\"x\", ", "")` …). Real settings.json is pretty-printed, so the
 * separator is `,\n      ` (comma + newline + indent), not `, ` — the targeted replacements missed
 * and the bare `"x"` replacement stripped only the quoted string, leaving a dangling comma that
 * broke the whole file's JSON. This implementation extracts the quoted tokens and REBUILDS the array
 * instead, so it can't leave a stray comma — and it repairs a file an older build already broke.
 */
internal object SettingsPermissions {

    /** Permission lines the CURRENT build maintains in `permissions.allow`. SINGLE SOURCE OF
     *  TRUTH — the update path (ensure) adds these and the uninstall path removes them; both
     *  derive from this list so a new helper script can't be added to one and forgotten in
     *  the other. */
    val CURRENT_ENTRIES: List<String> = listOf(
        "Bash(node ~/.claude/rider-plugin/current-project.js)",
        "Bash(node ~/.claude/rider-plugin/backup-active.js)",
        "Bash(node ~/.claude/rider-plugin/backup-active.js *)",
        "Bash(node ~/.claude/rider-plugin/tab-name.js *)",
    )

    /** Permission lines written by 1.x builds — removed on update AND on uninstall, never
     *  re-added. Same single-source rationale as [CURRENT_ENTRIES]. */
    val LEGACY_ENTRIES: List<String> = listOf(
        "Bash(bash ~/.claude/rider-plugin/rename-tab.sh *)",
        "Bash(bash ~/.claude/rider-plugin/tab.sh *)",
        "Bash(node ~/.claude/rider-plugin/tab-backup.js *)",
        "Bash(node ~/.claude/rider-plugin/tab-now.js)",
        "Bash(node ~/.claude/rider-plugin/tab-now.js *)",
    )

    /** Matches the `"allow": [ … ]` array and captures its body. The body is consumed as a
     *  sequence of complete string tokens OR non-quote/non-`]` filler, so a `]` INSIDE a quoted
     *  entry (e.g. a glob character class like `Bash(ls src/[a-z]*)`) does not end the match —
     *  only the array's real closing bracket does. A naive `[^\]]*` body would truncate there
     *  and the rewrite would corrupt the file. */
    private val ALLOW_ARRAY = Regex(""""allow"\s*:\s*\[((?:[^"\]]|"(?:[^"\\]|\\.)*")*)]""")

    /** A JSON string token (handles escaped quotes), used to pull individual entries out of the
     *  array body regardless of how it's whitespace-formatted or whether it's been left malformed. */
    private val STRING_TOKEN = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** The raw body of the `allow` array, or null when there is none. Exposed so tests assert
     *  through the SAME regex production code uses — a private copy in the test would silently
     *  drift when this parser changes. */
    fun allowArrayBody(text: String): String? = ALLOW_ARRAY.find(text)?.groupValues?.get(1)

    /** The entries of the `allow` array, or null when there is none. Same single-source rationale
     *  as [allowArrayBody]. */
    fun parseAllowEntries(text: String): List<String>? {
        val body = allowArrayBody(text) ?: return null
        return STRING_TOKEN.findAll(body).map { it.groupValues[1] }.toList()
    }

    /** True if [body] has a structurally broken separator: a leading, trailing, or doubled comma —
     *  the shapes an older build's botched removal left behind. */
    private fun isMalformed(body: String): Boolean {
        val t = body.trim()
        return t.startsWith(",") || t.endsWith(",") || Regex(",\\s*,").containsMatchIn(body)
    }

    /**
     * Drop every entry in [remove] from the `allow` array, ensure every entry in [add] is present,
     * and return the rewritten settings text — or null when there's no `allow` array or nothing
     * needs changing (so the caller can skip the write).
     *
     * Order of existing (non-removed) entries is preserved; [add] entries that aren't already
     * present are appended. The array is rebuilt from the clean token list, so the result is always
     * valid JSON even if the input body had a dangling comma. The file's existing indentation style
     * is preserved (entry indent = the `"allow"` line's indent + 2 spaces).
     */
    fun rewriteAllowArray(text: String, remove: Set<String>, add: List<String>): String? {
        val match = ALLOW_ARRAY.find(text) ?: return null
        val body = match.groupValues[1]
        val existing = STRING_TOKEN.findAll(body).map { it.groupValues[1] }.toList()

        val needsChange = isMalformed(body) ||
            existing.any { it in remove } ||
            add.any { it !in existing }
        if (!needsChange) return null

        val kept = LinkedHashSet<String>()
        for (e in existing) if (e !in remove) kept.add(e)
        for (e in add) kept.add(e)

        // Indent of the line the "allow" key sits on; entries get that + 2 spaces.
        val lineStart = text.lastIndexOf('\n', match.range.first)
        val allowIndent =
            if (lineStart >= 0) text.substring(lineStart + 1, match.range.first).takeWhile { it == ' ' || it == '\t' }
            else ""
        val entryIndent = "$allowIndent  "

        val newBody =
            if (kept.isEmpty()) ""
            else "\n" + kept.joinToString(",\n") { "$entryIndent\"$it\"" } + "\n$allowIndent"
        return text.replaceRange(match.range, "\"allow\": [$newBody]")
    }
}
