# E2E driver: the pre/post-restart acceptance scenarios against a sandboxed IDE.
#
#   1. Prepares build/e2e: isolated .claude home, fake session root, stub CLI wrappers,
#      throwaway project. Nothing outside build/e2e (plus the IDE sandbox) is touched.
#   2. Launches the sandbox IDE (gradle runIdeForE2e) with the Robot Server.
#   3. Runs the requested PHASES, each in its own IDE launch against the SAME isolated state --
#      every pair is "do something, quit, relaunch, assert what came back":
#        1 -> 2  open 2 windows-root + 2 fake-root claude tabs, x one of each, then a hand-opened
#                tab that resumes a session without the id ever reaching the tab's command; then
#                assert restore, per-root resume commands, snapshot integrity, titles.
#        3 -> 4  open THREE fake-root tabs in ONE directory so the 1:1 adoption pass can bind none
#                of them, and assert the unbound arm of the snapshot keeps them anyway (the
#                data-loss incident: 13 of 17 open sessions dropped from restore), survives an
#                unobservable root, and evicts exactly the one whose tab the user x'd.
#
#   4. Or, with -Cycles, runs the MULTI-GENERATION scenario instead: N consecutive restarts
#      against one carried-over state (E2eCycleTest) -- see below.
#
# Each invocation WIPES build/e2e first, so a 3/4 run never inherits 1/2's sessions, ledger or
# snapshot (and vice versa). RUN ONE PAIR PER INVOCATION: the phases within an invocation
# deliberately share state (that IS the restart being tested), so `-Phases 1,2,3,4` would hand
# phase 3 phase 2's restored sessions and phase 4 would then find more in the snapshot than the
# two it is asserting about.
#
# -Cycles N is the multi-generation mode and is MUTUALLY EXCLUSIVE with -Phases. It wipes
# build/e2e once and then boots the IDE N+1 times against that one state, running E2eCycleTest in
# each launch: generation i asserts that exactly what generation i-1 left open came back, opens
# four more tabs, xs two of them plus one tab that was RESTORED this generation, and quits. The
# final launch is verify-only. Every timing -- the gaps between opens, the dwell before closing,
# the gap between the last x and the quit, and the gap between one IDE exiting and the next
# booting -- is drawn from -Seed, which is PRINTED so a failure replays exactly.
#
# Usage: powershell -ExecutionPolicy Bypass -File e2e\run-e2e.ps1            # phases 1,2
#        powershell -ExecutionPolicy Bypass -File e2e\run-e2e.ps1 -Phases 3,4
#        powershell -ExecutionPolicy Bypass -File e2e\run-e2e.ps1 -Cycles 3
#        powershell -ExecutionPolicy Bypass -File e2e\run-e2e.ps1 -Cycles 3 -Seed 12345
param([string[]]$Phases = @('1', '2'), [string]$Cycles = '', [string]$Seed = '')
$ErrorActionPreference = 'Stop'
# `powershell -File … -Phases 1,2` hands the script ONE string, "1,2" — only -Command splits a
# comma list into an array — so the phase set is normalised here: split each argument on commas,
# trim, cast — into a NEW, untyped variable: the parameter itself is [string[]]-constrained, and
# assigning ints back to it would silently coerce them to strings again.
$phaseList = @($Phases | ForEach-Object { "$_" -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ } | ForEach-Object { [int]$_ })
# -Cycles / -Seed arrive as STRINGS for the same reason (`-File` has no type coercion to offer),
# and for the same reason they are normalised into NEW, untyped variables — never assigned back to
# the [string] parameters, which would coerce the numbers straight back to text.
$cycleMode = -not [string]::IsNullOrWhiteSpace($Cycles)
if ($cycleMode -and $PSBoundParameters.ContainsKey('Phases')) {
    throw '-Cycles and -Phases are mutually exclusive: -Cycles runs the multi-generation restart scenario end to end.'
}
$cycleCount = 0
$seedValue = 0
if ($cycleMode) {
    $cycleCount = [int]("$Cycles".Trim())
    if ($cycleCount -lt 1) { throw "-Cycles must be at least 1 (got '$Cycles')" }
    if ([string]::IsNullOrWhiteSpace($Seed)) {
        # Unseeded runs still have to be REPLAYABLE, so a seed is always drawn and always printed.
        $seedValue = Get-Random -Minimum 1 -Maximum 2147483647
    } else {
        $seedValue = [int]("$Seed".Trim())
    }
}
$repo = Split-Path $PSScriptRoot -Parent
$e2e = Join-Path $repo 'build\e2e'
# The gradle wrapper takes its PROJECT DIRECTORY from the current directory, so a run started
# from anywhere else (a shell parked on another drive, a CI step, an editor's terminal) would
# otherwise fail with "does not contain a Gradle build" the moment a phase invokes it.
Set-Location $repo

