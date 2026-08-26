package com.claudetabs

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A SESSION ROOT is one place Claude sessions live: the Windows-side `~/.claude`, or a WSL
 * distribution's `~/.claude` reached over the `\\wsl.localhost\<distro>\` share.
 *
 * Each root is a fully separate Claude installation — typically a separate Anthropic ACCOUNT —
 * so identity must never cross roots:
 *  - a sid discovered under a root is tagged with that root in its `active-sessions` entry;
 *  - liveness / process-start checks go against THAT root's process table (`ProcessHandle` for
 *    Windows, `/proc/<pid>` over the UNC share for WSL — a Linux pid means nothing to Windows);
 *  - restore launches through THAT root's launcher (`claude --resume` vs
 *    `wsl.exe -d <distro> -u <user> -- … claude --resume`), so a session always resumes under
 *    the account and installation that owns it.
 *
 * WSL cwds are translated to their Windows-comparable form at scan time (`/mnt/d/X` → `d:/X`),
 * so every downstream cwd comparison (project claims, 1:1 adoption grouping, spawn working
 * directory) works unchanged — and the spawned host shell's Windows cwd maps straight back to
 * the same `/mnt` path when `wsl.exe` enters the distro.
 */
/**
 * Resolves the Windows-side `~/.claude` (and the CLI's sibling `~/.claude.json`). Overridable
 * via `-Dclaudetabs.home=<dir>` so the E2E harness can point a sandbox IDE at an isolated
 * throwaway home instead of the user's real one. Never set in production.
 */
internal object ClaudeHome {
    private val override: File? =
        System.getProperty("claudetabs.home")?.takeIf { it.isNotBlank() }?.let(::File)

    val dir: File = override ?: File(System.getProperty("user.home"), ".claude")

    /** `~/.claude.json` — lives NEXT TO `.claude`, so an override relocates it alongside. */
    val globalConfig: File =
        override?.let { File(it.parentFile ?: it, ".claude.json") }
            ?: File(System.getProperty("user.home"), ".claude.json")
}

internal sealed class SessionRoot {

    /** Stable identity persisted in `active-sessions` entries: "windows" or
     *  "wsl:<distro>:<user>" — the Linux user is part of the identity because two users in one
     *  distro are two homes, two installations and two accounts. */
    abstract val id: String

    /** The root's `~/.claude` directory as reachable from Windows. */
    abstract val claudeHome: File

    val sessionsDir: File get() = File(claudeHome, "sessions")
    val projectsDir: File get() = File(claudeHome, "projects")

    /** Liveness of [pid] in THIS root's process namespace. */
    abstract fun processAlive(pid: Long): Boolean

    /** Epoch millis [pid] started in this root, or null when the root can't report it. */
    abstract fun processStartEpochMs(pid: Long): Long?

    /** Command/argv info for [pid], or null when the root can't inspect it (WSL /proc over the
     *  9p share doesn't always serve virtual-file contents). Null means "unknown", not "not
     *  claude" — the caller decides how strictly to treat it per root. */
    abstract fun processInfo(pid: Long): SessionsDirScanner.ProcessInfo?

    /** The shell command typed into a spawned host terminal to resume [sid] in this root. */
    abstract fun resumeCommand(sid: String): String

    /** Root-local cwd → Windows-comparable path ("d:/x/y" form for WSL /mnt paths; unchanged
     *  otherwise). Not normalised — feed through [ClaudeTabsHelpers.normalizeCwd] to compare. */
    open fun comparableCwd(cwd: String): String = cwd

    object Windows : SessionRoot() {
        const val ID = "windows"
        override val id = ID
        override val claudeHome: File get() = ClaudeHome.dir
        override fun processAlive(pid: Long): Boolean = try {
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        } catch (_: Throwable) { false }

        override fun processStartEpochMs(pid: Long): Long? = try {
            ProcessHandle.of(pid)
                .flatMap { it.info().startInstant() }
                .map { it.toEpochMilli() }
                .orElse(null)
        } catch (_: Throwable) { null }

