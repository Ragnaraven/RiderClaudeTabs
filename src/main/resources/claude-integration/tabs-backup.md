Manually snapshot currently-active Claude sessions into history.json so they appear in /tabs-history.

Useful when you want to checkpoint your sessions without closing Rider or waiting for tabs to close naturally.

1. Run this to append all currently-active sessions from restore files into history.json:

```bash
node - <<'EOF'
const fs = require('fs'), path = require('path'), os = require('os');
const home = path.join(os.homedir(), '.claude', 'rider-plugin');
const historyPath = path.join(home, 'history.json');

let history = [];
if (fs.existsSync(historyPath)) {
  try { history = JSON.parse(fs.readFileSync(historyPath, 'utf8')); }
  catch { history = []; }
}

let active = [];
if (fs.existsSync(home)) {
  for (const f of fs.readdirSync(home).filter(n => n.startsWith('restore-') && n.endsWith('.json'))) {
    try { active.push(...JSON.parse(fs.readFileSync(path.join(home, f), 'utf8'))); }
    catch {}
  }
}

if (!active.length) {
  console.log('No active sessions to back up.');
  process.exit(0);
}

const now = Date.now();
let added = 0, updated = 0;
for (const s of active) {
  const sid = s.sessionId;
  if (!sid) continue;
  const wasPresent = history.some(e => e.sessionId === sid);
  history = history.filter(e => e.sessionId !== sid);
  history.push({
    sessionId: sid,
    cwd: s.cwd || '',
    tabName: s.tabName || '',
    bypassPermissions: !!s.bypassPermissions,
    closedAt: now,
    backedUp: true,
  });
  if (wasPresent) updated++; else added++;
}

// Prune entries older than 90 days
const cutoff = now - 90 * 24 * 60 * 60 * 1000;
history = history.filter(e => (e.closedAt || 0) > cutoff);

fs.mkdirSync(home, { recursive: true });
fs.writeFileSync(historyPath, JSON.stringify(history, null, 2));

console.log(`Backup complete: ${added} new, ${updated} updated, ${history.length} total in history.`);
console.log('');
console.log('Run /tabs-history to browse all sessions.');
EOF
```

2. Show the output to the user.
