package com.claudetabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the 2.0 cwd filter — sibling worktrees (`<base>-<suffix>`) are
 * accepted as belonging to `<base>`. Driven by the original bug:
 *
 *  - User is in MyApp's Rider window
 *  - Runs `cd ../MyApp-feature && claude --resume <sid>`
 *  - 1.x rejected this session because cwd wasn't a subpath of MyApp's basePath
 *  - 2.0 accepts it via the sibling-worktree clause
 */
class WorktreeSiblingCwdTest {

    @Test fun exactBaseMatches() {
        assertTrue(ClaudeTabsHelpers.isCwdUnderProject("D:\\Dev\\MyApp", "D:\\Dev\\MyApp"))
    }

    @Test fun subpathMatches() {
        assertTrue(ClaudeTabsHelpers.isCwdUnderProject("D:\\Dev\\MyApp\\src\\foo", "D:\\Dev\\MyApp"))
    }

    @Test fun siblingWorktreeWithDashSuffixMatches() {
        // The original bug-shape: D:\Dev\MyApp-feature is a git worktree of MyApp,
        // not a subpath. 1.x rejected; 2.0 accepts.
        assertTrue(
            "sibling worktree must be accepted",
            ClaudeTabsHelpers.isCwdUnderProject("D:\\Dev\\MyApp-feature", "D:\\Dev\\MyApp")
        )
    }

    @Test fun siblingWorktreeSubpathMatches() {
        assertTrue(
            ClaudeTabsHelpers.isCwdUnderProject("D:\\Dev\\MyApp-feature\\packages\\api", "D:\\Dev\\MyApp")
        )
    }

    @Test fun unrelatedSiblingProject_doesNotMatch() {
        // No `-` boundary between MyApp and OtherApp — the two are clearly unrelated.
        assertFalse(ClaudeTabsHelpers.isCwdUnderProject("D:\\Dev\\OtherApp", "D:\\Dev\\MyApp"))
    }

    @Test fun forwardSlashNormalisationWorks() {
        // Mixed separators must normalise. Unix-style cwd, Windows-style basePath.
        assertTrue(ClaudeTabsHelpers.isCwdUnderProject("D:/Dev/MyApp-foo", "D:\\Dev\\MyApp"))
    }

    @Test fun trailingSlashStripped() {
        assertTrue(ClaudeTabsHelpers.isCwdUnderProject("D:\\Dev\\MyApp\\", "D:\\Dev\\MyApp"))
    }

    @Test fun nullCwd_rejected() {
        assertFalse(ClaudeTabsHelpers.isCwdUnderProject(null, "D:\\Dev\\MyApp"))
    }

    @Test fun blankCwd_rejected() {
        assertFalse(ClaudeTabsHelpers.isCwdUnderProject("", "D:\\Dev\\MyApp"))
        assertFalse(ClaudeTabsHelpers.isCwdUnderProject("   ", "D:\\Dev\\MyApp"))
    }

    @Test fun nullProjectBase_acceptsAnything() {
        // Defensive default for detached/default Rider projects.
        assertTrue(ClaudeTabsHelpers.isCwdUnderProject("D:\\anywhere", null))
    }
}
