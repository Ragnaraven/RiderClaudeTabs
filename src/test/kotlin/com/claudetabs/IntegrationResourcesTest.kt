package com.claudetabs

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the deployment manifests in [ClaudeTabWatcherStartup].
 *
 * The deploy loop skips a name whose resource is missing (`?: continue`) rather than failing,
 * so a helper or command listed but not packaged would simply never appear in the user's
 * `~/.claude` — silently, and only on their machine. These assertions turn that into a build
 * failure instead.
 */
class IntegrationResourcesTest {

    private fun resource(name: String) =
        javaClass.classLoader.getResourceAsStream("claude-integration/$name")

    @Test fun everyDeployedHelperIsPackaged() {
        for (name in ClaudeTabWatcherStartup.INTEGRATION_HELPERS) {
            resource(name).use { assertNotNull("missing resource claude-integration/$name", it) }
        }
    }

    @Test fun everyDeployedCommandIsPackaged() {
        for (name in ClaudeTabWatcherStartup.INTEGRATION_COMMANDS) {
            resource(name).use { assertNotNull("missing resource claude-integration/$name", it) }
        }
    }

    /** Each shipped Node helper the user can invoke needs a permission line, or every run of it
     *  prompts. The recovery helper is the one a crashed session reaches for first. */
    @Test fun recoveryHelperIsPermitted() {
        assertTrue(
            SettingsPermissions.CURRENT_ENTRIES.any { it.contains("recover-tabs.js") },
        )
    }
}
