# E2E driver: the pre/post-restart acceptance scenario against a sandboxed IDE.
#
#   1. Prepares build/e2e: isolated .claude home, fake session root, stub CLI wrappers,
#      throwaway project. Nothing outside build/e2e (plus the IDE sandbox) is touched.
#   2. Launches the sandbox IDE (gradle runIdeForE2e) with the Robot Server.
#   3. Runs Phase 1 (open 2 windows-root + 2 fake-root claude tabs, x one of each, then a
#      hand-opened tab that resumes a session without the id ever reaching the tab's command).
#   4. Relaunches the same sandbox and runs Phase 2 (asserts restore, per-root resume
#      commands, snapshot integrity, titles).
#
# Usage: powershell -ExecutionPolicy Bypass -File e2e\run-e2e.ps1
$ErrorActionPreference = 'Stop'
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

function Start-Ide {
    Start-Process -FilePath (Join-Path $repo 'gradlew.bat') `
        -ArgumentList 'runIdeForE2e', '--console=plain' `
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
function Run-Phase($testClass) {
    & (Join-Path $repo 'gradlew.bat') uiTest --tests $testClass --console=plain
    if ($LASTEXITCODE -ne 0) { throw "E2E phase failed: $testClass (see build/reports/tests/uiTest)" }
}

# One outer try/finally: whatever fails, no sandbox IDE is left holding the robot port (and the
# stub CLIs it spawned) behind us.
try {

Write-Host '== Phase 1: launching sandbox IDE =='
Stop-LeftoverSandbox
$ide = Start-Ide
try {
    Wait-RobotUp $ide
    Run-Phase 'com.claudetabs.ui.E2ePhase1Test'
} finally {
    # Phase 1 exits the IDE itself; give the gradle task a moment to wind down either way.
    Wait-Process -Id $ide.Id -Timeout 180 -ErrorAction SilentlyContinue
    if (-not $ide.HasExited) { Stop-Process -Id $ide.Id -Force -ErrorAction SilentlyContinue }
}

Write-Host '== Phase 2: relaunching sandbox IDE (restore) =='
Stop-LeftoverSandbox
$ide2 = Start-Ide
try {
    Wait-RobotUp $ide2
    Run-Phase 'com.claudetabs.ui.E2ePhase2Test'
} finally {
    Wait-Process -Id $ide2.Id -Timeout 180 -ErrorAction SilentlyContinue
    if (-not $ide2.HasExited) { Stop-Process -Id $ide2.Id -Force -ErrorAction SilentlyContinue }
}

Write-Host ''
Write-Host '== E2E PASSED: open -> x -> hand-open adopt -> quit -> restart -> per-root restore =='

} finally {
    Stop-LeftoverSandbox
}
