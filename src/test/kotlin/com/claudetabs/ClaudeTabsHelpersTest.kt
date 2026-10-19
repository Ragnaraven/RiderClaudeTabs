package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [ClaudeTabsHelpers] — pure functions, no IDE/filesystem dependencies for most
 * (the transcript-lookup test uses TemporaryFolder).
 *
 * The 2.0 helpers are a slimmed subset of 1.x: name-classification helpers
 * (`isGenericTabName`, `isAiOverlayName`, `isRenameRedundant`, `NameEntry`) are gone.
 */
class ClaudeTabsHelpersTest {

    @get:Rule val tmp = TemporaryFolder()

    // ── windowsPathToWslMount ────────────────────────────────────────

    @Test fun windowsPathToWslMount_translatesDriveLetter() {
        assertEquals("/mnt/c/Users/dev/.claude", ClaudeTabsHelpers.windowsPathToWslMount("C:\\Users\\dev\\.claude"))
    }

    @Test fun windowsPathToWslMount_acceptsForwardSlashes() {
        assertEquals("/mnt/d/Dev/MyApp", ClaudeTabsHelpers.windowsPathToWslMount("D:/Dev/MyApp"))
    }

    @Test fun windowsPathToWslMount_driveRoot() {
        assertEquals("/mnt/c", ClaudeTabsHelpers.windowsPathToWslMount("C:\\"))
    }

    /** A UNC share has no /mnt equivalent — null keeps the caller from writing a pointer that
     *  resolves to nothing rather than one that resolves to the wrong directory. */
    @Test fun windowsPathToWslMount_rejectsUncAndRootedPaths() {
        assertNull(ClaudeTabsHelpers.windowsPathToWslMount("\\\\wsl.localhost\\Distro\\home\\dev"))
        assertNull(ClaudeTabsHelpers.windowsPathToWslMount("/home/dev/.claude"))
    }

    /** The two translations are inverses over the paths both accept — the pointer the plugin
     *  writes must name the same directory the session records' cwds were translated from. */
    @Test fun windowsPathToWslMount_roundTripsWithWslCwdToWindows() {
        val wsl = "/mnt/d/Dev/MyApp"
        val windows = ClaudeTabsHelpers.wslCwdToWindows(wsl)!!
        assertEquals(wsl, ClaudeTabsHelpers.windowsPathToWslMount(windows))
    }

    // ── extractJsonString ────────────────────────────────────────────

    @Test fun extractJsonString_simpleField() {
        assertEquals("hello", ClaudeTabsHelpers.extractJsonString("""{"x":"hello"}""", "x"))
    }

    @Test fun extractJsonString_missingKeyReturnsNull() {
        assertNull(ClaudeTabsHelpers.extractJsonString("""{"x":"hello"}""", "y"))
    }

    @Test fun extractJsonString_escapedQuotes() {
        assertEquals("a\"b", ClaudeTabsHelpers.extractJsonString("""{"x":"a\"b"}""", "x"))
    }

    @Test fun extractJsonString_escapedBackslashes() {
        // On-disk `\\` (two chars) means one literal backslash after decode.
        assertEquals("D:\\Dev\\X", ClaudeTabsHelpers.extractJsonString("""{"x":"D:\\Dev\\X"}""", "x"))
    }

    // ── extractJsonLong ──────────────────────────────────────────────

    @Test fun extractJsonLong_readsTheStartedAtStamp() {
        // The CLI's `startedAt` is the primary adoption-freshness anchor — see isAdoptablyFresh.
        assertEquals(1_700_000_000_000L, ClaudeTabsHelpers.extractJsonLong("""{"startedAt":1700000000000}""", "startedAt"))
        assertEquals(0L, ClaudeTabsHelpers.extractJsonLong("""{"a":1,"startedAt": 0 }""", "startedAt"))
    }

    @Test fun extractJsonLong_missingOrNonNumericFieldsReturnNull() {
        // Unknown age must stay UNKNOWN (freshness fails closed on null) — never coerced to a number.
        assertNull(ClaudeTabsHelpers.extractJsonLong("""{"startedAt":1700000000000}""", "endedAt"))
        assertNull(ClaudeTabsHelpers.extractJsonLong("""{"startedAt":"1700000000000"}""", "startedAt"))
        assertNull(ClaudeTabsHelpers.extractJsonLong("""{"startedAt":null}""", "startedAt"))
        assertNull(ClaudeTabsHelpers.extractJsonLong("", "startedAt"))
    }

    // ── esc ──────────────────────────────────────────────────────────

    @Test fun esc_quoteAndBackslash() {
        assertEquals("a\\\\b\\\"c", ClaudeTabsHelpers.esc("a\\b\"c"))
    }

    // ── parseConfig ──────────────────────────────────────────────────

    @Test fun parseConfig_emptyReturnsDefaults() {
        val c = ClaudeTabsHelpers.parseConfig(null)
        assertEquals(ClaudeTabsHelpers.Config.DEFAULT.backlogMaxEntries, c.backlogMaxEntries)
        assertEquals(ClaudeTabsHelpers.Config.DEFAULT.deadStrikesNeeded, c.deadStrikesNeeded)
    }

