Reopen Claude sessions in this project as terminal tabs, picked by NAME from the recent ones. Writes the picks into the plugin's restore list, which the plugin acts on within a few seconds — nothing to paste, no restart.

Use this when tabs are missing after a restart, a crash or a cleanup and you know roughly which conversations you want back. For "everything that was open when it died" prefer `/tabs-recover`, which reconstructs that set itself.

## Arguments

Parse `$ARGUMENTS`:

- `--within <minutes>` or a bare number — how far back to look (default `20160`, two weeks)
- `--all` — every project, not just this one
- anything else is treated as a selection hint (names, "all", "the last two weeks") and used in step 3 instead of asking

## Steps

1. **List candidates — dry run, always first.** Never pass `--apply` here:

   ```bash
   node ~/.claude/rider-plugin/recover-tabs.js --json --within <minutes> [--all]
   ```

   Exit `2` means the plugin's state directory was not found: say the plugin does not look installed (or Rider has not run since) and stop.

2. **Show them by name, newest first, numbered.** Use `recovered` (sessions that can be reopened) — never show raw session ids, they mean nothing to the reader:

   ```markdown
   | # | Conversation          | Last active | Where      |
   |--:|:----------------------|:------------|:-----------|
   | 1 | **Fix Auth Flow**     | 2h ago      | WSL        |
   | 2 | **Pets**              | yesterday   | WSL        |
   | 3 | *(untitled)*          | 3 days ago  | Windows    |
   ```

   `Conversation` is `userName`, else `name`, else *(untitled)*. `Where` is the root in plain words (`WSL`, `Windows`). Show `lastActive` as a relative age. One line after the table: *"N already open — left alone."* (from `alreadyOpen`). If `recovered` is empty, say so and offer a wider window (`/tabs-restore 43200` for a month) — do not widen on your own.

   Mention the skipped groups only when non-empty, in one line each: `skippedUserClosed` (*"closed on purpose; say so if you want one of them back"*), `skippedNoTranscript` (*"no transcript on disk; cannot be resumed"*), `skippedStillRunning` (*"already running"*).

3. **Ask which to reopen** — unless the user's message already said (e.g. "all of them", "the ones from this week", two names). Accept numbers (`1,3,5-7`), names, or `all`. Map the choice back to the sids yourself; the user never types an id.

4. **Apply the pick**, passing exactly the chosen sids:

   ```bash
   node ~/.claude/rider-plugin/recover-tabs.js --apply --json --sids <sid>,<sid>,... [--all]
   ```

   `unknownSids` non-empty means a pick did not match — say which and stop before claiming it was reopened.

5. **Confirm.** The plugin reopens each session as a `claude --resume` tab within a few seconds. Wait ~10 s, re-run step 1 for the same sids (`--json --sids ...`) and expect them in `alreadyOpen`. Then tell the user, by name: *"Reopened: Fix Auth Flow, Pets."* If one has not appeared after ~30 s, say the plugin retries three times with a cooldown, and point at `/tabs-status`.

## Notes

- Never print `claude --resume` commands for the user to paste: the plugin owns reopening, and a pasted resume inside an existing Claude tab fails anyway.
- This never launches Claude itself and never closes anything. Reopening a session that is already open is a no-op.
- `/tabs-history` shows sessions the plugin evicted (a crash evicts everything at once) with their names; `/tabs-recover` rebuilds the whole pre-crash set in one go.
