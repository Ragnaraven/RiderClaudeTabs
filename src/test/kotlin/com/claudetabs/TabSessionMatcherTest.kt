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
 * 2.1 naming-listener coverage:
 *  - [TabSessionMatcher.isHostedBy] ancestry matching (pure, injected parentOf)
 *  - [ClaudeTabsHelpers.prettifySessionName] slug → Title Case
 *  - [ClaudeTabsHelpers.isGenericTabName]
 *  - field-rename compat: pre-2.1 `metaName` files parse into `name`
 */
class TabSessionMatcherTest {

    @get:Rule val tmp = TemporaryFolder()

    // ── isHostedBy ───────────────────────────────────────────────────

    @Test fun directParent_matches() {
        // claude(100) → shell(50)
        val parents = mapOf(100L to 50L)
        assertTrue(TabSessionMatcher.isHostedBy(100L, 50L, { parents[it] }))
    }

    @Test fun wrappedChain_matches() {
        // claude(100) → node(90) → cmd(80) → shell(50)
        val parents = mapOf(100L to 90L, 90L to 80L, 80L to 50L)
        assertTrue(TabSessionMatcher.isHostedBy(100L, 50L, { parents[it] }))
    }

    @Test fun unrelatedShell_doesNotMatch() {
        val parents = mapOf(100L to 90L, 90L to 1L)
        assertFalse(TabSessionMatcher.isHostedBy(100L, 50L, { parents[it] }))
    }

    // ── matchTitlesToSessions — the /tab title handshake ─────────────

    @Test fun titleHandshake_uniquePair_claims() {
        val claims = TabSessionMatcher.matchTitlesToSessions(
            mapOf("sid-a" to "My Feature Work"),
            listOf("Local", "✳ My Feature Work", "✳ Other Tab"),
        )
        assertEquals(mapOf(1 to "sid-a"), claims)
    }

    @Test fun titleHandshake_stripsGlyphAndWhitespace() {
        // The poke writes "✳ <name>"; the plugin may already have re-owned it into the
        // padded animation format. Both strip to the same bare name.
        val claims = TabSessionMatcher.matchTitlesToSessions(
            mapOf("sid-a" to "My Feature Work"),
            listOf(" ✳  My Feature Work"),
        )
        assertEquals(mapOf(0 to "sid-a"), claims)
    }

    @Test fun titleHandshake_twoTabsSameTitle_ambiguous_noClaim() {
        val claims = TabSessionMatcher.matchTitlesToSessions(
            mapOf("sid-a" to "My Feature Work"),
            listOf("✳ My Feature Work", "✳ My Feature Work"),
        )
        assertTrue(claims.isEmpty())
    }

    @Test fun titleHandshake_twoSessionsSameName_ambiguous_noClaim() {
        val claims = TabSessionMatcher.matchTitlesToSessions(
            mapOf("sid-a" to "My Feature Work", "sid-b" to "My Feature Work"),
            listOf("✳ My Feature Work"),
        )
        assertTrue(claims.isEmpty())
    }

    @Test fun titleHandshake_nullOrBlankUserNames_neverClaim() {
        val claims = TabSessionMatcher.matchTitlesToSessions(
            mapOf("sid-a" to null, "sid-b" to "  "),
            listOf("Local", "✳ Claude"),
        )
        assertTrue(claims.isEmpty())
    }

    @Test fun titleHandshake_multipleDistinctPairs_allClaim() {
        val claims = TabSessionMatcher.matchTitlesToSessions(
            mapOf("sid-a" to "Tab A", "sid-b" to "Tab B"),
            listOf("✳ Tab B", "Local", "✳ Tab A"),
        )
        assertEquals(mapOf(0 to "sid-b", 2 to "sid-a"), claims)
    }

    @Test fun titleHandshake_plainTitleWithoutGlyph_alsoMatches() {
        // A rename applied without the glyph (or a terminal that dropped it) still matches.
        val claims = TabSessionMatcher.matchTitlesToSessions(
            mapOf("sid-a" to "My Feature Work"),
            listOf("My Feature Work"),
        )
        assertEquals(mapOf(0 to "sid-a"), claims)
    }

    // ── resolveUniqueByDisplayName — immediate title-based close resolution ──

    @Test fun resolveByDisplayName_uniqueMatch_returnsSid() {
        val candidates = mapOf("sid-a" to "My Feature Work", "sid-b" to "Other Tab")
        assertEquals("sid-a", TabSessionMatcher.resolveUniqueByDisplayName(candidates, "My Feature Work"))
    }

    @Test fun resolveByDisplayName_trimsBothSides() {
        val candidates = mapOf("sid-a" to "  My Feature Work ")
        assertEquals("sid-a", TabSessionMatcher.resolveUniqueByDisplayName(candidates, "My Feature Work"))
    }

    @Test fun resolveByDisplayName_ambiguousDuplicateNames_returnsNull() {
        // Two sessions share the name → can't safely pick which tab closed.
        val candidates = mapOf("sid-a" to "Dup", "sid-b" to "Dup")
        assertNull(TabSessionMatcher.resolveUniqueByDisplayName(candidates, "Dup"))
    }

