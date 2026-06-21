Name this Rider terminal tab. Sets a persistent, user-chosen tab name for THIS Claude session — it outranks the auto-generated topic name, shows as `✳ <name>` on the tab within ~1s, and survives Rider restarts.

## Steps

1. Determine the desired name:
   - If `$ARGUMENTS` is non-empty, use it verbatim as the name (the user's exact words ARE the name — do not reinterpret or summarize them).
   - If `$ARGUMENTS` is empty, pick a concise 3–5 word name describing this conversation's purpose.

2. Set it:
   ```bash
   node ~/.claude/rider-plugin/tab-name.js "<name>"
   ```

3. Confirm with the script's one-line output. If it fails with "could not determine this Claude session", tell the user the session isn't tracked yet (it appears within ~5s of starting) and to retry.
