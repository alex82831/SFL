#!/bin/bash
# Quick GC-observability trial: run a server with SFL_GC_LOG=1 under load and
# summarize the GC pause lines it printed.
#   usage: gclog-trial.sh <hello|rproxy> <conns> [duration] [extra env...]
set -u
DIR=$(cd "$(dirname "$0")" && pwd)
KIND=${1:-hello}
CONNS=${2:-64}
DUR=${3:-5s}

pkill -x hello 2>/dev/null; pkill -x rproxy 2>/dev/null
pgrep -f nginx-backend.conf >/dev/null || {
  taskset -c 0 nginx -c "$DIR/nginx-backend.conf" >/dev/null 2>&1 &
  sleep 0.5
}

if [ "$KIND" = hello ]; then
  SFL_GC_LOG=1 taskset -c 1 "$DIR/hello" 18081 >"$DIR/logs/gclog.txt" 2>&1 &
  URL=http://127.0.0.1:18081/
else
  SFL_GC_LOG=1 taskset -c 1 "$DIR/rproxy" 8001 127.0.0.1:9001 127.0.0.1:9002 >"$DIR/logs/gclog.txt" 2>&1 &
  URL=http://127.0.0.1:8001/
fi
SRV=$!
sleep 0.6

taskset -c 3 wrk -t2 -c"$CONNS" -d"$DUR" --latency "$URL" | grep -E "Latency +[0-9]|50%|99%|Requests/sec"
kill $SRV 2>/dev/null
sleep 0.3

echo "--- GC log summary:"
grep -c GCLOG "$DIR/logs/gclog.txt" | awk '{print "collections:", $1}'
grep GCLOG "$DIR/logs/gclog.txt" | sed 's/[a-z_]*=//g' | awk '
  {p=$2; s=$3; t=$4; l=$5; h=$6; n++; sum+=p; if(p>max)max=p; ssum+=s; if($4>tmax)tmax=$4; lsum+=l; hsum+=h}
  END{if(n>0) printf "pause avg=%dus max=%dus | stop avg=%dus | threads max=%d | live avg=%dKB heap avg=%dKB\n",
      sum/n, max, ssum/n, tmax, lsum/n, hsum/n}'
tail -3 "$DIR/logs/gclog.txt" | grep GCLOG
