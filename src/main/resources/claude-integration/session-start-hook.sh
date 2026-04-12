#!/bin/bash
# SessionStart hook — maps TERM_SESSION_ID → Claude session ID.
# Each terminal tab gets its own mapping file — no shared queue, no race condition.

INPUT=$(cat)
SID=$(echo "$INPUT" | sed -n 's/.*"session_id":"\([^"]*\)".*/\1/p')

if [ -n "$SID" ]; then
  MAP_DIR="$HOME/.claude/rider-plugin/session-map"
  mkdir -p "$MAP_DIR"

  TERM_SID="${TERM_SESSION_ID}"
  if [ -n "$TERM_SID" ]; then
    # Race-condition free: each tab writes to its own unique file
    echo "$SID" > "$MAP_DIR/$TERM_SID"
  fi

  # Also keep the queue for backwards compatibility with old rename-tab.sh
  QUEUE_DIR="$HOME/.claude/rider-plugin/session-queue"
  mkdir -p "$QUEUE_DIR"
  TIMESTAMP=$(date +%s%N 2>/dev/null || date +%s)
  echo "$SID" > "$QUEUE_DIR/$TIMESTAMP"
fi
