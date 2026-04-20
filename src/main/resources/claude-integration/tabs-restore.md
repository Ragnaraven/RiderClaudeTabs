List saved Claude sessions from the Rider plugin restore files. Print the resume command for the user to run themselves — do NOT attempt to execute `claude --resume` from inside this session (nesting fails).

1. Run this to find and display all saved sessions:

```bash
node - <<'EOF'
const fs = require('fs'), path = require('path'), os = require('os');
const home = path.join(os.homedir(), '.claude', 'rider-plugin');

const restoreFiles = fs.existsSync(home)
  ? fs.readdirSync(home).filter(f => f.startsWith('restore-') && f.endsWith('.json')).sort()
  : [];

if (!restoreFiles.length) {
  console.log('No saved sessions found.');
  process.exit(0);
}

let total = 0;
for (const rf of restoreFiles) {
  let sessions;
  try { sessions = JSON.parse(fs.readFileSync(path.join(home, rf), 'utf8')); }
  catch { continue; }
  if (!sessions.length) continue;

  const BS = String.fromCharCode(92);
  const sampleCwd = (sessions[0].cwd || '').split(BS).join('/').replace(/\/+$/, '');
  const projectName = sampleCwd.split('/').pop() || rf;

  console.log(`=== ${projectName} ===`);
  console.log(`    ${sampleCwd}`);
  console.log('');
  sessions.forEach((s, i) => {
    const bypass = s.bypassPermissions ? ' [bypass]' : '';
    console.log(`  ${i + 1}. ${s.tabName || '?'}${bypass}`);
    console.log(`     ${s.sessionId}`);
  });
  console.log('');
  total += sessions.length;
}
console.log(`${total} saved session(s).`);
EOF
```

2. Show the user the list.

3. If `$ARGUMENTS` names a specific tab or number, print the resume command for that ONE session. If empty, ask which to resume.

4. Output the resume command exactly like this (adjust `--dangerously-skip-permissions` based on the session's `bypassPermissions` flag):

```
claude --resume <sessionId> --dangerously-skip-permissions
```

5. Remind the user: "Run this yourself in the terminal (or prefix with `!` to run in this Claude session)." Do NOT call it via Bash — the nested `claude --resume` will fail with an exit code 1.

6. Mention that after resume the plugin will rename the tab automatically, but they can also run:

```
bash ~/.claude/rider-plugin/rename-tab.sh "<tabName>"
```

if the auto-rename didn't pick up.