function Stop-LeftoverSandbox {
    # A previous run's sandbox IDE may still hold the robot port (and its gradle wrapper the
    # build/e2e log handles); a fresh run must own both.
    $conn = Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($conn) {
        $p = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
        if ($p -and $p.ProcessName -like 'java*') {
            Write-Host "Stopping leftover sandbox IDE (pid $($p.Id))"
            Stop-Process -Id $p.Id -Force
            Start-Sleep -Seconds 5
        }
    }
    # A sandbox JVM that logged its own shutdown but never exited (field-observed once: it kept
    # the robot-server jar open and the next phase's prepareSandbox could not copy over it) no
    # longer owns the port, so it is matched by what it IS: the IDE main class on the e2e project.
    $hung = @(Get-CimInstance Win32_Process | Where-Object {
        $_.CommandLine -match 'com\.intellij\.idea\.Main' -and $_.CommandLine -match 'build\\e2e\\project'
    })
    foreach ($h in $hung) {
        Write-Host "Stopping hung sandbox IDE (pid $($h.ProcessId))"
        Stop-Process -Id $h.ProcessId -Force -ErrorAction SilentlyContinue
    }
    if ($hung.Count -gt 0) { Start-Sleep -Seconds 5 }
}

Write-Host '== E2E: preparing isolated state under build/e2e =='
Stop-LeftoverSandbox
if (Test-Path $e2e) {
    # The previous wrapper releases its log handles as the IDE task winds down -- retry briefly.
    $wiped = $false
    for ($i = 0; $i -lt 10 -and -not $wiped; $i++) {
        try { Remove-Item $e2e -Recurse -Force -ErrorAction Stop; $wiped = $true }
        catch { Start-Sleep -Seconds 3 }
    }
    if (-not $wiped) { throw "build/e2e still locked by a previous run -- close leftover gradle/IDE processes" }
}
# Belt for the whole-dir wipe above. The paths that DECIDE a phase's verdict -- the plugin's own
# state (active-sessions + the open-tabs snapshot), the fake root's session files and liveness
# markers, the stub argv log and the multi-generation handoff -- must be gone even if that wipe was
# only partial, or a phase-3 run would inherit phase 1/2's sessions and assert against them, and a
# cycle run would assert generation 1 against a previous run's expectations. No-op after a clean wipe.
foreach ($stale in @('claude-home\.claude\rider-plugin', 'fake-root\.claude\sessions',
                     'fake-root\.claude\proc', 'fake-root\.claude\sessions.off',
                     'fake-root\.claude\scan-throws', 'fake-root\.claude\scan-hang-ms',
                     'stub-argv.log', 'cycle-state.json')) {
    $p = Join-Path $e2e $stale
    if (Test-Path $p) { Remove-Item $p -Recurse -Force -ErrorAction SilentlyContinue }
}
# The sandbox IDE APPENDS to idea.log across runs, and the phases that assert on the plugin's
# diagnostics read that file. Dropping it here means everything in it belongs to THIS invocation.
$sandboxRoot = Join-Path $repo 'build\idea-sandbox'
if (Test-Path $sandboxRoot) {
    # Depth-bounded: the sandbox's plugins tree is large and the log lives two levels down
    # (<sandbox>/<ide>/log_<task>/idea.log).
    Get-ChildItem -Path $sandboxRoot -Recurse -Depth 3 -Filter 'idea.log*' -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue
}
foreach ($d in @('bin', 'claude-home\.claude\sessions', 'fake-root\.claude\sessions', 'fake-root\.claude\proc', 'project')) {
    New-Item -ItemType Directory -Force (Join-Path $e2e $d) | Out-Null
}
Set-Content (Join-Path $e2e 'project\readme.txt') 'e2e sandbox project' -Encoding utf8
New-Item -ItemType File -Force (Join-Path $e2e 'stub-argv.log') | Out-Null

