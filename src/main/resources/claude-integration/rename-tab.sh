#!/bin/bash
# ─────────────────────────────────────────────────────────────────────
# rename-tab.sh — Renames the Rider terminal tab for the current
#                 Claude Code session.
# ─────────────────────────────────────────────────────────────────────
# Usage: bash ~/.claude/rider-plugin/rename-tab.sh "Tab Name"
#
# How it works:
#   Writes a rename directive as "{sessionId}.json" into the plugin's
#   tabs directory. The plugin watches this dir and applies the name to
#   the matching terminal tab on the next poll.
#
# Strategies (in order):
#   1. TERM_SESSION_ID → session-map lookup (race-condition free).
#      The SessionStart hook pre-writes the mapping when Claude starts.
#   2. Fallback: scan Claude session files for the newest alive process
#      whose cwd matches this terminal's cwd. Unreliable with multiple
#      tabs in the same project, but useful as a safety net.
#
# Cross-platform notes:
#   Strategy 2's process-liveness check uses `tasklist` on Windows
#   (Git Bash / MSYS2) and `kill -0` on macOS / Linux.
# ─────────────────────────────────────────────────────────────────────

NAME="$1"
if [ -z "$NAME" ]; then
  echo 'Usage: bash ~/.claude/rider-plugin/rename-tab.sh "Tab Name"'
  exit 1
fi

TABS_DIR="$HOME/.claude/rider-plugin/tabs"
MAP_DIR="$HOME/.claude/rider-plugin/session-map"
mkdir -p "$TABS_DIR"

# ── Detect OS for process-liveness check ───────────────────────────
# Returns 0 if a PID is currently alive, 1 otherwise.
is_pid_alive() {
  local pid="$1"
  case "$(uname -s 2>/dev/null)" in
  MINGW* | MSYS* | CYGWIN*)
    tasklist //FI "PID eq $pid" 2>/dev/null | grep -q "$pid"
    ;;
  *)
    kill -0 "$pid" 2>/dev/null
    ;;
  esac
}

# ── Strategy 1: TERM_SESSION_ID → session-map lookup ──────────────
# The session-start-hook.sh writes: session-map/{TERM_SESSION_ID} → claude-session-id
# Each tab has its own unique TERM_SESSION_ID, so there is no shared state
# and no race condition between concurrent renames.
TERM_SID="${TERM_SESSION_ID}"
if [ -n "$TERM_SID" ] && [ -f "$MAP_DIR/$TERM_SID" ]; then
  sid=$(cat "$MAP_DIR/$TERM_SID")
  if [ -n "$sid" ]; then
    echo "{\"name\":\"$NAME\"}" >"$TABS_DIR/$sid.json"
    exit 0
  fi
fi

# ── Strategy 2 (fallback): newest alive Claude session in this cwd ──
# Used when TERM_SESSION_ID isn't set (non-JetBrains terminal) or when
# the mapping doesn't exist yet (hook hasn't run, fresh session, etc.).
SESSIONS_DIR="$HOME/.claude/sessions"
CWD_WIN="$(pwd -W 2>/dev/null || pwd)"
norm_cwd=$(echo "$CWD_WIN" | sed 's|\\|/|g')
best_sid=""
best_time=0

for sf in "$SESSIONS_DIR/"*.json; do
  [ -f "$sf" ] || continue
  pid=$(basename "$sf" .json)
  is_pid_alive "$pid" || continue

  file_cwd=$(grep -o '"cwd":"[^"]*"' "$sf" | head -1 | sed 's/"cwd":"//;s/"$//')
  norm_file=$(echo "$file_cwd" | sed 's|\\\\|/|g; s|\\|/|g')
  [ "$norm_cwd" != "$norm_file" ] && continue

  sid=$(grep -o '"sessionId":"[^"]*"' "$sf" | head -1 | sed 's/"sessionId":"//;s/"$//')
  [ -z "$sid" ] && continue

  started=$(grep -o '"startedAt":[0-9]*' "$sf" | head -1 | sed 's/"startedAt"://')
  if [ -n "$started" ] && [ "$started" -gt "$best_time" ] 2>/dev/null; then
    best_time="$started"
    best_sid="$sid"
  fi
done

if [ -n "$best_sid" ]; then
  echo "{\"name\":\"$NAME\"}" >"$TABS_DIR/$best_sid.json"
  exit 0
fi

echo "No Claude session found" >&2
exit 1
