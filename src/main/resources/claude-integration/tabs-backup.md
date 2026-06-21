Sync `active-sessions/` with whatever Claude processes are alive RIGHT NOW.

In normal operation the plugin's poll loop keeps `active-sessions/` fresh every ~5 seconds. This skill is the **explicit sync** for cases where you don't want to wait — typically right before suspecting a crash, or to verify state before closing Rider.

By default this only syncs sessions whose cwd belongs to the project you're currently in. Pass `--all` to sync every alive session across every project.

## Steps

1. Decide scope:
   - If `$ARGUMENTS` contains `--all` (case-insensitive), skip step 2 — sync all projects.
   - Otherwise, resolve the current project hash:
     ```bash
     node ~/.claude/rider-plugin/current-project.js
     ```
     Output is JSON with `root`, `hash`, `name`. Capture the `hash`.

2. Run the sync helper. **Pass the project hash to scope to current project** (omit the flag for `--all`):
   ```bash
   node ~/.claude/rider-plugin/backup-active.js --hash=<hash>
   ```

3. Show the one-line output verbatim. Format: `synced: N added, K refreshed (M tracked entries untouched)`.
   - `added` = per-sid files newly written for alive sessions the plugin hadn't picked up yet
   - `refreshed` = per-sid files for alive sessions updated in place
   - `untouched` = tracked entries without a live process right now — left alone; the sync
     is purely additive (only the Rider plugin ever evicts or backlogs sessions)
