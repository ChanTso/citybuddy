#!/usr/bin/env bash
# Print the shared retail deployment instructions without taking ownership of its processes.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ "$#" -gt 1 ]; then
  echo "usage: scripts/demo.sh [start|stop] (instructions only)" >&2
  exit 2
fi
case "${1:-start}" in
  start|stop) exec python3 "$repo_root/scripts/demo_story.py" --mode "${1:-start}" ;;
  *) echo "usage: scripts/demo.sh [start|stop] (instructions only)" >&2; exit 2 ;;
esac
