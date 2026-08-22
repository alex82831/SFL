#!/bin/bash
# Diagnose rproxy throughput variance: full wrk output, socket-state snapshots,
# and a 502 probe while under load.
set -u
DIR=$(cd "$(dirname "$0")" && pwd)

pkill -x rproxy 2>/dev/null; sleep 0.3
pgrep -f nginx-backend.conf >/dev/null || {
  taskset -c 0 nginx -c "$DIR/nginx-backend.conf" >/dev/null 2>&1 &
  sleep 0.5
}
RPROXY_TRACE=1 taskset -c 1 "$DIR/rproxy" 8001 127.0.0.1:9001 127.0.0.1:9002 >"$DIR/logs/diag-rproxy.txt" 2>&1 &
SRV=$!
sleep 0.6

( sleep 4
  echo "--- mid-run ss -s:"; ss -s | head -6
  echo "--- mid-run rproxy sockets:"; ss -tnp 2>/dev/null | grep -c rproxy
  echo "--- mid-run TIME_WAIT to backends:"; ss -tn state time-wait '( dport = 9001 or dport = 9002 )' | wc -l
  echo "--- mid-run ESTAB to backends:"; ss -tn state established '( dport = 9001 or dport = 9002 )' | wc -l
) &

taskset -c 3 wrk -t2 -c64 -d8s --latency http://127.0.0.1:8001/
wait %2 2>/dev/null

kill $SRV 2>/dev/null
echo "--- STALL lines:"; grep -c STALL "$DIR/logs/diag-rproxy.txt"
echo "--- SLOW lines:"; grep -c SLOW "$DIR/logs/diag-rproxy.txt"
grep SLOW "$DIR/logs/diag-rproxy.txt" | head -8
grep STALL "$DIR/logs/diag-rproxy.txt" | head -5
echo "--- slow-phase histogram (exch-dominant vs dial-dominant vs pre):"
grep SLOW "$DIR/logs/diag-rproxy.txt" | awk '{
  gsub(/[a-z]*=|ms/,"",$2); gsub(/[a-z]*=|ms/,"",$3); gsub(/[a-z]*=|ms/,"",$4); gsub(/[a-z]*=|ms/,"",$5);
  if ($5+0 >= $2*0.6) e++; else if ($4+0 >= $2*0.6) d++; else if ($3+0 >= $2*0.6) p++; else m++
} END {printf "exch=%d dial=%d pre=%d mixed=%d\n", e, d, p, m}'
