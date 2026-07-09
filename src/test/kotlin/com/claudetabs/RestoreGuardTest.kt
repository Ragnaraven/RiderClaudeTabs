package com.claudetabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RestoreGuard.blocksRetrySpawn] — the poll's resurrection guard.
 *
 * Regression focus: "create a session, do work, close it → it comes back ~1 min later." The fix is
 * that retry-spawn only revives a restore SEED that never came alive; any session that was alive or
 * held a tab this session is a close/crash, not a failed seed. [everSeenAlive] specifically covers a
 * user-opened tab the plugin never managed to claim a widget for.
 *
 * (The old `planRestore` flood guard and its tests were removed — age/count-based retirement of
 * open tabs violates the restore contract; see project memory `tab-restore-contract`.)
 */
class RestoreGuardTest {

    private val sid = "sid-a"
    private val empty = emptySet<String>()

    private fun blocks(
        userClosed: Set<String> = empty,
        everHadWidget: Set<String> = empty,
        everSeenAlive: Set<String> = empty,
        pendingClose: Set<String> = empty,
        spawnedWidgets: Set<String> = empty,
    ) = RestoreGuard.blocksRetrySpawn(
        sid, userClosed, everHadWidget, everSeenAlive, pendingClose, spawnedWidgets,
    )

    @Test fun genuineFailedSeed_neverAliveNeverHadWidget_isEligible() {
        // The only case retry-spawn is FOR: a pid=null seed that never came alive or held a tab.
        assertFalse(blocks())
    }

    @Test fun userClosed_blocks() {
        assertTrue(blocks(userClosed = setOf(sid)))
    }

    @Test fun everHadWidget_blocks_claimedTabClosed() {
        assertTrue(blocks(everHadWidget = setOf(sid)))
    }

    @Test fun everSeenAlive_blocks_unclaimedUserTabClosed() {
        // The actual reported bug: a user-opened tab the plugin saw alive but never claimed a widget
        // for. Not in everHadWidget, but everSeenAlive must still block its resurrection.
        assertTrue(blocks(everSeenAlive = setOf(sid)))
    }

    @Test fun pendingClose_blocks_dontRaceSignalTwo() {
        assertTrue(blocks(pendingClose = setOf(sid)))
    }

    @Test fun spawnedWidgets_blocks_noDoubleSpawn() {
        assertTrue(blocks(spawnedWidgets = setOf(sid)))
    }

    @Test fun otherSidsInSets_doNotBlockThisSid() {
        // Guard is per-sid: another session being closed/alive must not block this seed's retry.
        assertFalse(
            blocks(
                userClosed = setOf("other-1"),
                everHadWidget = setOf("other-2"),
                everSeenAlive = setOf("other-3"),
                pendingClose = setOf("other-4"),
                spawnedWidgets = setOf("other-5"),
            )
        )
    }
}
