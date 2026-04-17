# Claude Terminal Tab Namer

A JetBrains Rider / IntelliJ plugin that names terminal tabs to match the [Claude Code](https://claude.com/claude-code) conversation running inside them.

## What it does

- Renames terminal tabs via a slash command or auto-naming.
- Saves your tabs when Rider closes and restores them on reopen.
- Keeps a history of past sessions you can resume later.

## Install

**Settings → Plugins → Marketplace → search "Claude Terminal Tab Namer" → Install → restart.**

Everything else (scripts, commands, CLAUDE.md section, permissions) is set up on first start.

## Slash commands

```
/tab My Topic         Rename this tab, snapshot to history
/tabs-status          Show all active sessions
/tabs-backup          Snapshot current sessions to history
/tabs-history         List past sessions, resume any
/tabs-restore         Show what will auto-restore on next start
/tabs-clear           Clear the rename cache
```

## Config

Optional. `~/.claude/rider-plugin/config.json`:

```json
{
  "historyMaxAgeDays": 90,
  "snapshotKeepCount": 10
}
```

Restart Rider after editing.

## Compatibility

- Rider / IntelliJ 2024.3+
- Windows primary. macOS / Linux should work but less tested.
- Requires Claude Code CLI (provides the `node` runtime the plugin's scripts use).

## Files it writes

All under `~/.claude/rider-plugin/`:

```
rename-tab.sh, session-start-hook.sh   # shell integration
tabs/{sessionId}.json                  # rename requests
session-map/{TERM_SESSION_ID}          # per-tab session mapping
restore-<project>.json                 # current state (auto-restore target)
snapshots/<project>-<timestamp>.json   # rolling backups
history.json                           # closed sessions (90d default)
config.json                            # user overrides
```

## Uninstall

Plugins page → Uninstall → restart. All deployed files are removed.

## License

[MPL-2.0](LICENSE)

## Issues / PRs

[github.com/Ragnaraven/RiderClaudeTabs](https://github.com/Ragnaraven/RiderClaudeTabs)
