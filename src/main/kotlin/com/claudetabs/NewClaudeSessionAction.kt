package com.claudetabs

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * "New Claude Session" — the owned path for opening Claude tabs, into one Claude installation.
 *
 * Instead of opening a raw terminal and hand-typing `claude` (where the plugin has to *guess* which
 * session ended up in which tab), this action has the plugin create the terminal widget itself and
 * launch `claude --session-id <fresh-uuid>` in it — through [rootId]'s launcher, so a WSL button
 * opens that distro's Claude and a Windows button the local one. The plugin therefore holds
 * `widget ↔ Content ↔ sid` from birth — identity is a stored fact, so the tab restores durably,
 * named and animated, with zero heuristics. See [ClaudeTabWatcherStartup.requestNewSession].
 *
 * Reachable three ways: the Tools menu and the terminal tab strip, where [NewClaudeSessionGroup]
 * lays out one of these per installation; and — when the official Claude Code plugin is
 * installed — its own toolbar button / Ctrl+Esc, whose action is replaced with this one at
 * startup (same id, copied presentation, so placement/icon/shortcut are untouched; such an
 * instance keeps that presentation, [styled] = false). Tabs opened by hand are still best-effort
 * adopted, but only this path carries the durability guarantee.
 */
class NewClaudeSessionAction(
    private val rootId: String = SessionRoot.Windows.ID,
    /** Whether `update` paints the platform text/icon from [NewSessionTargets]; false for an
     *  instance that must keep a presentation copied from elsewhere. */
    private val styled: Boolean = true,
) : AnAction() {

    /** plugin.xml needs a no-arg constructor; this is the Windows (default) button. */
    constructor() : this(SessionRoot.Windows.ID)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
        if (!styled) return
        val target = NewSessionTargets.from(SessionRoot.launchable()).firstOrNull { it.rootId == rootId } ?: return
        e.presentation.text = target.text
        e.presentation.icon = ClaudeTabsIcons.forTarget(target)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ClaudeTabWatcherStartup.requestNewSession(project, rootId)
    }

    companion object {
        const val ID = "ClaudeTabs.NewSession"
    }
}
