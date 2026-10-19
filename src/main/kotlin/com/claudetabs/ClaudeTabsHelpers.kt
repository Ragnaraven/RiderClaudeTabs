package com.claudetabs

/**
 * Pure helper functions extracted from [ClaudeTabWatcherStartup] so they can be unit-tested
 * without needing an IntelliJ [com.intellij.openapi.project.Project] instance or a running IDE.
 *
 * Nothing in here touches threads, reflection, or the IntelliJ platform. The one function that
 * reads the world is [sessionStillRunning], and it reads it only through the [SessionRoot] it is
 * handed — so a test drives it with `SessionRoot.Fake` and no IDE, exactly like the rest.
 */
internal object ClaudeTabsHelpers {

    // ══════════════════════════════════════════════════════════════
    // JSON HELPERS
    // ══════════════════════════════════════════════════════════════

    /**
     * Extract a string-valued field from [json] by [key], handling standard JSON string escapes.
     * Returns null if the key is missing or malformed.
     *
     * Intentionally hand-rolled (instead of Gson/Jackson) to keep the plugin zero-deps.
     * Only supports flat objects — entries in the plugin's files never have nested objects.
     */
    fun extractJsonString(json: String, key: String): String? {
        val m = Regex(""""$key"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(json) ?: return null
        return m.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"")
    }

    /** Escape backslashes and double-quotes for embedding into a JSON string literal. */
    fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** Extract a non-negative integer-valued field from flat [json], or null. */
    fun extractJsonLong(json: String, key: String): Long? =
        Regex(""""$key"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toLongOrNull()

    // ══════════════════════════════════════════════════════════════
    // TAB NAME HELPERS
    // ══════════════════════════════════════════════════════════════

    /**
     * True if [name] looks like a default JetBrains terminal tab name ("Local", "Local (2)",
     * "bash", "pwsh", etc.). A generic title is safe to overwrite with Claude's session name;
     * anything else is treated as user-chosen and left alone.
     */
    fun isGenericTabName(name: String): Boolean {
        val n = name.trim()
        // "Claude Code" is the terminal AI-agents launcher's DEFAULT tab title — treating it as a
        // real name made the rename-adoption path persist it as an explicit userName, which then
        // outranked the session's actual topic forever (field-observed: tabs stuck on the default).
        return n == "Local" || n.matches(Regex("Local \\(\\d+\\)")) ||
            n == "bash" || n == "pwsh" || n == "PowerShell" || n == "cmd" ||
            n.matches(Regex("bash \\(\\d+\\)")) || n.matches(Regex("pwsh \\(\\d+\\)")) ||
            n.equals("Claude", ignoreCase = true) ||
            n.equals("Claude Code", ignoreCase = true) ||
            n.matches(Regex("Claude Code \\(\\d+\\)", RegexOption.IGNORE_CASE))
    }

    /** Shell default tab names (lowercased) — what the IDE titles a plain terminal tab. */
    private val SHELL_TAB_NAMES = setOf(
        "local", "bash", "git bash", "sh", "zsh", "fish", "pwsh", "powershell",
        "windows powershell", "cmd", "command prompt", "wsl", "terminal",
    )

    /** Filler characters the CLI pads its title with. Not stripped by [String.trim] (none of them
     *  is a Java whitespace character), so the anchored tests must remove them explicitly. The
     *  BRAILLE PATTERN BLANK belongs here rather than in the busy-frame range below: it RENDERS as
     *  whitespace, so a title leading with it carries no Claude signal at all. */
    private const val TITLE_FILLERS = " \t\u00A0\u2007\u202F\u2800"

    /** Leading glyphs a Claude-hosting terminal wears: the plugin's own `✳`, and the CLI's own
     *  spinner characters. The braille block (U+2801..U+28FF, tested separately) carries claude's
     *  busy animation frames — U+2800 is excluded, being the blank pattern (see TITLE_FILLERS). */
    private val CLAUDE_TITLE_GLYPHS = setOf('✳', '·', '✶', '✷', '✸')

    /**
     * True when [t] is a title a Claude-hosting terminal wears. Both shapes are START-ANCHORED,
     * once the CLI's filler padding is trimmed:
     *
     *  - the first character is a plugin/CLI glyph or a braille busy frame — `✳ <topic>` while
     *    idle, `⠂ <topic>` / `⠐ <topic>` while busy;
     *  - or the title STARTS WITH "claude": the CLI's own process title during startup is
     *    literally `claude`, and the agent launcher's default tab title is `Claude Code`.
     *
     * Claude sets that title on its host terminal itself, so it is the one positive signal a
     * hand-opened tab running `claude` ever emits.
     *
     * Why not a SUBSTRING match on "claude" (what this did until the verdict began granting FIFO
     * eligibility and immediate commit): a shell titles its window with its cwd or its prompt, and
     * users of THIS plugin disproportionately have "claude" somewhere in their repo paths — a
     * title like `MINGW64:/path/to/claude-tools` or `user@host:~/claude-x` then defeats the whole
     * shell gate and hands a plain shell tab a session. Nothing Claude emits ever carries the word
     * anywhere but at the front.
     */
    fun looksLikeClaudeTitle(t: String): Boolean {
        val s = t.trimStart { it in TITLE_FILLERS }
        val first = s.firstOrNull() ?: return false
        if (s.startsWith("claude", ignoreCase = true)) return true
        return first in CLAUDE_TITLE_GLYPHS || first in '\u2801'..'\u28FF'
    }

    /**
     * True when a tab's observable titles say it is a PLAIN SHELL tab, not a Claude tab. A tab
     * this returns true for is excluded from EVERY correlation pass — it can never receive a
     * session (field-observed: a plain shell tab got a session's glyph and name because, on the
     * reworked terminal, an unbound shell tab is just as "undetermined" as an unbound Claude tab).
     *
     * Anything claude-ish in ANY title wins immediately ([looksLikeClaudeTitle]) — only then do
     * shell signals count: a known shell default name, a path-shaped title (shells set the window
     * title to their cwd, e.g. `MINGW64:/path/to/project` or `C:\path`), or a `user@host` prompt
     * shape. All titles unreadable → NOT shell (binding stays possible when the bridge is dark).
     *
     * Why the claude-ish exit must outrank EVERY shell slot: a tab wears the generic "Local"
     * defaultTitle (and the matching Content.displayName) forever, whatever runs inside it, so
     * without the rescue one shell-shaped slot condemns the whole tab.
     *
     * HOW FAR THAT RESCUE REACHES (verified on 2026.1 — do not re-derive): the slot that could
     * carry claude's own process title is `applicationTitle`, and on a REWORKED tab it is written
     * only when the advanced setting `terminal.show.application.title` is enabled — default OFF,
     * with no per-tab opt-in (runtime proof: a tab hosting the CLI reported `application=null` for
     * its whole life). So on reworked tabs this function has no positive signal to find and will
     * call a CLI-hosting tab a shell; the rescue is in practice a CLASSIC-tab affordance. That is
     * not a hole in adoption: a hand-opened reworked tab is bound by the EXACT passes (launch argv
     * naming the session, or the session's process descending from the tab's pty root), which
     * ignore this veto entirely.
     */
    fun titleLooksLikeShellTab(titles: Collection<String?>): Boolean {
        val real = titles.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }
        if (real.any { looksLikeClaudeTitle(it) }) return false
        return real.any { t ->
            t.lowercase() in SHELL_TAB_NAMES ||
                t.matches(Regex("(?i)(local|bash|pwsh) \\(\\d+\\)")) ||
                t.contains('\\') || t.contains(":/") || t.count { c -> c == '/' } >= 2 ||
                Regex("^\\S+@\\S+").containsMatchIn(t)
        }
    }

    /**
     * Adoption-freshness gate: true only when a session's OS-process start time is KNOWN and at
     * most [maxAgeMs] before [nowMs]. Unknown start (null — process gone, or the platform can't
     * report it) fails CLOSED: correlation-based adoption must never pair a session of unknown
     * age with a tab (field-observed: a 12-hour-old orphan session was 1:1-adopted onto a fresh
     * empty tab and stole its identity). A start time slightly in the future (clock skew) counts
     * as fresh — it is by definition not an old process.
     */
    fun isAdoptablyFresh(startEpochMs: Long?, nowMs: Long, maxAgeMs: Long): Boolean =
        startEpochMs != null && nowMs - startEpochMs <= maxAgeMs

    // ══════════════════════════════════════════════════════════════
    // WSL SESSION ROOTS — pure translation/parsing halves
    // ══════════════════════════════════════════════════════════════

    /**
     * Translate a WSL-side cwd to its Windows-comparable form: `/mnt/d/Dev/X` → `d:/Dev/X`.
     * Returns null for anything that isn't a `/mnt/<drive>` path (e.g. `/home/...`, which has
     * no Windows equivalent, or a path that is already Windows-shaped). Keeping every stored
     * cwd in Windows form lets ALL existing cwd machinery (project claims, adoption grouping,
     * spawn working directory) work unchanged — and `wsl.exe` maps the Windows cwd straight
     * back to the same `/mnt` path on entry.
     */
    fun wslCwdToWindows(cwd: String): String? {
        val m = Regex("""^/mnt/([a-zA-Z])(/.*)?$""").find(cwd.trim()) ?: return null
        val drive = m.groupValues[1]
        val rest = m.groupValues[2]
        return "$drive:" + (rest.ifEmpty { "/" })
    }

    /**
     * The inverse of [wslCwdToWindows]: `C:\Users\dev\.claude` → `/mnt/c/Users/dev/.claude`.
     * Returns null for a path with no drive letter (a UNC share has no `/mnt` equivalent).
     *
     * Used to tell a WSL-side helper script where the plugin's state dir is. The plugin only
     * ever runs on the Windows side, so its state lives in the Windows `~/.claude`; a helper
     * invoked from a Claude session INSIDE a distro sees that same directory through `/mnt`
     * and cannot derive the path on its own.
     */
    fun windowsPathToWslMount(path: String): String? {
        val m = Regex("""^([a-zA-Z]):[\\/](.*)$""").find(path.trim()) ?: return null
        val drive = m.groupValues[1].lowercase()
        val rest = m.groupValues[2].replace('\\', '/').trimEnd('/')
        return "/mnt/$drive" + if (rest.isEmpty()) "" else "/$rest"
    }

    /**
     * Normalise a cwd read off a TERMINAL TAB (the reworked bridge) into the same space session
     * cwds live in. Session cwds are stored Windows-TRANSLATED at scan time (see
     * [SessionRoot.comparableCwd]), so a tab cwd must be comparably translated or not used at all:
     *  - Windows-shaped (drive letter or a backslash present) → passed through unchanged;
     *  - `/`-rooted → through [wslCwdToWindows], which yields NULL for a Linux-native path
     *    (`/home/dev/project`) that simply has no Windows equivalent.
     *
     * Null means "cwd unreadable", NOT "some other cwd": an untranslatable path would otherwise
     * form a bucket no session can ever match, and the tab would silently lose the last-resort
     * eligibility it keeps while its cwd is merely unknown.
     */
    fun normalizeBridgeCwd(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.contains('\\') || Regex("^[a-zA-Z]:").containsMatchIn(s)) return raw
        if (s.startsWith("/")) return wslCwdToWindows(s)
        return raw
    }

