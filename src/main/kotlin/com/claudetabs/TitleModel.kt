package com.claudetabs

/**
 * Pure title logic for Claude terminal tabs — zero IDE imports, fully unit-testable.
 *
 * The plugin OWNS `userDefinedTitle` for every tab hosting a Claude session (the only way to
 * get a per-tab NAME — Claude's native title is generic and git-bash swallows its escapes, so
 * without ownership tabs fall back to "Local").
 *
 * Look (user-specified):
 *  - IDLE  → a static STAR at the left, then the name: `✳ Name` (matches native `✳ Claude Code`).
 *  - BUSY  → a DOT that TRAVELS left → centre → right across a 3-cell field while thinking:
 *            `· Name` → ` · Name` → `  ·Name`, then loops.
 * The three busy frames are the exact same characters (one dot + two non-breaking fillers) just
 * reordered, so the tab NEVER resizes mid-animation. Idle uses a star instead of a dot — that one
 * glyph swap on the idle↔busy flip is intentional (star idle, travelling dot while thinking). The
 * filler is a NON-BREAKING space so the IDE's tab label can't collapse the field.
 *
 * Display-name priority: explicit user-chosen name (IDE tab rename or /tab skill, stored as
 * `userName`) > Claude's live auto topic name > the cached topic name from the per-sid file
 * > "Claude".
 */
internal object TitleModel {

    /** Idle indicator — Claude's star — held static at the left, matching native `✳ Claude Code`. */
    const val STAR = "✳"

    /** Thinking indicator — a dot that TRAVELS left → centre → right across the field while the
     *  session is busy. Idle = star; busy = travelling dot. */
    const val DOT = "·"

    /** Field the indicator occupies/travels across (3 cells), and the static index used when idle
     *  (1 = centre, so the idle star isn't stranded far to the left of the name). */
    const val BUSY_WIDTH = 3
    const val IDLE_POS = 1

    /** Non-breaking space filler — renders as a space but IDE tab labels don't trim it, so the
     *  fixed-width field survives even where regular leading/trailing spaces would be stripped. */
    private const val NB = " "

    /** Legacy/recognition prefixes: the star + dot, plus old builds' spinner frames — so a title
     *  from any state or past build is re-owned, never mistaken for a user rename. */
    val OUR_GLYPHS: List<String> = listOf(STAR, DOT, "✶", "✷", "✸")

    /** Generic terminal titles that are NOT real names — Claude's own defaults plus the IDE's
     *  shell placeholders. A captured title matching one of these carries no information. */
    private val GENERIC_TITLES = setOf("claude", "claude code")

    /** Shell-set titles that masquerade as a name when Claude isn't actively setting the terminal
     *  title: git-bash (`MINGW64:/d/Dev/X`, `MSYS`, `CYGWIN`) and bare filesystem paths
     *  (`D:\Dev\X`, `/d/Dev/X`). Anchored at the start so a real topic that merely CONTAINS a
     *  slash (`Make a/b mode a toggle button`) is NOT rejected — only path/shell shapes are. */
    private val SHELL_TITLE = Regex("""^(MINGW\d*|MSYS\w*|CYGWIN)""", RegexOption.IGNORE_CASE)
    private val PATH_TITLE = Regex("""^([A-Za-z]:[\\/]|[\\/])""")

