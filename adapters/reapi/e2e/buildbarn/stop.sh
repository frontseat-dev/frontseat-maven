#!/bin/sh
# Stops the Buildbarn started by start.sh. Add "clean" to also delete its storage.
cd "$(dirname "$0")"
for f in run/logs/*.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null; rm -f "$f"; done
[ "${1:-}" = clean ] && rm -rf run
exit 0