    /**
     * Parse `wsl.exe -l -q` output into distro names. The tool writes UTF-16LE (the classic
     * gotcha — decoded as UTF-8 every other character is a NUL); a NUL byte anywhere means
     * UTF-16LE, otherwise plain UTF-8. BOM and blank lines are dropped.
     */
    fun parseWslDistroList(raw: ByteArray): List<String> {
        if (raw.isEmpty()) return emptyList()
        val text =
            if (raw.any { it == 0.toByte() }) String(raw, Charsets.UTF_16LE)
            else String(raw, Charsets.UTF_8)
        return text.lineSequence()
            .map { it.trim().trim('\uFEFF', '\u0000') }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /**
     * True when [s] may be interpolated into the `wsl.exe` command line and into a
     * `\\wsl.localhost\<distro>\…` path: non-blank and free of `"`, backtick, `$`, `;`, `&`, `|`,
     * backslash and line breaks. Names carrying any of those are SKIPPED, never escaped — the
     * command is consumed by an unknown host shell (git-bash, PowerShell, cmd), none of which
     * quote the same way, and a backslash would also break out of the UNC path.
     */
    fun isSafeWslToken(s: String): Boolean =
        s.isNotBlank() && s.none { it in "\"`$;&|\\\n\r" }

    /**
     * The command typed into a HOST terminal to resume a WSL-side session: enter the distro
     * (booting it if stopped) as [user] and run its own — LINUX-NATIVE — claude.
     *
     * [user] is the Linux user whose `~/.claude` the session was DISCOVERED under; without `-u`
     * the distro's DEFAULT user runs the resume, which for a multi-user distro is a different
     * home, a different claude installation and a different account. Null means unknown (a root
     * id persisted before roots carried a user) → `-u` is omitted and the default user runs.
     *
     * Explicit install locations are tried BEFORE bare `claude`, because WSL's interop PATH
     * appends the Windows PATH: field-verified, `sh -lc "which claude"` inside a distro resolved
     * to the WINDOWS npm claude under `/mnt/c/...` — a different installation and account, the
     * exact wire-crossing this feature exists to prevent. Bare `claude` runs only when no native
     * candidate exists; if that ever hits the Windows claude, `--resume` of a sid it doesn't own
     * errors visibly instead of silently opening the wrong account's session.
     *
     * Quoting is host-shell-agnostic (the widget's shell may be git-bash, PowerShell, or cmd):
     * one double-quoted argument, no `$`/backticks (PowerShell and bash would both expand them),
     * `~` and `;`/`&&` left for the WSL-side `sh` to interpret. [distro]/[user] are expected to
     * have passed [isSafeWslToken] at discovery time.
     */
    fun buildWslResumeCommand(distro: String, sid: String, user: String?): String {
        val candidates = listOf(
            "~/.local/bin/claude", "~/.npm-global/bin/claude",
            "/usr/local/bin/claude", "/usr/bin/claude",
        )
        val chain = candidates.joinToString("") { "test -x $it && exec $it --resume $sid; " }
        val asUser = user?.let { " -u \"$it\"" } ?: ""
        return "wsl.exe -d \"$distro\"$asUser -- sh -lc \"${chain}claude --resume $sid\""
    }

    /**
     * Convert Claude's auto-generated session-name slug (the `name` field in
     * `~/.claude/sessions/<pid>.json`, e.g. `fix-auth-token-rotation`) into a
     * human-friendly tab title (`Fix Auth Token Rotation`). Returns null for
     * null/blank input so callers can skip cleanly.
     */
    fun prettifySessionName(slug: String?): String? {
        if (slug.isNullOrBlank()) return null
        return slug.trim()
            .split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
            .takeIf { it.isNotBlank() }
    }

    // ══════════════════════════════════════════════════════════════
    // CONFIG PARSING
    // ══════════════════════════════════════════════════════════════

    /** Parsed config values with defaults already applied. */
    data class Config(
        val backlogMaxEntries: Int,
        val deadStrikesNeeded: Int,
    ) {
        companion object {
            /** Defaults: 100 backlog entries, 2 dead strikes before eviction. */
            val DEFAULT = Config(100, 2)
        }
    }

    /**
     * Parse a config.json blob leniently — any missing or malformed field falls back to the
     * corresponding default from [Config.DEFAULT]. Unknown fields are ignored.
     */
    fun parseConfig(text: String?): Config {
        var backlog = Config.DEFAULT.backlogMaxEntries
        var strikes = Config.DEFAULT.deadStrikesNeeded
        if (text.isNullOrBlank()) return Config(backlog, strikes)

        try {
            Regex(""""backlogMaxEntries"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                if (it > 0) backlog = it
            }
            Regex(""""deadStrikesNeeded"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                if (it > 0) strikes = it
            }
        } catch (_: Exception) { /* fall through to defaults */ }

        return Config(backlog, strikes)
    }

    // ══════════════════════════════════════════════════════════════
    // SHELL DETECTION
    // ══════════════════════════════════════════════════════════════

    /** Process names recognised as terminal shells. */
    private val SHELL_NAMES = setOf(
        "bash", "bash.exe", "sh", "sh.exe", "zsh", "fish",
        "pwsh", "pwsh.exe", "powershell", "powershell.exe", "cmd.exe"
    )

    /**
     * True if [cmd] (absolute or relative path) ends in a known shell executable name.
     * Case-insensitive; handles both `/` and `\` as path separators.
     */
    fun isShellCommand(cmd: String): Boolean {
        val name = cmd.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return name in SHELL_NAMES
    }

    // ══════════════════════════════════════════════════════════════
    // PROJECT HASH
    // ══════════════════════════════════════════════════════════════

    /**
     * Derive a stable filesystem-safe identifier from a project base path.
     * Used as the suffix of per-project state files (`user-closed-<hash>.json`, etc.).
     */
    fun projectHashForPath(basePath: String?): String =
        (basePath ?: "default").replace("\\", "/").replace(":/", "--").replace("/", "-")

    /** Normalise a path for cwd comparison: backslashes → '/', trailing '/' stripped, lowercased.
     *  (Salvaged from the deleted TabSessionMatcher — the one bridge the hand-open adopter needs.) */
    fun normalizeCwd(path: String): String = path.replace("\\", "/").trimEnd('/').lowercase()

    /**
     * True if [cwd] is the project base path, a descendant of it, OR a sibling worktree.
     *
     * Why sibling worktrees matter: a common workflow is `cd ../MyApp-feature-branch &&
     * claude --resume <sid>` from inside the main `MyApp` Rider window. The Claude process
     * cwd is `D:\Dev\MyApp-feature-branch` (NOT a subpath of `D:\Dev\MyApp`), but the tab
     * is hosted in MyApp's Rider window and conceptually belongs to MyApp. We accept any
     * cwd of the form `<projectBase>-<suffix>` as a sibling worktree.
     *
     * Both paths are normalised: backslashes → forward slashes, trailing slashes stripped,
     * lowercased. The strict-descendant check uses a `/` boundary so `/repos/MyApp` does
     * NOT accidentally match `/repos/MyApp-mobile` as a descendant — but the worktree clause
     * DOES accept it (which is correct: `MyApp-mobile` IS a sibling-worktree-style sibling
     * of `MyApp` from the user's perspective).
     */
    fun isCwdUnderProject(cwd: String?, projectBasePath: String?): Boolean {
        if (cwd.isNullOrBlank()) return false
        // Defensive: if we can't resolve the project base path (rare — only happens for
        // detached/default Rider projects), don't filter.
        if (projectBasePath.isNullOrBlank()) return true
        val n1 = cwd.replace("\\", "/").trimEnd('/').lowercase()
        val n2 = projectBasePath.replace("\\", "/").trimEnd('/').lowercase()
        if (n1 == n2) return true
        if (n1.startsWith("$n2/")) return true
        // Sibling-worktree tolerance: `<n2>-<suffix>` and `<n2>-<suffix>/...` both qualify.
        if (n1.startsWith("$n2-")) return true
        return false
    }

    /**
     * Does the project at [base] claim [cwd] EXCLUSIVELY — i.e. it claims by [isCwdUnderProject]
     * and no more specific OPEN project claims it too? The most specific open project wins.
     *
     * "More specific" is a strictly longer normalised base in [openBases] that also claims [cwd].
     * Length ties leave both claiming (they are different projects with equal standing), and the
     * longest claiming base is never suppressed by anything — so a cwd that any open project
     * claims always keeps at least one owner.
     *
     * [openBases] must be the OPEN projects' base paths and nothing else; this function reads no
     * index and decides no openness itself. That is the point: arbitrating against projects that
     * are merely KNOWN lets a closed sub-project veto the open parent's claim, and the session is
     * then restored by nobody — a closed window cannot spawn the tab it just vetoed.
     *
     * Two failures this prevents, both with every window otherwise claiming honestly: `<repo>`
     * and `<repo>/sub` open side by side, each restoring the same session into its own window
     * (one conversation, two tabs); and `<repo>` swallowing the sessions of sibling worktree
     * `<repo>-feature` while `<repo>-feature` is itself open in its own window.
     *
     * Stricter than the descendant fast path it grew out of: a DESCENDANT cwd arbitrates here
     * too, not just a dash-suffix sibling, because two nested open projects are exactly the
     * double-claim case above.
     */
    fun claimsExclusively(cwd: String, base: String, openBases: Collection<String>): Boolean {
        if (!isCwdUnderProject(cwd, base)) return false
        val nCwd = normalizeCwd(cwd)
        val nBase = normalizeCwd(base)
        return openBases.none { other ->
            val nOther = normalizeCwd(other)
            nOther != nBase && nOther.length > nBase.length && isCwdUnderProject(nCwd, nOther)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // TRANSCRIPT LOOKUP
    // ══════════════════════════════════════════════════════════════

    /**
     * True if a transcript file `<sessionId>.jsonl` exists anywhere under [projectsDir]
     * (which is `~/.claude/projects/` in production).
     *
     * Fast path: check the cwd-derived subdir (`<projectsDir>/<encoded-cwd>/<sessionId>.jsonl`)
     * since that's where Claude writes for fresh sessions.
     *
     * Fallback: scan all immediate subdirs. Needed when a session originally started in
     * cwd A is later resumed by sid from cwd B — Claude keeps appending to the original
     * transcript path (under A's encoded dir), not the one derived from B. Without the
     * fallback, every cross-cwd resume (most commonly: resuming a session inside a git
     * worktree shell) was rejected.
     *
     * The cwd → subdir encoding matches Claude's: backslash → forward-slash, then `:/` →
     * `--`, then `/` → `-`.
     */
    fun hasTranscriptAnywhere(projectsDir: java.io.File, sessionId: String, cwd: String?): Boolean =
        transcriptFile(projectsDir, sessionId, cwd) != null

    /**
     * Resolve the transcript file `<sessionId>.jsonl` for [sessionId], or null if none exists.
     * Same lookup order as [hasTranscriptAnywhere] (cwd-derived subdir fast path, then a scan of
     * every immediate subdir for the cross-cwd resume case).
     */
    fun transcriptFile(projectsDir: java.io.File, sessionId: String, cwd: String?): java.io.File? {
        if (sessionId.isBlank()) return null
        if (!cwd.isNullOrBlank()) {
            val h = cwd.replace("\\", "/").replace(":/", "--").replace("/", "-")
            val f = java.io.File(java.io.File(projectsDir, h), "$sessionId.jsonl")
            if (f.exists()) return f
        }
        val dirs = projectsDir.listFiles { f -> f.isDirectory } ?: return null
        for (d in dirs) {
            val f = java.io.File(d, "$sessionId.jsonl")
            if (f.exists()) return f
        }
        return null
    }

    // ══════════════════════════════════════════════════════════════
    // ARGV PARSING — canonical session id from --resume flag
    // ══════════════════════════════════════════════════════════════

    private val UUID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /**
     * Parse a Claude process's argv and return the UUID following `--resume` / `-r` /
     * `--resume=<uuid>`, or null if no resume flag is present (fresh session) or the
     * value isn't a UUID.
     */
    fun extractResumeIdFromArgs(args: Array<String>?): String? {
        if (args == null) return null
        for (i in args.indices) {
            val a = args[i]
            if (a == "--resume" || a == "-r") {
                return args.getOrNull(i + 1)?.takeIf { it.matches(UUID_REGEX) }
            }
            if (a.startsWith("--resume=")) {
                return a.substringAfter('=').takeIf { it.matches(UUID_REGEX) }
            }
        }
        return null
    }

    /**
     * Is this argv a HEADLESS run (`claude -p <prompt>` / `claude --print`) rather than an
     * interactive session? Null when [args] is null: the root could not tokenize argv, and
     * unknown must stay unknown rather than be guessed.
     *
     * WHOLE TOKENS only, which is why this takes argv and not the joined command line. In the
     * joined form `--permission-mode` contains `-p`, a directory can be named with a `-p`
     * segment, and a prompt can quote `--print` — each one reads as headless under a substring
     * scan. A headless run mistaken for a session gets tracked, titled and then RESTORED as a tab
     * the user never opened, and it reappears at every startup because nothing ever closes it.
     */
    fun isHeadlessArgs(args: List<String>?): Boolean? =
        args?.any { it == "-p" || it == "--print" }

    // ══════════════════════════════════════════════════════════════
    // OPEN-TABS SNAPSHOT
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute the open-tabs snapshot for one poll: the present sids, plus **warmup stickiness** for
     * just-spawned sids whose async widget→Content link hasn't landed yet.
     *
     * Warmup keeps a previously-snapshotted sid that is (a) not yet present and (b) spawned within
     * [warmupMs] — bridging the gap between `spawnOwnedTab` returning and the tab's Content appearing
     * in the tool window. It must NOT keep a sid that has **already materialized** ([materializedSids]):
     * once a tab was present, a later absence is a real × close. Omitting that check resurrects a tab
     * ×'d within [warmupMs] of restore on every poll (field-verified: "can't kill it").
     *
     * @param prev previous on-disk snapshot for this project
     * @param present sids whose Content is present in the tool window right now
     * @param materializedSids sids observed present at least once this session
     * @param lastSpawnAttempt sid → last spawn attempt time (ms)
     */
    fun computeOpenSnapshot(
        prev: Set<String>,
        present: Set<String>,
        materializedSids: Set<String>,
        lastSpawnAttempt: Map<String, Long>,
        now: Long,
        warmupMs: Long,
    ): Set<String> {
        val warming = prev.filter { sid ->
            sid !in present &&
                sid !in materializedSids &&
                lastSpawnAttempt[sid]?.let { (now - it) < warmupMs } == true
        }
        return present + warming
    }

    /**
     * Tab lists from three sources merged into ONE display-ordered list without duplicates:
     * [primary] (the ContentManager's own tabs — its order drives restore ordinals) first, then
     * every [nested] tab not already listed (split groups), then every [known] tab the terminal
     * frontend lists that neither source had (the stale-tool-window case — see [TerminalTabs]).
     * Compared by IDENTITY, the way the platform treats Contents.
     */
    fun <T : Any> mergeTabLists(primary: List<T>, nested: List<T>, known: Collection<T>): List<T> {
        val seen: MutableSet<T> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        val out = ArrayList<T>(primary.size + nested.size + known.size)
        for (source in listOf(primary, nested, known)) for (t in source) if (seen.add(t)) out.add(t)
        return out
    }

    // ══════════════════════════════════════════════════════════════
    // RESTORE ORDER AND RESTORE-TIME LIVENESS
    // ══════════════════════════════════════════════════════════════

    /**
     * The order the restore loop spawns entries in: saved tab position first, then the session's
     * own start time, then the sid. Nulls sort LAST in both leading keys.
     *
     * The start-time key carries the entries that never had a tab bound and so have no ordinal:
     * ordering those by [ActiveSessionsStore.Entry.startedAt] reopens them oldest-first, the
     * order the user created them in, instead of by sid — which is a random shuffle the user has
     * to re-arrange by hand after every restart. The sid tiebreak keeps the order TOTAL, so the
     * same set of entries always spawns the same left-to-right layout.
     */
    fun restoreOrder(): Comparator<ActiveSessionsStore.Entry> =
        compareBy<ActiveSessionsStore.Entry, Int?>(nullsLast<Int>()) { it.ordinal }
            .thenBy(nullsLast<Long>()) { it.startedAt }
            .thenBy { it.sid }

    /** Clock slack for the start-time check in [sessionStillRunning]: the poll's `lastSeen` stamp
     *  and a process's start time come from different clocks at different granularities, so a
     *  start a few seconds "after" the last sighting is still the same process. */
    const val START_SLACK_MS = 5_000L

    /** How much older than its process a session marker's own start stamp may be before the
     *  marker is read as a leftover of an EARLIER process that had the same pid. */
    const val MARKER_STALE_SLACK_MS = 60_000L

    /**
     * Is a `sessions/<pid>.json` marker a leftover from a previous process that had this pid?
     *
     * The CLI writes the marker at start, stamping its own `startedAt`, and does not always
     * remove it when it dies (killed at an IDE shutdown, say). After a restart the OS hands the
     * low pids out again, so a NEW claude can carry a dead conversation's marker for the moment
     * before it writes its own. Read naively, that marker says "pid N is alive and is
     * conversation X" while N's argv says `--resume Y` — which the in-app switch detector takes
     * for a Y→X switch, re-points the tab to a dead conversation, and leaves Y bound to nothing.
     * Field-observed on four tabs within a minute of one restore; all four dead conversations
     * were restored at the next start. A marker that predates its process by more than
     * [slackMs] cannot be that process's. Unknown stamps cannot discriminate → not stale.
     */
    fun markerIsStale(markerStartedAt: Long?, processStartedAt: Long?, slackMs: Long = MARKER_STALE_SLACK_MS): Boolean {
        if (markerStartedAt == null || processStartedAt == null) return false
        return markerStartedAt + slackMs < processStartedAt
    }

    /**
     * Is [entry]'s recorded pid still THIS session's live process in [root]? The restore-time
     * guard: a session that outlived the IDE must not be resumed into a second tab, because both
     * tabs then append to the one conversation.
     *
     * All four probes must pass:
     *  a. the entry has a pid and [SessionRoot.processAlive] says it is alive;
     *  b. the start time is CONSISTENT: [SessionRoot.processStartEpochMs] is at most
     *     [ActiveSessionsStore.Entry.lastSeen] + [START_SLACK_MS];
     *  c. the root's own `sessions/<pid>.json` names [ActiveSessionsStore.Entry.sid];
     *  d. the process's command line mentions "claude".
     *
     * (b) is the pid-reuse discriminator and the one HARD reject. A process that started after
     * the poll last saw this session cannot be it — the pid was recycled by something unrelated —
     * and calling that "still running" suppresses the restore and loses the user's conversation,
     * which is the worse of the two failures. A root that cannot report a start time cannot
     * discriminate at all, so an unknown one does not reject.
     *
     * (c) and (d) fail toward RUNNING whenever they cannot be read: a pid file caught mid-rewrite
     * or briefly unreadable, a root that cannot serve argv (a UNC-mounted `/proc` does not always
     * serve virtual-file contents), a live process reported with a blank command line (Windows
     * does this across an access-token boundary). They exist only to catch a stale pid that (b)
     * could not, so an unreadable moment there must not manufacture a double resume.
     *
     * Never throws and never logs — any probe failure means "not running", and the CALLER owns
     * the log line, so this stays a decision function tests can drive with `SessionRoot.Fake`.
     * [now] is accepted so callers can pin the clock; the rule above compares against the entry's
     * own `lastSeen` and does not consult it.
     */
    fun sessionStillRunning(
        root: SessionRoot,
        entry: ActiveSessionsStore.Entry,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val pid = entry.pid ?: return false
        return try {
            root.processAlive(pid) &&
                startTimeConsistent(root, pid, entry.lastSeen) &&
                pidFileNamesSession(root, pid, entry.sid) &&
                commandLineMentionsClaude(root, pid)
        } catch (_: Throwable) {
            false
        }
    }

    /** (b) — the pid-reuse discriminator. Unknown start time cannot discriminate → no reject. */
    private fun startTimeConsistent(root: SessionRoot, pid: Long, lastSeen: Long): Boolean {
        val started = root.processStartEpochMs(pid) ?: return true
        return started <= lastSeen + START_SLACK_MS
    }

    /** (c) — the root's own pid file. Absent, unreadable or without the field → unknown → no
     *  reject; a pid file that names a DIFFERENT session is a real one. */
    private fun pidFileNamesSession(root: SessionRoot, pid: Long, sid: String): Boolean {
        val text = try {
            val f = java.io.File(root.sessionsDir, "$pid.json")
            if (!f.isFile) return true
            f.readText()
        } catch (_: Throwable) { return true }
        val recorded = extractJsonString(text, "sessionId") ?: return true
        return recorded == sid
    }

    /** (d) — command-line sanity. A root that cannot inspect the process, or reports it blank,
     *  is the same "unknown" the scan already treats as not-a-verdict → no reject. */
    private fun commandLineMentionsClaude(root: SessionRoot, pid: Long): Boolean {
        val cmd = try { root.processInfo(pid)?.commandLine } catch (_: Throwable) { null }
        if (cmd.isNullOrBlank()) return true
        return cmd.contains("claude", ignoreCase = true)
    }
}