    /**
     * Clean a title read straight off the live terminal (its `applicationTitle`, set by Claude's
     * escape sequence) into the bare topic worth persisting — or null if it's blank, one of our
     * own glyph-wrapped titles, a generic shell placeholder, or just Claude's default. This is
     * how a name shows up on a tab and gets captured even when Claude never wrote it to the
     * session file: we read what Rider is displaying and persist that.
     */
    fun cleanCapturedName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // Strip ALL leading animation decoration up to the first letter/digit — whitespace (incl.
        // non-breaking), our own glyph, AND Claude's native spinner glyphs (dots, stars, braille,
        // bullets). On a tab opened by the official Claude button, Claude animates the terminal
        // title itself; without this we'd capture a FRAME of that animation (e.g. "· Build…") into
        // the name and then render our own indicator in front of it → a double dot.
        // Reject shell-set titles / filesystem paths on the RAW value first — these appear when
        // Claude isn't actively owning the terminal title and must never be captured as a name.
        // Checked before decoration-stripping, which would eat a leading "/" or "C:\".
        val trimmed = raw.trim()
        if (SHELL_TITLE.containsMatchIn(trimmed)) return null
        if (PATH_TITLE.containsMatchIn(trimmed)) return null
        val bare = trimmed.trimStart { !it.isLetterOrDigit() }.trim()
        if (bare.isEmpty()) return null
        // Reject generics on the bare value (case-sensitive prettify would turn "pwsh" into the
        // non-generic-looking "Pwsh" and slip past these checks).
        if (ClaudeTabsHelpers.isGenericTabName(bare)) return null
        if (bare.lowercase() in GENERIC_TITLES) return null
        // Prettify so a captured slug (`fix-auth`) becomes `Fix Auth`; spaced names and their
        // casing (`Build data importer for spreadsheets`) pass through untouched.
        val pretty = ClaudeTabsHelpers.prettifySessionName(bare) ?: return null
        // Re-check after prettify in case it reveals a generic (`claude-code` → `Claude Code`).
        if (pretty.lowercase() in GENERIC_TITLES) return null
        return pretty
    }

    /** The fixed-width prefix with [glyph] at [pos]; all other cells are non-breaking fillers. */
    private fun field(glyph: String, pos: Int): String =
        (0 until BUSY_WIDTH).joinToString("") { if (it == pos) glyph else NB }

    /**
     * Ping-pong index across the field: 0,1,2,1,0,1,2,1,… i.e. L → C → R → C → L → repeat. The
     * dot bounces back through the centre rather than wrapping, so it never jumps R→L.
     */
    fun bouncePos(frame: Int): Int {
        val period = 2 * (BUSY_WIDTH - 1)          // 4 for a 3-cell field
        val m = Math.floorMod(frame, period)
        return if (m < BUSY_WIDTH) m else period - m
    }

    /**
     * Idle: static star centred (` ✳  Name`). Busy: a dot bounces left → centre → right →
     * centre → left (`·   Name` → ` ·  Name` → `  · Name` → ` ·  Name` → …). A constant
     * non-breaking separator sits between the field and the name in EVERY state, so the rightmost
     * dot always has a space after it AND the name's position is identical across idle and every
     * busy frame (the separator is uniform, so it can't resize the tab on the idle↔busy flip). All
     * busy frames share one character multiset, so the width is constant while thinking too.
     */
    fun compose(displayName: String, busy: Boolean, frameIndex: Int): String =
        if (busy) "${field(DOT, bouncePos(frameIndex))}$NB$displayName"
        else "${field(STAR, IDLE_POS)}$NB$displayName"

    /** True iff [title] (after trimming) starts with one of our glyphs — a title this plugin
     *  wrote (possibly a previous IDE session). Foreign decorations (`✦ x`) do NOT count.
     *  Kotlin's `trim()` strips non-breaking spaces too, so a centre/right dot frame (leading
     *  filler) is still recognised. */
    fun isOurFormat(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val t = title.trim()
        return OUR_GLYPHS.any { t.startsWith(it) }
    }

    /** Strip our leading glyph (+ surrounding whitespace, incl. non-breaking), leaving the bare
     *  display name. Since the animation only moves the indicator (the name text is unchanged),
     *  every frame strips to the same name — so a frame change never reads as a rename.
     *  Non-our-format titles pass through trimmed unchanged (a user's literal name is preserved). */
    fun stripGlyph(title: String): String {
        val t = title.trim()
        val g = OUR_GLYPHS.firstOrNull { t.startsWith(it) } ?: return t
        return t.removePrefix(g).trim()
    }

    /** First non-blank of: explicit user name, live topic, cached topic; else "Claude". */
    fun resolveDisplayName(userName: String?, liveTopic: String?, cachedTopic: String?): String =
        userName?.takeIf { it.isNotBlank() }
            ?: liveTopic?.takeIf { it.isNotBlank() }
            ?: cachedTopic?.takeIf { it.isNotBlank() }
            ?: "Claude"

    /**
     * @param apply title to write this tick, or null when the tab already shows it.
     * @param adoptName non-null when the user renamed the tab — persist as the entry's
     *   `userName` so the rename survives restarts.
     */
    data class Decision(val apply: String?, val adoptName: String?)

    /**
     * One enforcement step for one tab.
     *
     * Rename detection: [observed] counts as a user rename when it is non-blank, differs from
     * [lastApplied], is not a generic default ("Local"…), and either
     *  - is not our format at all (plain rename), or
     *  - is our format but its stripped text differs from [lastApplied]'s stripped text.
     * An our-format title with [lastApplied] == null is NOT a rename — it's our own stale title
     * surviving an IDE restart; re-own it. (A mere indicator-position change strips to the same
     * name, so it never registers as a rename.)
     */
    fun tick(
        observed: String?,
        lastApplied: String?,
        userName: String?,
        liveTopic: String?,
        cachedTopic: String?,
        busy: Boolean,
        frameIndex: Int,
    ): Decision {
        var adopt: String? = null
        if (!observed.isNullOrBlank()
            && observed != lastApplied
            && !ClaudeTabsHelpers.isGenericTabName(observed)
        ) {
            if (!isOurFormat(observed)) {
                adopt = observed.trim()
            } else if (lastApplied != null && stripGlyph(observed) != stripGlyph(lastApplied)) {
                adopt = stripGlyph(observed)
            }
            if (adopt != null && (adopt.isBlank() || adopt == userName)) adopt = null
        }
        val displayName = adopt ?: resolveDisplayName(userName, liveTopic, cachedTopic)
        val desired = compose(displayName, busy, frameIndex)
        return Decision(apply = desired.takeIf { it != observed }, adoptName = adopt)
    }
}
