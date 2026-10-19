// Crash recovery for Claude terminal tabs.
//
// After a hard stop that kills the Claude processes without the IDE noticing — a WSL
// shutdown, an OOM, a machine reset — the plugin's alive-tab snapshot can end up holding
// fewer sids than were really open, so its restore pass reopens only that subset. This
// helper rebuilds the snapshot from the evidence that OUTLIVES the crash (per-session
// records and on-disk transcripts), so the plugin's own retry-spawn pass reopens the rest.
//
// It only ever writes the snapshot; it never launches anything itself. The running plugin
// picks the added sids up within a poll or two and opens `claude --resume` tabs for them.
//
// Usage:
//   node recover-tabs.js [--within <minutes>] [--all] [--include-closed] [--apply] [--json]
//
//   --within N        only sessions active in the last N minutes (default 60)
//   --sids a,b,c      only these sessions (full ids or unambiguous prefixes); the time window
//                     is then ignored — a session picked by name is wanted whatever its age
//   --all             every project, not just the one containing cwd (report-wide, but it
//                     cannot find sessions the plugin never recorded — that scan needs a
//                     project root to look under, so run without --all to catch those)
//   --include-closed  also recover sids the user explicitly closed (and un-close them)
//   --apply           write the changes; without it this is a dry run
//   --json            machine-readable output
//
// Exit codes: 0 ok — including "nothing to recover", which is a normal outcome, not a
// failure; 2 when the plugin state dir cannot be found at all.

const fs = require('fs');
const path = require('path');
const os = require('os');
const { resolveProject } = require('./current-project.js');

// ── args ──────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const has = (f) => argv.includes(f);
const val = (f, d) => { const i = argv.indexOf(f); return i >= 0 && argv[i + 1] ? argv[i + 1] : d; };

const WITHIN_MIN = Math.max(1, parseInt(val('--within', '60'), 10) || 60);
const SIDS = (val('--sids', '') || '').split(',').map((s) => s.trim().toLowerCase()).filter(Boolean);
const ALL = has('--all');
const INCLUDE_CLOSED = has('--include-closed');
const APPLY = has('--apply');
const JSON_OUT = has('--json');
const CUTOFF = SIDS.length ? 0 : (Date.now() - WITHIN_MIN * 60 * 1000);

// ── state dir ─────────────────────────────────────────────────────────────────
// The plugin runs on the Windows side, so its state lives in the WINDOWS ~/.claude even
// when this helper runs inside WSL. state-root.json (deployed alongside this file) points
// at that directory in a form this side can open, and it WINS: the plugin writes it to say
// where its state actually is, whereas a local active-sessions/ proves nothing — an older
// build, or a helper run in the wrong home, can leave a stale one behind, and preferring it
// silently reads and rewrites a snapshot the plugin never looks at.
function resolveStateDir() {
  const local = path.join(os.homedir(), '.claude', 'rider-plugin');
  try {
    const ptr = JSON.parse(fs.readFileSync(path.join(local, 'state-root.json'), 'utf8'));
    if (ptr.stateDir && fs.existsSync(path.join(ptr.stateDir, 'active-sessions'))) {
      return { dir: ptr.stateDir, viaPointer: true, rootId: ptr.rootId || null };
    }
  } catch {}
  if (fs.existsSync(path.join(local, 'active-sessions'))) return { dir: local, viaPointer: false };
  return null;
}

// ── path shapes ───────────────────────────────────────────────────────────────
// Mirror ClaudeTabsHelpers.wslCwdToWindows: /mnt/d/x → d:/x. Session cwds are stored
// Windows-translated, so a WSL-side path must be translated before it can be compared.
const wslToWindows = (p) => {
  const m = /^\/mnt\/([a-zA-Z])(\/.*)?$/.exec(String(p).trim());
  return m ? `${m[1]}:${m[2] || '/'}` : null;
};

const normCwd = (p) => {
  let s = String(p || '').replace(/\\/g, '/').trim();
  const w = wslToWindows(s);
  if (w) s = w;
  return s.toLowerCase().replace(/\/+$/, '');
};

// Claude Code's transcript directory name for a cwd: every non-alphanumeric run to '-'.
const mungeCwd = (p) => String(p).replace(/[^A-Za-z0-9]/g, '-');

// A session belongs to the project when its cwd IS the root, sits under it, or is a
// sibling worktree (`<root>-feature`) — the same rule /tabs-status filters by.
const inProject = (cwd, rootNorm) =>
  cwd === rootNorm || cwd.startsWith(rootNorm + '/') || cwd.startsWith(rootNorm + '-');

