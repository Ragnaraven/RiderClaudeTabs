package com.claudetabs

import com.claudetabs.SessionsDirScanner.ProcessInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SessionsDirScanner.looksLikeClaude] — the PID-recycling guard used by
 * [ClaudeTabWatcherStartup.pollOnce]. A PID with a `sessions/<pid>.json` file might
 * have been Claude when it was written, but could have been recycled by the OS to an
 * unrelated process by the time we look. We refuse to treat anything but a Claude-
 * shaped process as alive.
 */
class SessionsDirScannerTest {

    @Test fun unixClaudeBinary_matches() {
        assertTrue(SessionsDirScanner.looksLikeClaude(ProcessInfo("/usr/local/bin/claude", "/usr/local/bin/claude")))
    }

    @Test fun windowsClaudeExe_matches() {
        assertTrue(SessionsDirScanner.looksLikeClaude(ProcessInfo(
            "C:\\Users\\me\\AppData\\Roaming\\npm\\claude.exe", "claude.exe"
        )))
    }

    @Test fun windowsClaudeCmd_matches() {
        assertTrue(SessionsDirScanner.looksLikeClaude(ProcessInfo(
            "C:\\Users\\me\\AppData\\Roaming\\npm\\claude.cmd", "claude.cmd"
        )))
    }

    @Test fun nodeWrapperWithAnthropic_matches() {
        assertTrue(SessionsDirScanner.looksLikeClaude(ProcessInfo(
            "/usr/bin/node", "/usr/bin/node /opt/@anthropic-ai/claude-code/cli.mjs"
        )))
    }

    @Test fun nodeWrapperWithClaudeCode_matches() {
        assertTrue(SessionsDirScanner.looksLikeClaude(ProcessInfo(
            "/usr/bin/node", "/usr/bin/node /usr/lib/claude-code/cli.mjs"
        )))
    }

    @Test fun nodeWrapperWithClaudeInCmdLine_matches() {
        assertTrue(SessionsDirScanner.looksLikeClaude(ProcessInfo(
            "/usr/local/bin/node", "/usr/local/bin/node /opt/claude/bin/claude"
        )))
    }

    @Test fun bashRejected() {
        assertFalse(SessionsDirScanner.looksLikeClaude(ProcessInfo("/usr/bin/bash", "/usr/bin/bash")))
    }

    @Test fun chromeRejected() {
        assertFalse(SessionsDirScanner.looksLikeClaude(ProcessInfo(
            "C:\\Program Files\\Chrome\\chrome.exe", "chrome.exe --type=renderer"
        )))
    }

    @Test fun nodeWithoutClaudeInCmdLineRejected() {
        assertFalse(SessionsDirScanner.looksLikeClaude(ProcessInfo("/usr/bin/node", "/usr/bin/node server.js")))
    }

    @Test fun explorerRejected() {
        assertFalse(SessionsDirScanner.looksLikeClaude(ProcessInfo(
            "C:\\Windows\\explorer.exe", "explorer.exe"
        )))
    }
}
