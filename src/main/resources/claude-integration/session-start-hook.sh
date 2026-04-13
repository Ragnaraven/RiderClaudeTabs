#!/bin/bash
# SessionStart hook — maps TERM_SESSION_ID → Claude session ID
# and injects a tab-rename reminder into the conversation context.
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

# Inject a tab-rename reminder into Claude's conversation context.
# This fires on every session start/resume, ensuring Claude never forgets.
cat <<'HOOK_JSON'
{"additionalContext":"RIDER TAB RENAME REQUIRED: Your FIRST action in this conversation MUST be to run: bash ~/.claude/rider-plugin/rename-tab.sh \"Topic Name\" — Pick a concise 3-5 word name based on the user's message. Do this BEFORE any other tool calls or responses. This is a blocking requirement."}
HOOK_JSON
