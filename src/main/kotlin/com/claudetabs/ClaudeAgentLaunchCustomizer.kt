package com.claudetabs

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.LocalTerminalCustomizer

/**
 * Assigns a session id to every Claude CLI launched DIRECTLY as a terminal's own process — the
 * shape the terminal tool window's built-in AI-agents button ("Claude Code" in the title bar and
 * its agent-selector dropdown) uses: `claude` IS the pty root command, with no shell in between.
 *
 * Those launches bypass the plugin's owned spawner, so without this hook their session ids are
 * unknown at birth and the tabs fall back to heuristic adoption — which (correctly) refuses to
 * guess when several such tabs open at once, and the tabs are then lost on restart. Injecting
 * `--session-id <minted-uuid>` at spawn time turns every such launch into an owned-identity
 * launch: the id is a birth-time fact, readable back from the widget's own shell command
 * (see the exact-adopt pass in [ClaudeTabWatcherStartup]).
 *
 * Commands that already pin a session (`--session-id`, `--resume`, `--continue`) are never
 * touched, so a hand-crafted launch — or a tab re-created by the IDE's own terminal persistence
 * with a previously injected argv — is not double-injected.
 */
class ClaudeAgentLaunchCustomizer : LocalTerminalCustomizer() {

    override fun customizeCommandAndEnvironment(
        project: Project,
        workingDirectory: String?,
        command: Array<String>,
        envs: MutableMap<String, String>,
    ): Array<String> =
        ClaudeTabWatcherStartup.injectSessionIdIfClaudeLaunch(project, workingDirectory, command)
}
