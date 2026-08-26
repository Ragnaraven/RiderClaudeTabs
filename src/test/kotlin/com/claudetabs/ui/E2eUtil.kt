package com.claudetabs.ui

import com.intellij.remoterobot.RemoteRobot
import java.io.File

/**
 * Shared plumbing for the Layer-3b end-to-end tests (see `e2e/run-e2e.ps1`).
 *
 * The tests run in a PLAIN JVM outside the sandbox IDE and interact with it two ways:
 *  - the Remote Robot HTTP bridge (`callJs` executes Rhino JS inside the IDE process) for
 *    UI-shaped actions: invoking actions, typing into terminals, closing tabs;
 *  - direct reads of the ISOLATED plugin state under `build/e2e/` (active-sessions entries,
 *    open-tabs snapshots, the stub CLI's argv log) for deterministic assertions.
 */
internal object E2eUtil {

    private const val ROBOT_URL = "http://127.0.0.1:8082"

    val e2eDir: File = File("build/e2e").absoluteFile
    val claudeHome = File(e2eDir, "claude-home/.claude")
    val activeSessionsDir = File(claudeHome, "rider-plugin/active-sessions")
    val stateDir = File(claudeHome, "rider-plugin")
    val stubLog = File(e2eDir, "stub-argv.log")

    /** The session id `bin/claude-hand.cmd` resumes — see [armHandLauncher]. */
    val handSidFile = File(e2eDir, "hand-sid.txt")

    fun connect(timeoutMs: Long = 240_000): RemoteRobot {
        val robot = RemoteRobot(ROBOT_URL)
        var lastError: Throwable? = null
        var logged = 0
        try {
            await("robot server up", timeoutMs) {
                try {
                    robot.callJs<Boolean>("true")
                } catch (t: Throwable) {
                    lastError = t
                    // Surface the swallowed reason while still retrying (max a few times).
                    if (logged < 3) { logged++; println("[e2e] connect retry after: ${t.javaClass.name}: ${t.message}") }
                    false
                }
            }
        } catch (e: AssertionError) {
            throw AssertionError("robot server up — last error: ${lastError?.javaClass?.name}: ${lastError?.message}", lastError)
        }
        return robot
    }

