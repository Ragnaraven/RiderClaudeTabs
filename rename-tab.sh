#!/bin/bash
# Renames the Rider terminal tab for the current Claude Code session.
# Usage: bash ~/.claude/rider-plugin/rename-tab.sh "Tab Name"
#
# Consumes the OLDEST queued session ID (written by SessionStart hook).
# FIFO order ensures each rename gets the correct session.

NAME="$1"

if [ -z "$NAME" ]; then
  echo "Usage: bash ~/.claude/rider-plugin/rename-tab.sh \"Tab Name\""
  exit 1
fi

TABS_DIR="$HOME/.claude/rider-plugin/tabs"
QUEUE_DIR="$HOME/.claude/rider-plugin/session-queue"
mkdir -p "$TABS_DIR"

# Consume the OLDEST queued session (FIFO)
if [ -d "$QUEUE_DIR" ]; then
  OLDEST=$(ls "$QUEUE_DIR" 2>/dev/null | sort -n | head -1)
  if [ -n "$OLDEST" ]; then
    SID=$(cat "$QUEUE_DIR/$OLDEST" 2>/dev/null)
    # Atomic consume: rename the file so no other script grabs it
    if mv "$QUEUE_DIR/$OLDEST" "$QUEUE_DIR/$OLDEST.claimed" 2>/dev/null; then
      rm -f "$QUEUE_DIR/$OLDEST.claimed"
      if [ -n "$SID" ]; then
        echo "{\"name\":\"$NAME\"}" > "$TABS_DIR/$SID.json"
        exit 0
      fi
    fi
  fi
fi

# Fallback for re-renames: update newest alive session in CWD
CWD_WIN="$(pwd -W 2>/dev/null || pwd)"
norm_cwd=$(echo "$CWD_WIN" | sed 's|\\|/|g')
best_sid=""
best_time=0

for sf in "$HOME/.claude/sessions/"*.json; do
  [ -f "$sf" ] || continue
  pid=$(basename "$sf" .json)
  if ! tasklist //FI "PID eq $pid" 2>/dev/null | grep -q "$pid"; then continue; fi
  file_cwd=$(grep -o '"cwd":"[^"]*"' "$sf" | head -1 | sed 's/"cwd":"//;s/"$//')
  norm_file=$(echo "$file_cwd" | sed 's|\\\\|/|g; s|\\|/|g')
  [ "$norm_cwd" != "$norm_file" ] && continue
  sid=$(grep -o '"sessionId":"[^"]*"' "$sf" | head -1 | sed 's/"sessionId":"//;s/"$//')
  [ -z "$sid" ] && continue
  started=$(grep -o '"startedAt":[0-9]*' "$sf" | head -1 | sed 's/"startedAt"://')
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