    @Test fun parseConfig_overrideBacklogMaxEntries() {
        val c = ClaudeTabsHelpers.parseConfig("""{"backlogMaxEntries": 100}""")
        assertEquals(100, c.backlogMaxEntries)
    }

    @Test fun parseConfig_overrideDeadStrikes() {
        val c = ClaudeTabsHelpers.parseConfig("""{"deadStrikesNeeded": 4}""")
        assertEquals(4, c.deadStrikesNeeded)
    }

    @Test fun parseConfig_malformedFallsBackToDefaults() {
        val c = ClaudeTabsHelpers.parseConfig("""not valid json""")
        assertEquals(ClaudeTabsHelpers.Config.DEFAULT.backlogMaxEntries, c.backlogMaxEntries)
    }

    // ── isShellCommand ────────────────────────────────────────────────

    @Test fun isShellCommand_recognisedShells() {
        assertTrue(ClaudeTabsHelpers.isShellCommand("/usr/bin/bash"))
        assertTrue(ClaudeTabsHelpers.isShellCommand("/usr/bin/zsh"))
        assertTrue(ClaudeTabsHelpers.isShellCommand("C:\\Windows\\System32\\cmd.exe"))
        assertTrue(ClaudeTabsHelpers.isShellCommand("C:\\Program Files\\PowerShell\\7\\pwsh.exe"))
    }

    @Test fun isShellCommand_rejectsClaudeAndOtherProcesses() {
        assertFalse(ClaudeTabsHelpers.isShellCommand("/usr/bin/claude"))
        assertFalse(ClaudeTabsHelpers.isShellCommand("C:\\Windows\\explorer.exe"))
    }

    // ── projectHashForPath ────────────────────────────────────────────

    @Test fun projectHashForPath_windowsPath() {
        assertEquals("D--Dev-MyApp", ClaudeTabsHelpers.projectHashForPath("D:\\Dev\\MyApp"))
    }

    @Test fun projectHashForPath_unixPath() {
        assertEquals("-home-me-repos-MyApp", ClaudeTabsHelpers.projectHashForPath("/home/me/repos/MyApp"))
    }

    @Test fun projectHashForPath_nullDefaults() {
        assertEquals("default", ClaudeTabsHelpers.projectHashForPath(null))
    }

    // ── hasTranscriptAnywhere ─────────────────────────────────────────

