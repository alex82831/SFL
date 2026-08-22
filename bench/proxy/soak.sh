#!/bin/bash
# Soak test: sustained load against one rproxy build; reports whether the
# proxy survived and its GC pause profile.
#   usage: soak.sh <rproxy|rproxy-fixed> [seconds] [conns]
set -u
DIR=$(cd "$(dirname "$0")" && pwd)
BIN=${1:-rproxy}
SECS=${2:-30}
CONNS=${3:-64}

pkill -x rproxy 2>/dev/null; pkill -x rproxy-fixed 2>/dev/null; sleep 0.3
pgrep -f nginx-backend.conf >/dev/null || {
  taskset -c 0 nginx -c "$DIR/nginx-backend.conf" >/dev/null 2>&1 &
  sleep 0.5
}

SFL_GC_LOG=1 taskset -c 1 "$DIR/$BIN" 8001 127.0.0.1:9001 127.0.0.1:9002 \
  >"$DIR/logs/soak-$BIN.txt" 2>&1 &
SRV=$!
sleep 0.6

taskset -c 3 wrk -t2 -c"$CONNS" -d"${SECS}s" --latency http://127.0.0.1:8001/ \
  | grep -E "Latency +[0-9]|50%|75%|90%|99%|Requests/sec|Socket errors"

ALIVE=dead
curl -s -o /dev/null --max-time 2 http://127.0.0.1:8001/ && ALIVE=alive
echo "== after ${SECS}s at c=${CONNS}: proxy is ${ALIVE}"
kill $SRV 2>/dev/null; sleep 0.3

echo "== GC profile:"
grep -c GCLOG "$DIR/logs/soak-$BIN.txt" | awk '{print "  collections:", $1}'
grep GCLOG "$DIR/logs/soak-$BIN.txt" | sed 's/[a-z_]*=//g' | awk '
  {n++; sum+=$2; if($2>max)max=$2}
  END{if(n>0) printf "  pause avg=%dus max=%dus, total stopped %.1fms\n", sum/n, max, sum/1000}'
