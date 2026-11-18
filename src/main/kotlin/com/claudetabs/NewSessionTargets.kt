package com.claudetabs

/**
 * What the "New Claude Session" control shows: one button per Claude installation the plugin can
 * launch into — the Windows one always, plus every running WSL distro with a Claude home.
 *
 * With a single root the control is the plain button it has always been. With two or more, each
 * button carries its platform on its icon and in its text, so the user picks WHICH Claude opens —
 * the Windows CLI and a distro's CLI are different installations with different accounts and
 * histories. The Windows root is always first, keeping the default where it was.
 *
 * Pure: handed the roots, returns the buttons.
 */
internal object NewSessionTargets {

    enum class Platform { WINDOWS, WSL, FAKE }

    data class Target(
        val rootId: String,
        val platform: Platform,
        /** Button text; names the platform only when there is a choice to make. */
        val text: String,
        /** True when the icon should carry the platform badge (two or more roots). */
        val badged: Boolean,
    )

    const val BASE_TEXT = "New Claude Session"

    fun from(roots: List<SessionRoot>): List<Target> {
        val ordered = roots.distinctBy { it.id }.sortedBy { if (it is SessionRoot.Windows) 0 else 1 }
        if (ordered.size <= 1) {
            val only = ordered.firstOrNull() ?: SessionRoot.Windows
            return listOf(Target(only.id, platformOf(only), BASE_TEXT, badged = false))
        }
        // Two roots in the same distro (two Linux users) are told apart by the user name.
        val distroCounts = ordered.filterIsInstance<SessionRoot.Wsl>().groupingBy { it.distro }.eachCount()
        return ordered.map { root ->
            val where = when (root) {
                is SessionRoot.Windows -> "Windows"
                is SessionRoot.Wsl ->
                    if ((distroCounts[root.distro] ?: 0) > 1) "WSL: ${root.distro}, ${root.user}" else "WSL: ${root.distro}"
                is SessionRoot.Fake -> "Fake: ${root.name}"
            }
            Target(root.id, platformOf(root), "$BASE_TEXT ($where)", badged = true)
        }
    }

    private fun platformOf(root: SessionRoot) = when (root) {
        is SessionRoot.Windows -> Platform.WINDOWS
        is SessionRoot.Wsl -> Platform.WSL
        is SessionRoot.Fake -> Platform.FAKE
    }
}
