#!/bin/bash
# SessionStart hook — queues session ID for the next rename-tab.sh call.
# Uses a numbered queue so multiple simultaneous sessions don't collide.

INPUT=$(cat)
SID=$(echo "$INPUT" | sed -n 's/.*"session_id":"\([^"]*\)".*/\1/p')

if [ -n "$SID" ]; then
  QUEUE_DIR="$HOME/.claude/rider-plugin/session-queue"
  mkdir -p "$QUEUE_DIR"
  # Write with timestamp as filename for FIFO ordering
  TIMESTAMP=$(date +%s%N 2>/dev/null || date +%s)
  echo "$SID" > "$QUEUE_DIR/$TIMESTAMP"
fi