        override fun processInfo(pid: Long): SessionsDirScanner.ProcessInfo? = try {
            ProcessHandle.of(pid).map { ph ->
                val info = ph.info()
                SessionsDirScanner.ProcessInfo(info.command().orElse(""), info.commandLine().orElse(""))
            }.orElse(null)
        } catch (_: Throwable) { null }

        override fun resumeCommand(sid: String): String =
            OwnedTerminalSpawner.launchCommand(sid, OwnedTerminalSpawner.Mode.RESUME)
    }

    /** One Linux [user]'s `~/.claude` inside [distro] — the read side ([claudeHome] over the UNC
     *  share) and the launch side (`wsl.exe -u <user>`) must name the SAME user, or a session is
     *  resumed by an installation that never owned it. */
    class Wsl(val distro: String, val user: String, override val claudeHome: File) : SessionRoot() {
        override val id = "$WSL_PREFIX$distro:$user"

        private fun procDir(pid: Long) = File("\\\\wsl.localhost\\$distro\\proc\\$pid")

        /** `/proc/<pid>` existing IS liveness on Linux; the share serves it while the distro
         *  runs. Distro stopped → unreachable → false, which is correct: nothing is running. */
        override fun processAlive(pid: Long): Boolean = try {
            procDir(pid).isDirectory
        } catch (_: Throwable) { false }

        /** `/proc/<pid>`'s mtime is the process start time on Linux. 0/unreadable → null. */
        override fun processStartEpochMs(pid: Long): Long? = try {
            procDir(pid).lastModified().takeIf { it > 0L }
        } catch (_: Throwable) { null }

        override fun processInfo(pid: Long): SessionsDirScanner.ProcessInfo? = try {
            val bytes = File(procDir(pid), "cmdline").readBytes()
            if (bytes.isEmpty()) null
            else {
                val args = String(bytes, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
                SessionsDirScanner.ProcessInfo(args.firstOrNull() ?: "", args.joinToString(" "))
            }
        } catch (_: Throwable) { null }

        override fun resumeCommand(sid: String): String =
            ClaudeTabsHelpers.buildWslResumeCommand(distro, sid, user)

        override fun comparableCwd(cwd: String): String =
            ClaudeTabsHelpers.wslCwdToWindows(cwd) ?: cwd
    }

    /**
     * E2E-ONLY file-driven root, active only when `-Dclaudetabs.fakeRoots=<name>=<dir>[;…]` is
     * set (never in production). Emulates a foreign root's observable surface with plain files:
     * liveness = `<home>/proc/<pid>` marker exists (the stub creates/deletes it), start time =
     * that marker's mtime, resume command = `<home>/resume-command.txt` template with `{SID}`
     * substituted. Lets the harness prove multi-root separation without depending on WSL.
     */
    class Fake(val name: String, override val claudeHome: File) : SessionRoot() {
        override val id = "$FAKE_PREFIX$name"
        private fun marker(pid: Long) = File(File(claudeHome, "proc"), pid.toString())
        override fun processAlive(pid: Long): Boolean = try { marker(pid).exists() } catch (_: Throwable) { false }
        override fun processStartEpochMs(pid: Long): Long? = try {
            marker(pid).lastModified().takeIf { it > 0L }
        } catch (_: Throwable) { null }
        override fun processInfo(pid: Long): SessionsDirScanner.ProcessInfo? = null
        override fun resumeCommand(sid: String): String = try {
            File(claudeHome, "resume-command.txt").readText().trim().replace("{SID}", sid)
        } catch (_: Throwable) { "claude --resume $sid" }
    }

