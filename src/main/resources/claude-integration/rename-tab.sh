#!/bin/bash
# Renames the Rider terminal tab for the current Claude Code session.
# Usage: bash ~/.claude/rider-plugin/rename-tab.sh "Tab Name" [sessionId]
#
# Writes a rename request keyed by this script's PID.
# The plugin walks up from this PID to find the terminal tab.
# If sessionId is provided, also writes a session-keyed file for the poll fallback.

NAME="$1"
SESSION_ID="$2"

if [ -z "$NAME" ]; then
  echo "Usage: bash ~/.claude/rider-plugin/rename-tab.sh \"Tab Name\" [sessionId]"
  exit 1
fi

TABS_DIR="$HOME/.claude/rider-plugin/tabs"
mkdir -p "$TABS_DIR"

# Write PID-keyed file (plugin uses this PID to trace back to the terminal tab)
echo "{\"name\":\"$NAME\",\"pid\":$$}" > "$TABS_DIR/pid-$$.json"

# Also write session-keyed file if session ID provided (for poll fallback)
if [ -n "$SESSION_ID" ]; then
  echo "{\"name\":\"$NAME\"}" > "$TABS_DIR/$SESSION_ID.json"
fi
