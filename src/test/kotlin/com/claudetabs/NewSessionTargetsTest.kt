package com.claudetabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NewSessionTargetsTest {

    private fun wsl(distro: String, user: String) = SessionRoot.Wsl(distro, user, File("/path/to/$distro/$user/.claude"))

    @Test fun oneInstallation_isThePlainButton() {
        val targets = NewSessionTargets.from(listOf(SessionRoot.Windows))
        assertEquals(1, targets.size)
        assertEquals(SessionRoot.Windows.ID, targets[0].rootId)
        assertEquals(NewSessionTargets.BASE_TEXT, targets[0].text)
        assertFalse("a single root has no choice to show", targets[0].badged)
    }

    @Test fun noRootsAtAll_stillOffersTheDefault() {
        val targets = NewSessionTargets.from(emptyList())
        assertEquals(listOf(SessionRoot.Windows.ID), targets.map { it.rootId })
        assertFalse(targets[0].badged)
    }

    @Test fun twoInstallations_oneBadgedButtonEach_windowsFirst() {
        val distro = wsl("DistroA", "dev")
        val targets = NewSessionTargets.from(listOf(distro, SessionRoot.Windows))
        assertEquals(listOf(SessionRoot.Windows.ID, distro.id), targets.map { it.rootId })
        assertTrue("every button carries its platform when there is a choice", targets.all { it.badged })
        assertEquals(NewSessionTargets.Platform.WINDOWS, targets[0].platform)
        assertEquals(NewSessionTargets.Platform.WSL, targets[1].platform)
        assertTrue(targets[0].text.contains("Windows"))
        assertTrue("the distro is named", targets[1].text.contains("DistroA"))
        assertFalse("a lone user in a distro is not named", targets[1].text.contains("dev"))
    }

    @Test fun twoUsersInOneDistro_areToldApartByUser() {
        val a = wsl("DistroA", "dev")
        val b = wsl("DistroA", "ops")
        val targets = NewSessionTargets.from(listOf(SessionRoot.Windows, a, b))
        val texts = targets.drop(1).map { it.text }
        assertTrue(texts[0].contains("DistroA") && texts[0].contains("dev"))
        assertTrue(texts[1].contains("DistroA") && texts[1].contains("ops"))
        assertEquals("texts must differ or the buttons are indistinguishable", 2, texts.toSet().size)
    }

    @Test fun duplicateRoots_collapseToOneButton() {
        val a = wsl("DistroA", "dev")
        val targets = NewSessionTargets.from(listOf(SessionRoot.Windows, a, wsl("DistroA", "dev")))
        assertEquals(2, targets.size)
    }
}
