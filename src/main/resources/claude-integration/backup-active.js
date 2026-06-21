// /tabs-backup — sync `active-sessions/` with whatever Claude processes are alive RIGHT NOW.
//
// The plugin writes one file per alive Claude session into
// `~/.claude/rider-plugin/active-sessions/<sid>.json` every poll (~5s). This script is
// the explicit sync for when you don't want to wait.
//
// PURELY ADDITIVE: this script registers/refreshes alive sessions and NEVER deletes
// anything. The Rider plugin is the sole owner of session lifecycle (eviction, backlog,
// user-close) — a second deleter with subtly different rules is how restore-pending
// sessions got wiped in the past.
//
// `name` is Claude's own auto-generated session topic name, mirrored from <pid>.json
// (prettified slug → Title Case). `userName` (user-chosen, via /tab or an IDE tab
// rename) is never touched here. Old files may carry the pre-2.0 `metaName` key —
// read both, write `name`.
//
// What this does:
//   1. Scan ~/.claude/sessions/*.json — every alive Claude session: cwd, sid, pid, name.
//   2. Filter by --hash=<projectHash> if given (scoped to one project), else all.
//   3. For each alive session, write/update active-sessions/<sid>.json.
//   4. Print one-line summary: `synced: N added, K refreshed (M tracked entries untouched)`.
//
// Usage: node backup-active.js [--hash=<projectHash>]

const fs = require('fs');
const path = require('path');
const os = require('os');

const hashArg = process.argv.slice(2).find((a) => a.startsWith('--hash='));
const projectHash = hashArg ? hashArg.slice('--hash='.length) : null;

const stateDir = path.join(os.homedir(), '.claude', 'rider-plugin');
const sessionsDir = path.join(os.homedir(), '.claude', 'sessions');
const activeDir = path.join(stateDir, 'active-sessions');


function readJsonSafe(p, fallback) {
  if (!fs.existsSync(p)) return fallback;
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); }
  catch { return fallback; }
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

function projectHashForPath(p) {
  return p.replace(/\\/g, '/').replace(/:\//, '--').replace(/\//g, '-');
}

function inProject(cwd) {
  if (!projectHash) return true;
  return projectHashForPath(cwd) === projectHash;
}

function processAlive(pid) {
  try { process.kill(pid, 0); return true; }
  catch (e) { return e && e.code === 'EPERM'; } // alive but owned by another user
}

// Mirror the Kotlin prettifySessionName: "fix-auth-rotation" → "Fix Auth Rotation".
function prettify(slug) {
  if (!slug || typeof slug !== 'string' || !slug.trim()) return null;
  return slug.trim().split(/[-_]/).filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ') || null;
}

// Read the cached name from a per-sid entry, accepting both the 2.1 `name` key and
// the pre-2.1 `metaName` key.
function cachedName(entry) {
  if (!entry) return null;
  if (entry.name !== undefined && entry.name !== null) return entry.name;
  if (entry.metaName !== undefined && entry.metaName !== null) return entry.metaName;
  return null;
}

// ── Step 1+2: scan sessions/, filter by project ──────────────────────────
const alive = new Map(); // sid → {sid, cwd, pid, name}
if (fs.existsSync(sessionsDir)) {
  for (const fname of fs.readdirSync(sessionsDir)) {
    if (!fname.endsWith('.json')) continue;
    const pid = parseInt(fname.slice(0, -5), 10);
    if (!Number.isFinite(pid)) continue;
    let obj;
    try { obj = JSON.parse(fs.readFileSync(path.join(sessionsDir, fname), 'utf8')); }
    catch { continue; }
    const sid = obj.sessionId;
    const cwd = obj.cwd;
    if (!sid || !cwd) continue;
    if (!inProject(cwd)) continue;
    if (!processAlive(pid)) continue;
    alive.set(sid, { sid, cwd, pid, name: prettify(obj.name) });
  }
}

// ── Step 3: write/update per-sid files (additive only) ───────────────────
let added = 0, refreshed = 0;
const now = Date.now();
fs.mkdirSync(activeDir, { recursive: true });
for (const s of alive.values()) {
  const file = path.join(activeDir, s.sid + '.json');
  const existing = readJsonSafe(file, null);
  const name = s.name !== null ? s.name : cachedName(existing);
  const userName = existing && existing.userName !== undefined ? existing.userName : null;
  const entry = { sid: s.sid, cwd: s.cwd, pid: s.pid, lastSeen: now, name, userName };
  atomicWrite(file, JSON.stringify(entry));
  if (!existing) added++; else refreshed++;
}

// ── Step 4: report (never delete — the plugin owns eviction) ─────────────
let untouched = 0;
if (fs.existsSync(activeDir)) {
  for (const fname of fs.readdirSync(activeDir)) {
    if (!fname.endsWith('.json')) continue;
    const entry = readJsonSafe(path.join(activeDir, fname), null);
    if (!entry || !entry.sid || alive.has(entry.sid)) continue;
    if (projectHash && !inProject(entry.cwd || '')) continue;
    untouched++;
  }
}

console.log(`synced: ${added} added, ${refreshed} refreshed (${untouched} tracked entries untouched)`);
