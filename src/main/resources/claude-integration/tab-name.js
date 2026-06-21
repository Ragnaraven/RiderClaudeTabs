// /tab — set an explicit, persistent tab name for THIS Claude session.
//
// Usage: node tab-name.js "My Tab Name"
//
// Resolves which Claude session this script is running inside by walking its own
// process ancestry (the script runs as a child of the Claude CLI process) and matching
// ancestor pids against ~/.claude/sessions/<pid>.json. Writes the name into the
// `userName` field of ~/.claude/rider-plugin/active-sessions/<sid>.json — the Rider
// plugin's title controller applies it to the tab within ~1s and it survives restarts.
//
// `userName` outranks Claude's auto topic name for display. The IDE-side tab rename
// dialog writes the same field, so /tab and manual renames are equivalent.

const fs = require('fs');
const path = require('path');
const os = require('os');
const { execSync } = require('child_process');

const name = process.argv.slice(2).join(' ').trim();
if (!name) {
  console.error('usage: node tab-name.js "My Tab Name"');
  process.exit(1);
}

const sessionsDir = path.join(os.homedir(), '.claude', 'sessions');
const activeDir = path.join(os.homedir(), '.claude', 'rider-plugin', 'active-sessions');

function readJsonSafe(p) {
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch { return null; }
}

function atomicWrite(p, content) {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  const tmp = p + '.tmp.' + process.pid + '.' + Date.now();
  fs.writeFileSync(tmp, content);
  try { fs.renameSync(tmp, p); }
  catch {
    try { fs.unlinkSync(p); } catch {}
    fs.renameSync(tmp, p);
  }
}

// pid → {sid, cwd} for every alive-looking Claude session.
function claudePids() {
  const map = new Map();
  if (!fs.existsSync(sessionsDir)) return map;
  for (const fname of fs.readdirSync(sessionsDir)) {
    if (!fname.endsWith('.json')) continue;
    const pid = parseInt(fname.slice(0, -5), 10);
    if (!Number.isFinite(pid)) continue;
    const obj = readJsonSafe(path.join(sessionsDir, fname));
    if (obj && obj.sessionId) map.set(pid, { sid: obj.sessionId, cwd: obj.cwd || null });
  }
  return map;
}

// pid → ppid for every process (one system query, both platforms).
function parentMap() {
  const map = new Map();
  try {
    let out;
    if (process.platform === 'win32') {
      out = execSync(
        'powershell -NoProfile -Command "Get-CimInstance Win32_Process | ForEach-Object { \\"$($_.ProcessId) $($_.ParentProcessId)\\" }"',
        { encoding: 'utf8', timeout: 15000 },
      );
    } else {
      out = execSync('ps -eo pid=,ppid=', { encoding: 'utf8', timeout: 15000 });
    }
    for (const line of out.split('\n')) {
      const m = line.trim().match(/^(\d+)\s+(\d+)$/);
      if (m) map.set(parseInt(m[1], 10), parseInt(m[2], 10));
    }
  } catch { /* fall through to cwd fallback */ }
  return map;
}

const claude = claudePids();

// Primary: walk own ancestry (script → shell → claude → ...), max 12 hops.
let sid = null;
const parents = parentMap();
let cur = process.pid;
for (let hop = 0; hop < 12 && cur; hop++) {
  if (claude.has(cur)) { sid = claude.get(cur).sid; break; }
  cur = parents.get(cur) || null;
}

// Fallback: exactly one session whose cwd matches ours.
if (!sid) {
  const here = process.cwd().replace(/\//g, '\\').toLowerCase();
  const matches = [...claude.values()].filter(
    (s) => s.cwd && s.cwd.replace(/\//g, '\\').toLowerCase() === here,
  );
  if (matches.length === 1) sid = matches[0].sid;
}

if (!sid) {
  console.error('could not determine this Claude session (ancestry walk and cwd fallback both failed)');
  process.exit(2);
}

const file = path.join(activeDir, sid + '.json');
const existing = readJsonSafe(file) || {};
const entry = {
  sid,
  cwd: existing.cwd || (claude.get(cur) ? claude.get(cur).cwd : null) || process.cwd(),
  pid: existing.pid !== undefined ? existing.pid : null,
  lastSeen: existing.lastSeen || Date.now(),
  name: existing.name !== undefined ? existing.name : null,
  userName: name,
};
atomicWrite(file, JSON.stringify(entry));
console.log(`tab name set: "${name}" (session ${sid.slice(0, 8)})`);
