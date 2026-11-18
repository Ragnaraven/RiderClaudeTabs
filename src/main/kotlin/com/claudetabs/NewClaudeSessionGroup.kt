package com.claudetabs

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * The "New Claude Session" control as an inline group: one [NewClaudeSessionAction] per Claude
 * installation (see [NewSessionTargets]). Its children follow the roots the poll loop has already
 * discovered — a distro that starts or stops shows up or goes within a minute — and are never
 * discovered from here: `wsl.exe` is not something to run on every toolbar refresh.
 *
 * The Windows child is the registered `ClaudeTabs.NewSession` action itself, so its keymap
 * shortcut and the official-button takeover keep pointing at one instance.
 */
class NewClaudeSessionGroup : ActionGroup() {

    private val perRoot = ConcurrentHashMap<String, NewClaudeSessionAction>()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> =
        NewSessionTargets.from(SessionRoot.launchable()).map { actionFor(it.rootId) }.toTypedArray()

    private fun actionFor(rootId: String): AnAction {
        if (rootId == SessionRoot.Windows.ID) {
            ActionManager.getInstance().getAction(NewClaudeSessionAction.ID)?.let { return it }
        }
        return perRoot.computeIfAbsent(rootId) { NewClaudeSessionAction(it) }
    }
}