    fun await(what: String, timeoutMs: Long = 60_000, stepMs: Long = 1_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try { if (cond()) return } catch (_: Throwable) { /* keep waiting */ }
            Thread.sleep(stepMs)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    // ── IDE-side operations (Rhino JS inside the sandbox IDE) ──

    fun projectOpen(robot: RemoteRobot): Boolean =
        robot.callJs("com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects().length > 0")

    fun invokeAction(robot: RemoteRobot, actionId: String) {
        robot.callJs<Boolean>(
            """
            var am = com.intellij.openapi.actionSystem.ActionManager.getInstance();
            var action = am.getAction('$actionId');
            if (action == null) false;
            else {
                var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
                var dc = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                    .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                    .build();
                com.intellij.openapi.actionSystem.ex.ActionUtil.invokeAction(action, dc, 'e2e', null, null);
                true;
            }
            """.trimIndent(),
            true,
        ).also { check(it) { "action not found: $actionId" } }
    }

    fun activateTerminal(robot: RemoteRobot) {
        robot.callJs<Boolean>(
            """
            var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
            var tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Terminal');
            if (tw == null) false; else { tw.activate(null, true, true); true; }
            """.trimIndent(),
            true,
        ).also { check(it) { "Terminal tool window not found" } }
    }

    fun terminalTabTitles(robot: RemoteRobot): List<String> {
        val joined = robot.callJs<String>(
            """
            var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
            var tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Terminal');
            if (tw == null) '' ;
            else {
                var cs = tw.getContentManager().getContents();
                var out = [];
                for (var i = 0; i < cs.length; i++) out.push(cs[i].getDisplayName());
                out.join('|::|');
            }
            """.trimIndent(),
            true,
        )
        return if (joined.isEmpty()) emptyList() else joined.split("|::|")
    }

    /** × the terminal tab at [index] — same ContentManager removal the close button performs. */
    fun closeTerminalTab(robot: RemoteRobot, index: Int) {
        robot.callJs<Boolean>(
            """
            var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
            var tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Terminal');
            var cm = tw.getContentManager();
            var cs = cm.getContents();
            if (cs.length <= $index) false; else { cm.removeContent(cs[$index], true); true; }
            """.trimIndent(),
            true,
        ).also { check(it) { "no terminal tab at index $index" } }
    }

    /**
     * The HAND-OPEN shape without OS-focus dependence: create a plain shell tab (NOT via the
     * plugin's owned spawner — the plugin has no birth binding for it) and execute [command] in
     * it, exactly as if the user opened a terminal tab and typed the command. Robot keyboard
     * typing is deliberately avoided: it synthesizes OS-level keystrokes into whatever window
     * REALLY has focus, which on a busy desktop may not be the sandbox.
     *
     * The terminal classes live in the terminal plugin's classloader (invisible to Rhino's
     * default resolution) — reached via an action instance's own loader.
     */
    fun openHandTabRunning(robot: RemoteRobot, command: String) {
        robot.callJs<Boolean>(
            """
            $JS_PROJECT_AND_LOADER
            ${classicShellTabJs(command)}
            true;
            """.trimIndent(),
            true,
        ).also { check(it) { "hand-open tab creation failed" } }
    }

    /** Preamble every terminal-touching script needs: the open project + the terminal plugin's
     *  own classloader (its classes are invisible to the script engine's default resolution). */
    private val JS_PROJECT_AND_LOADER = """
        var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
        var pluginLoader = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .getAction('Terminal.NewTab').getClass().getClassLoader();
    """.trimIndent()

    /**
     * A stub wrapper under `build/e2e/bin`, as an ABSOLUTE-path invocation to type into a sandbox
     * shell. The harness never trusts PATH for this: the sandbox shell has been observed to lose
     * the prepended bin dir entirely (an IDE shell-integration script threw before it applied),
     * which silently turned `claude` into the machine's REAL CLI. A path without spaces runs
     * verbatim in both cmd and PowerShell; the quoted form PowerShell needs takes the call operator.
     */
    fun binCommand(name: String): String {
        val path = File(e2eDir, "bin/$name").absolutePath
        return if (path.any { it.isWhitespace() }) "& \"$path\"" else path
    }

    /** Classic-terminal shell tab in the project dir with [command] typed into it. Needs
     *  [JS_PROJECT_AND_LOADER] evaluated first. [command] is embedded in a JS string literal, so
     *  its Windows separators are escaped here — callers pass the plain shell command. */
    private fun classicShellTabJs(rawCommand: String): String {
        val command = rawCommand.replace("\\", "\\\\")
        return """
        var shellMgrCls = java.lang.Class.forName('org.jetbrains.plugins.terminal.TerminalToolWindowManager', true, pluginLoader);
        var shellMgr = shellMgrCls.getMethod('getInstance', com.intellij.openapi.project.Project).invoke(null, project);
        var create = shellMgrCls.getMethod('createShellWidget',
            java.lang.String, java.lang.String, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE);
        var widget = create.invoke(shellMgr, project.getBasePath(), null,
            java.lang.Boolean.FALSE, java.lang.Boolean.FALSE);
        var widgetCls = java.lang.Class.forName('com.intellij.terminal.ui.TerminalWidget', true, pluginLoader);
        widgetCls.getMethod('sendCommandToExecute', java.lang.String).invoke(widget, '$command');
    """.trimIndent()
    }

    /**
     * Point `bin/claude-hand.cmd` at [sid]: the launcher reads the id from this file, so the tab
     * that runs it exposes NO session id in its command line — the whole point of the hand-open
     * scenario (a user typing `claude --resume <sid>` keeps the argv inside their shell).
     *
     * Written with a trailing CRLF: the launcher's `set /p` wants a properly terminated line, and
     * an unterminated last line is exactly what a half-written file looks like to a reader that
     * races us.
     */
    fun armHandLauncher(sid: String) {
        handSidFile.parentFile.mkdirs()
        handSidFile.writeText("$sid\r\n")
    }

    /**
     * The FIELD shape of the hand-open bug: a terminal tab the plugin never spawned, whose launch
     * command is just a shell running a launcher script, and whose title says "shell" until the
     * program inside announces itself.
     *
     * Prefers a REWORKED tab (`TerminalToolWindowTabsManager.createTabBuilder()`) — that is the tab
     * kind the field bug lives in: it has no classic widget, so the cwd-based adoption pass sees
     * nothing to read. The manager lives in the terminal plugin's *frontend* content module, which
     * has its own classloader; it is reached the same way the plugin's own bridge reaches it — by
     * harvesting classloaders from a live terminal tab's component tree and keeping only a class
     * whose `getService` actually resolves (the dual-classloader identity trap).
     *
     * Falls back to the classic `createShellWidget` + typed command when the reworked manager is
     * not resolvable in this sandbox — a debugging convenience for a human driving the sandbox by
     * hand, NOT an acceptable outcome: the classic path is the one that already worked before the
     * fix, so a phase that silently ran it would be a green no-op. Returns `"reworked"`, or
     * `"classic:<exception class + message>"` carrying WHY the reworked builder failed, so the
     * phase can fail with the real reason instead of "the fallback happened".
     */
    fun openHandTab(robot: RemoteRobot, launcher: File, launcherOnPath: String): String {
        // Windows path → JS string literal: every separator has to survive the embedding.
        val launcherPath = launcher.absolutePath.replace("\\", "\\\\")
        val variant = robot.callJs<String>(
            """
            $JS_PROJECT_AND_LOADER
            var tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Terminal');
            // Explicit empty arrays for the no-arg reflective calls: never lean on the JS
            // engine's varargs filling for `getMethod(name)` / `Method.invoke(target)`.
            var NOCLS = java.lang.reflect.Array.newInstance(java.lang.Class, 0);
            var NOARGS = java.lang.reflect.Array.newInstance(java.lang.Object, 0);

            // Harvest every classloader in the live terminal tabs' component trees.
            var loaders = new java.util.LinkedHashSet();
            var cm = (tw == null) ? null : tw.getContentManagerIfCreated();
            if (cm != null) {
                var cs = cm.getContents();
                for (var i = 0; i < cs.length; i++) {
                    var queue = [cs[i].getComponent()];
                    var seen = 0;
                    while (queue.length > 0 && seen < 4000) {
                        var comp = queue.shift();
                        seen++;
                        if (comp == null) continue;
                        var cl = comp.getClass().getClassLoader();
                        if (cl != null) loaders.add(cl);
                        if (comp instanceof java.awt.Container) {
                            var kids = comp.getComponents();
                            for (var k = 0; k < kids.length; k++) queue.push(kids[k]);
                        }
                    }
                }
            }
            loaders.add(pluginLoader);

            var mgr = null; var mgrCls = null;
            var it = loaders.iterator();
            while (it.hasNext() && mgr == null) {
                var ld = it.next();
                var cls = null;
                try {
                    cls = java.lang.Class.forName(
                        'com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager', false, ld);
                } catch (e) { cls = null; }
                if (cls == null) continue;
                var svc = null;
                try { svc = project.getService(cls); } catch (e2) { svc = null; }
                if (svc != null) { mgr = svc; mgrCls = cls; }
            }

            var variant = null;
            var reason = 'tabs-manager not resolvable in any harvested classloader';
            if (mgr != null) {
                try {
                    var builder = mgrCls.getMethod('createTabBuilder', NOCLS).invoke(mgr, NOARGS);
                    var bCls = java.lang.Class.forName(
                        'com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabBuilder', false, mgrCls.getClassLoader());
                    var cmd = new java.util.ArrayList();
                    cmd.add('cmd.exe'); cmd.add('/c'); cmd.add('$launcherPath');
                    bCls.getMethod('shellCommand', java.util.List).invoke(builder, cmd);
                    bCls.getMethod('workingDirectory', java.lang.String).invoke(builder, project.getBasePath());
                    bCls.getMethod('requestFocus', java.lang.Boolean.TYPE).invoke(builder, java.lang.Boolean.TRUE);
                    bCls.getMethod('createTab', NOCLS).invoke(builder, NOARGS);
                    variant = 'reworked';
                } catch (e3) {
                    // Carry the reason out: a bare fallback would hide the ONE failure that turns
                    // this regression phase into a no-op. A Java throwable arrives wrapped, so
                    // unwrap it — its toString() is already `class: message`.
                    var cause = (e3 != null && e3.javaException != null) ? e3.javaException : e3;
                    reason = '' + cause;
                    // Every step here is a reflective call, so the real failure arrives wrapped in
                    // an InvocationTargetException whose toString() carries no message — append the
                    // cause or the reason degrades to a bare wrapper name.
                    try {
                        if (cause.getCause && cause.getCause() != null) reason = reason + ' <- ' + cause.getCause();
                    } catch (e4) { /* reason without the cause still beats no reason */ }
                }
            }
            if (variant == null) {
                ${classicShellTabJs(launcherOnPath)}
                variant = 'classic:' + reason;
            }
            variant;
            """.trimIndent(),
            true,
        )
        check(variant == "reworked" || variant.startsWith("classic:")) { "hand-open tab creation failed: $variant" }
        return variant
    }

    /** Committed exit — exercises the appClosing snapshot freeze on the way out. */
    fun exitIde(robot: RemoteRobot) {
        try {
            robot.callJs<Boolean>(
                "com.intellij.openapi.application.ApplicationManager.getApplication().exit(true, true, false); true",
                true,
            )
        } catch (_: Throwable) {
            // The connection dying mid-call IS the expected outcome of a successful exit.
        }
    }

    // ── Isolated-state readers (plain file reads, no IDE involved) ──

    data class SessionEntry(val sid: String, val root: String, val pid: Long?, val file: File)

    fun activeSessions(): List<SessionEntry> =
        activeSessionsDir.listFiles { f -> f.name.endsWith(".json") }?.mapNotNull { f ->
            val text = try { f.readText() } catch (_: Exception) { return@mapNotNull null }
            val sid = Regex(""""sid"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: return@mapNotNull null
            val root = Regex(""""root"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: "windows"
            val pid = Regex(""""pid"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull()
            SessionEntry(sid, root, pid, f)
        } ?: emptyList()

    /**
     * Ask a stub claude to exit (control channel: `<home>/kill/<pid>`), so its tab can be ×'d
     * without the terminal's terminate-confirmation modal (which blocks the EDT — and with it
     * every robot call). The stub polls every 1.5s; wait for its session heartbeat to stop.
     */
    /** The recorded pid for [sid], awaiting a poll that has it non-null (a demote/re-promote or
     *  read race can transiently show null — retry instead of failing the phase on a flake). */
    fun pidOf(sid: String): Long {
        await("pid recorded for sid=$sid", 30_000) {
            activeSessions().firstOrNull { it.sid == sid }?.pid != null
        }
        return activeSessions().first { it.sid == sid }.pid!!
    }

    fun killStub(root: String, pid: Long) {
        val home = if (root.startsWith("fake:")) File(e2eDir, "fake-root/.claude") else claudeHome
        val dir = File(home, "kill").apply { mkdirs() }
        File(dir, pid.toString()).writeText("die")
        Thread.sleep(4_000)
    }

    /**
     * The `/tab` rename, as the real script performs it: rewrite `userName` in the session's
     * active-sessions entry, atomically (tmp + move) so a concurrent plugin read can never see a
     * torn file. Everything downstream — the tab title, and its survival across a restart — is the
     * plugin's job.
     *
     * IDEMPOTENT, and meant to be re-applied: this is a cross-process read-modify-write racing the
     * plugin's own ~5s rewrite of the same entry, so one call can be clobbered (we read, it writes,
     * we move our stale-plus-name copy over its update — or the reverse, and the name is simply
     * gone). Callers that wait on the result should re-apply each poll rather than bet a whole
     * timeout on a single write landing.
     */
    fun setUserName(sid: String, userName: String) {
        val target = File(activeSessionsDir, "$sid.json")
        val text = target.readText()
        val updated = if (Regex(""""userName"\s*:""").containsMatchIn(text)) {
            text.replace(Regex(""""userName"\s*:\s*(?:null|"[^"]*")"""), """"userName":"$userName"""")
        } else {
            text.replaceFirst(Regex("}\\s*$"), ""","userName":"$userName"}""")
        }
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(updated)
        java.nio.file.Files.move(
            tmp.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }

    fun snapshotSids(): Set<String> {
        val snap = stateDir.listFiles { f -> f.name.startsWith("open-tabs-") && f.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() } ?: return emptySet()
        val text = try { snap.readText() } catch (_: Exception) { return emptySet() }
        return Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            .findAll(text).map { it.value }.toSet()
    }

    data class StubLaunch(val ts: Long, val flavor: String?, val argv: List<String>)

    fun stubLaunches(): List<StubLaunch> {
        if (!stubLog.exists()) return emptyList()
        return stubLog.readLines().mapNotNull { line ->
            val ts = Regex(""""ts"\s*:\s*(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull()
                ?: return@mapNotNull null
            val flavor = Regex(""""flavor"\s*:\s*"([^"]+)"""").find(line)?.groupValues?.get(1)
            val argv = Regex(""""argv"\s*:\s*\[([^\]]*)]""").find(line)?.groupValues?.get(1)
                ?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }
                ?: emptyList()
            StubLaunch(ts, flavor, argv)
        }
    }

    fun resumedSids(launches: List<StubLaunch>): Map<String, String?> =
        launches.filter { "--resume" in it.argv }
            .associate { l -> l.argv[l.argv.indexOf("--resume") + 1] to l.flavor }

    /** File the phases share: phase 1 records what phase 2 must assert against. */
    val expectationsFile = File(e2eDir, "phase1-expectations.json")

    fun writeExpectations(
        keptWin: String,
        keptFake: String,
        closedWin: String,
        closedFake: String,
        handSid: String,
        handUserName: String,
        // Phase 1's own `--resume` of the hand session is already in the argv log; phase 2 must
        // only count launches AFTER this instant as restore work.
        phase1End: Long,
    ) {
        expectationsFile.writeText(
            """{"keptWin":"$keptWin","keptFake":"$keptFake","closedWin":"$closedWin","closedFake":"$closedFake",""" +
                """"handSid":"$handSid","handUserName":"$handUserName","phase1End":"$phase1End"}""",
        )
    }

    fun readExpectations(): Map<String, String> {
        val text = expectationsFile.readText()
        return Regex(""""(\w+)"\s*:\s*"([^"]+)"""").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}
