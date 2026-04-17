Show a status report of all currently-active Claude Code sessions tracked by the Rider plugin.

Similar output to what you'd see when asking "what tabs do I have open", but structured.

1. Run this to display the report:

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
import json, os, glob
home = os.path.expanduser('~/.claude/rider-plugin')

restore_files = sorted(glob.glob(os.path.join(home, 'restore-*.json')))
if not restore_files:
    print('No active Claude sessions tracked.')
    raise SystemExit

total = 0
for rf in restore_files:
    try:
        with open(rf) as f:
            sessions = json.load(f)
    except Exception:
        continue
    if not sessions:
        continue

    project_key = os.path.basename(rf).removeprefix('restore-').removesuffix('.json')
    sep = chr(92)
    sample_cwd = sessions[0].get('cwd', '').replace(sep, '/').rstrip('/')
    project_name = sample_cwd.split('/')[-1] if sample_cwd else project_key

    print(f'=== {project_name} ({len(sessions)} tab{"s" if len(sessions) != 1 else ""}) ===')
    print(f'    {sample_cwd}')
    print()
    for i, s in enumerate(sessions, 1):
        bypass = ' [bypass]' if s.get('bypassPermissions') else ''
        sid = s.get('sessionId', '?')[:12]
        print(f'  {i}. {s.get("tabName", "?")}{bypass}')
        print(f'     session: {sid}...')
    print()
    total += len(sessions)

print(f'Total: {total} active session(s) across {len(restore_files)} project(s).')
PYEOF
```

2. Show the user the output.
