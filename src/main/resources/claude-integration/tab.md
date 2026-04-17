Rename your Rider terminal tab AND snapshot this tab to /tabs-history so you can resume it later.

If no name was provided, pick a concise name (3-5 words) based on the current conversation topic.

1. Rename the tab:

```bash
bash ~/.claude/rider-plugin/rename-tab.sh "$ARGUMENTS"
```

2. Snapshot THIS tab to history (looks up this tab's session via TERM_SESSION_ID mapping):

```bash
PY=""
for candidate in python py python3; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" --version >/dev/null 2>&1; then
    PY="$candidate"; break
  fi
done
[ -z "$PY" ] && exit 0  # skip silently if no Python

TAB_NAME="$ARGUMENTS"
export TAB_NAME TERM_SESSION_ID
"$PY" - <<'PYEOF'
import json, os, time
name = os.environ.get('TAB_NAME', '').strip()
term_sid = os.environ.get('TERM_SESSION_ID', '').strip()
home = os.path.expanduser('~/.claude/rider-plugin')

# Resolve this tab's Claude session ID via the session-map
sid = None
cwd = ''
bypass = False
if term_sid:
    map_path = os.path.join(home, 'session-map', term_sid)
    if os.path.exists(map_path):
        with open(map_path) as f:
            sid = f.read().strip()

# Fall back: find our session in the restore files (match by cwd + name)
if not sid:
    import glob
    for rf in glob.glob(os.path.join(home, 'restore-*.json')):
        try:
            with open(rf) as f: sessions = json.load(f)
        except Exception: continue
        for s in sessions:
            if s.get('tabName') == name:
                sid = s.get('sessionId'); cwd = s.get('cwd', ''); bypass = s.get('bypassPermissions', False)
                break
        if sid: break

if not sid:
    # No mapping — session will be added to history naturally on close or via /tabs-backup
    raise SystemExit

# Find cwd/bypass if not set yet
if not cwd:
    import glob
    for rf in glob.glob(os.path.join(home, 'restore-*.json')):
        try:
            with open(rf) as f: sessions = json.load(f)
        except Exception: continue
        for s in sessions:
            if s.get('sessionId') == sid:
                cwd = s.get('cwd', ''); bypass = s.get('bypassPermissions', False)
                break
        if cwd: break

# Append/update history
history_path = os.path.join(home, 'history.json')
history = []
if os.path.exists(history_path):
    try:
        with open(history_path) as f: history = json.load(f)
    except Exception: history = []

now = int(time.time() * 1000)
history = [e for e in history if e.get('sessionId') != sid]
history.append({
    'sessionId': sid,
    'cwd': cwd,
    'tabName': name,
    'bypassPermissions': bypass,
    'closedAt': now,
    'backedUp': True,
})
cutoff = now - 90 * 24 * 60 * 60 * 1000
history = [e for e in history if e.get('closedAt', 0) > cutoff]

os.makedirs(home, exist_ok=True)
with open(history_path, 'w') as f:
    json.dump(history, f, indent=2)
PYEOF
```

3. Confirm with the user: "Tab renamed to '$ARGUMENTS' and backed up to history."