    companion object {
        const val WSL_PREFIX = "wsl:"
        const val FAKE_PREFIX = "fake:"

        /** Split a `wsl:`-prefixed id into distro and user. WSL distro names cannot contain ':',
         *  so the FIRST ':' after the prefix separates the two segments; an id with no ':' is a
         *  legacy (pre-per-user) id whose user is unknown. */
        private fun splitWslId(id: String): Pair<String, String?> {
            val rest = id.removePrefix(WSL_PREFIX)
            val i = rest.indexOf(':')
            return if (i < 0) rest to null else rest.take(i) to rest.substring(i + 1)
        }

        /** Resolve a persisted root id back to a live root. "windows" (or the pre-3.1 absent
         *  field) → [Windows]; "wsl:<distro>:<user>" → that exact distro+user root (a legacy
         *  user-less id takes the distro's first discovered root), or null while the distro is
         *  stopped (callers treat null as "quiet": no liveness, no title, but restore still
         *  proceeds through the id-derived launcher — `wsl.exe` boots the distro). */
        fun byId(id: String?): SessionRoot? = when {
            id == null || id == Windows.ID -> Windows
            id.startsWith(WSL_PREFIX) -> splitWslId(id).let { (d, u) -> WslDiscovery.rootFor(d, u) }
            id.startsWith(FAKE_PREFIX) -> FakeRoots.rootFor(id.removePrefix(FAKE_PREFIX))
            else -> null
        }

        /** The resume command for a persisted root id — derivable even while a WSL distro is
         *  stopped (running it is exactly what boots the distro). A live root is preferred so a
         *  legacy user-less id still learns which user to launch as; a stopped distro falls back
         *  to the id's own segments. */
        fun resumeCommandFor(rootId: String?, sid: String): String = when {
            rootId == null || rootId == Windows.ID ->
                Windows.resumeCommand(sid)
            rootId.startsWith(WSL_PREFIX) -> {
                val (distro, user) = splitWslId(rootId)
                val live = byId(rootId) as? Wsl
                ClaudeTabsHelpers.buildWslResumeCommand(live?.distro ?: distro, sid, live?.user ?: user)
            }
            rootId.startsWith(FAKE_PREFIX) ->
                FakeRoots.rootFor(rootId.removePrefix(FAKE_PREFIX))?.resumeCommand(sid)
                    ?: Windows.resumeCommand(sid)
            else -> Windows.resumeCommand(sid)
        }
    }
}

/** E2E-only fake roots parsed once from `-Dclaudetabs.fakeRoots=<name>=<dir>[;…]`. Empty (and
 *  zero-cost) when the property is absent — i.e. always, outside the harness. */
internal object FakeRoots {
    val all: List<SessionRoot.Fake> by lazy {
        System.getProperty("claudetabs.fakeRoots")
            ?.split(';')
            ?.mapNotNull { spec ->
                val eq = spec.indexOf('=')
                if (eq <= 0) null
                else SessionRoot.Fake(spec.take(eq).trim(), File(spec.substring(eq + 1).trim()))
            }
            ?: emptyList()
    }

    fun rootFor(name: String): SessionRoot.Fake? = all.firstOrNull { it.name == name }
}

/**
 * Discovers WSL roots: for each RUNNING distro (via `wsl.exe -l -q --running` — listing running
 * distros never boots a stopped one, and a stopped distro has no live sessions to scan anyway),
 * one root per Linux user owning a `~/.claude/sessions` directory. Results are cached for
 * [CACHE_MS]; failures are logged once and yield no roots, so machines without WSL lose nothing.
 */
internal object WslDiscovery {
    private val LOG = Logger.getInstance(WslDiscovery::class.java)
    private const val CACHE_MS = 60_000L
    private const val LIST_TIMEOUT_S = 5L

    @Volatile private var cachedAt = 0L
    @Volatile private var cached: List<SessionRoot.Wsl> = emptyList()
    @Volatile private var failureLogged = false
    @Volatile private var unsafeLogged = false
    @Volatile private var lastLoggedRoots: List<String>? = null