// ── io ────────────────────────────────────────────────────────────────────────
const readJson = (f, d) => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return d; } };

// Atomic replace, matching how the plugin writes its own state.
function writeJson(file, value) {
  const tmp = `${file}.tmp-${process.pid}`;
  fs.writeFileSync(tmp, JSON.stringify(value, null, 2) + '\n');
  fs.renameSync(tmp, file);
}

// ── transcript lookup ─────────────────────────────────────────────────────────
// Every ~/.claude/projects dir this side can actually open: our own home, the home that
// owns the plugin state, and — only when running on Windows, where the share is reachable —
// the WSL homes named by the session records' `root` ids.
function transcriptDirs(stateDir, roots) {
  const homes = new Set([path.join(os.homedir(), '.claude')]);
  homes.add(path.resolve(stateDir, '..'));
  if (path.sep === '\\') {
    for (const id of roots) {
      const m = /^wsl:(.+):([^:]+)$/.exec(id || '');
      if (!m) continue;
      const [, distro, user] = m;
      const home = user === 'root' ? 'root' : path.join('home', user);
      homes.add(path.join(`\\\\wsl.localhost\\${distro}`, home, '.claude'));
    }
  }
  return [...homes].map((h) => path.join(h, 'projects')).filter((d) => { try { return fs.statSync(d).isDirectory(); } catch { return false; } });
}

// The newest <sid>.jsonl across every visible transcript dir: its mtime (when the session
// last did anything, which survives a crash that never updated the session record) and its
// path, so a session with no record can still be named.
function transcriptInfo(dirs, sid) {
  let newest = null;
  for (const dir of dirs) {
    let entries;
    try { entries = fs.readdirSync(dir); } catch { continue; }
    for (const proj of entries) {
      const f = path.join(dir, proj, `${sid}.jsonl`);
      try {
        const m = fs.statSync(f).mtimeMs;
        if (newest === null || m > newest.mtime) newest = { mtime: m, file: f };
      } catch {}
    }
  }
  return newest;
}

// A human label for a session the plugin never named, read out of its own transcript: the
// rolling summary if one was written, else the first thing the user typed. Transcripts run
// to tens of megabytes, so only the head is read — both live near the start of the file.
const HEAD_BYTES = 256 * 1024;

