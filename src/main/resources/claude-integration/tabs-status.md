Status report of currently-tracked Claude sessions (live in `active-sessions/`, plus the most recent eviction backlog entries).

By default this is scoped to the **current project**. Pass `--all` for everything across every project.

**Do NOT shell out to Node for reading** — read the JSON files yourself with **Read** and **Glob** for minimal terminal noise. The one helper invocation is the project resolver.

## Steps

1. Resolve the current project (unless `--all` is in `$ARGUMENTS`):
   ```bash
   node ~/.claude/rider-plugin/current-project.js
   ```
   Capture `root` and `name`.

2. **Live section** — use the **Glob** tool to find every `~/.claude/rider-plugin/active-sessions/*.json`. Read each with **Read**. Each is `{ sid, cwd, pid, lastSeen, name }`.

   Filter (unless `--all`): keep entries whose `cwd`, after normalising (`\` → `/`, lowercase, trim trailing `/`), is `root`, starts with `root + "/"`, or starts with `root + "-"` (sibling worktrees).

   If non-empty, render as a markdown table:

   ```markdown
   ## Live — <name> (N tabs)

   | # | Tab note        | Session     | cwd                       | pid    | Last seen |
   |--:|:----------------|:------------|:--------------------------|-------:|:----------|
   | 1 | **Fix Auth Flow** | `aa11bb22` | `D:\Dev\MyApp`         | 33236  | 2s        |
   | 2 | —               | `cc33dd44`  | `D:\Dev\MyApp`          | 32356  | 3s        |
   ```

   Use bold for `name` when present; `—` when null. Render `Last seen` as relative age (`Ns` / `Nm Ss` etc., from `Date.now() - lastSeen`).

3. **Recently evicted section** — read `~/.claude/rider-plugin/session-backlog.json`. Same project filter as live. Take the most recent 10. Render only if non-empty:

   ```markdown
   ## Recently evicted (last 10)

   | # | Age      | Tab note        | Session     | cwd               |
   |--:|:---------|:----------------|:------------|:------------------|
   | 1 | 42s      | **Login Bug**   | `ee55ff66`  | `D:\Dev\MyApp`  |
   ```

4. **Restore info** — optionally read `~/.claude/rider-plugin/last-restore.json` (`{ "restoredAt": <ms>, "projectName": "...", "count": N, "sessions": [...] }`). If present AND `projectName` matches `<name>` (case-insensitive, unless `--all`), print one line:

   `Sessions restored on this Rider start: <count> (<projectName>).`

   Skip silently if missing/mismatched.

5. Final line (plain text):
   - **Project-scoped**: `Total: <N> live session(s) for <name>, <M> recently evicted.`
   - **--all**: `Total: <N> live session(s), <M> recently evicted across <P> projects.`

If both sections are empty, just say: *"No tracked Claude sessions for `<name>`. Open a terminal and run `claude` to start one."*
