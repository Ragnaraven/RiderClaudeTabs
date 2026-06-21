Clear the plugin's `active-sessions/` tracking for the current project (or all projects with `--all`). Does NOT touch `session-backlog.json` — the eviction history is preserved so you can still `/tabs-history` to recover something later.

## Steps

1. Decide scope:
   - If `$ARGUMENTS` contains `--all` (case-insensitive), wipe everything:
     ```bash
     rm -f ~/.claude/rider-plugin/active-sessions/*.json && echo "Cleared all active-session tracking (backlog preserved)"
     ```
     Stop here.

2. Otherwise, resolve the current project:
   ```bash
   node ~/.claude/rider-plugin/current-project.js
   ```
   Capture `root` and `name`.

3. Use the **Glob** tool to list `~/.claude/rider-plugin/active-sessions/*.json`. **Read** each, parse `{ sid, cwd }`. Keep ones whose `cwd`, after normalising, is `root`, starts with `root + "/"`, or starts with `root + "-"` (sibling worktrees).

4. Delete the matching files with the **Bash** tool (one rm command per match, or grouped — both fine):
   ```bash
   rm -f ~/.claude/rider-plugin/active-sessions/<sid1>.json ~/.claude/rider-plugin/active-sessions/<sid2>.json
   ```

5. Report: `Cleared N session(s) for <name>. Backlog preserved (use /tabs-history to recover).`

6. One-line reminder: *"The next poll (~5s) will re-create per-sid files for sessions still alive. To truly stop auto-restore for a session, close its terminal tab in Rider — that records a persistent user-closed entry."*