    /** E2E-only kill-switch: the sandbox harness isolates the Windows home via
     *  `claudetabs.home` but real WSL distros would still be discovered and scanned —
     *  a real WSL claude in a matching cwd could contaminate a hermetic test run. */
    private val disabled = System.getProperty("claudetabs.disableWslDiscovery") == "true"

    fun runningRoots(): List<SessionRoot.Wsl> {
        if (disabled) return emptyList()
        val now = System.currentTimeMillis()
        if (now - cachedAt >= CACHE_MS) {
            cachedAt = now // set first so concurrent polls don't stampede wsl.exe
            cached = try {
                discover()
            } catch (e: Throwable) {
                if (!failureLogged) {
                    failureLogged = true
                    LOG.info("[ClaudeTabs][wsl] discovery unavailable (${e.message}) — Windows-only session tracking")
                }
                emptyList()
            }
            val ids = cached.map { it.id }
            if (ids != lastLoggedRoots) {
                lastLoggedRoots = ids
                LOG.info("[ClaudeTabs][wsl] session roots: ${if (ids.isEmpty()) "(none)" else ids.joinToString()}")
            }
        }
        return cached
    }

    /** The root for [distro] owned by [user]; a null [user] (legacy id) takes the distro's first
     *  discovered root — the pre-per-user behaviour, kept only for ids persisted back then. */
    fun rootFor(distro: String, user: String?): SessionRoot.Wsl? =
        runningRoots().firstOrNull { it.distro == distro && (user == null || it.user == user) }

    private fun discover(): List<SessionRoot.Wsl> {
        val exe = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\wsl.exe")
        if (!exe.exists()) return emptyList()
        val proc = ProcessBuilder(exe.absolutePath, "-l", "-q", "--running")
            .redirectErrorStream(true)
            .start()
        // waitFor BEFORE reading: `-l -q` output is tiny (never fills the pipe), and a hung
        // wsl.exe must not block the poll thread on a stream read with no timeout.
        if (!proc.waitFor(LIST_TIMEOUT_S, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return emptyList()
        }
        if (proc.exitValue() != 0) return emptyList()
        val distros = ClaudeTabsHelpers.parseWslDistroList(proc.inputStream.readBytes())
        return distros.filter { isSafeName("distro", it) }
            .flatMap { d -> claudeHomesIn(d).map { (user, home) -> SessionRoot.Wsl(d, user, home) } }
    }

    /**
     * Every (user, `~/.claude`) pair in the distro whose `sessions/` dir exists: `/home/<user>`
     * plus `/root`. A user with sessions outranks one without — an empty `sessions/` dir in an
     * unused home must not shadow the home actually running claude — so empty ones are returned
     * only when NO user in the distro has any.
     */
    private fun claudeHomesIn(distro: String): List<Pair<String, File>> = try {
        val base = File("\\\\wsl.localhost\\$distro")
        val homes = buildList {
            File(base, "home").listFiles { f -> f.isDirectory }?.forEach { add(it.name to it) }
            add("root" to File(base, "root"))
        }
        val candidates = homes
            .filter { (user, _) -> isSafeName("user", user) }
            .map { (user, home) -> user to File(home, ".claude") }
            .filter { (_, claudeHome) -> File(claudeHome, "sessions").isDirectory }
        candidates.filter { (_, claudeHome) -> File(claudeHome, "sessions").list()?.isNotEmpty() == true }
            .ifEmpty { candidates }
    } catch (_: Throwable) { emptyList() }

    /** Distro and user names are interpolated into a `wsl.exe` command line and into UNC paths,
     *  so a shell-active name is skipped rather than escaped (logged once — a machine with such a
     *  name would otherwise log it on every poll). */
    private fun isSafeName(kind: String, name: String): Boolean {
        if (ClaudeTabsHelpers.isSafeWslToken(name)) return true
        if (!unsafeLogged) {
            unsafeLogged = true
            LOG.info("[ClaudeTabs][wsl] skipping $kind with shell-unsafe characters in its name")
        }
        return false
    }
}
