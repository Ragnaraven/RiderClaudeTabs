#!/bin/bash
# Renames the Rider terminal tab for the current Claude Code session.
# Usage: bash ~/.claude/rider-plugin/rename-tab.sh "Tab Name" <sessionId>
#
# sessionId is REQUIRED — Claude Code must pass its own session ID.
# No fallback discovery. No guessing. Exact match only.

NAME="$1"
SESSION_ID="$2"

if [ -z "$NAME" ] || [ -z "$SESSION_ID" ]; then
  echo "Usage: bash ~/.claude/rider-plugin/rename-tab.sh \"Tab Name\" \"sessionId\""
  echo "Error: Both name and sessionId are required"
  exit 1
fi

TABS_DIR="$HOME/.claude/rider-plugin/tabs"
mkdir -p "$TABS_DIR"
echo "{\"name\":\"$NAME\"}" > "$TABS_DIR/$SESSION_ID.json"
