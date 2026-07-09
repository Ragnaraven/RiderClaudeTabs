package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TitleModel] — the pure title/animation logic behind [TitleController].
 * Contract: idle = static STAR at the left (`✳ Name`); busy = a DOT that bounces
 * left → centre → right → centre → left. Every BUSY frame is the same characters reordered, so
 * the tab never resizes mid-animation; idle swaps the dot for the star (intentional). User
 * renames are adopted; "Local" self-heals.
 */
class TitleModelTest {

    private val NB = " " // the non-breaking-space filler used by the field

    private fun allStates(name: String): List<String> =
        buildList {
            add(TitleModel.compose(name, busy = false, frameIndex = 0))      // idle
            add(TitleModel.compose(name, busy = false, frameIndex = 99))     // idle, other frame
            for (i in -1..TitleModel.BUSY_WIDTH + 1) add(TitleModel.compose(name, busy = true, frameIndex = i))
        }

    // ── the core guarantee: no resize ─────────────────────────────────

    @Test fun allStates_sameLength_noResize() {
        // idle + every busy frame are all (one glyph + two fillers + name) → identical length.
        val states = allStates("Fix Auth")
        assertEquals("all states must be the same length", 1, states.map { it.length }.toSet().size)
    }

    @Test fun busyFrames_identicalCharacterMultiset() {
        // While THINKING the dot only moves — every busy frame is the same characters reordered,
        // so the tab can't resize mid-animation.
        val busy = (-1..TitleModel.BUSY_WIDTH + 1).map { TitleModel.compose("Fix Auth", busy = true, frameIndex = it) }
        assertEquals(1, busy.map { it.toSortedSet() }.toSet().size)
    }

    @Test fun idle_isStar_busy_isDot() {
        // Idle uses the star; busy uses the travelling dot. The glyph swap on idle↔busy is intended.
        val idle = TitleModel.compose("X", busy = false, frameIndex = 0)
        assertTrue(idle.contains(TitleModel.STAR)); assertFalse(idle.contains(TitleModel.DOT))
        val busy0 = TitleModel.compose("X", busy = true, frameIndex = 0)
        assertTrue(busy0.contains(TitleModel.DOT)); assertFalse(busy0.contains(TitleModel.STAR))
    }

    @Test fun bouncePos_pingPongSequence_LCRCL() {
        // 0,1,2,1,0,1,2,1,0 — L → C → R → C → L → repeat (no R→L jump).
        assertEquals(listOf(0, 1, 2, 1, 0, 1, 2, 1, 0), (0..8).map { TitleModel.bouncePos(it) })
    }

    @Test fun compose_idle_starCentred() {
        // star centred in the field (1 leading filler) + 1 constant separator before the name
        assertEquals("$NB✳$NB${NB}Name", TitleModel.compose("Name", busy = false, frameIndex = 0))
    }

    @Test fun compose_busy_dotBounces() {
        val l = "·$NB$NB${NB}Name"   // left  + separator
        val c = "$NB·$NB${NB}Name"   // centre + separator
        val r = "$NB$NB·${NB}Name"   // right — note the space after the dot
        assertEquals(l, TitleModel.compose("Name", busy = true, frameIndex = 0))
        assertEquals(c, TitleModel.compose("Name", busy = true, frameIndex = 1))
        assertEquals(r, TitleModel.compose("Name", busy = true, frameIndex = 2))
        assertEquals(c, TitleModel.compose("Name", busy = true, frameIndex = 3)) // bounce back
        assertEquals(l, TitleModel.compose("Name", busy = true, frameIndex = 4)) // full cycle
        assertEquals(c, TitleModel.compose("Name", busy = true, frameIndex = -1)) // floorMod safe
    }

    // ── cleanCapturedName (name pulled off the live terminal title) ────

    @Test fun cleanCapturedName_realName_kept() {
        assertEquals("Fix Auth", TitleModel.cleanCapturedName("Fix Auth"))
        // our own glyph-wrapped title strips back to the bare name
        assertEquals("Fix Auth", TitleModel.cleanCapturedName(TitleModel.compose("Fix Auth", busy = false, frameIndex = 0)))
        // spaced names + casing are preserved (only slugs get title-cased)
        assertEquals("Build data importer for spreadsheets",
            TitleModel.cleanCapturedName("Build data importer for spreadsheets"))
    }

