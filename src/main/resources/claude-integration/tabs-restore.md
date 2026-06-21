List the Claude sessions in `~/.claude/rider-plugin/active-sessions/` and print a `claude --resume` command for each. Useful when Rider restored a session-less terminal and you want to resume it manually.

By default this only lists sessions whose cwd belongs to the project you're currently in. Pass `--all` for every session across every project.

## Steps

1. Resolve the current project (unless `--all` is in `$ARGUMENTS`):
   ```bash
   node ~/.claude/rider-plugin/current-project.js
   ```
   Capture `root` (the project base path) and `name` for filtering and display.

2. List the per-sid files. Use the **Glob** tool with pattern `**/active-sessions/*.json` under `~/.claude/rider-plugin/` — do NOT shell out. Read each file with the **Read** tool. Each entry is `{ "sid": "...", "cwd": "...", "pid": ..., "lastSeen": ..., "name": ... }`.

3. Filter (unless `--all`): keep entries whose `cwd`, after normalising (replace `\` with `/`, lowercase, strip trailing `/`), is `root`, starts with `root + "/"`, or starts with `root + "-"` (sibling worktrees like `MyApp-feature`).

4. If empty: tell the user *"No active Claude sessions for `<name>`. Run `/tabs-restore --all` to see other projects' sessions, or `/tabs-history` to see recently-evicted ones."* and stop.

5. Render as a markdown table, one row per session:

   ```markdown
   | # | Name | Session    | cwd                       |
   |--:|:--------------------|:-----------|:--------------------------|
   | 1 | **Fix Auth Flow**   | `aa11bb22` | `D:\Dev\MyApp`          |
   | 2 | —                   | `cc33dd44` | `D:\Dev\MyApp`          |
   ```

6. After the table, print one combined block listing every resume command, one per line, for easy copy-paste:

   ```
   claude --resume aa11bb22-...
   claude --resume cc33dd44-...
   ```

7. Tell the user: *"Paste any command into a fresh terminal tab to resume that session. The plugin will pick it up on the next poll. (Nested `--resume` from inside an active Claude session always fails.)"*
