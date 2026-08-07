package com.claudetabs

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * "New Claude Session" — the owned path for opening Claude tabs.
 *
 * Instead of opening a raw terminal and hand-typing `claude` (where the plugin has to *guess* which
 * session ended up in which tab), this action has the plugin create the terminal widget itself and
 * launch `claude --session-id <fresh-uuid>` in it. The plugin therefore holds
 * `widget ↔ Content ↔ sid` from birth — identity is a stored fact, so the tab restores durably,
 * named and animated, with zero heuristics. See [ClaudeTabWatcherStartup.requestNewSession].
 *
 * Reachable two ways: the Tools menu entry registered in plugin.xml, and — when the official
 * Claude Code plugin is installed — its own toolbar button / Ctrl+Esc, whose action is replaced
 * with this one at startup (same id, copied presentation, so placement/icon/shortcut are
 * untouched). One button, one path. Tabs opened by hand are still best-effort adopted, but only
 * this path carries the durability guarantee.
 */
class NewClaudeSessionAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ClaudeTabWatcherStartup.requestNewSession(project)
    }
}
