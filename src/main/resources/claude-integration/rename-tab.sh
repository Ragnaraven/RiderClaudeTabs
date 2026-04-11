#!/bin/bash
# Renames the Rider terminal tab for the current Claude Code session.
# Usage: bash ~/.claude/rider-plugin/rename-tab.sh "Tab Name" [sessionId]
#
# If sessionId provided: updates that session's rename file directly.
# Otherwise: finds the newest alive Claude session in the CWD that
# doesn't already have a rename file (first-time naming).

NAME="$1"
SESSION_ID="$2"

if [ -z "$NAME" ]; then
  echo "Usage: bash ~/.claude/rider-plugin/rename-tab.sh \"Tab Name\" [sessionId]"
  exit 1
fi

TABS_DIR="$HOME/.claude/rider-plugin/tabs"
mkdir -p "$TABS_DIR"

# If session ID provided, use it directly (for re-renames and known sessions)
if [ -n "$SESSION_ID" ] && [ "$SESSION_ID" != '$CLAUDE_SESSION_ID' ]; then
  echo "{\"name\":\"$NAME\"}" > "$TABS_DIR/$SESSION_ID.json"
  exit 0
fi

# Auto-discover: find newest alive unmatched session in this CWD
CWD_WIN="$(pwd -W 2>/dev/null || pwd)"
norm_cwd=$(echo "$CWD_WIN" | sed 's|\\|/|g')

best_sid=""
best_time=0

for f in "$HOME/.claude/sessions/"*.json; do
  [ -f "$f" ] || continue
  pid=$(basename "$f" .json)

  if ! tasklist //FI "PID eq $pid" 2>/dev/null | grep -q "$pid"; then
    continue
  fi

  file_cwd=$(grep -o '"cwd":"[^"]*"' "$f" | head -1 | sed 's/"cwd":"//;s/"$//')
  norm_file=$(echo "$file_cwd" | sed 's|\\\\|/|g; s|\\|/|g')
  [ "$norm_cwd" != "$norm_file" ] && continue

  sid=$(grep -o '"sessionId":"[^"]*"' "$f" | head -1 | sed 's/"sessionId":"//;s/"$//')
  [ -z "$sid" ] && continue

  # Skip sessions that ALREADY have a rename file
  [ -f "$TABS_DIR/$sid.json" ] && continue

  started=$(grep -o '"startedAt":[0-9]*' "$f" | head -1 | sed 's/"startedAt"://')
  if [ -n "$started" ] && [ "$started" -gt "$best_time" ] 2>/dev/null; then
    best_time="$started"
    best_sid="$sid"
  fi
done

if [ -n "$best_sid" ]; then
  echo "{\"name\":\"$NAME\"}" > "$TABS_DIR/$best_sid.json"
  exit 0
fi

# No unmatched session — this is a re-rename. Update the newest matched session.
for f in "$HOME/.claude/sessions/"*.json; do
  [ -f "$f" ] || continue
  pid=$(basename "$f" .json)
  if ! tasklist //FI "PID eq $pid" 2>/dev/null | grep -q "$pid"; then continue; fi
  file_cwd=$(grep -o '"cwd":"[^"]*"' "$f" | head -1 | sed 's/"cwd":"//;s/"$//')
  norm_file=$(echo "$file_cwd" | sed 's|\\\\|/|g; s|\\|/|g')
  [ "$norm_cwd" != "$norm_file" ] && continue
  sid=$(grep -o '"sessionId":"[^"]*"' "$f" | head -1 | sed 's/"sessionId":"//;s/"$//')
  [ -z "$sid" ] && continue
  started=$(grep -o '"startedAt":[0-9]*' "$f" | head -1 | sed 's/"startedAt"://')
  if [ -n "$started" ] && [ "$started" -gt "$best_time" ] 2>/dev/null; then
    best_time="$started"; best_sid="$sid"
  fi
done

if [ -n "$best_sid" ]; then
  echo "{\"name\":\"$NAME\"}" > "$TABS_DIR/$best_sid.json"
  exit 0
fi

echo "No Claude session found" >&2
exit 1
