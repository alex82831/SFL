#!/bin/bash
# GC-threshold sweep on the SFL rproxy (fixed-runtime build — the stock one
# deadlocks before a sweep can finish): quantify how much of the gap is the
# collector's cadence. Each setting runs REPS times; report every run's rps
# and p99 so variance is visible. Needs the instrumented runtime (env knobs).
set -u
DIR=$(cd "$(dirname "$0")" && pwd)
REPS=${REPS:-4}
CONNS=${CONNS:-64}
DUR=${DUR:-8s}

pgrep -f nginx-backend.conf >/dev/null || {
  taskset -c 0 nginx -c "$DIR/nginx-backend.conf" >/dev/null 2>&1 &
  sleep 0.5
}

one() { # label env...
  local label=$1
  shift
  for r in $(seq 1 "$REPS"); do
    pkill -x rproxy 2>/dev/null; pkill -x rproxy-fixed 2>/dev/null; sleep 0.3
    env "$@" taskset -c 1 "$DIR/rproxy-fixed" 8001 127.0.0.1:9001 127.0.0.1:9002 >/dev/null 2>&1 &
    sleep 0.6
    taskset -c 3 wrk -t2 -c"$CONNS" -d2s http://127.0.0.1:8001/ >/dev/null 2>&1
    local out
    out=$(taskset -c 3 wrk -t2 -c"$CONNS" -d"$DUR" --latency http://127.0.0.1:8001/ 2>&1)
    local rps p50 p99
    rps=$(awk '/^Requests\/sec/{print $2}' <<<"$out")
    p50=$(awk '/ 50%/{print $2}' <<<"$out")
    p99=$(awk '/ 99%/{print $2}' <<<"$out")
    echo "$label run$r: rps=$rps p50=$p50 p99=$p99"
  done
  pkill -x rproxy 2>/dev/null; pkill -x rproxy-fixed 2>/dev/null
}

one stock       SFL_NOOP=1
one thr8mb      SFL_GC_MIN_THRESHOLD=8388608
one thr32mb     SFL_GC_MIN_THRESHOLD=33554432
one thr128mb    SFL_GC_MIN_THRESHOLD=134217728
