Browse past Claude Code sessions saved by the Rider plugin and optionally resume one.

1. Run this to display session history:

```bash
# Find a working Python (skip Windows Store stubs)
PY=""
for candidate in python py python3; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" --version >/dev/null 2>&1; then
    PY="$candidate"; break
  fi
done
if [ -z "$PY" ]; then echo "No working Python found"; exit 1; fi
if [ ! -f ~/.claude/rider-plugin/history.json ]; then echo "No session history found. Run /backup-tabs to snapshot currently-active sessions."; exit 0; fi

"$PY" - <<'PYEOF'
import json, time, os
with open(os.path.expanduser('~/.claude/rider-plugin/history.json')) as f:
    entries = json.load(f)
entries.sort(key=lambda e: e.get('closedAt', 0), reverse=True)
print('=== Session History (newest first) ===')
print('')
for i, e in enumerate(entries, 1):
    ts = e.get('closedAt', 0) / 1000
    age = time.time() - ts
    if age < 3600: ago = f'{int(age/60)}m ago'
    elif age < 86400: ago = f'{int(age/3600)}h ago'
    else: ago = f'{int(age/86400)}d ago'
    date = time.strftime('%Y-%m-%d %H:%M', time.localtime(ts))
    cwd = e.get('cwd', '')
    # Normalize both forward and backward slashes (chr(92) = backslash, avoids shell escape issues)
    sep = chr(92)
    cwd_norm = cwd.replace(sep, '/').rstrip('/')
    proj = cwd_norm.split('/')[-1] if cwd_norm else '?'
    marker = ' [backup]' if e.get('backedUp') else ''
    print(f'  {i}. [{ago}] {e.get("tabName", "?")}  ({proj}){marker}  — {date}')
    print(f'     session: {e.get("sessionId", "?")[:12]}...')
PYEOF
```

2. Show the user the numbered list. Each entry shows: age, tab name, project, date, and session ID prefix.

3. If `$ARGUMENTS` specifies a number or tab name, resume that session using:
   `claude --resume <sessionId>` (add `--dangerously-skip-permissions` if `bypassPermissions` is true)
   Then rename the tab: `bash ~/.claude/rider-plugin/rename-tab.sh "<tabName>"`

4. If `$ARGUMENTS` is empty, just display the list and let the user pick.
