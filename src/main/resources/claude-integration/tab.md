Rename your Rider terminal tab AND snapshot this tab to /tabs-history so you can resume it later.

If no name was provided, pick a concise name (3-5 words) based on the current conversation topic.

1. Rename the tab:

```bash
bash ~/.claude/rider-plugin/rename-tab.sh "$ARGUMENTS"
```

2. Snapshot THIS tab to history (looks up this tab's session via TERM_SESSION_ID mapping):

```bash
TAB_NAME="$ARGUMENTS"
export TAB_NAME TERM_SESSION_ID
node - <<'EOF'
const fs = require('fs'), path = require('path'), os = require('os');
const name = (process.env.TAB_NAME || '').trim();
const termSid = (process.env.TERM_SESSION_ID || '').trim();
const home = path.join(os.homedir(), '.claude', 'rider-plugin');

// Resolve this tab's Claude session ID via the session-map
let sid = null, cwd = '', bypass = false;
if (termSid) {
  const mapPath = path.join(home, 'session-map', termSid);
  if (fs.existsSync(mapPath)) {
    sid = fs.readFileSync(mapPath, 'utf8').trim();
  }
}

// Helper: iterate all restore files
const restoreFiles = fs.existsSync(home)
  ? fs.readdirSync(home).filter(f => f.startsWith('restore-') && f.endsWith('.json'))
  : [];

// Fallback: match by tab name in restore files
if (!sid) {
  for (const f of restoreFiles) {
    let sessions;
    try { sessions = JSON.parse(fs.readFileSync(path.join(home, f), 'utf8')); }
    catch { continue; }
    const match = sessions.find(s => s.tabName === name);
    if (match) { sid = match.sessionId; cwd = match.cwd || ''; bypass = !!match.bypassPermissions; break; }
  }
}

// If we still have no sid, skip — the session will be captured by a future /tabs-backup or close
if (!sid) process.exit(0);

// Fill in cwd/bypass from restore files if missing
if (!cwd) {
  for (const f of restoreFiles) {
    let sessions;
    try { sessions = JSON.parse(fs.readFileSync(path.join(home, f), 'utf8')); }
    catch { continue; }
    const match = sessions.find(s => s.sessionId === sid);
    if (match) { cwd = match.cwd || ''; bypass = !!match.bypassPermissions; break; }
  }
}

// Append/update history
const historyPath = path.join(home, 'history.json');
let history = [];
if (fs.existsSync(historyPath)) {
  try { history = JSON.parse(fs.readFileSync(historyPath, 'utf8')); }
  catch { history = []; }
}

const now = Date.now();
history = history.filter(e => e.sessionId !== sid);
history.push({ sessionId: sid, cwd, tabName: name, bypassPermissions: bypass, closedAt: now, backedUp: true });
const cutoff = now - 90 * 24 * 60 * 60 * 1000;
history = history.filter(e => (e.closedAt || 0) > cutoff);

fs.mkdirSync(home, { recursive: true });
fs.writeFileSync(historyPath, JSON.stringify(history, null, 2));
EOF
```

3. Confirm with the user: "Tab renamed to '$ARGUMENTS' and backed up to history."
