package com.claudetabs

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** The plugin's icons; `_dark` variants are picked up by the loader. */
internal object ClaudeTabsIcons {
    /** The plain Claude mark — the one-installation button. */
    val session: Icon = IconLoader.getIcon("/icons/claudeSession.svg", ClaudeTabsIcons::class.java)
    private val sessionWindows: Icon = IconLoader.getIcon("/icons/claudeSessionWindows.svg", ClaudeTabsIcons::class.java)
    private val sessionWsl: Icon = IconLoader.getIcon("/icons/claudeSessionWsl.svg", ClaudeTabsIcons::class.java)

    fun forTarget(target: NewSessionTargets.Target): Icon = when {
        !target.badged -> session
        target.platform == NewSessionTargets.Platform.WINDOWS -> sessionWindows
        else -> sessionWsl // a WSL distro, or the E2E fake root standing in for one
    }
}