function transcriptLabel(file) {
  let buf;
  try {
    const fd = fs.openSync(file, 'r');
    try {
      const size = fs.fstatSync(fd).size;
      buf = Buffer.alloc(Math.min(size, HEAD_BYTES));
      fs.readSync(fd, buf, 0, buf.length, 0);
    } finally { fs.closeSync(fd); }
  } catch { return null; }

  let firstPrompt = null;
  for (const line of buf.toString('utf8').split('\n')) {
    if (!line.startsWith('{')) continue;
    let ev;
    try { ev = JSON.parse(line); } catch { continue; }
    if (ev.type === 'summary' && ev.summary) return String(ev.summary).slice(0, 60);
    if (firstPrompt === null && ev.type === 'user' && !ev.isMeta) {
      const c = ev.message && ev.message.content;
      // Only a typed prompt names a tab. A `user` entry can also carry tool-notification
      // XML, a resumed session's replayed assistant turn (bulleted), or an interruption
      // notice — all of which would name the tab after the harness rather than the work.
      const text = typeof c === 'string' ? c.trim() : '';
      const chatter = /^(<|●|⎿|\[Request interrupted|Caveat:|This session is being continued)/;
      if (text && !chatter.test(text)) {
        firstPrompt = text.replace(/\s+/g, ' ').slice(0, 60);
      }
    }
  }
  return firstPrompt;
}

// This side's root id, as the plugin names roots. The pointer the plugin deploys into a
// WSL home carries the authoritative id; WSL_DISTRO_NAME is the fallback when an older
// pointer predates that field.
function ownRootId() {
  if (!state || !state.viaPointer) return null; // same home the plugin writes to
  if (state.rootId) return state.rootId;
  const distro = process.env.WSL_DISTRO_NAME;
  return distro ? `wsl:${distro}:${os.userInfo().username}` : null;
}

// The root id to record for a session we have to backfill: whichever home holds its
// transcript. A wrong root resumes the session in the wrong OS, where its transcript
// does not exist and `claude --resume` fails outright.
function inferRoot(dirs, sid, cwd) {
  for (const dir of dirs) {
    for (const form of [mungeCwd(cwd), wslToWindows(cwd) ? mungeCwd(wslToWindows(cwd)) : null]) {
      if (!form) continue;
      if (fs.existsSync(path.join(dir, form, `${sid}.jsonl`))) {
        const m = /wsl\.localhost[\\/]([^\\/]+)[\\/](home[\\/]([^\\/]+)|root)/.exec(dir);
        if (m) return `wsl:${m[1]}:${m[3] || 'root'}`;
        if (dir.startsWith('/mnt/')) return 'windows';
        // Our own home. When the plugin's state lives on the other side of a /mnt boundary
        // this side is a WSL distro, and the record MUST say so — a rootless record resumes
        // on Windows, where the transcript we just matched does not exist.
        return ownRootId();
      }
    }
  }
  return null;
}

// ── snapshot file ─────────────────────────────────────────────────────────────
const hashFor = (winPath) => winPath.replace(/:\//, '--').replace(/\//g, '-');

// Existing snapshots, indexed by lowercased hash. The snapshot is named for the project as
// RIDER sees it — the Windows path, even when this helper is looking at the project through
// /mnt, and with whatever drive-letter case Rider recorded. Matching an existing file beats
// computing a name: a case or separator difference would otherwise strand the recovery in a
// second snapshot nothing ever reads.
function existingSnapshots(stateDir) {
  const byHash = new Map();
  let files = [];
  try { files = fs.readdirSync(stateDir); } catch {}
  for (const f of files) {
    const m = /^open-tabs-(.*)\.json$/.exec(f);
    if (m) byHash.set(m[1].toLowerCase(), path.join(stateDir, f));
  }
  return byHash;
}

// The snapshot a cwd belongs to: try the cwd itself, then each ancestor, so a session opened
// in a subdirectory lands in its project's snapshot rather than one of its own.
function snapshotForCwd(snapshots, cwd) {
  let cur = String(cwd).replace(/\\/g, '/').replace(/\/+$/, '');
  while (cur && cur.includes('/')) {
    const hit = snapshots.get(hashFor(cur).toLowerCase());
    if (hit) return hit;
    cur = cur.slice(0, cur.lastIndexOf('/'));
  }
  return null;
}

const closedFileFor = (snapFile) =>
  path.join(path.dirname(snapFile), path.basename(snapFile).replace(/^open-tabs-/, 'user-closed-'));

// ── main ──────────────────────────────────────────────────────────────────────
const state = resolveStateDir();
if (!state) {
  const msg = 'Plugin state dir not found — is the Claude tabs plugin installed and has Rider run since?';
  if (JSON_OUT) console.log(JSON.stringify({ ok: false, error: msg }));
  else console.error(msg);
  process.exit(2);
}
const stateDir = state.dir;
const sessionsDir = path.join(stateDir, 'active-sessions');

const project = ALL ? null : resolveProject();
const rootNorm = project ? normCwd(project.root) : null;

// Every per-session record, project-filtered.
const records = [];
const seenRecord = new Set();
const running = new Set();
for (const f of (fs.readdirSync(sessionsDir).filter((f) => f.endsWith('.json')))) {
  const r = readJson(path.join(sessionsDir, f), null);
  if (!r || !r.sid) continue;
  const cwd = normCwd(r.cwd);
  if (rootNorm && !inProject(cwd, rootNorm)) continue;
  seenRecord.add(r.sid);
  records.push({ ...r, cwdNorm: cwd, hasRecord: true });
  // A live pid means the plugin currently believes this session is running — it nulls the
  // field the moment the process is gone. Recovering a running session is at best a no-op
  // and at worst a second tab for it, so it is never a candidate.
  if (r.pid != null) running.add(r.sid);
}

// The eviction backlog — sessions the plugin stopped tracking. A crash evicts every session
// at once, so the tabs lost to one are in here with their names intact, and nowhere else.
for (const e of (readJson(path.join(stateDir, 'session-backlog.json'), []) || [])) {
  if (!e || !e.sid || seenRecord.has(e.sid)) continue;
  const cwd = normCwd(e.cwd);
  if (rootNorm && !inProject(cwd, rootNorm)) continue;
  seenRecord.add(e.sid);
  records.push({
    sid: e.sid, cwd: e.cwd, name: e.name, userName: e.userName, root: e.root || null,
    lastSeen: e.evictedAt || 0, cwdNorm: cwd, hasRecord: false, fromBacklog: true,
  });
}

const dirs = transcriptDirs(stateDir, records.map((r) => r.root));

// Last activity is the later of the record's lastSeen and the transcript's mtime: a crash
// leaves lastSeen stale, and a session the plugin never registered has no record at all.
const candidates = [];
for (const r of records) {
  const info = transcriptInfo(dirs, r.sid);
  const lastActive = Math.max(r.lastSeen || 0, info ? info.mtime : 0);
  if (lastActive < CUTOFF) continue;
  candidates.push({
    sid: r.sid, cwd: r.cwd, userName: r.userName,
    name: r.name || (info ? transcriptLabel(info.file) : null),
    root: r.root, lastActive, transcript: info !== null,
    record: r.hasRecord, fromBacklog: !!r.fromBacklog,
    inferredRoot: r.root ? null : inferRoot(dirs, r.sid, r.cwd),
  });
}

// Sessions with a recent transcript but no record at all — the plugin never saw them, so
// nothing else in the state dir remembers they were open. Only reachable in project mode:
// the scan is by transcript DIRECTORY, which is named for a project root we only know when
// one project is in scope.
const known = new Set(candidates.map((c) => c.sid));
if (project) {
  const forms = new Set([mungeCwd(project.root)]);
  const win = wslToWindows(project.root);
  if (win) forms.add(mungeCwd(win));
  for (const dir of dirs) {
    for (const form of forms) {
      let files = [];
      try { files = fs.readdirSync(path.join(dir, form)); } catch { continue; }
      for (const f of files) {
        const m = /^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.jsonl$/.exec(f);
        if (!m || known.has(m[1])) continue;
        let mtime;
        try { mtime = fs.statSync(path.join(dir, form, f)).mtimeMs; } catch { continue; }
        if (mtime < CUTOFF) continue;
        known.add(m[1]);
        candidates.push({
          sid: m[1], cwd: project.root, name: transcriptLabel(path.join(dir, form, f)),
          userName: null, root: null,
          lastActive: mtime, transcript: true, record: false, fromBacklog: false,
          inferredRoot: inferRoot(dirs, m[1], project.root),
        });
      }
    }
  }
}

// An explicit pick: keep only the requested sessions, and say which requests matched nothing.
const unknownSids = [];
if (SIDS.length) {
  const kept = candidates.filter((c) => SIDS.some((p) => c.sid.toLowerCase().startsWith(p)));
  for (const p of SIDS) if (!candidates.some((c) => c.sid.toLowerCase().startsWith(p))) unknownSids.push(p);
  candidates.splice(0, candidates.length, ...kept);
}

const snapshots = existingSnapshots(stateDir);

// The project's own snapshot may not exist yet (a project whose tabs the plugin has never
// recorded); its name is then computed from the project root in Windows form.
let projectSnapFile = null;
if (project) {
  projectSnapFile = snapshotForCwd(snapshots, wslToWindows(project.root) || project.root)
    || path.join(stateDir, `open-tabs-${hashFor(wslToWindows(project.root) || project.root)}.json`);
}

// A session with no transcript on any visible disk cannot be resumed — `claude --resume`
// would fail and the plugin would burn its three attempts on an empty tab.
const unresumable = candidates.filter((c) => !c.transcript);

// Group every recoverable session under the snapshot the plugin restores it from. In project
// mode that is one file; with --all it is one per project, and a session whose project has no
// snapshot at all has nowhere to be restored from and is reported instead of written.
const groups = new Map();
const unplaceable = [];
for (const c of candidates.filter((c) => c.transcript && !running.has(c.sid))) {
  const file = project ? projectSnapFile : snapshotForCwd(snapshots, wslToWindows(c.cwd) || c.cwd);
  if (!file) { unplaceable.push(c); continue; }
  if (!groups.has(file)) {
    groups.set(file, {
      file,
      snapshot: readJson(file, []) || [],
      closed: new Set(readJson(closedFileFor(file), []) || []),
      toAdd: [],
      skippedClosed: [],
    });
  }
  const g = groups.get(file);
  if (g.snapshot.includes(c.sid)) continue;
  if (!INCLUDE_CLOSED && g.closed.has(c.sid)) g.skippedClosed.push(c);
  else g.toAdd.push(c);
}
for (const g of groups.values()) g.toAdd.sort((a, b) => b.lastActive - a.lastActive);

// Running sessions are excluded above; only one the snapshot does NOT already hold is worth
// reporting — that is a live tab the plugin has lost track of, which recovery cannot fix.
const snapshotted = new Set([...groups.values()].flatMap((g) => g.snapshot));
const stillRunning = candidates.filter((c) => running.has(c.sid) && !snapshotted.has(c.sid));

const toAdd = [...groups.values()].flatMap((g) => g.toAdd);
const skippedClosed = [...groups.values()].flatMap((g) => g.skippedClosed);
const alreadyOpen = [...groups.values()].reduce((n, g) => n + g.snapshot.length, 0);

// ── apply ─────────────────────────────────────────────────────────────────────
const backfilled = [];
if (APPLY) {
  for (const g of groups.values()) {
    if (!g.toAdd.length) continue;
    for (const c of g.toAdd) {
      if (c.record) continue;
      // Records store the Windows-comparable cwd (SessionRoot.comparableCwd) — a `/mnt/...`
      // cwd would land in a bucket no project matches and never be restored anywhere.
      writeJson(path.join(sessionsDir, `${c.sid}.json`), {
        sid: c.sid, cwd: wslToWindows(c.cwd) || c.cwd, pid: null, lastSeen: Math.round(c.lastActive),
        name: null, userName: null, ordinal: null, restoreAttempts: 0,
        ...(c.inferredRoot ? { root: c.inferredRoot } : {}),
      });
      backfilled.push(c.sid);
    }
    writeJson(g.file, [...g.snapshot, ...g.toAdd.map((c) => c.sid)]);
    if (INCLUDE_CLOSED && g.closed.size) {
      const remaining = [...g.closed].filter((sid) => !g.toAdd.some((c) => c.sid === sid));
      if (remaining.length !== g.closed.size) writeJson(closedFileFor(g.file), remaining);
    }
  }
}

// ── report ────────────────────────────────────────────────────────────────────
const result = {
  ok: true,
  applied: APPLY && toAdd.length > 0,
  project: project ? { name: project.name, root: project.root } : null,
  withinMinutes: SIDS.length ? null : WITHIN_MIN,
  requestedSids: SIDS,
  unknownSids,
  stateDir,
  snapshotFiles: [...groups.keys()],
  alreadyOpen,
  recovered: toAdd.map((c) => ({
    sid: c.sid, name: c.userName || c.name || null, cwd: c.cwd,
    root: c.root || c.inferredRoot || null,
    lastActive: new Date(Math.round(c.lastActive)).toISOString(),
    hadRecord: c.record, fromBacklog: c.fromBacklog,
  })),
  skippedUserClosed: skippedClosed.map((c) => c.sid),
  skippedNoTranscript: unresumable.map((c) => c.sid),
  skippedNoSnapshot: unplaceable.map((c) => c.sid),
  skippedStillRunning: stillRunning.map((c) => c.sid),
  backfilledRecords: backfilled,
};

if (JSON_OUT) {
  console.log(JSON.stringify(result, null, 2));
} else {
  const scope = project ? project.name : 'all projects';
  console.log(`state: ${stateDir}${state.viaPointer ? ' (via state-root.json)' : ''}`);
  console.log(`scope: ${scope}, active within ${WITHIN_MIN}m, snapshot holds ${alreadyOpen}`);
  if (!toAdd.length) console.log('nothing to recover — every recently-active session is already in the snapshot');
  for (const r of result.recovered) {
    console.log(`  + ${r.sid}  ${r.lastActive}  ${r.root || 'root:?'}  ${r.name || '—'}${r.hadRecord ? '' : (r.fromBacklog ? '  (from eviction history)' : '  (no record — will be backfilled)')}`);
  }
  if (skippedClosed.length) console.log(`  skipped (user-closed): ${skippedClosed.map((c) => c.sid).join(', ')}`);
  if (unresumable.length) console.log(`  skipped (no transcript): ${unresumable.map((c) => c.sid).join(', ')}`);
  if (unplaceable.length) console.log(`  skipped (no snapshot for their project): ${unplaceable.map((c) => c.sid).join(', ')}`);
  if (result.skippedStillRunning.length) console.log(`  skipped (still running): ${result.skippedStillRunning.join(', ')}`);
  console.log(APPLY && toAdd.length
    ? `applied — wrote ${toAdd.length} sid(s) across ${groups.size} snapshot(s)`
    : (toAdd.length ? 'dry run — re-run with --apply to write' : ''));
}
