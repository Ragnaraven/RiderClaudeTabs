#!/bin/bash
# Renames the Rider terminal tab for the current Claude Code session.
# Usage: bash ~/.claude/rider-plugin/rename-tab.sh "My Topic Name"
#
# Identifies the correct Claude session by finding which Claude process
# is an ancestor of the current script, then writes a rename request.

NAME="$1"
if [ -z "$NAME" ]; then
  echo "Usage: bash ~/.claude/rider-plugin/rename-tab.sh \"Tab Name\""
  exit 1
fi

TABS_DIR="$HOME/.claude/rider-plugin/tabs"
mkdir -p "$TABS_DIR"

# Strategy 1: Walk up our process tree to find an ancestor Claude PID
# that has a session file. This uniquely identifies which Claude invoked us.
find_ancestor_session() {
  # Collect all alive Claude session PIDs
  local session_pids=()
  for f in "$HOME/.claude/sessions/"*.json; do
    [ -f "$f" ] || continue
    local pid
    pid=$(basename "$f" .json)
    if tasklist //FI "PID eq $pid" 2>/dev/null | grep -q "$pid"; then
      session_pids+=("$pid")
    fi
  done

  # Walk up from our PID looking for an ancestor that matches a session PID
  local current=$$
  local depth=0
  while [ "$current" != "0" ] && [ "$current" != "1" ] && [ $depth -lt 10 ]; do
    for sp in "${session_pids[@]}"; do
      if [ "$current" = "$sp" ]; then
        echo "$sp"
        return 0
      fi
    done
    # Get parent PID (Windows compatible via wmic)
    local parent
    parent=$(wmic process where "ProcessId=$current" get ParentProcessId 2>/dev/null | grep -o '[0-9]*' | head -1)
    [ -z "$parent" ] && break
    current="$parent"
    depth=$((depth + 1))
  done
  return 1
}

# Strategy 2: Fallback — use the most recently started session in this CWD
find_newest_session() {
  local CWD_WIN
  CWD_WIN="$(pwd -W 2>/dev/null || pwd)"
  local norm_cwd
  norm_cwd=$(echo "$CWD_WIN" | sed 's|\\|/|g')

  local newest_sid=""
  local newest_time=0

  for f in "$HOME/.claude/sessions/"*.json; do
    [ -f "$f" ] || continue
    local pid
    pid=$(basename "$f" .json)
    if ! tasklist //FI "PID eq $pid" 2>/dev/null | grep -q "$pid"; then
      continue
    fi

    local file_cwd
    file_cwd=$(grep -o '"cwd":"[^"]*"' "$f" | head -1 | sed 's/"cwd":"//;s/"$//')
    local norm_file
    norm_file=$(echo "$file_cwd" | sed 's|\\\\|/|g; s|\\|/|g')

    if [ "$norm_cwd" = "$norm_file" ]; then
      local started
      started=$(grep -o '"startedAt":[0-9]*' "$f" | head -1 | sed 's/"startedAt"://')
      if [ -n "$started" ] && [ "$started" -gt "$newest_time" ] 2>/dev/null; then
        newest_time="$started"
        newest_sid=$(grep -o '"sessionId":"[^"]*"' "$f" | head -1 | sed 's/"sessionId":"//;s/"$//')
      fi
    fi
  done

  [ -n "$newest_sid" ] && echo "$newest_sid"
}

# Try ancestor walk first (precise), fall back to newest (heuristic)
ancestor_pid=$(find_ancestor_session)
if [ -n "$ancestor_pid" ]; then
  sid=$(grep -o '"sessionId":"[^"]*"' "$HOME/.claude/sessions/$ancestor_pid.json" | head -1 | sed 's/"sessionId":"//;s/"$//')
  if [ -n "$sid" ]; then
    echo "{\"name\":\"$NAME\"}" > "$TABS_DIR/$sid.json"
    exit 0
  fi
fi

sid=$(find_newest_session)
if [ -n "$sid" ]; then
  echo "{\"name\":\"$NAME\"}" > "$TABS_DIR/$sid.json"
  exit 0
fi

echo "No matching Claude session found" >&2
exit 1
