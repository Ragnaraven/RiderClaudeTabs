Rebuild the tab snapshot after a crash, so the plugin reopens the Claude sessions that were really open.

Use this when tabs did **not** come back after a hard stop — a WSL shutdown, an OOM kill, a machine reset — and the plugin restored only some of them (or none). It writes the plugin's snapshot; the running plugin does the reopening itself on its next poll.

It looks for the lost tabs in three places, in order of how much a crash damages them: the plugin's live session records, its eviction history (`session-backlog.json` — a crash evicts every session at once, so this is usually where the names survive), and the transcripts on disk, which outlive both and are the only trace of a session the plugin never recorded. Sessions with no record are named from their own transcript.

By default it recovers sessions active in the **last 60 minutes**, in the **current project**.

## Arguments

Parse `$ARGUMENTS`:

- `--within <minutes>` — widen or narrow the activity window (default `60`)
- `--all` — every project, not just this one. It cannot pick up sessions the plugin never recorded, so prefer the per-project run when tabs are actually missing
- `--include-closed` — also recover sessions the user had explicitly closed
- a bare number (e.g. `/tabs-recover 90`) — same as `--within 90`

## Steps

1. **Dry run first — always.** Never pass `--apply` on the first invocation:

   ```bash
   node ~/.claude/rider-plugin/recover-tabs.js --json <args>
   ```

   Exit `2` means the plugin state dir was not found: tell the user the plugin doesn't look installed (or Rider has never run since installing) and stop. An empty `recovered` array means everything recently active is already open — report that and stop.

2. **Show what would come back.** Render `recovered` as a table, newest first:

   ```markdown
   | # | Tab note      | Session    | Last active | Root                  |
   |--:|:--------------|:-----------|:------------|:----------------------|
   | 1 | **Fix Auth**  | `aa11bb22` | 3m ago      | `wsl:MyDistro:dev`    |
   | 2 | —             | `cc33dd44` | 12m ago     | `windows`             |
   ```

   A row with `fromBacklog: true` came from the eviction history rather than a live record — worth a footnote, since those are the ones the user is least likely to remember having open. A row with `hadRecord: false` is one the plugin had no memory of at all.

   Show `lastActive` as a relative age, and `alreadyOpen` as a plain line: *"N tab(s) already open — those are left alone."*

   Then surface what was **not** recovered, only if non-empty:
   - `skippedNoTranscript` — *"no transcript on disk, so `claude --resume` would fail; these can't be recovered."*
   - `skippedUserClosed` — *"you closed these deliberately; re-run with `--include-closed` to bring them back too."*
   - `skippedStillRunning` — already alive, so there is nothing to recover for them. Mention only if the user expected them to be missing.

3. **If the first pass finds nothing, widen before giving up.** The default hour is a guess about when the outage was, not a fact:
   - If the user said when things died ("an hour ago", "after lunch", "last night"), set `--within` from that directly, generously.
   - Otherwise re-run at `--within 240`, then `--within 1440`, and stop at the first window that returns sessions. Say which window you ended up using and why — a session found only at 24h probably ended on purpose, so let the user judge it rather than writing it silently.
   - Still nothing at 24h with tabs genuinely missing means the transcripts are gone or the sessions belong to another project — check `/tabs-status` and `/tabs-history`, and say which one you checked.

4. **Ask before writing**, unless the user's message already said to go ahead (e.g. "recover my tabs", "reopen them"). A dry run that found nothing needs no confirmation — there is nothing to ask about.

5. **Apply**:

   ```bash
   node ~/.claude/rider-plugin/recover-tabs.js --apply --json <args>
   ```

   `backfilledRecords` lists sessions the plugin had no record for at all — mention the count if non-zero, since those are the ones that would otherwise have been lost for good.

6. **Confirm the plugin picked it up.** The retry-spawn pass runs every few seconds, so wait briefly, then re-run the dry run (step 1) and expect *"nothing to recover"* — every sid is now in the snapshot. Tell the user:

   *"Wrote N session(s) to the snapshot. The plugin reopens them as `claude --resume` tabs within a few seconds — the tabs appear in the terminal tool window on their own; nothing to paste."*

   If a tab still hasn't appeared after ~30s, the plugin gives each sid 3 attempts with a 60s cooldown — say so, and point at `/tabs-status` to see what it currently tracks.

## Notes

- Names come from the plugin's own record first, then the transcript's summary, then the first prompt typed in that session — a `—` means the transcript had nothing usable, not that the session is empty.
- This never launches Claude itself. It only rewrites the snapshot the plugin already restores from, so it is safe to dry-run at any time.
- Sessions already open are never touched, and re-running after an apply is a no-op.
- A session added while the IDE is running is picked up by the plugin's next poll and reopened, one or many. The plugin's own snapshot write can, rarely, land on top of this one within the same few milliseconds — the dry run in step 6 catches that; apply again if a sid is missing.
- `skippedNoSnapshot` (with `--all`) means the plugin has no snapshot for that session's project at all — run `/tabs-recover` from inside that project instead.
- Widening `--within` past a few hours starts pulling in sessions that were finished, not crashed — prefer the smallest window that covers the outage.
