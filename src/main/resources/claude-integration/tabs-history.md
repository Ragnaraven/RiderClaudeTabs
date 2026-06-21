Browse the eviction backlog (`~/.claude/rider-plugin/session-backlog.json`) — sessions the plugin used to track that have since died/exited. Up to 50 entries, newest first.

By default this is scoped to the **current project**. Pass `--all` for every project.

## Steps

1. Read `~/.claude/rider-plugin/session-backlog.json` with the **Read** tool. (Windows: `%USERPROFILE%\.claude\rider-plugin\session-backlog.json`.)
   - If the file doesn't exist or is empty (`[]`), tell the user: *"No session history yet. The backlog populates as alive sessions get evicted (Claude process exits)."* and stop.
   - If the file is corrupt JSON, say so and stop.

2. Parse the array. Each entry: `{ "sid": "...", "cwd": "...", "name": ..., "evictedAt": <ms> }`. Already newest-first.

3. **Filter by current project by default**. History is global; default behaviour scopes to the user's current project.

   - If `$ARGUMENTS` contains `--all` (case-insensitive), skip the filter and add a `Project` column to the rendered table.
   - Otherwise, resolve the current project:
     ```bash
     node ~/.claude/rider-plugin/current-project.js
     ```
     Capture `root` and `name`. Filter: keep an entry if its `cwd`, after normalising (replace `\` with `/`, lowercase, strip trailing `/`), equals `root`, starts with `root + "/"`, or starts with `root + "-"` (sibling worktrees like `MyApp-feature`).
   - After filtering, if empty AND `--all` would show entries: *"No history for `<name>`. Run `/tabs-history --all` to see entries from other projects."* and stop.
   - Otherwise if total is also empty, fall back to the "No session history" message above.
   - If filtered, mention at the top: *"Showing N entries from `<name>`. K hidden from other projects — run `/tabs-history --all` to see them."* (omit second sentence if K=0).

4. **If `$ARGUMENTS` (sans `--all`) is provided**: match it against `name` (case-insensitive contains), `sid` prefix, or 1-based number from the filtered list. If exactly one matches, skip to step 6.

5. **Present the menu via AskUserQuestion**:
   - Show top-3 newest entries as options. Label = `<name-or-"—"> · <basename(cwd)> · <age>` (Age formatted as `Ns` / `Nm Ss` / `Nh Mm` / `Nd Hh`).
   - If 4+ entries, the 4th option is `"Show all evicted sessions"`.
   - Question: `"Which session would you like to resume?"`
   - Header: `"Resume which?"`

   **If user picks "Show all":** print full filtered list as a markdown table:

   ```markdown
   | # | Age      | Tab note      | Session     | cwd                       |
   |--:|:---------|:--------------|:------------|:--------------------------|
   | 1 | 42s      | **Login Bug** | `ee55ff66`  | `D:\Dev\MyApp`          |
   | 2 | 7m 12s   | —             | `ab77cd88`  | `D:\Dev\MyApp`          |
   ```

   Add a `Project` column when `--all`. Ask plain text: *"Which one? Enter a number or note."* Then step 6.

6. **Print the resume command** — do NOT execute (nested `--resume` always fails):
   ```
   claude --resume <full-sid>
   ```
   Tell the user: *"Run this in a fresh terminal tab. The plugin will re-add it to `active-sessions/` on the next poll."*
