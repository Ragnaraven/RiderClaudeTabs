Manually snapshot currently-active Claude sessions into history.json so they appear in /tab-history.

Useful when you want to checkpoint your sessions without closing Rider or waiting for tabs to close naturally.

1. Run this to append all currently-active sessions from restore files into history.json:

```bash
# Find a working Python (skip Windows Store stubs)
PY=""
for candidate in python py python3; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" --version >/dev/null 2>&1; then
    PY="$candidate"; break
  fi
done
if [ -z "$PY" ]; then echo "No working Python found"; exit 1; fi

"$PY" - <<'PYEOF'
import json, time, os, glob
home = os.path.expanduser('~/.claude/rider-plugin')
history_path = os.path.join(home, 'history.json')

history = []
if os.path.exists(history_path):
    try:
        with open(history_path) as f:
            history = json.load(f)
    except Exception:
        history = []

active = []
for f in glob.glob(os.path.join(home, 'restore-*.json')):
    try:
        with open(f) as fp:
            active.extend(json.load(fp))
    except Exception:
        pass

if not active:
    print('No active sessions to back up.')
else:
    now = int(time.time() * 1000)
    added = 0
    updated = 0
    for s in active:
        sid = s.get('sessionId')
        if not sid:
            continue
        before = len(history)
        history = [e for e in history if e.get('sessionId') != sid]
        was_present = len(history) < before
        entry = {
            'sessionId': sid,
            'cwd': s.get('cwd', ''),
            'tabName': s.get('tabName', ''),
            'bypassPermissions': s.get('bypassPermissions', False),
            'closedAt': now,
            'backedUp': True,
        }
        history.append(entry)
        if was_present:
            updated += 1
        else:
            added += 1

    cutoff = now - 90 * 24 * 60 * 60 * 1000
    history = [e for e in history if e.get('closedAt', 0) > cutoff]

    os.makedirs(home, exist_ok=True)
    with open(history_path, 'w') as f:
        json.dump(history, f, indent=2)

    print(f'Backup complete: {added} new, {updated} updated, {len(history)} total in history.')
    print('')
    print('Run /tab-history to browse all sessions.')
PYEOF
```

2. Show the output to the user.
