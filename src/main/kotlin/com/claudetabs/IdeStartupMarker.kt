package com.claudetabs

import com.intellij.ide.AppLifecycleListener

/**
 * Was this plugin instance loaded WITH the IDE, or into one that was already running?
 *
 * The platform fires `appFrameCreated` exactly once, while the IDE starts. A plugin loaded
 * dynamically later — installed, updated or enabled without a restart — is subscribed too late to
 * hear it, so the flag stays false for the rest of that JVM. That is the distinction the
 * startup sweep of IDE-restored terminal tabs rests on: at a genuine IDE start every terminal tab
 * present was just re-created by the IDE, whereas after a dynamic load the tabs present are the
 * user's LIVE ones and must not be touched. It has to be a fact the platform states, not something
 * inferred from timing: a first-project-open timestamp is wrong in both directions (a long stay on
 * the welcome screen disables the sweep; an install minutes after launch would let it close
 * running conversations).
 */
internal class IdeStartupMarker : AppLifecycleListener {
    // Only the public hook: `appStarted` is @Internal and the Marketplace verifier flags it.
    override fun appFrameCreated(commandLineArgs: MutableList<String>) { loadedWithIde = true }

    companion object {
        @Volatile var loadedWithIde: Boolean = false
            private set
    }
}
