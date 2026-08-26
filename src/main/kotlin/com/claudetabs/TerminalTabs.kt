package com.claudetabs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.util.concurrent.ConcurrentHashMap

/**
 * The Terminal tool window and its tab [Content]s, resolved LIVE on every call — never through a
 * cached reference.
 *
 * ## Why (field failure fixed in 3.1.2)
 * `TerminalToolWindowManager.getToolWindow()` returns a private field that `initToolWindow` sets
 * ONCE; a later init logs "Terminal tool window already initialized" and keeps the OLD instance
 * (verified by disassembling the terminal plugin of the 2026.2 build). When the Terminal tool
 * window is re-created during a long IDE session, that field keeps pointing at a DISPOSED window
 * whose ContentManager is empty — while the terminal frontend's own tabs manager (what the user
 * sees, and what [ReworkedTerminalBridge] reads) keeps listing every real tab. Every pass that
 * enumerated tabs through the cached reference then saw "no tabs": bindings were pruned as absent
 * each poll, no session was ever recorded present, the open-tabs snapshot sat at `[]` for days,
 * and after the next restart nothing came back. Signature in idea.log: the bridge scan reporting
 * N tabs while the adopt-scan reports `readableTabs=0 undetermined=0`, plus the same "Exact adopt"
 * line repeating every poll (the binding was pruned again each time).
 *
 * ## Contract
 *  - The tool window comes from [ToolWindowManager] — the platform registry, the only source of
 *    truth for "the current Terminal tool window" — never from the terminal manager's cache. A
 *    divergence between the two is logged once per project as a WARN.
 *  - Tabs are enumerated RECURSIVELY (`getContentsRecursively`): a tab moved into a split group
 *    lives in a nested ContentManager and is invisible to `getContents()`.
 *  - Any Content the frontend tabs manager lists that the enumeration missed is appended (logged
 *    once per project). A tab the user can see must never be a tab the plugin cannot.
 *  - Order is preserved: the ContentManager's own order first (it drives restore ordinals), then
 *    nested extras, then frontend-only extras — see [ClaudeTabsHelpers.mergeTabLists].
 */
internal object TerminalTabs {
    private val LOG = Logger.getInstance(TerminalTabs::class.java)

    const val TOOL_WINDOW_ID = "Terminal"

    /** Project hashes that already logged the stale-cache warning / the frontend-union notice. */
    private val staleCacheWarned: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val unionNoticed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The registered, non-disposed Terminal tool window, or null when there is none (yet). */
    fun toolWindow(project: Project): ToolWindow? {
        val live = try {
            ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        } catch (_: Throwable) { null }
        if (live == null || isDisposed(live)) return null
        val cached = try { TerminalToolWindowManager.getInstance(project).toolWindow } catch (_: Throwable) { null }
        if (cached != null && cached !== live && staleCacheWarned.add(project.locationHash)) {
            LOG.warn(
                "[ClaudeTabs] TerminalToolWindowManager caches a Terminal tool window that is no longer " +
                    "the registered one (cachedDisposed=${isDisposed(cached)}) — tabs are enumerated " +
                    "through the registered window; the cached one would have shown none",
            )
        }
        return live
    }

    /**
     * Every valid tab Content of [tw], in display order. [frontendTabs] is the frontend tabs
     * manager's list when the caller already holds one (saves a reflective walk); null = read it
     * here via [ReworkedTerminalBridge.tabContents].
     */
    fun contents(project: Project, tw: ToolWindow, frontendTabs: Collection<Content>? = null): List<Content> {
        val cm: ContentManager? = try { tw.contentManagerIfCreated } catch (_: Throwable) { null }
        val own = try { cm?.contents?.toList() } catch (_: Throwable) { null } ?: emptyList()
        val nested = try { cm?.contentsRecursively } catch (_: Throwable) { null } ?: emptyList()
        val known = frontendTabs ?: ReworkedTerminalBridge.tabContents(project)
        val fromManager = ClaudeTabsHelpers.mergeTabLists(own, nested, emptyList()).filter { isValid(it) }
        val merged = ClaudeTabsHelpers.mergeTabLists(fromManager, emptyList(), known).filter { isValid(it) }
        val frontendOnly = merged.size - fromManager.size
        if (frontendOnly > 0 && unionNoticed.add(project.locationHash)) {
            LOG.warn(
                "[ClaudeTabs] $frontendOnly terminal tab(s) listed by the terminal frontend were absent from " +
                    "the tool window's ContentManager (own=${own.size} nested=${nested.size}) — included anyway",
            )
        }
        return merged
    }

    private fun isDisposed(tw: ToolWindow): Boolean = try { tw.isDisposed } catch (_: Throwable) { false }
    private fun isValid(c: Content): Boolean = try { c.isValid } catch (_: Throwable) { true }
}