# Stub wrappers. `claude` (windows flavor) inherits CLAUDE_STUB_HOME/LOG from the IDE env;
# `claude-fake` (the WSL-shaped foreign root) overrides them to the fake root's home.
$stub = Join-Path $repo 'e2e\stub\claude-stub.js'
# runtime\claude.exe = a renamed copy of node.exe, so the stub's OS-level process image matches
# the real CLI's shape (the scan's recycling guard reads the process command). It lives OUTSIDE
# the PATH dir: next to claude.cmd it would win PATHEXT resolution (.exe beats .cmd) and the
# shell would launch bare node without the stub script.
New-Item -ItemType Directory -Force (Join-Path $e2e 'runtime') | Out-Null
$node = (Get-Command node.exe).Source
Copy-Item $node (Join-Path $e2e 'runtime\claude.exe')
Set-Content (Join-Path $e2e 'bin\claude.cmd') @"
@echo off
set CLAUDE_STUB_FLAVOR=windows
"$e2e\runtime\claude.exe" "$stub" %*
"@ -Encoding ascii
# CLAUDE_STUB_TITLE: the fake-root tabs are HAND-OPENED (a plain shell tab running `claude-fake`),
# and the plugin refuses to bind a tab whose titles never stop looking like a plain shell. The real
# CLI always titles its host terminal, so the stub must too -- without it these tabs stay
# shell-classified and adoption (steps 3/4) can never complete.
Set-Content (Join-Path $e2e 'bin\claude-fake.cmd') @"
@echo off
set CLAUDE_STUB_FLAVOR=fake
set CLAUDE_STUB_TITLE=1
set CLAUDE_STUB_HOME=$e2e\fake-root\.claude
set CLAUDE_STUB_PROC_DIR=$e2e\fake-root\.claude\proc
set CLAUDE_STUB_TOPIC=Fake Topic One
"$node" "$stub" %*
"@ -Encoding ascii
# The fake root's resume template -- restore must run THIS, not the windows claude. Named by
# ABSOLUTE path like every other stub invocation: the sandbox shell's PATH is not trusted.
Set-Content (Join-Path $e2e 'fake-root\.claude\resume-command.txt') "$e2e\bin\claude-fake.cmd --resume {SID}" -Encoding ascii
# Per-TOPIC fake wrappers for the unbound-ledger phases. Two things the single claude-fake.cmd
# cannot give them:
#  - a DISTINCT title per tab. All three tabs live in ONE directory, so the phase can only x a
#    named tab if their titles differ; with one shared topic every tab reads the same and closing
#    "one of them" is a coin flip.
#  - CLAUDE_STUB_REGISTER_DELAY_MS. All three sessions must appear inside a single plugin poll, so
#    the 1:1 adoption pass meets three sessions AND three tabs and refuses. Without the delay the
#    first tab's session can be seen while the other two tabs' Contents do not exist yet -- it
#    binds 1:1, and the ambiguity the phases exist to reproduce never happens.
# Restore still runs claude-fake.cmd (resume-command.txt above), which carries no delay: a
# restored session must register promptly, and its tab is bound at birth anyway.
$fakeTopics = [ordered]@{ 'a' = 'Tab A'; 'b' = 'Tab B'; 'c' = 'Tab C' }
foreach ($k in $fakeTopics.Keys) {
    Set-Content (Join-Path $e2e "bin\claude-fake-$k.cmd") @"
@echo off
set CLAUDE_STUB_FLAVOR=fake
set CLAUDE_STUB_TITLE=1
set CLAUDE_STUB_HOME=$e2e\fake-root\.claude
set CLAUDE_STUB_PROC_DIR=$e2e\fake-root\.claude\proc
set CLAUDE_STUB_REGISTER_DELAY_MS=6000
set CLAUDE_STUB_TOPIC=$($fakeTopics[$k])
"$node" "$stub" %*
"@ -Encoding ascii
}
# The same wrapper GENERALISED, for the multi-generation scenario: it takes the topic and the
# register delay as ARGUMENTS instead of baking them in. E2eCycleTest opens four new tabs per
# generation across an arbitrary number of generations, each needing its own topic (`C<i>-T<n>`) so
# the session running in a given tab can be named — a fixed a/b/c set cannot cover that, and one
# generated wrapper per tab would leave a growing pile of near-identical scripts behind.
# It forwards NO argv to the stub: these tabs always start a FRESH session (the stub mints its own
# id), and `%*` in cmd is immune to `shift`, so there is no way to hand on "the rest" of the
# arguments cleanly anyway. Restore still runs claude-fake.cmd via resume-command.txt.
Set-Content (Join-Path $e2e 'bin\claude-fake-topic.cmd') @"
@echo off
setlocal
set CLAUDE_STUB_FLAVOR=fake
set CLAUDE_STUB_TITLE=1
set CLAUDE_STUB_HOME=$e2e\fake-root\.claude
set CLAUDE_STUB_PROC_DIR=$e2e\fake-root\.claude\proc
set CLAUDE_STUB_TOPIC=%~1
set CLAUDE_STUB_REGISTER_DELAY_MS=%~2
"$node" "$stub"
"@ -Encoding ascii
# claude-hand.cmd = the HAND-OPEN launcher: it reads the session id from a file the test writes
# instead of taking it on the command line, so the tab's launch command carries NO session id --
# exactly what the IDE sees when a user types `claude --resume <sid>` into a shell tab themselves
# (the argv lives inside the shell, not in the tab's recorded command). CLAUDE_STUB_TITLE makes
# the stub wear claude's real title shape (`claude`, then the glyph+topic) so the tab starts out
# looking like a plain shell and only later announces itself.
# The test writes hand-sid.txt with a trailing CRLF (an unterminated last line is what a torn
# concurrent write looks like); `set /p` reads the first line and strips the CR/LF, so %HANDSID%
# carries the bare id with no stray carriage return.
Set-Content (Join-Path $e2e 'bin\claude-hand.cmd') @"
@echo off
setlocal
set CLAUDE_STUB_FLAVOR=windows
set CLAUDE_STUB_TITLE=1
set CLAUDE_STUB_TOPIC=Hand Topic
set /p HANDSID=<"$e2e\hand-sid.txt"
"$e2e\runtime\claude.exe" "$stub" --resume %HANDSID%
"@ -Encoding ascii

