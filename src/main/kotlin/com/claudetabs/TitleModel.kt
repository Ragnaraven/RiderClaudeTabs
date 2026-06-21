package com.claudetabs

/**
 * Pure title logic for Claude terminal tabs — zero IDE imports, fully unit-testable.
 *
 * The plugin OWNS `userDefinedTitle` for every tab hosting a Claude session. The title is
 * always `<glyph> <DisplayName>`; the glyph cycles through [FRAMES] while the session is
 * busy (Claude thinking) and sits at the first frame when idle. [TitleController] calls
 * [tick] every ~450ms per tab and applies/persists whatever it decides.
 *
 * Display-name priority: explicit user-chosen name (IDE tab rename or /tab skill, stored
 * as `userName`) > Claude's live auto topic name > the cached topic name from the per-sid
 * file > "Claude".
 */
internal object TitleModel {

    /** Animation frames. Frame 0 doubles as the static idle glyph. */
    val FRAMES = listOf("✳", "✶", "✷", "✸")

    fun glyph(busy: Boolean, frameIndex: Int): String =
        if (busy) FRAMES[Math.floorMod(frameIndex, FRAMES.size)] else FRAMES[0]

    /** `"✳ Fix Auth Rotation"` */
    fun compose(displayName: String, busy: Boolean, frameIndex: Int): String =
        "${glyph(busy, frameIndex)} $displayName"

    /** True iff [title] starts with one of our frame glyphs — i.e. a title this plugin wrote
     *  (possibly in a previous IDE session). Anchored to exactly our glyph set so foreign
     *  decorations (`✦ x`, `· x`) do NOT count as ours. */
    fun isOurFormat(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val t = title.trim()
        return FRAMES.any { t.startsWith(it) }
    }

    /** Remove a leading our-glyph (+ whitespace). Non-our-format titles pass through trimmed. */
    fun stripGlyph(title: String): String {
        val t = title.trim()
        for (f in FRAMES) {
            if (t.startsWith(f)) return t.removePrefix(f).trim()
        }
        return t
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
     * Rename detection: [observed] counts as a user rename when it is non-blank, differs
     * from [lastApplied], is not a generic default ("Local"…), and either
     *  - is not our format at all (plain rename), or
     *  - is our format but its text differs from [lastApplied]'s text (the rename dialog
     *    pre-fills the current `✳ …` title; the user edited around the glyph).
     * An our-format title with [lastApplied] == null is NOT a rename — it's our own stale
     * title surviving an IDE restart; re-own it.
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
