package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TitleModel] — the pure title/animation logic behind [TitleController].
 * The contract: title is always `<glyph> <DisplayName>`; glyph animates while busy;
 * user renames are adopted (persisted) instead of fought; tabs stuck on generic
 * defaults ("Local") self-heal.
 */
class TitleModelTest {

    // ── compose / glyph ───────────────────────────────────────────────

    @Test fun compose_idle_usesStaticGlyphRegardlessOfFrame() {
        assertEquals("✳ Fix Auth", TitleModel.compose("Fix Auth", busy = false, frameIndex = 0))
        assertEquals("✳ Fix Auth", TitleModel.compose("Fix Auth", busy = false, frameIndex = 7))
    }

    @Test fun compose_busy_cyclesFrames() {
        val titles = (0..5).map { TitleModel.compose("X", busy = true, frameIndex = it) }
        assertEquals(listOf("✳ X", "✶ X", "✷ X", "✸ X", "✳ X", "✶ X"), titles)
    }

    @Test fun glyph_negativeFrameIndex_safe() {
        // floorMod, not %, so a wrapped counter can't throw.
        assertEquals("✸", TitleModel.glyph(busy = true, frameIndex = -1))
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

    @Test fun isOurFormat_allFramesRecognised() {
        for (f in TitleModel.FRAMES) assertTrue(TitleModel.isOurFormat("$f Name"))
    }

    @Test fun isOurFormat_foreignDecorationsRejected() {
        assertFalse(TitleModel.isOurFormat("✦ Name"))
        assertFalse(TitleModel.isOurFormat("· Name"))
        assertFalse(TitleModel.isOurFormat("Name"))
        assertFalse(TitleModel.isOurFormat(null))
        assertFalse(TitleModel.isOurFormat("  "))
    }

    @Test fun stripGlyph_edges() {
        assertEquals("Name", TitleModel.stripGlyph("✳ Name"))
        assertEquals("Name", TitleModel.stripGlyph("  ✷  Name  "))
        assertEquals("Plain", TitleModel.stripGlyph("Plain"))
        assertEquals("✦ Foreign", TitleModel.stripGlyph("✦ Foreign"))
    }

    // ── tick: self-heal ───────────────────────────────────────────────

    @Test fun tick_blankObserved_appliesDesired_noAdoption() {
        val d = TitleModel.tick(null, null, null, "Fix Auth", null, busy = false, frameIndex = 0)
        assertEquals("✳ Fix Auth", d.apply)
        assertNull(d.adoptName)
    }

    @Test fun tick_genericLocal_selfHeals_noAdoption() {
        for (generic in listOf("Local", "Local (2)", "bash", "pwsh")) {
            val d = TitleModel.tick(generic, null, null, null, "Cached", busy = false, frameIndex = 0)
            assertEquals("✳ Cached", d.apply)
            assertNull("'$generic' must not be adopted as a user name", d.adoptName)
        }
    }

    // ── tick: write avoidance + animation ─────────────────────────────

    @Test fun tick_idleUnchanged_noWrite() {
        val d = TitleModel.tick("✳ Fix Auth", "✳ Fix Auth", null, "Fix Auth", null, busy = false, frameIndex = 3)
        assertNull(d.apply)
        assertNull(d.adoptName)
    }

    @Test fun tick_busy_frameAdvanceWritesEachTick() {
        val d1 = TitleModel.tick("✳ X", "✳ X", null, "X", null, busy = true, frameIndex = 1)
        assertEquals("✶ X", d1.apply)
        val d2 = TitleModel.tick("✶ X", "✶ X", null, "X", null, busy = true, frameIndex = 2)
        assertEquals("✷ X", d2.apply)
        assertNotEquals(d1.apply, d2.apply)
    }

    @Test fun tick_busyToIdle_returnsToStaticGlyph() {
        val d = TitleModel.tick("✷ X", "✷ X", null, "X", null, busy = false, frameIndex = 9)
        assertEquals("✳ X", d.apply)
    }

    // ── tick: rename adoption ─────────────────────────────────────────

    @Test fun tick_plainRename_adoptedAndWrapped() {
        val d = TitleModel.tick("My Refactor", "✳ Old Topic", null, "Old Topic", null, busy = false, frameIndex = 0)
        assertEquals("My Refactor", d.adoptName)
        assertEquals("✳ My Refactor", d.apply)
    }

    @Test fun tick_renameKeepingGlyphPrefix_adoptsStrippedText() {
        // Rename dialog pre-fills "✳ Old Topic"; the user edits the text but keeps the glyph.
        // The tab already shows exactly what we'd compose → no write, just the adoption.
        val d = TitleModel.tick("✳ My Refactor", "✳ Old Topic", null, "Old Topic", null, busy = false, frameIndex = 0)
        assertEquals("My Refactor", d.adoptName)
        assertNull(d.apply)
    }

    @Test fun tick_ourFormatWithNullLastApplied_isReowned_notARename() {
        // Stale "✳ ..." title surviving an IDE restart: lastApplied is unknown → re-own.
        val d = TitleModel.tick("✳ Old Topic", null, null, "New Topic", null, busy = false, frameIndex = 0)
        assertNull(d.adoptName)
        assertEquals("✳ New Topic", d.apply)
    }

    @Test fun tick_adoptionIdempotent_noRepersistLoop() {
        // userName already persisted and displayed — observed == desired → nothing to do.
        val d = TitleModel.tick("✳ My Refactor", "✳ My Refactor", "My Refactor", "Topic", null, busy = false, frameIndex = 0)
        assertNull(d.adoptName)
        assertNull(d.apply)
    }

    @Test fun tick_renameMatchingExistingUserName_notReadopted() {
        // External title equals current userName (e.g. another window applied it first).
        val d = TitleModel.tick("My Refactor", "✳ Something Else", "My Refactor", null, null, busy = false, frameIndex = 0)
        assertNull(d.adoptName)
        assertEquals("✳ My Refactor", d.apply)
    }

    @Test fun tick_userNameOutranksLiveTopic() {
        val d = TitleModel.tick("✳ Stale", "✳ Stale", "Chosen", "Auto Topic", "Cached", busy = false, frameIndex = 0)
        assertEquals("✳ Chosen", d.apply)
        assertNull(d.adoptName)
    }

    @Test fun tick_glyphOnlyRename_fallsBackToResolveChain() {
        // User reduced the title to just a glyph — nothing adoptable.
        val d = TitleModel.tick("✳", "✳ Old", null, "Topic", null, busy = false, frameIndex = 0)
        assertNull(d.adoptName)
        assertEquals("✳ Topic", d.apply)
    }
}
