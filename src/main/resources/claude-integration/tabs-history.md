Browse past Claude Code sessions saved by the Rider plugin and optionally resume one.

1. Run this to display session history:

```bash
node - <<'EOF'
const fs = require('fs'), path = require('path'), os = require('os');
const historyPath = path.join(os.homedir(), '.claude', 'rider-plugin', 'history.json');

if (!fs.existsSync(historyPath)) {
  console.log('No session history found. Run /tabs-backup to snapshot currently-active sessions.');
  process.exit(0);
}

let entries;
try { entries = JSON.parse(fs.readFileSync(historyPath, 'utf8')); }
catch { console.log('history.json is corrupt.'); process.exit(1); }

entries.sort((a, b) => (b.closedAt || 0) - (a.closedAt || 0));

console.log('=== Session History (newest first) ===\n');
const now = Date.now();
entries.forEach((e, i) => {
  const ts = e.closedAt || 0;
  const ageMs = now - ts;
  let ago;
  if (ageMs < 3600_000)      ago = `${Math.floor(ageMs / 60_000)}m ago`;
  else if (ageMs < 86400_000) ago = `${Math.floor(ageMs / 3600_000)}h ago`;
  else                        ago = `${Math.floor(ageMs / 86400_000)}d ago`;
  const date = new Date(ts).toISOString().slice(0, 16).replace('T', ' ');
  const BS = String.fromCharCode(92);
  const cwdNorm = (e.cwd || '').split(BS).join('/').replace(/\/+$/, '');
  const proj = cwdNorm.split('/').pop() || '?';
  const marker = e.backedUp ? ' [backup]' : '';
  console.log(`  ${i + 1}. [${ago}] ${e.tabName || '?'}  (${proj})${marker}  - ${date}`);
  console.log(`     session: ${(e.sessionId || '?').slice(0, 12)}...`);
});
EOF
```

2. Show the user the numbered list. Each entry shows: age, tab name, project, date, and session ID prefix.

3. If `$ARGUMENTS` specifies a number or tab name, resume that session using:
   `claude --resume <sessionId>` (add `--dangerously-skip-permissions` if `bypassPermissions` is true)
   Then rename the tab: `bash ~/.claude/rider-plugin/rename-tab.sh "<tabName>"`

4. If `$ARGUMENTS` is empty, just display the list and let the user pick.
