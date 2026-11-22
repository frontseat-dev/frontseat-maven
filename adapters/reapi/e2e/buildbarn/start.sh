#!/bin/sh
# Starts a single-machine Buildbarn in ./run: storage, frontend (REAPI on localhost:8980), scheduler,
# one worker and its runner. Actions run directly on this machine, so Maven, the JDK and the local
# repository of this machine are the worker's tools.
set -eu
cd "$(dirname "$0")"
HERE="$(pwd)"; BIN="${BB_BIN:-$HERE/bin}"
mkdir -p run && cd run
mkdir -p storage-ac/persistent_state storage-cs/persistent_state storage-cms/persistent_state \
  storage-fsac/persistent_state worker/build worker/cache worker/cs/persistent_state logs
export OS=Linux PWD="$(pwd)"
for p in storage:bb_storage frontend:bb_storage scheduler:bb_scheduler worker:bb_worker runner:bb_runner; do
  nohup "$BIN/${p#*:}" "$HERE/config/${p%%:*}.jsonnet" > "logs/${p%%:*}.log" 2>&1 &
  echo $! > "logs/${p%%:*}.pid"
done
sleep 5
for p in storage frontend scheduler worker runner; do
  kill -0 "$(cat "logs/$p.pid")" 2>/dev/null || { echo "$p did not start; see run/logs/$p.log" >&2; exit 1; }
done
echo "Buildbarn is up: REAPI endpoint localhost:8980 (logs in $HERE/run/logs)"
