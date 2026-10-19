package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [SessionRoot]'s observable surface — the session-file listing every root scan goes
 * through, and the marker files the E2E harness uses to drive the scan's failure arms.
 *
 * [SessionRoot.Fake] stands in for a foreign root: no WSL share, no process table, plain files.
 */
class SessionRootsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fakeRoot(): SessionRoot.Fake = SessionRoot.Fake("root-a", tmp.newFolder())

    // ── listSessionFiles: unobservable is not empty ──────────────────

    @Test fun listSessionFiles_missingDirIsUnobservable() {
        // Null, never an empty array: a caller that reads it as "zero sessions" concludes every
        // session in the root died the moment its directory became unreachable.
        assertNull(fakeRoot().listSessionFiles())
    }

    @Test fun listSessionFiles_existingButEmptyDirIsObservablyEmpty() {
        val root = fakeRoot()
        root.sessionsDir.mkdirs()
        val listed = root.listSessionFiles()
        assertNotNull("an existing directory IS observable", listed)
        assertEquals(0, listed!!.size)
    }

    @Test fun listSessionFiles_returnsOnlyJsonFiles() {
        val root = fakeRoot()
        root.sessionsDir.mkdirs()
        File(root.sessionsDir, "4242.json").writeText("{}")
        File(root.sessionsDir, "notes.txt").writeText("x")
        File(root.sessionsDir, "nested.json").mkdirs() // a DIRECTORY that ends in .json
        assertEquals(listOf("4242.json"), root.listSessionFiles()!!.map { it.name })
    }

    // ── Fake-root scan-breaker arms (E2E only) ───────────────────────

    @Test fun fakeRoot_scanThrowsMarkerDrivesTheThrowArm() {
        val root = fakeRoot()
        root.sessionsDir.mkdirs()
        File(root.claudeHome, "scan-throws").writeText("")
        try {
            root.listSessionFiles()
            fail("scan-throws must make the listing throw, so the harness can bench a root")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test fun fakeRoot_scanHangMarkerDelaysTheListing() {
        val root = fakeRoot()
        root.sessionsDir.mkdirs()
        File(root.claudeHome, "scan-hang-ms").writeText("120")
        val startedNs = System.nanoTime()
        assertNotNull("the listing still succeeds after the stall", root.listSessionFiles())
        val tookMs = (System.nanoTime() - startedNs) / 1_000_000
        assertTrue("listing should have stalled ~120ms, took ${tookMs}ms", tookMs >= 100)
    }

    @Test fun fakeRoot_unusableHangMarkerIsIgnored() {
        val root = fakeRoot()
        root.sessionsDir.mkdirs()
        File(root.claudeHome, "scan-hang-ms").writeText("not-a-number")
        assertNotNull(root.listSessionFiles())
    }

    @Test fun fakeRoot_noMarkersBehavesLikeAnyOtherRoot() {
        val root = fakeRoot()
        root.sessionsDir.mkdirs()
        File(root.sessionsDir, "7.json").writeText("{}")
        assertEquals(1, root.listSessionFiles()!!.size)
    }
}