# Diagnostic: the IDE's own powershell shell-integration script enumerates env vars with a
# wildcard (`Get-ChildItem env:_INTELLIJ_FORCE_PREPEND_*`) and has been seen to throw
# ArgumentException in the sandbox, which swallows every PATH prepend it was supposed to apply.
# The usual culprit is a variable whose NAME is not a legal wildcard-path segment. Record the
# oddly-named ones we hand down so the cause can be identified from a failed run's artifacts.
$oddNames = @()
foreach ($e in [System.Environment]::GetEnvironmentVariables().Keys) {
    if ($e -notmatch '^[A-Za-z0-9_()]+$') { $oddNames += $e }
}
Set-Content (Join-Path $e2e 'env-names.txt') ($oddNames -join "`n") -Encoding utf8
if ($oddNames.Count -gt 0) { Write-Host "Env vars with unusual names (see build/e2e/env-names.txt): $($oddNames -join ', ')" }

function Start-Ide([switch]$Unbound) {
    # -PclaudetabsE2eUnbound=true is what makes the build hand the sandbox the unbound-ledger
    # tuning (admit/attribution in seconds instead of tens of seconds). Phases 1/2 must NOT get it:
    # their scenario is timed against the shipped defaults.
    $gradleArgs = @('runIdeForE2e', '--console=plain')
    if ($Unbound) { $gradleArgs += '-PclaudetabsE2eUnbound=true' }
    # A stub the IDE kills at quit never runs its signal handler, so its proc/<pid> liveness
    # marker outlives it into the next launch — where the restore guard would read the session
    # as still running and refuse to reopen it. Every launch starts from an empty proc/: by
    # definition nothing from the previous IDE process is alive.
    $procDir = Join-Path $e2e 'fake-root\.claude\proc'
    if (Test-Path $procDir) { Remove-Item -Recurse -Force $procDir }
    # The sandbox IDE's exit does not always take its terminals' children with it, and a stub
    # that outlives it is exactly a "session still running elsewhere" to the next IDE — which
    # rightly refuses to resume it. Between launches that is the harness's mess, not the
    # plugin's behaviour under test: the stubs belong to this run and are put down here.
    # The stub script lives under e2e\stub (NOT build\e2e), so the match is on the script alone:
    # every claude-stub.js on this machine belongs to this harness.
    Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'claude-stub\.js' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    # Quitting with a live terminal process raises the platform's "Process ... Is Running —
    # terminate?" modal (General > On closing tool window with running process: Ask). A modal
    # blocks the EDT and every robot call, so a phase's exit would wait on a human click. The
    # sandbox is seeded with the answer a user gives once via "Don't ask again": Terminate.
    $optDir = Join-Path $repo 'build\idea-sandbox\IU-2026.1\config_runIdeForE2e\options'
    New-Item -ItemType Directory -Force -Path $optDir | Out-Null
    $general = Join-Path $optDir 'ide.general.xml'
    if (-not (Test-Path $general) -or -not (Select-String -Path $general -Pattern 'processCloseConfirmation' -Quiet)) {
        @'
<application>
  <component name="GeneralSettings">
    <option name="processCloseConfirmation" value="TERMINATE" />
  </component>
</application>
'@ | Set-Content -Path $general -Encoding UTF8
    }
    Start-Process -FilePath (Join-Path $repo 'gradlew.bat') `
        -ArgumentList $gradleArgs `
        -WorkingDirectory $repo -PassThru `
        -RedirectStandardOutput (Join-Path $e2e 'ide-out.log') `
        -RedirectStandardError (Join-Path $e2e 'ide-err.log')
}
function Wait-RobotUp($ideProc, $timeoutSec = 600) {
    # Decouple IDE boot time (gradle re-init + cold start can take minutes) from the test
    # phase's own timeouts: only start a phase once the robot endpoint answers.
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ($ideProc.HasExited) { throw "sandbox IDE exited during boot (see build/e2e/ide-*.log)" }
        try {
            $r = Invoke-WebRequest -Uri 'http://127.0.0.1:8082/hello' -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -eq 200) { Write-Host 'Robot server is up.'; return }
        } catch { }
        Start-Sleep -Seconds 3
    }
    throw "robot server did not come up within $timeoutSec s"
}
function Run-Phase($testClass, $extraArgs = @()) {
    # $extraArgs carries the cycle wiring (-PclaudetabsE2eCycle/-Cycles/-Seed) through to the
    # uiTest task, which turns those project properties into system properties for the test JVM.
    # Empty for every phase run, which is then byte-for-byte the invocation it always was.
    & (Join-Path $repo 'gradlew.bat') uiTest --tests $testClass --console=plain @extraArgs
    if ($LASTEXITCODE -ne 0) { throw "E2E phase failed: $testClass (see build/reports/tests/uiTest)" }
}