    @Test fun cleanCapturedName_rejectsShellAndPathTitles() {
        // git-bash / msys / cygwin titles and bare filesystem paths must never become a name.
        assertNull(TitleModel.cleanCapturedName("MINGW64:/c/dev/MyApp"))
        assertNull(TitleModel.cleanCapturedName("MINGW32:/c/foo"))
        assertNull(TitleModel.cleanCapturedName("MSYS:/x"))
        assertNull(TitleModel.cleanCapturedName("C:\\dev\\MyApp"))
        assertNull(TitleModel.cleanCapturedName("/c/dev/MyApp"))
        // ...but a real topic that merely contains a slash is kept.
        assertEquals("Make a/b mode a toggle button with tooltips",
            TitleModel.cleanCapturedName("Make a/b mode a toggle button with tooltips"))
    }

    @Test fun cleanCapturedName_stripsLeadingAnimationDecoration() {
        // A frame of Claude's own animated title must not leak a leading dot/star/braille glyph
        // into the captured name (which would render as a double indicator once we add ours).
        assertEquals("Fix Data Importer", TitleModel.cleanCapturedName("· fix-data-importer"))
        assertEquals("Fix data importer", TitleModel.cleanCapturedName("·· Fix data importer"))
        assertEquals("Fix data importer", TitleModel.cleanCapturedName("✳ Fix data importer"))
        assertEquals("Fix Auth Token Rotation", TitleModel.cleanCapturedName("⠂ fix-auth-token-rotation"))
    }

    @Test fun cleanCapturedName_genericOrBlank_null() {
        assertNull(TitleModel.cleanCapturedName(null))
        assertNull(TitleModel.cleanCapturedName(""))
        assertNull(TitleModel.cleanCapturedName("   "))
        assertNull(TitleModel.cleanCapturedName("Claude"))
        assertNull(TitleModel.cleanCapturedName("Claude Code"))
        assertNull(TitleModel.cleanCapturedName("Local"))
        assertNull(TitleModel.cleanCapturedName("Local (2)"))
        assertNull(TitleModel.cleanCapturedName("pwsh"))
    }

    // ── resolveDisplayName ────────────────────────────────────────────

    @Test fun resolveDisplayName_chain() {
        assertEquals("User", TitleModel.resolveDisplayName("User", "Live", "Cached"))
        assertEquals("Live", TitleModel.resolveDisplayName(null, "Live", "Cached"))
        assertEquals("Cached", TitleModel.resolveDisplayName(null, null, "Cached"))
        assertEquals("Claude", TitleModel.resolveDisplayName(null, null, null))
        assertEquals("Claude", TitleModel.resolveDisplayName("  ", "", null))
    }

    // ── isOurFormat / stripGlyph ──────────────────────────────────────

    @Test fun isOurFormat_recognisesEveryFrame_andLegacy() {
        for (s in allStates("Name")) assertTrue("'$s' should be ours", TitleModel.isOurFormat(s))
        for (legacy in listOf("✳ Name", "·  Name", "✶ Name", "✷ Name", "✸ Name")) {
            assertTrue("legacy '$legacy' should be ours", TitleModel.isOurFormat(legacy))
        }
    }

    @Test fun isOurFormat_foreignDecorationsRejected() {
        assertFalse(TitleModel.isOurFormat("✦ Name"))
        assertFalse(TitleModel.isOurFormat("Name"))
        assertFalse(TitleModel.isOurFormat(null))
        assertFalse(TitleModel.isOurFormat("  "))
    }

    @Test fun stripGlyph_everyFrame_yieldsBareName() {
        for (s in allStates("Fix Auth")) assertEquals("Fix Auth", TitleModel.stripGlyph(s))
        assertEquals("Plain", TitleModel.stripGlyph("Plain"))
        assertEquals("✦ Foreign", TitleModel.stripGlyph("✦ Foreign"))
    }

    // ── tick: self-heal ───────────────────────────────────────────────

    @Test fun tick_blankObserved_appliesIdle_noAdoption() {
        val d = TitleModel.tick(null, null, null, "Fix Auth", null, busy = false, frameIndex = 0)
        assertEquals(TitleModel.compose("Fix Auth", busy = false, frameIndex = 0), d.apply)
        assertNull(d.adoptName)
    }

