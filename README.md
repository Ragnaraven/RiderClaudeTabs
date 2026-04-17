# Claude Terminal Tab Namer

A JetBrains Rider / IntelliJ plugin that names terminal tabs to match the [Claude Code](https://claude.com/claude-code) conversation running inside them — so you can juggle multiple Claude sessions without losing track of which tab is which.

---

## Features

### Tab naming that actually works with multiple Claude sessions
- **Race-condition free** — each tab is identified via JetBrains' `TERM_SESSION_ID` env var, mapped to the Claude session ID at session start. No shared queues, no guessing, no cross-wired tabs when you `/tab` two conversations at the same time.
- **Manual rename priority** — if you rename a tab yourself in the Rider UI, the plugin detects it and backs off. Your name wins.
- **Instant** — a file watcher picks up renames immediately; no polling delay.

### Session restore across IDE restarts
- Every named tab is saved to `~/.claude/rider-plugin/restore-<project>.json` as you work.
- On restart, the plugin finds your tabs and runs `claude --resume <id>` in each one — no duplicate tabs, no retyping.
- `--dangerously-skip-permissions` is preserved per session (so bypass-mode tabs come back the same way).

### Crash resilience
- Every successful save also writes a timestamped snapshot to `~/.claude/rider-plugin/snapshots/`.
- If Rider crashes or the live restore file is wiped, startup falls back through snapshots (newest first) so your last known-good state is preserved.
- Retention (N snapshots per project) is configurable.

### Long-term session history
- Closed sessions are auto-appended to `~/.claude/rider-plugin/history.json` (last 90 days by default).
- Browse and resume any past session with `/tabs-history`.

### Zero-config install
- Drops all integration files (`rename-tab.sh`, `session-start-hook.sh`, slash commands, CLAUDE.md section, permission entry in `settings.json`) on first startup.
- Updates them automatically when you update the plugin.
- Clean uninstall — removes every deployed artifact when you remove the plugin.

---

## Slash commands

These are installed automatically into `~/.claude/commands/`:

| Command | What it does |
|---|---|
| `/tab <name>` | Renames the current tab **and** snapshots it to history. No name = pick one from conversation context. |
| `/tabs-status` | Report of every active Claude session, grouped by project. |
| `/tabs-backup` | Manually snapshot currently-active sessions into history (checkpoint without closing). |
| `/tabs-history` | Numbered list of past sessions (newest first). Pick one to resume via `claude --resume`. |
| `/tabs-restore` | Show currently-saved sessions from the restore files (what would auto-restore next Rider start). |
| `/tabs-clear` | Clear the tab rename cache and restore files. |

All commands use **Node.js** (which ships with Claude Code) — no Python dependency.

---

## Installation

### From the JetBrains Marketplace
**Settings → Plugins → Marketplace → search "Claude Terminal Tab Namer" → Install → restart Rider.**

That's it. The plugin auto-installs all integration files on first project open.

### From source
Requires JDK 17 and the Gradle wrapper (included).

```bash
./gradlew buildPlugin
```

Then **Settings → Plugins → gear icon → Install Plugin from Disk...** → pick `build/distributions/rider-claude-tabs-1.0.0.zip`.

---

## Configuration

Optional. The plugin creates `~/.claude/rider-plugin/config.json` with defaults on first run:

```json
{
  "historyMaxAgeDays": 90,
  "snapshotKeepCount": 10
}
```

- `historyMaxAgeDays` — how long closed sessions stay in `/tabs-history`.
- `snapshotKeepCount` — how many restore snapshots to retain per project. Set to `0` to disable snapshots.

Edit and restart Rider to apply.

---

## How it works

### Tab naming flow

```
SessionStart hook              rename-tab.sh                plugin
─────────────────              ─────────────                ──────
TERM_SESSION_ID + sessId  →    TERM_SESSION_ID lookup  →    file watcher sees
write session-map/<UUID>       finds sessId                 {sessId}.json
                               writes {sessId}.json   →     matches tab by
                                                            Claude process tree
                                                            renames via terminal API
```

Each terminal tab has a unique, stable `TERM_SESSION_ID` injected by JetBrains. The `SessionStart` hook maps that to the Claude session ID in a per-tab file. When `rename-tab.sh` runs inside a specific tab, it reads the right session ID from its own map file — no shared state, no collisions.

### Restore flow

```
shutdown                    startup
────────                    ───────
poll() saves state      →   loadRestoreFile() loads sessions into memory
  → restore-<proj>.json       → fall back to newest snapshot if live file is empty
  + snapshot to              → processPendingRestores() matches saved names
    snapshots/<proj>-<ts>       to the shell tabs JetBrains restored
                             → sendText("claude --resume <id>") into each one
                             → saveState() re-writes restore file with live sessions
```

### Files the plugin writes
All under `~/.claude/rider-plugin/`:

| Path | Purpose |
|---|---|
| `rename-tab.sh`, `session-start-hook.sh` | Shell integration (auto-deployed) |
| `tabs/{sessionId}.json` | Rename directives from scripts → plugin |
| `session-map/{TERM_SESSION_ID}` | Per-tab map to Claude session ID |
| `restore-<project>.json` | Current named-tab state (auto-restore target) |
| `snapshots/<project>-<ts>.json` | Rolling snapshots of the restore file |
| `history.json` | Closed/backed-up sessions (90d default) |
| `config.json` | User-overridable retention settings |

---

## Compatibility

- **JetBrains Rider 2024.3+** (build 243, tested on 2026.1)
- **JetBrains IntelliJ IDEA 2024.3+**
- **Windows** (primary) — full process-tree detection via `tasklist`
- **macOS / Linux** — shell scripts use `kill -0` fallback for liveness; main rename path works identically since it's Claude Code/Node-driven
- Supports both the **reworked terminal API** (2024.3+) and the **classic terminal widget** (older). Graceful fallback when one is unavailable.

---

## Uninstall

Plugin uninstall removes everything the plugin installed:
- CLAUDE.md section between plugin markers
- Permission entry from `settings.json`
- All files under `~/.claude/rider-plugin/`
- All `/tab` and `/tabs-*` command files

Manually: **Settings → Plugins → find "Claude Terminal Tab Namer" → Uninstall → restart.**

---

## License

[MPL-2.0](LICENSE). Free for personal and commercial use; source modifications must remain MPL-2.0.

## Contributing / Issues

File an issue or PR at [github.com/Ragnaraven/RiderClaudeTabs](https://github.com/Ragnaraven/RiderClaudeTabs).