# Every phase: its test class, the banner, and whether its sandbox needs the unbound-ledger
# tuning. Each phase runs in its OWN IDE launch -- the phase itself quits the IDE, and the next
# one comes up against the state it left behind.
$phaseSpecs = @{
    1 = @{ Class = 'com.claudetabs.ui.E2ePhase1Test'; Banner = 'Phase 1: open / x / hand-open adopt / quit'; Unbound = $false }
    2 = @{ Class = 'com.claudetabs.ui.E2ePhase2Test'; Banner = 'Phase 2: relaunch -- per-root restore'; Unbound = $false }
    3 = @{ Class = 'com.claudetabs.ui.E2ePhase3Test'; Banner = 'Phase 3: three unbindable tabs -- unbound ledger holds them'; Unbound = $true }
    4 = @{ Class = 'com.claudetabs.ui.E2ePhase4Test'; Banner = 'Phase 4: relaunch -- restore from the unbound ledger; outside recovery; the root dies'; Unbound = $true }
    5 = @{ Class = 'com.claudetabs.ui.E2ePhase5Test'; Banner = 'Phase 5: relaunch -- restore everything the outage took down'; Unbound = $true }
    6 = @{ Class = 'com.claudetabs.ui.E2eCloseProbeTest'; Banner = 'Phase 6: close probe -- x under a live shell vs a tab that closed itself'; Unbound = $false }
}
if (-not $cycleMode) {
    foreach ($p in $phaseList) { if (-not $phaseSpecs.ContainsKey($p)) { throw "unknown phase: $p (known: 1,2,3,4,5,6)" } }
}