    @Test fun tick_genericLocal_selfHeals_noAdoption() {
        for (generic in listOf("Local", "Local (2)", "bash", "pwsh")) {
            val d = TitleModel.tick(generic, null, null, null, "Cached", busy = false, frameIndex = 0)
            assertEquals(TitleModel.compose("Cached", busy = false, frameIndex = 0), d.apply)
            assertNull("'$generic' must not be adopted as a user name", d.adoptName)
        }
    }

    // ── tick: write avoidance + animation ─────────────────────────────

    @Test fun tick_idleUnchanged_noWrite() {
        val idle = TitleModel.compose("Fix Auth", busy = false, frameIndex = 0)
        val d = TitleModel.tick(idle, idle, null, "Fix Auth", null, busy = false, frameIndex = 3)
        assertNull(d.apply)
        assertNull(d.adoptName)
    }

    @Test fun tick_busy_starAdvanceWritesEachTick_noFalseRename() {
        val f0 = TitleModel.compose("X", busy = true, frameIndex = 0)
        val f1 = TitleModel.compose("X", busy = true, frameIndex = 1)
        val d = TitleModel.tick(f0, f0, null, "X", null, busy = true, frameIndex = 1)
        assertEquals(f1, d.apply)
        assertNull("a star-position change must not be read as a rename", d.adoptName)
    }

    @Test fun tick_busyToIdle_returnsToCentredStar() {
        val busyFrame = TitleModel.compose("X", busy = true, frameIndex = 2)
        val d = TitleModel.tick(busyFrame, busyFrame, null, "X", null, busy = false, frameIndex = 9)
        assertEquals(TitleModel.compose("X", busy = false, frameIndex = 0), d.apply)
        assertNull(d.adoptName)
    }

    // ── tick: rename adoption ─────────────────────────────────────────

    @Test fun tick_plainRename_adoptedAndWrapped() {
        val old = TitleModel.compose("Old Topic", busy = false, frameIndex = 0)
        val d = TitleModel.tick("My Refactor", old, null, "Old Topic", null, busy = false, frameIndex = 0)
        assertEquals("My Refactor", d.adoptName)
        assertEquals(TitleModel.compose("My Refactor", busy = false, frameIndex = 0), d.apply)
    }

    @Test fun tick_renameKeepingGlyphPrefix_adoptsStrippedText() {
        val old = TitleModel.compose("Old Topic", busy = false, frameIndex = 0)
        val edited = TitleModel.compose("My Refactor", busy = false, frameIndex = 0)
        val d = TitleModel.tick(edited, old, null, "Old Topic", null, busy = false, frameIndex = 0)
        assertEquals("My Refactor", d.adoptName)
        assertNull(d.apply)
    }

    @Test fun tick_ourFormatWithNullLastApplied_isReowned_notARename() {
        val stale = TitleModel.compose("Old Topic", busy = false, frameIndex = 0)
        val d = TitleModel.tick(stale, null, null, "New Topic", null, busy = false, frameIndex = 0)
        assertNull(d.adoptName)
        assertEquals(TitleModel.compose("New Topic", busy = false, frameIndex = 0), d.apply)
    }

    @Test fun tick_adoptionIdempotent_noRepersistLoop() {
        val cur = TitleModel.compose("My Refactor", busy = false, frameIndex = 0)
        val d = TitleModel.tick(cur, cur, "My Refactor", "Topic", null, busy = false, frameIndex = 0)
        assertNull(d.adoptName)
        assertNull(d.apply)
    }

    @Test fun tick_renameMatchingExistingUserName_notReadopted() {
        val other = TitleModel.compose("Something Else", busy = false, frameIndex = 0)
        val d = TitleModel.tick("My Refactor", other, "My Refactor", null, null, busy = false, frameIndex = 0)
        assertNull(d.adoptName)
        assertEquals(TitleModel.compose("My Refactor", busy = false, frameIndex = 0), d.apply)
    }

    @Test fun tick_userNameOutranksLiveTopic() {
        val stale = TitleModel.compose("Stale", busy = false, frameIndex = 0)
        val d = TitleModel.tick(stale, stale, "Chosen", "Auto Topic", "Cached", busy = false, frameIndex = 0)
        assertEquals(TitleModel.compose("Chosen", busy = false, frameIndex = 0), d.apply)
        assertNull(d.adoptName)
    }
}
