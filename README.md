# Claude Terminal Tab Namer

A JetBrains Rider/IntelliJ plugin that auto-renames terminal tabs running [Claude Code](https://claude.ai/claude-code) and restores sessions across IDE restarts.

## Features

- **Tab Renaming** — Claude Code names its own terminal tab via a simple bash command. The plugin detects the rename request and updates the tab title.
- **Session Restore** — When Rider closes, active Claude sessions are saved. On restart, the plugin claims the existing terminal tab and runs `claude --resume` to pick up where you left off.
- **Permission Preservation** — `--dangerously-skip-permissions` is automatically included on restore if your Claude settings have `skipDangerousModePermissionPrompt` enabled.

## How It Works

### Tab Naming

Claude Code writes a rename request file:

```bash
bash ~/.claude/rider-plugin/rename-tab.sh "My Topic Name"
```

The plugin watches `~/.claude/rider-plugin/tabs/` for these files, matches the session ID to a terminal tab (via process tree detection), and renames it.

### Session Restore

1. While running, the plugin saves active Claude sessions to `~/.claude/rider-plugin/restore-{project}.json`
2. On IDE restart, Rider reopens empty terminal tabs with the same names
3. The plugin claims those tabs by writing `claude --resume {sessionId}` directly into their shell
4. No duplicate tabs — the existing tab is reused

### Claude Code Integration

Add this to your global `~/.claude/CLAUDE.md`:

```markdown
## Terminal Tab Naming (Rider Plugin)
At the **start of every conversation**, rename your Rider terminal tab by running:
\```bash
bash ~/.claude/rider-plugin/rename-tab.sh "Short Topic Name"
\```
Pick a concise name (3-5 words) that describes the conversation's purpose.
Update it if the topic shifts significantly.
```

## Installation

### Build from Source

Requires JDK 17+ and Gradle (wrapper included).

```bash
cd rider-claude-tabs
./gradlew buildPlugin
```

Output: `build/distributions/rider-claude-tabs-1.0.0.zip`

### Install in Rider

1. **Settings** → **Plugins** → gear icon → **Install Plugin from Disk...**
2. Select the ZIP file
3. Restart Rider

### Setup

1. Copy `rename-tab.sh` to `~/.claude/rider-plugin/rename-tab.sh`
2. Add the Claude Code integration snippet to `~/.claude/CLAUDE.md`

## Compatibility

- JetBrains Rider 2024.3+ (tested on 2026.1)
- JetBrains IntelliJ IDEA 2024.3+
- Windows (primary), macOS/Linux (should work but untested)
- Uses the Reworked Terminal API (2025.2+) with legacy fallback

## Architecture

```
Claude Code                          Rider Plugin
───────────                          ────────────
bash rename-tab.sh "Topic"    →    watches ~/.claude/rider-plugin/tabs/
  writes {sessionId}.json            reads rename request
                                     matches sessionId → Claude PID → shell PID → tab
                                     renames tab via ContentManager

IDE shutdown                   →    saves active sessions to restore file

IDE startup                    →    reads restore file
                                     finds stale tab by name
                                     writes "claude --resume {id}" into its tty
                                     session continues in same tab
```

### Key Implementation Details

- **Process detection**: Walks the terminal shell's child process tree to find Claude Code (`node` running `claude`)
- **PID extraction**: Reflects into `StateAwareTerminalSession → delegate → BackendTerminalSessionImpl → ttyConnector → ProcessTtyConnector → Process.pid()`
- **Rename**: Sets both `Content.displayName` (visual) and calls `TerminalTabsManager.renameTerminalTab()` (internal state) via reflection
- **Suspend functions**: JetBrains' reworked terminal API uses Kotlin coroutines; invoked via `runBlocking` + manual `Continuation`

## License

MIT
