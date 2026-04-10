#!/bin/bash
# Renames the Rider terminal tab for the current Claude Code session.
# Usage: bash ~/.claude/rider-plugin/rename-tab.sh "My Topic Name"
#
# Finds the alive Claude session matching the current working directory,
# then writes a rename request that the Rider plugin picks up.

NAME="$1"
if [ -z "$NAME" ]; then
  echo "Usage: bash ~/.claude/rider-plugin/rename-tab.sh \"Tab Name\""
  exit 1
fi

CWD_WIN="$(pwd -W 2>/dev/null || pwd)"
TABS_DIR="$HOME/.claude/rider-plugin/tabs"
mkdir -p "$TABS_DIR"

for f in "$HOME/.claude/sessions/"*.json; do
  [ -f "$f" ] || continue
  pid=$(basename "$f" .json)

  # Check if process is alive (Windows)
  if ! tasklist //FI "PID eq $pid" 2>/dev/null | grep -q "$pid"; then
    continue
  fi

  # Match by CWD
  file_cwd=$(grep -o '"cwd":"[^"]*"' "$f" | head -1 | sed 's/"cwd":"//;s/"$//')
  norm_file=$(echo "$file_cwd" | sed 's|\\\\|/|g; s|\\|/|g')
  norm_cwd=$(echo "$CWD_WIN" | sed 's|\\|/|g')

  if [ "$norm_cwd" = "$norm_file" ]; then
    sid=$(grep -o '"sessionId":"[^"]*"' "$f" | head -1 | sed 's/"sessionId":"//;s/"$//')
    echo "{\"name\":\"$NAME\"}" > "$TABS_DIR/$sid.json"
    exit 0
  fi
done

echo "No matching Claude session found for: $CWD_WIN" >&2
exit 1