    @Test fun hasTranscriptAnywhere_fastPathHit() {
        val projects = tmp.newFolder("projects")
        val cwd = "D:\\Dev\\Foo"
        val encoded = "D--Dev-Foo"
        File(projects, encoded).mkdirs()
        File(File(projects, encoded), "sid-1.jsonl").writeText("[]")
        assertTrue(ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "sid-1", cwd))
    }

    @Test fun hasTranscriptAnywhere_fallback_worktreeResume() {
        // Session originally started in D:\Dev\Foo (transcript under D--Dev-Foo/).
        // Later resumed from D:\Dev\Foo-feature; cwd-derived path won't find it,
        // fallback scan must.
        val projects = tmp.newFolder("projects")
        File(projects, "D--Dev-Foo").mkdirs()
        File(File(projects, "D--Dev-Foo"), "sid-1.jsonl").writeText("[]")
        assertTrue(
            "fallback scan must find sid-1 in any project dir, not just the cwd-derived one",
            ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "sid-1", "D:\\Dev\\Foo-feature")
        )
    }

    @Test fun hasTranscriptAnywhere_missing_returnsFalse() {
        val projects = tmp.newFolder("projects")
        File(projects, "D--Dev-X").mkdirs()
        assertFalse(ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "missing-sid", "D:\\Dev\\X"))
    }

    @Test fun hasTranscriptAnywhere_blankSid_false() {
        val projects = tmp.newFolder("projects")
        assertFalse(ClaudeTabsHelpers.hasTranscriptAnywhere(projects, "", "D:\\Dev\\X"))
    }

    // ── transcriptFile — resolves the .jsonl path ────────────────────────

    @Test fun transcriptFile_returnsFileOnHit_nullOnMiss() {
        val projects = tmp.newFolder("projects")
        val dir = File(projects, "D--Dev-Foo").apply { mkdirs() }
        val f = File(dir, "sid-1.jsonl").apply { writeText("[]") }
        assertEquals(f, ClaudeTabsHelpers.transcriptFile(projects, "sid-1", "D:\\Dev\\Foo"))
        assertNull(ClaudeTabsHelpers.transcriptFile(projects, "missing", "D:\\Dev\\Foo"))
    }

    // ── extractResumeIdFromArgs ──────────────────────────────────────

    @Test fun extractResumeIdFromArgs_dashDashResumeWithSpace() {
        val args = arrayOf("--resume", "12345678-1234-1234-1234-123456789012")
        assertEquals("12345678-1234-1234-1234-123456789012", ClaudeTabsHelpers.extractResumeIdFromArgs(args))
    }

    @Test fun extractResumeIdFromArgs_dashR() {
        val args = arrayOf("-r", "12345678-1234-1234-1234-123456789012")
        assertEquals("12345678-1234-1234-1234-123456789012", ClaudeTabsHelpers.extractResumeIdFromArgs(args))
    }

    @Test fun extractResumeIdFromArgs_dashDashResumeEquals() {
        val args = arrayOf("--resume=12345678-1234-1234-1234-123456789012")
        assertEquals("12345678-1234-1234-1234-123456789012", ClaudeTabsHelpers.extractResumeIdFromArgs(args))
    }

    @Test fun extractResumeIdFromArgs_noResumeFlag() {
        assertNull(ClaudeTabsHelpers.extractResumeIdFromArgs(arrayOf("--debug")))
    }

    @Test fun extractResumeIdFromArgs_nonUuidValue() {
        // Garbage after --resume is rejected (not a UUID).
        assertNull(ClaudeTabsHelpers.extractResumeIdFromArgs(arrayOf("--resume", "not-a-uuid")))
    }

    @Test fun extractResumeIdFromArgs_nullArgs() {
        assertNull(ClaudeTabsHelpers.extractResumeIdFromArgs(null))
    }

    // ── computeOpenSnapshot ─────────────────────────────────────────

    private val WARMUP = 45_000L

    @Test fun computeOpenSnapshot_presentAreAlwaysIncluded() {
        val snap = ClaudeTabsHelpers.computeOpenSnapshot(
            prev = setOf("a"), present = setOf("a", "b"),
            materializedSids = emptySet(), lastSpawnAttempt = emptyMap(), now = 1000, warmupMs = WARMUP,
        )
        assertEquals(setOf("a", "b"), snap)
    }

    @Test fun computeOpenSnapshot_warmupKeepsJustSpawnedNotYetPresent() {
        // "s" was in prev, isn't present yet, spawned 1s ago, never materialized → kept (warming).
        val snap = ClaudeTabsHelpers.computeOpenSnapshot(
            prev = setOf("s"), present = emptySet(),
            materializedSids = emptySet(), lastSpawnAttempt = mapOf("s" to 1_000L), now = 2_000L, warmupMs = WARMUP,
        )
        assertEquals(setOf("s"), snap)
    }

    @Test fun computeOpenSnapshot_materializedThenAbsentIsNotResurrected() {
        // THE REGRESSION: "s" spawned 1s ago (inside warmup) AND already materialized, now absent
        // (user ×'d it). Must NOT be re-added — otherwise the tab can never be killed.
        val snap = ClaudeTabsHelpers.computeOpenSnapshot(
            prev = setOf("s"), present = emptySet(),
            materializedSids = setOf("s"), lastSpawnAttempt = mapOf("s" to 1_000L), now = 2_000L, warmupMs = WARMUP,
        )
        assertEquals(emptySet<String>(), snap)
    }

    @Test fun computeOpenSnapshot_warmupExpiresAfterWindow() {
        // Spawned long ago, never materialized, still absent → warmup no longer protects it.
        val snap = ClaudeTabsHelpers.computeOpenSnapshot(
            prev = setOf("s"), present = emptySet(),
            materializedSids = emptySet(), lastSpawnAttempt = mapOf("s" to 1_000L), now = 1_000L + WARMUP + 1, warmupMs = WARMUP,
        )
        assertEquals(emptySet<String>(), snap)
    }

    @Test fun computeOpenSnapshot_absentWithNoSpawnStampIsDropped() {
        // A zombie carries no recent spawn stamp → never re-admitted.
        val snap = ClaudeTabsHelpers.computeOpenSnapshot(
            prev = setOf("z"), present = emptySet(),
            materializedSids = emptySet(), lastSpawnAttempt = emptyMap(), now = 5_000L, warmupMs = WARMUP,
        )
        assertEquals(emptySet<String>(), snap)
    }

    // ── Generic tab names: launcher defaults must never become a persisted user rename ──
    // ── markerIsStale (recycled-pid guard) ───────────────────────────
    @Test fun markerIsStale_markerOlderThanItsProcessIsStale() {
        val processStart = 2_000_000L
        assertTrue(ClaudeTabsHelpers.markerIsStale(processStart - 3_600_000L, processStart))
    }

    @Test fun markerIsStale_markerAtOrAfterProcessStartIsNot() {
        val processStart = 2_000_000L
        assertFalse(ClaudeTabsHelpers.markerIsStale(processStart + 800L, processStart))
        assertFalse(ClaudeTabsHelpers.markerIsStale(processStart - 30_000L, processStart)) // inside the slack
    }

    @Test fun markerIsStale_unknownStampsCannotDiscriminate() {
        assertFalse(ClaudeTabsHelpers.markerIsStale(null, 2_000_000L))
        assertFalse(ClaudeTabsHelpers.markerIsStale(1_000L, null))
    }

    @Test fun genericTabName_includesAgentLauncherDefaults() {
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Claude Code"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("claude code"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Claude Code (2)"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Claude"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Local"))
        // Real names stay adoptable.
        assertTrue(!ClaudeTabsHelpers.isGenericTabName("Fix Auth Token Rotation"))
        assertTrue(!ClaudeTabsHelpers.isGenericTabName("Claude Refactor Plan"))
    }

    // ── Shell-titled tab detection: keeps FIFO/last-resort from binding a session to a shell ──

    @Test fun shellTabTitle_detectsShellDefaultsAndPromptShapes() {
        assertTrue(ClaudeTabsHelpers.titleLooksLikeShellTab(listOf("Local", null, null, null)))
        assertTrue(ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, "MINGW64:/path/to/project", "Local", null)))
        assertTrue(ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, """C:\path\to\project""", null, null)))
        assertTrue(ClaudeTabsHelpers.titleLooksLikeShellTab(listOf("Git Bash", null, null, null)))
        assertTrue(ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, "user@host /path/to/project", null, null)))
        assertTrue(ClaudeTabsHelpers.titleLooksLikeShellTab(listOf("Local (2)", null, null, null)))
    }

    @Test fun shellTabTitle_pathShapedTitleContainingClaudeIsStillAShell() {
        // Users of this plugin disproportionately have "claude" inside their repo paths, and a
        // shell titles its window with its cwd — a substring match let such a title defeat the
        // whole shell gate and hand a plain shell tab a session.
        assertTrue(ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, "MINGW64:/path/to/claude-tools", null, null)))
        assertTrue(ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, "user@host:~/claude-x", null, null)))
    }

    @Test fun shellTabTitle_claudeishTitleAlwaysWinsOverShellSignals() {
        // The agent launcher titles its tabs "Claude Code" — never a shell, even when another
        // field carries a path-shaped application title.
        assertTrue(!ClaudeTabsHelpers.titleLooksLikeShellTab(listOf("Claude Code", "MINGW64:/path/to/project", null, null)))
        assertTrue(!ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, null, "claude", null)))
    }

    @Test fun claudeTitle_recognisesWordAndLeadingGlyphs() {
        // The CLI's own process title during startup, and the launcher's default tab title.
        assertTrue(ClaudeTabsHelpers.looksLikeClaudeTitle("claude"))
        assertTrue(ClaudeTabsHelpers.looksLikeClaudeTitle("Claude Code"))
        // Idle: plugin/CLI glyph + topic.
        assertTrue(ClaudeTabsHelpers.looksLikeClaudeTitle("✳ Fix Auth Token Rotation"))
        assertTrue(ClaudeTabsHelpers.looksLikeClaudeTitle("✶ Fix Auth Token Rotation"))
        assertTrue(ClaudeTabsHelpers.looksLikeClaudeTitle("· Fix Auth Token Rotation"))
        // Busy: braille animation frames.
        assertTrue(ClaudeTabsHelpers.looksLikeClaudeTitle("⠂ Fix Auth Token Rotation"))
        assertTrue(ClaudeTabsHelpers.looksLikeClaudeTitle("⠐ Fix Auth Token Rotation"))
        // NBSP-family padding in front of the glyph must not hide it.
        assertTrue(ClaudeTabsHelpers.looksLikeClaudeTitle(" ✳ Topic"))
        assertTrue(ClaudeTabsHelpers.looksLikeClaudeTitle("  ⠂ Topic"))
        // Plain shell / topic titles are not claude signals.
        assertTrue(!ClaudeTabsHelpers.looksLikeClaudeTitle("Local"))
        assertTrue(!ClaudeTabsHelpers.looksLikeClaudeTitle("MINGW64:/path/to/project"))
        assertTrue(!ClaudeTabsHelpers.looksLikeClaudeTitle("Fix Auth Token Rotation"))
        assertTrue(!ClaudeTabsHelpers.looksLikeClaudeTitle(""))
    }

    @Test fun claudeTitle_isStartAnchoredNotASubstringMatch() {
        // The word must LEAD the title: claude's own titles are "claude" / "Claude Code", while a
        // shell's path-shaped title can carry the word anywhere.
        assertTrue(!ClaudeTabsHelpers.looksLikeClaudeTitle("MINGW64:/path/to/claude-tools"))
        assertTrue(!ClaudeTabsHelpers.looksLikeClaudeTitle("user@host:~/claude-x"))
        assertTrue(!ClaudeTabsHelpers.looksLikeClaudeTitle("ProjectA (claude)"))
        // Still anchored after the CLI's filler padding.
        assertTrue(ClaudeTabsHelpers.looksLikeClaudeTitle(" claude"))
    }

    @Test fun claudeTitle_brailleBlankIsFillerNotABusyFrame() {
        // U+2800 renders as whitespace — a title leading with it carries no signal by itself.
        assertTrue(!ClaudeTabsHelpers.looksLikeClaudeTitle("⠀Fix Auth Token Rotation"))
        assertTrue(!ClaudeTabsHelpers.looksLikeClaudeTitle("⠀"))
        // ...but it must not hide a real busy frame or glyph behind it either.
        assertTrue(ClaudeTabsHelpers.looksLikeClaudeTitle("⠀⠂ Fix Auth Token Rotation"))
        assertTrue(ClaudeTabsHelpers.looksLikeClaudeTitle("⠀✳ Fix Auth Token Rotation"))
    }

    @Test fun shellTabTitle_glyphTitleRescuesTheGenericLocalDefault() {
        // The field shape: a hand-opened reworked tab wears "Local" in defaultTitle AND
        // displayName forever, while claude's own topic title lands in applicationTitle. The glyph
        // must rescue the tab — otherwise one shell-shaped slot vetoes the whole tab.
        assertTrue(!ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, "✳ Fix Auth Token Rotation", "Local", "Local")))
        assertTrue(!ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, "⠐ Fix Auth Token Rotation", "Local", "Local (2)")))
        // The CLI's startup title is the plain word.
        assertTrue(!ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, "claude", "Local", "Local")))
        // Same bag WITHOUT any claude signal is still a shell.
        assertTrue(ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, null, "Local", "Local")))
    }

    @Test fun shellTabTitle_topicTitlesAndUnreadableTitlesStayEligible() {
        // A real conversation topic is not a shell signal.
        assertTrue(!ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, "Fix Auth Token Rotation", null, null)))
        // All titles unreadable → cannot rule the tab out (bridge dark ≠ shell).
        assertTrue(!ClaudeTabsHelpers.titleLooksLikeShellTab(listOf(null, null, null, null)))
        assertTrue(!ClaudeTabsHelpers.titleLooksLikeShellTab(emptyList()))
    }

    // ── Adoption freshness: stale sessions must never pair with a tab ──

    @Test fun adoptFreshness_freshPassesStaleAndUnknownFail() {
        val now = 10_000_000L
        val window = 5 * 60_000L
        // Started seconds ago → fresh (the legitimate hand-typed-claude case).
        assertTrue(ClaudeTabsHelpers.isAdoptablyFresh(now - 3_000L, now, window))
        // Exactly at the window boundary → still fresh.
        assertTrue(ClaudeTabsHelpers.isAdoptablyFresh(now - window, now, window))
        // Hours old → never adoptable (the tab-theft case).
        assertTrue(!ClaudeTabsHelpers.isAdoptablyFresh(now - 12 * 3_600_000L, now, window))
        // Unknown start → fails CLOSED.
        assertTrue(!ClaudeTabsHelpers.isAdoptablyFresh(null, now, window))
        // Slight clock skew into the future → by definition not an old process.
        assertTrue(ClaudeTabsHelpers.isAdoptablyFresh(now + 1_000L, now, window))
    }

    // ── WSL session roots: pure translation/parsing halves ──

    @Test fun wslCwd_mntPathsTranslateToDrivePaths() {
        assertEquals("d:/Dev/ProjectA", ClaudeTabsHelpers.wslCwdToWindows("/mnt/d/Dev/ProjectA"))
        assertEquals("c:/", ClaudeTabsHelpers.wslCwdToWindows("/mnt/c"))
        assertEquals("c:/x y/z", ClaudeTabsHelpers.wslCwdToWindows("/mnt/c/x y/z"))
    }

    @Test fun wslCwd_nonMntAndWindowsPathsReturnNull() {
        // A Linux-native home has no Windows equivalent — must NOT pretend it does.
        assertNull(ClaudeTabsHelpers.wslCwdToWindows("/home/dev/project"))
        assertNull(ClaudeTabsHelpers.wslCwdToWindows("D:\\Dev\\ProjectA"))
        assertNull(ClaudeTabsHelpers.wslCwdToWindows("/mnt"))
        assertNull(ClaudeTabsHelpers.wslCwdToWindows("/mntx/d"))
        assertNull(ClaudeTabsHelpers.wslCwdToWindows(""))
    }

    // ── Bridge cwd normalisation: a tab cwd must live in the same space as session cwds ──

    @Test fun bridgeCwd_windowsShapedPathsPassThroughUnchanged() {
        assertEquals("""D:\Dev\ProjectA""", ClaudeTabsHelpers.normalizeBridgeCwd("""D:\Dev\ProjectA"""))
        assertEquals("D:/Dev/ProjectA", ClaudeTabsHelpers.normalizeBridgeCwd("D:/Dev/ProjectA"))
    }

    @Test fun bridgeCwd_wslMountPathsTranslateToTheirWindowsForm() {
        // Session cwds are stored Windows-translated at scan time, so tab cwds must match.
        assertEquals("d:/Dev/ProjectA", ClaudeTabsHelpers.normalizeBridgeCwd("/mnt/d/Dev/ProjectA"))
    }

    @Test fun bridgeCwd_linuxNativePathsAreUnusable() {
        // No Windows equivalent → NULL, so the tab stays "undetermined" (and keeps its last-resort
        // eligibility) instead of forming a bucket no session can ever match.
        assertNull(ClaudeTabsHelpers.normalizeBridgeCwd("/home/dev/project"))
        assertNull(ClaudeTabsHelpers.normalizeBridgeCwd("/opt/work"))
        assertNull(ClaudeTabsHelpers.normalizeBridgeCwd("   "))
    }

    @Test fun wslDistroList_decodesUtf16leOutput() {
        // `wsl.exe -l -q` writes UTF-16LE: "DistroA\r\nDistroB\r\n".
        val text = "DistroA\r\nDistroB\r\n"
        val bytes = text.toByteArray(Charsets.UTF_16LE)
        assertEquals(listOf("DistroA", "DistroB"), ClaudeTabsHelpers.parseWslDistroList(bytes))
    }

    @Test fun wslDistroList_decodesPlainUtf8AndDropsBlanks() {
        val bytes = "\uFEFFDistroA\n\n  \nDistroB\n".toByteArray(Charsets.UTF_8)
        assertEquals(listOf("DistroA", "DistroB"), ClaudeTabsHelpers.parseWslDistroList(bytes))
        assertEquals(emptyList<String>(), ClaudeTabsHelpers.parseWslDistroList(ByteArray(0)))
    }

    @Test fun wslResumeCommand_triesNativeClaudesBeforePath() {
        val sid = "11111111-2222-4333-8444-555555555555"
        val cmd = ClaudeTabsHelpers.buildWslResumeCommand("DistroA", sid, "UserA")
        // Enters the named distro (quoted — distro names can carry spaces) as the user whose
        // home the session was READ from: the default user would be another account entirely.
        assertTrue(cmd.startsWith("wsl.exe -d \"DistroA\" -u \"UserA\" -- sh -lc \""))
        // Explicit Linux-native locations come BEFORE bare `claude`: WSL's interop PATH can
        // resolve bare `claude` to the WINDOWS installation (field-verified) — wrong account.
        val nativeIdx = cmd.indexOf("~/.local/bin/claude --resume $sid")
        val bareIdx = cmd.indexOf("\"claude --resume $sid") // opening of the final PATH fallback
        assertTrue(nativeIdx >= 0)
        assertTrue(cmd.contains("exec ~/.local/bin/claude --resume $sid"))
        assertTrue(cmd.contains("exec /usr/local/bin/claude --resume $sid"))
        assertTrue("bare claude must be the LAST resort", cmd.trimEnd('"').endsWith("claude --resume $sid"))
        assertTrue(nativeIdx < cmd.lastIndexOf("claude --resume $sid"))
        // Host-shell-agnostic: nothing PowerShell or bash would expand before WSL's sh sees it.
        assertTrue(!cmd.contains('$') && !cmd.contains('`'))
        assertTrue(bareIdx != 0) // shape sanity: the fallback isn't the whole command
    }

    @Test fun wslResumeCommand_omitsUserFlagWhenUnknown() {
        // Legacy root id (persisted before roots carried a user): no `-u`, so the distro's
        // default user runs — the only thing we can honestly do without a known user.
        val sid = "11111111-2222-4333-8444-555555555555"
        val cmd = ClaudeTabsHelpers.buildWslResumeCommand("DistroA", sid, null)
        assertTrue(cmd.startsWith("wsl.exe -d \"DistroA\" -- sh -lc \""))
        assertTrue(!cmd.contains(" -u "))
        assertTrue(!cmd.contains('$') && !cmd.contains('`'))
    }

    @Test fun safeWslToken_acceptsOrdinaryDistroAndUserNames() {
        assertTrue(ClaudeTabsHelpers.isSafeWslToken("DistroA"))
        assertTrue(ClaudeTabsHelpers.isSafeWslToken("Distro-1.2_x"))
        assertTrue(ClaudeTabsHelpers.isSafeWslToken("userx"))
        assertTrue(ClaudeTabsHelpers.isSafeWslToken("Distro A")) // spaces survive the quoting
    }

    @Test fun safeWslToken_rejectsShellActiveAndPathBreakingNames() {
        // These land in a command line for an unknown host shell AND in a UNC path — skipped,
        // never escaped.
        for (bad in listOf("Distro\"A", "Distro\$A", "Distro`A", "Distro;A", "Distro&A",
                           "Distro|A", "Distro\\A", "Distro\nA", "", "   ")) {
            assertTrue("must reject: $bad", !ClaudeTabsHelpers.isSafeWslToken(bad))
        }
    }

    // ── mergeTabLists (tab enumeration across tool-window sources) ──

    private class Tab(val id: String) { override fun toString() = id }
    private fun ids(l: List<Tab>) = l.map { it.id }

    @Test fun mergeTabLists_primaryOrderFirstThenNestedThenFrontendExtras() {
        val a = Tab("a"); val b = Tab("b"); val n = Tab("nested"); val f = Tab("frontend-only")
        val out = ClaudeTabsHelpers.mergeTabLists(primary = listOf(b, a), nested = listOf(a, n, b), known = listOf(f, a))
        assertEquals(listOf("b", "a", "nested", "frontend-only"), ids(out))
    }

    @Test fun mergeTabLists_emptyManagerStillYieldsFrontendTabs() {
        // The stale-cache case: the ContentManager reports nothing, the frontend lists the real tabs.
        val t1 = Tab("t1"); val t2 = Tab("t2")
        val out = ClaudeTabsHelpers.mergeTabLists(primary = emptyList(), nested = emptyList(), known = listOf(t1, t2))
        assertEquals(listOf("t1", "t2"), ids(out))
    }

    @Test fun mergeTabLists_identityNotEquality() {
        // Contents are identity objects: two distinct instances are two tabs even if they compare equal.
        val x1 = "x"; val x2 = String(charArrayOf('x'))
        val out = ClaudeTabsHelpers.mergeTabLists(primary = listOf(x1), nested = listOf(x2), known = listOf(x1))
        assertEquals(2, out.size)
        assertTrue(out[0] === x1 && out[1] === x2)
    }

    @Test fun mergeTabLists_allEmpty() {
        assertTrue(ClaudeTabsHelpers.mergeTabLists<Any>(emptyList(), emptyList(), emptyList()).isEmpty())
    }

    // ── isHeadlessArgs — whole-token argv classification ─────────────

    @Test fun headlessArgs_unknownArgvStaysUnknown() {
        // A root that cannot tokenize argv gets no verdict — never a guess off the joined form.
        assertNull(ClaudeTabsHelpers.isHeadlessArgs(null))
    }

    @Test fun headlessArgs_wholeTokenFlagsAreHeadless() {
        assertEquals(true, ClaudeTabsHelpers.isHeadlessArgs(listOf("claude", "-p", "summarise the diff")))
        assertEquals(true, ClaudeTabsHelpers.isHeadlessArgs(listOf("claude", "--print")))
    }

    @Test fun headlessArgs_tokensThatMerelyContainTheFlagAreNotHeadless() {
        // Every one of these reads as headless under a substring scan of the command line.
        assertEquals(false, ClaudeTabsHelpers.isHeadlessArgs(listOf("claude", "--permission-mode", "plan")))
        assertEquals(false, ClaudeTabsHelpers.isHeadlessArgs(listOf("claude", "--add-dir", "/path/to/project-parts")))
        assertEquals(false, ClaudeTabsHelpers.isHeadlessArgs(listOf("claude", "explain what --print does")))
    }

    @Test fun headlessArgs_interactiveAndEmptyArgvAreNotHeadless() {
        assertEquals(false, ClaudeTabsHelpers.isHeadlessArgs(listOf("claude", "--resume", "sid-a")))
        assertEquals(false, ClaudeTabsHelpers.isHeadlessArgs(emptyList()))
    }

    // ── claimsExclusively — the most specific OPEN project wins ──────

    @Test fun claims_nestedOpenProjects_onlyTheDeeperOneClaims() {
        val open = listOf("/repo", "/repo/sub")
        assertTrue(ClaudeTabsHelpers.claimsExclusively("/repo/sub/x", "/repo/sub", open))
        assertFalse(
            "the outer window must not restore a session the inner window owns",
            ClaudeTabsHelpers.claimsExclusively("/repo/sub/x", "/repo", open),
        )
        // A cwd the deeper project does not claim still belongs to the outer one.
        assertTrue(ClaudeTabsHelpers.claimsExclusively("/repo/y", "/repo", open))
        assertFalse(ClaudeTabsHelpers.claimsExclusively("/repo/y", "/repo/sub", open))
    }

    @Test fun claims_siblingWorktreeGoesToTheParentUntilItsOwnProjectIsOpen() {
        // Unchanged from the plain cwd rule while the worktree has no window of its own.
        assertTrue(ClaudeTabsHelpers.claimsExclusively("/repo-feature", "/repo", listOf("/repo")))
        val bothOpen = listOf("/repo", "/repo-feature")
        assertFalse(ClaudeTabsHelpers.claimsExclusively("/repo-feature", "/repo", bothOpen))
        assertTrue(ClaudeTabsHelpers.claimsExclusively("/repo-feature", "/repo-feature", bothOpen))
        assertTrue(ClaudeTabsHelpers.claimsExclusively("/repo-feature/src", "/repo-feature", bothOpen))
    }

    @Test fun claims_aMoreSpecificBaseThatIsNotOpenNeverSuppresses() {
        // A closed sub-project cannot spawn the tab, so letting it veto leaves the session
        // claimed by nobody — which is why only OPEN bases arbitrate.
        assertTrue(ClaudeTabsHelpers.claimsExclusively("/repo/sub/x", "/repo", listOf("/repo")))
        assertTrue(ClaudeTabsHelpers.claimsExclusively("/repo-feature", "/repo", listOf("/repo", "/unrelated")))
    }

    @Test fun claims_cwdOutsideTheProjectIsNotClaimedAtAll() {
        assertFalse(ClaudeTabsHelpers.claimsExclusively("/other", "/repo", listOf("/repo")))
    }

    @Test fun claims_normalisesSeparatorsCaseAndTrailingSlash() {
        val open = listOf("D:\\Dev\\MyApp", "d:/dev/myapp/sub/")
        assertFalse(ClaudeTabsHelpers.claimsExclusively("D:/Dev/MyApp/Sub/x", "D:\\Dev\\MyApp\\", open))
        assertTrue(ClaudeTabsHelpers.claimsExclusively("D:/Dev/MyApp/Sub/x", "D:\\Dev\\MyApp\\Sub", open))
        // The project's own base spelled differently must never veto itself.
        assertTrue(ClaudeTabsHelpers.claimsExclusively("D:/Dev/MyApp/other", "d:\\dev\\myapp", open))
    }

    // ── restoreOrder — position, then start time, then sid ───────────

    private fun orderEntry(sid: String, ordinal: Int? = null, startedAt: Long? = null) =
        ActiveSessionsStore.Entry(
            sid = sid, cwd = "/path/to/project", pid = null, lastSeen = 0L, name = null,
            ordinal = ordinal, startedAt = startedAt,
        )

    private fun ordered(vararg entries: ActiveSessionsStore.Entry) =
        entries.sortedWith(ClaudeTabsHelpers.restoreOrder()).map { it.sid }

    @Test fun restoreOrder_savedPositionOutranksStartTime() {
        assertEquals(
            listOf("sid-a", "sid-b"),
            ordered(orderEntry("sid-b", ordinal = 1, startedAt = 900L), orderEntry("sid-a", ordinal = 0, startedAt = 5_000L)),
        )
    }

    @Test fun restoreOrder_positionlessEntriesComeLastOldestFirst() {
        // Entries admitted to the snapshot with no tab bound have no ordinal: reopen them in
        // creation order rather than the sid shuffle the user re-arranges by hand.
        assertEquals(
            listOf("sid-b", "sid-a", "sid-c"),
            ordered(
                orderEntry("sid-c", startedAt = 3_000L),
                orderEntry("sid-a", startedAt = 1_000L),
                orderEntry("sid-b", ordinal = 4),
            ),
        )
    }

    @Test fun restoreOrder_unknownStartTimeSortsAfterKnownOnesAtTheSamePosition() {
        assertEquals(
            listOf("sid-b", "sid-a"),
            ordered(orderEntry("sid-a", ordinal = 2), orderEntry("sid-b", ordinal = 2, startedAt = 10L)),
        )
    }

    @Test fun restoreOrder_bothKeysUnknownFallsBackToSidSoTheOrderIsTotal() {
        assertEquals(
            listOf("sid-a", "sid-b", "sid-c"),
            ordered(orderEntry("sid-c"), orderEntry("sid-b"), orderEntry("sid-a")),
        )
    }

    // ── sessionStillRunning — pid-reuse-safe restore-time liveness ───

    /** A [SessionRoot.Fake] set up like a real root: the `proc/<pid>` marker IS liveness and its
     *  mtime IS the process start time; `sessions/<pid>.json` is the root's own pid file. */
    private fun rootWith(pid: Long, startedAt: Long?, pidFileSid: String?): SessionRoot.Fake {
        val home = tmp.newFolder()
        if (startedAt != null) {
            val marker = File(File(home, "proc").apply { mkdirs() }, pid.toString())
            marker.writeText("")
            assertTrue("test setup: marker mtime must be settable", marker.setLastModified(startedAt))
        }
        if (pidFileSid != null) {
            val sessions = File(home, "sessions").apply { mkdirs() }
            File(sessions, "$pid.json").writeText("{\"sessionId\":\"$pidFileSid\",\"cwd\":\"/path/to/project\"}")
        }
        return SessionRoot.Fake("root-a", home)
    }

    private fun liveEntry(sid: String, pid: Long?, lastSeen: Long) =
        ActiveSessionsStore.Entry(sid = sid, cwd = "/path/to/project", pid = pid, lastSeen = lastSeen, name = null)

    @Test fun stillRunning_alivePidWithConsistentStartAndMatchingPidFile() {
        val seen = 1_700_000_000_000L
        assertTrue(ClaudeTabsHelpers.sessionStillRunning(
            rootWith(pid = 4242L, startedAt = seen - 60_000L, pidFileSid = "sid-a"),
            liveEntry("sid-a", 4242L, seen),
        ))
        // Within the clock slack a start stamped just after the last sighting is still this
        // session — the two stamps come from different clocks.
        assertTrue(ClaudeTabsHelpers.sessionStillRunning(
            rootWith(pid = 77L, startedAt = seen + 1_000L, pidFileSid = "sid-a"),
            liveEntry("sid-a", 77L, seen),
        ))
    }

    @Test fun stillRunning_processStartedAfterTheLastSightingIsARecycledPid() {
        // The hard reject: calling this "running" would suppress the restore and lose the tab.
        val seen = 1_700_000_000_000L
        assertFalse(ClaudeTabsHelpers.sessionStillRunning(
            rootWith(pid = 4242L, startedAt = seen + 60_000L, pidFileSid = "sid-a"),
            liveEntry("sid-a", 4242L, seen),
        ))
    }

    @Test fun stillRunning_pidFileNamingAnotherSessionRejects() {
        val seen = 1_700_000_000_000L
        assertFalse(ClaudeTabsHelpers.sessionStillRunning(
            rootWith(pid = 4242L, startedAt = seen - 60_000L, pidFileSid = "sid-b"),
            liveEntry("sid-a", 4242L, seen),
        ))
    }

    @Test fun stillRunning_missingPidFileIsUnknownAndStaysRunning() {
        // Unknown here must not manufacture a double resume — it is not the pid-reuse guard.
        val seen = 1_700_000_000_000L
        assertTrue(ClaudeTabsHelpers.sessionStillRunning(
            rootWith(pid = 4242L, startedAt = seen - 60_000L, pidFileSid = null),
            liveEntry("sid-a", 4242L, seen),
        ))
    }

    @Test fun stillRunning_deadProcessOrUnknownPidIsNotRunning() {
        val seen = 1_700_000_000_000L
        assertFalse(
            "no liveness marker — the process is gone",
            ClaudeTabsHelpers.sessionStillRunning(
                rootWith(pid = 4242L, startedAt = null, pidFileSid = "sid-a"),
                liveEntry("sid-a", 4242L, seen),
            ),
        )
        assertFalse(
            "an entry with no pid was never observed alive",
            ClaudeTabsHelpers.sessionStillRunning(
                rootWith(pid = 4242L, startedAt = seen - 60_000L, pidFileSid = "sid-a"),
                liveEntry("sid-a", null, seen),
            ),
        )
    }
}