# One outer try/finally: whatever fails, no sandbox IDE is left holding the robot port (and the
# stub CLIs it spawned) behind us. It wraps BOTH modes.
try {

if ($cycleMode) {

# MULTI-GENERATION MODE. N generations plus one verify-only relaunch, all against the single
# build/e2e state the preparation above just created -- it is NOT re-wiped between generations:
# carrying one state across every restart IS the scenario.
$cycleTotal = $cycleCount + 1
Write-Host ''
Write-Host "== E2E cycles: $cycleCount generation(s) + a verify-only relaunch, seed $seedValue =="
Write-Host "   replay this exact run with: -Cycles $cycleCount -Seed $seedValue"
# The driver's own draws (the relaunch gaps) come from their own Random seeded the same way. The
# test JVMs draw from the same seed but in their own processes and their own streams, so neither
# side can perturb the other's sequence.
$gapRandom = New-Object System.Random($seedValue)
# .NET's subtractive generator correlates its first outputs with the seed, so a bare
# New-Object Random(<seed>) hands out a run of near-identical "random" gaps. Burn the warm-up.
for ($w = 0; $w -lt 32; $w++) { [void]$gapRandom.Next() }
for ($i = 1; $i -le $cycleTotal; $i++) {
    if ($i -le $cycleCount) {
        $banner = "Cycle $i/${cycleTotal}: assert restore, open 4 tabs, x 2 new + 1 restored, quit"
    } else {
        $banner = "Cycle $i/${cycleTotal}: VERIFY-ONLY relaunch -- assert the final restore and quit"
    }
    Write-Host ''
    Write-Host "== $banner (seed $seedValue) =="
    Stop-LeftoverSandbox
    # Every generation gets the unbound-ledger tuning: the scenario hand-opens tabs that must stay
    # unbound, and E2eCycleTest asserts the tuning is present rather than silently timing itself
    # against the shipped defaults.
    $ide = Start-Ide -Unbound
    try {
        Wait-RobotUp $ide
        Run-Phase 'com.claudetabs.ui.E2eCycleTest' @(
            "-PclaudetabsE2eCycle=$i",
            "-PclaudetabsE2eCycles=$cycleCount",
            "-PclaudetabsE2eSeed=$seedValue"
        )
    } catch {
        # A bare "E2E phase failed" here would not say WHICH of N restarts broke, nor how to get
        # back to it -- and an unseeded run is otherwise unreproducible.
        throw "E2E CYCLE $i of $cycleTotal FAILED (seed $seedValue) -- replay with: -Cycles $cycleCount -Seed $seedValue`n  $($_.Exception.Message)"
    } finally {
        # Every generation exits the IDE itself; give the gradle task a moment to wind down either way.
        Wait-Process -Id $ide.Id -Timeout 180 -ErrorAction SilentlyContinue
        if (-not $ide.HasExited) { Stop-Process -Id $ide.Id -Force -ErrorAction SilentlyContinue }
    }
    if ($i -lt $cycleTotal) {
        # The relaunch gap is part of what is under test: a snapshot that only survives a leisurely
        # restart is not a snapshot that survives the user reopening the IDE straight away, and a
        # long gap gives the previous generation's dying stub processes time the short one does not.
        $gapMs = $gapRandom.Next(0, 10001)
        Write-Host "   relaunch gap before cycle $($i + 1): $gapMs ms (seed $seedValue)"
        Start-Sleep -Milliseconds $gapMs
    }
}

Write-Host ''
Write-Host "== E2E PASSED: $cycleCount cycle(s) + verify-only relaunch, seed $seedValue =="

} else {

foreach ($p in $phaseList) {
    $spec = $phaseSpecs[$p]
    Write-Host "== $($spec.Banner) =="
    Stop-LeftoverSandbox
    $ide = Start-Ide -Unbound:$spec.Unbound
    try {
        Wait-RobotUp $ide
        Run-Phase $spec.Class
    } finally {
        # Every phase exits the IDE itself; give the gradle task a moment to wind down either way.
        Wait-Process -Id $ide.Id -Timeout 180 -ErrorAction SilentlyContinue
        if (-not $ide.HasExited) { Stop-Process -Id $ide.Id -Force -ErrorAction SilentlyContinue }
        # The IDE JVM can outlive its own shutdown (the robot server's Ktor hook has been seen
        # waiting forever); the next phase's sandbox preparation cannot copy over the jars it
        # holds. Put it down here, not just at the next launch.
        Stop-LeftoverSandbox
    }
}

Write-Host ''
Write-Host "== E2E PASSED: phases $($phaseList -join ', ') =="

}

} finally {
    Stop-LeftoverSandbox
}