    @Test fun resolveByDisplayName_noMatch_returnsNull() {
        assertNull(TabSessionMatcher.resolveUniqueByDisplayName(mapOf("sid-a" to "Something"), "Nothing"))
    }

    @Test fun resolveByDisplayName_blankTitleOrNames_returnsNull() {
        assertNull(TabSessionMatcher.resolveUniqueByDisplayName(mapOf("sid-a" to "Name"), "   "))
        assertNull(TabSessionMatcher.resolveUniqueByDisplayName(mapOf("sid-a" to null, "sid-b" to ""), "Name"))
    }

    @Test fun chainTooDeep_doesNotMatch() {
        // Shell is 10 hops up but maxHops is 8.
        val parents = (0L until 20L).associate { (100L + it) to (100L + it + 1) }
        assertFalse(TabSessionMatcher.isHostedBy(100L, 110L, { parents[it] }, maxHops = 8))
        assertTrue(TabSessionMatcher.isHostedBy(100L, 108L, { parents[it] }, maxHops = 8))
    }

    @Test fun claudePidItselfIsNotItsOwnHost() {
        // Walking starts at the PARENT — claudePid == shellPid must not match.
        val parents = mapOf(100L to 50L)
        assertFalse(TabSessionMatcher.isHostedBy(100L, 100L, { parents[it] }))
    }

    // ── normalizeCwd (cwd-based tab claiming) ────────────────────────

    @Test fun normalizeCwd_backslashesForwardSlashesTrailingCaseAllNormalised() {
        val a = TabSessionMatcher.normalizeCwd("D:\\Dev\\OrbitApp\\")
        val b = TabSessionMatcher.normalizeCwd("d:/dev/orbitapp")
        assertTrue("equivalent paths must normalise equal", a == b)
        assertFalse("distinct paths stay distinct", a == TabSessionMatcher.normalizeCwd("D:/Dev/OrbitApp-mobile"))
    }

    // ── prettifySessionName ──────────────────────────────────────────

    @Test fun prettify_basicSlug() {
        assertEquals("Fix Auth Token Rotation", ClaudeTabsHelpers.prettifySessionName("fix-auth-token-rotation"))
    }

    @Test fun prettify_underscores() {
        assertEquals("Drop Manual Tab Naming", ClaudeTabsHelpers.prettifySessionName("drop_manual_tab_naming"))
    }

    @Test fun prettify_singleWord() {
        assertEquals("Refactor", ClaudeTabsHelpers.prettifySessionName("refactor"))
    }

    @Test fun prettify_nullAndBlank() {
        assertNull(ClaudeTabsHelpers.prettifySessionName(null))
        assertNull(ClaudeTabsHelpers.prettifySessionName(""))
        assertNull(ClaudeTabsHelpers.prettifySessionName("   "))
        assertNull(ClaudeTabsHelpers.prettifySessionName("---"))
    }

    // ── isGenericTabName ─────────────────────────────────────────────

    @Test fun generic_localVariants() {
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Local"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Local (2)"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("Local (25)"))
        assertTrue(ClaudeTabsHelpers.isGenericTabName("pwsh"))
    }

    @Test fun generic_realNamesAreNot() {
        assertFalse(ClaudeTabsHelpers.isGenericTabName("Fix Auth Rotation"))
        assertFalse(ClaudeTabsHelpers.isGenericTabName("My Tab"))
    }

    // ── metaName → name compat ───────────────────────────────────────

    @Test fun activeSessionsStore_readsPre21MetaNameKey() {
        val dir = tmp.newFolder()
        val s = ActiveSessionsStore(dir)
        // A file written by 2.0.x with the old key.
        File(dir, "sid-old.json").writeText(
            """{"sid":"sid-old","cwd":"D:\\Dev\\X","pid":null,"lastSeen":5,"metaName":"Carried Over"}"""
        )
        val r = s.read("sid-old")
        assertEquals("Carried Over", r!!.name)
    }

    @Test fun sessionBacklog_readsPre21MetaNameKey() {
        val f = File(tmp.newFolder(), "session-backlog.json")
        f.writeText(
            """[{"sid":"sid-old","cwd":"D:\\Dev\\X","metaName":"Old Label","evictedAt":7}]"""
        )
        val b = SessionBacklog(f)
        assertEquals("Old Label", b.list()[0].name)
    }

    @Test fun activeSessionsStore_writeAfterCompatRead_usesNewKey() {
        val dir = tmp.newFolder()
        val s = ActiveSessionsStore(dir)
        File(dir, "sid-old.json").writeText(
            """{"sid":"sid-old","cwd":"/x","pid":null,"lastSeen":5,"metaName":"Keep Me"}"""
        )
        // An update with name=null must preserve the compat-read name and write the new key.
        s.writeOrUpdate("sid-old", "/x", pid = 9L, lastSeen = 10L, name = null)
        val text = File(dir, "sid-old.json").readText()
        assertTrue("new key written", text.contains("\"name\":\"Keep Me\""))
        assertFalse("old key gone after rewrite", text.contains("metaName"))
    }
}
