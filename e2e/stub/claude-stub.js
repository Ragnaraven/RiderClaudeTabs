#!/usr/bin/env node
// E2E stub of the Claude CLI. Emulates ONLY the surface the plugin observes — no API calls,
// no accounts, no real sessions:
//  - registers `<home>/sessions/<pid>.json` (sessionId, cwd, startedAt, status, derived name)
//  - touches a transcript under `<home>/projects/stub/<sid>.jsonl` so the restore gate passes
//  - appends its argv to CLAUDE_STUB_LOG — the harness asserts resume commands from this
//  - optional fake-root liveness marker + terminal-title topic escape
//
// Env:
//  CLAUDE_STUB_HOME     (required) the `.claude` dir to register in
//  CLAUDE_STUB_LOG      (optional) argv log file, one JSON object per line
//  CLAUDE_STUB_FLAVOR   (optional) tag recorded in the log ("windows" / "fake") — proves which
//                       wrapper (i.e. which session root's launcher) executed a resume
//  CLAUDE_STUB_PROC_DIR (optional) dir where a `<pid>` liveness marker file is maintained
//  CLAUDE_STUB_TOPIC    (optional) topic emitted as a terminal-title escape after 1s
//  CLAUDE_STUB_TITLE    (optional, "1") emit the FULL claude-shaped title sequence: a bare
//                       `claude` while the CLI starts up, then `<glyph> <topic>` once the
//                       conversation has a topic. Opt-in so the phases that only need the
//                       plain topic escape keep their existing (title-free startup) shape.
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const home = process.env.CLAUDE_STUB_HOME;
if (!home) { console.error('[claude-stub] CLAUDE_STUB_HOME not set'); process.exit(2); }

const argv = process.argv.slice(2);
const log = process.env.CLAUDE_STUB_LOG;
if (log) {
  fs.appendFileSync(log, JSON.stringify({
    ts: Date.now(), pid: process.pid,
    flavor: process.env.CLAUDE_STUB_FLAVOR || null, argv,
  }) + '\n');
}

function argAfter(flag) { const i = argv.indexOf(flag); return i >= 0 ? argv[i + 1] : null; }
const sid = argAfter('--session-id') || argAfter('--resume') || crypto.randomUUID();
const resumed = argv.includes('--resume');

const started = Date.now();
const cwd = process.cwd();
const base = path.basename(cwd).toLowerCase().replace(/[^a-z0-9]+/g, '-') || 'stub';
const sessionsDir = path.join(home, 'sessions');
fs.mkdirSync(sessionsDir, { recursive: true });
const sessionFile = path.join(sessionsDir, process.pid + '.json');

function writeSession() {
  fs.writeFileSync(sessionFile, JSON.stringify({
    pid: process.pid, sessionId: sid, cwd, startedAt: started,
    version: '0.0.0-stub', kind: 'interactive', entrypoint: 'cli',
    name: base + '-' + sid.slice(0, 2), nameSource: 'derived',
    status: 'idle', updatedAt: Date.now(), statusUpdatedAt: Date.now(),
  }));
}
writeSession();

// Transcript, so the plugin's restore gate finds something to resume.
const tdir = path.join(home, 'projects', 'stub');
fs.mkdirSync(tdir, { recursive: true });
fs.appendFileSync(path.join(tdir, sid + '.jsonl'), JSON.stringify({ type: 'stub', sid, ts: started }) + '\n');

// Fake-root liveness marker (mirrors /proc/<pid> existing while the process runs).
const procDir = process.env.CLAUDE_STUB_PROC_DIR;
const markerFile = procDir ? path.join(procDir, String(process.pid)) : null;
if (markerFile) {
  fs.mkdirSync(procDir, { recursive: true });
  fs.writeFileSync(markerFile, String(started));
}
function cleanup() {
  try { if (markerFile) fs.unlinkSync(markerFile); } catch (_) { /* best effort */ }
  process.exit(0);
}
process.on('SIGINT', cleanup);
process.on('SIGTERM', cleanup);
process.on('SIGHUP', cleanup);

// Terminal-title escapes — what the real CLI does once a conversation has a topic. With
// CLAUDE_STUB_TITLE=1 the sequence also carries the startup shape a hand-typed launch shows:
// the program sets a bare `claude` title first, so the tab wears a NON-shell title before the
// topic ever lands (the hand-open regression scenario depends on that ordering).
const topic = process.env.CLAUDE_STUB_TOPIC;
const claudeShapedTitle = process.env.CLAUDE_STUB_TITLE === '1';
const IDLE_GLYPH = String.fromCharCode(0x2733); // the plugin's idle glyph
function setTitle(t) { process.stdout.write('\x1b]0;' + t + '\x07'); }
if (claudeShapedTitle) setTitle('claude');
if (topic) setTimeout(() => setTitle(claudeShapedTitle ? IDLE_GLYPH + ' ' + topic : topic), 1000);

console.log('[claude-stub] pid=' + process.pid + ' sid=' + sid + (resumed ? ' (resumed)' : ' (new)'));
// Heartbeat + control channel: the harness asks a stub to exit by dropping
// `<home>/kill/<pid>` — closing a tab whose process already ended shows no
// terminate-confirmation modal (which would block the robot's EDT calls).
const killFile = path.join(home, 'kill', String(process.pid));
setInterval(() => {
  writeSession();
  try { if (fs.existsSync(killFile)) { fs.unlinkSync(killFile); cleanup(); } } catch (_) { }
}, 1500);
process.stdin.resume();          // stay alive until the pty closes or a signal arrives
