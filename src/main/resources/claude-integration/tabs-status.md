Show a status report of all currently-active Claude Code sessions tracked by the Rider plugin.

Similar output to what you'd see when asking "what tabs do I have open", but structured.

1. Run this to display the report:

```bash
node - <<'EOF'
const fs = require('fs'), path = require('path'), os = require('os');
const home = path.join(os.homedir(), '.claude', 'rider-plugin');

const restoreFiles = fs.existsSync(home)
  ? fs.readdirSync(home).filter(f => f.startsWith('restore-') && f.endsWith('.json')).sort()
  : [];

if (!restoreFiles.length) {
  console.log('No active Claude sessions tracked.');
  process.exit(0);
}

let total = 0;
for (const rf of restoreFiles) {
  let sessions;
  try { sessions = JSON.parse(fs.readFileSync(path.join(home, rf), 'utf8')); }
  catch { continue; }
  if (!sessions.length) continue;

  // Build backslash from char code to avoid shell-escape issues in heredocs
  const BS = String.fromCharCode(92);
  const sampleCwd = (sessions[0].cwd || '').split(BS).join('/').replace(/\/+$/, '');
  const projectName = sampleCwd.split('/').pop() || rf;
  const plural = sessions.length === 1 ? '' : 's';
  console.log(`=== ${projectName} (${sessions.length} tab${plural}) ===`);
  console.log(`    ${sampleCwd}`);
  console.log('');
  sessions.forEach((s, i) => {
    const bypass = s.bypassPermissions ? ' [bypass]' : '';
    const sid = (s.sessionId || '?').slice(0, 12);
    console.log(`  ${i + 1}. ${s.tabName || '?'}${bypass}`);
    console.log(`     session: ${sid}...`);
  });
  console.log('');
  total += sessions.length;
}
console.log(`Total: ${total} active session(s) across ${restoreFiles.length} project(s).`);
EOF
```

2. Show the user the output.
