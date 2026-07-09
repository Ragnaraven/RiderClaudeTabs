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

// Primary: the session id Claude exports to every child process via env. Exact and instant —
// no ancestry guessing, no PowerShell, and (crucially) it disambiguates a RESUMED chat or many
// chats sharing one cwd, which the cwd fallback below cannot. This is the fix for "/tab failed
// on a resumed chat": ancestry + cwd could both fail when several tabs of one project share a cwd.
let sid = (process.env.CLAUDE_CODE_SESSION_ID || '').trim() || null;

// Fallback 1 (older Claude with no env var): walk own ancestry script → shell → claude, max 12.
if (!sid) {
  const parents = parentMap();
  let cur = process.pid;
  for (let hop = 0; hop < 12 && cur; hop++) {
    if (claude.has(cur)) { sid = claude.get(cur).sid; break; }
    cur = parents.get(cur) || null;
  }
}

// Fallback 2: exactly one alive session whose cwd matches ours.
if (!sid) {
  const here = process.cwd().replace(/\//g, '\\').toLowerCase();
  const matches = [...claude.values()].filter(
    (s) => s.cwd && s.cwd.replace(/\//g, '\\').toLowerCase() === here,
  );
  if (matches.length === 1) sid = matches[0].sid;
}

if (!sid) {
  console.error('could not determine this Claude session (env, ancestry, and cwd all failed)');
  process.exit(2);
}

const file = path.join(activeDir, sid + '.json');
const existing = readJsonSafe(file) || {};
// cwd: prefer what we already track; else the matching live session's cwd; else here.
const liveForSid = [...claude.values()].find((s) => s.sid === sid);
const entry = {
  sid,
  cwd: existing.cwd || (liveForSid && liveForSid.cwd) || process.cwd(),
  pid: existing.pid !== undefined ? existing.pid : null,
  lastSeen: existing.lastSeen || Date.now(),
  name: existing.name !== undefined ? existing.name : null,
  userName: name,
};
atomicWrite(file, JSON.stringify(entry));

// Poke the name into this tab's own terminal title via OSC title escapes. Two jobs: instant
// feedback on the tab even before the IDE plugin reacts, and a rendezvous — a pty is private to
// one tab, so the plugin claims a user-opened / `claude --resume` tab by matching its displayed
// title to this name even when its process/cwd reflection digs fail on the reworked terminal.
//
// CRITICAL: write to process.stdout (fd 1), which IS the pty. JediTerm parses OSC 0/2 from the
// pty stream and surfaces it as the tab's applicationTitle — which the plugin's title-handshake
// then reads to claim the tab. The old CONOUT$ write did NOT reach JediTerm's title parser, so
// "Local"/resume tabs stayed unnamed and unclaimable (the reported /tab bug). Send both OSC 0
// (icon+title) and OSC 2 (title) for terminals that honor only one. OSC title sequences are
// non-destructive (no cursor move / clear), so writing mid-session doesn't disturb Claude's TUI.
try {
  const ESC = String.fromCharCode(27);
  const BEL = String.fromCharCode(7);
  const STAR = String.fromCharCode(0x2733); // ✳ — the plugin's idle glyph, so it re-owns cleanly
  const seq = ESC + ']0;' + STAR + ' ' + name + BEL + ESC + ']2;' + STAR + ' ' + name + BEL;
  try { process.stdout.write(seq); } catch { /* stdout not a tty */ }
  // Also poke the controlling console directly, as a fallback for runners that redirect stdout.
  try {
    const tty = process.platform === 'win32' ? '\\\\.\\CONOUT$' : '/dev/tty';
    fs.writeFileSync(tty, seq);
  } catch { /* no controlling console */ }
} catch { /* best effort */ }

console.log(`tab name set: "${name}" (session ${sid.slice(0, 8)})`);
