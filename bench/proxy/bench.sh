#!/bin/bash
# Benchmark harness: the SFL rproxy (stock and fixed-runtime builds) against an
# nginx proxy and the bare backend.
#
# Topology (4 CPUs, everything on loopback):
#   CPU 0     nginx backend (1 worker, ports 9001/9002)
#   CPU 1[,2] the proxy under test (1 or 2 CPUs)
#   CPU 3     wrk (2 threads)          [direct runs borrow CPU 2 as well]
#
# The proxy is restarted before every case: the stock SFL runtime can deadlock
# under load (GC stop-the-world vs malloc arena lock — see the report), so each
# case gets a fresh process and a post-run liveness probe; a dead proxy is
# recorded in the `wedged` CSV column rather than poisoning later cases.
#
# Each case: 2s warmup, a measured run with latency percentiles, the proxy's
# CPU use (jiffies over wall time) and RSS. Results land in results/results.csv,
# raw wrk output in results/raw/.
set -u
DIR=$(cd "$(dirname "$0")" && pwd)
OUT=$DIR/results
RAW=$OUT/raw
mkdir -p "$RAW" "$DIR/logs"
CSV=$OUT/results.csv

DUR=${DUR:-10s}
WARMUP=${WARMUP:-2s}
BACKEND_CPU=0
WRK_CPU=3
HZ=$(getconf CLK_TCK)

log() { echo "[bench] $*" >&2; }

# --------------------------------------------------------- process utilities

tree_pids() { echo "$1"; pgrep -P "$1" 2>/dev/null; }

tree_jiffies() {
  local t=0 p j
  for p in $(tree_pids "$1"); do
    [ -r "/proc/$p/stat" ] && j=$(awk '{print $14+$15}' "/proc/$p/stat" 2>/dev/null) && t=$((t + ${j:-0}))
  done
  echo "$t"
}

tree_rss_kb() {
  local t=0 p r
  for p in $(tree_pids "$1"); do
    [ -r "/proc/$p/status" ] && r=$(awk '/VmRSS/{print $2}' "/proc/$p/status" 2>/dev/null) && t=$((t + ${r:-0}))
  done
  echo "$t"
}

wait_ready() { # url
  local i
  for i in $(seq 1 100); do
    curl -s -o /dev/null --max-time 1 "$1" && return 0
    sleep 0.1
  done
  log "server at $1 never came up"
  return 1
}

# ------------------------------------------------- proxy lifecycle per case

PROXY_PID=""
PROXY_KIND="none"   # none | sfl | sflfix | ngx
PROXY_CPUS=""
PROXY_URL=""

stop_proxy() {
  [ -n "$PROXY_PID" ] && kill "$PROXY_PID" 2>/dev/null
  pkill -x rproxy 2>/dev/null
  pkill -x rproxy-fixed 2>/dev/null
  pkill -f "nginx-proxy-" 2>/dev/null
  PROXY_PID=""
  sleep 0.3
}

restart_proxy() {
  stop_proxy
  case "$PROXY_KIND" in
    none) return 0 ;;
    sfl)
      taskset -c "$PROXY_CPUS" "$DIR/rproxy" 8001 127.0.0.1:9001 127.0.0.1:9002 \
        >"$DIR/logs/rproxy.out" 2>&1 &
      PROXY_PID=$!
      PROXY_URL=http://127.0.0.1:8001/ ;;
    sflfix)
      taskset -c "$PROXY_CPUS" "$DIR/rproxy-fixed" 8001 127.0.0.1:9001 127.0.0.1:9002 \
        >"$DIR/logs/rproxy-fixed.out" 2>&1 &
      PROXY_PID=$!
      PROXY_URL=http://127.0.0.1:8001/ ;;
    ngx)
      local n
      n=$(awk -F, '{print NF}' <<<"$PROXY_CPUS")
      local conf="$DIR/logs/nginx-proxy-$n.conf"
      sed "s/worker_processes 2;/worker_processes $n;/" "$DIR/nginx-proxy.conf" >"$conf"
      taskset -c "$PROXY_CPUS" nginx -c "$conf" >"$DIR/logs/nginx-proxy.out" 2>&1 &
      PROXY_PID=$!
      PROXY_URL=http://127.0.0.1:8002/ ;;
  esac
  wait_ready "$PROXY_URL" || return 1
}

# ------------------------------------------------------------------ one case

run_case() { # target cpus port payload conns wrk_cpus extra_header...
  local target=$1 cpus=$2 port=$3 payload=$4 conns=$5 wrk_cpus=$6
  shift 6
  local url="http://127.0.0.1:${port}${payload}"
  local tag=""
  [ $# -gt 0 ] && tag="_churn"
  local rawf="$RAW/${target}_${cpus}cpu_$(echo "$payload" | tr '/.' '__')_c${conns}${tag}.txt"

  if [ "$PROXY_KIND" != none ]; then
    restart_proxy || { echo "$target,$cpus,$payload,$conns,0,0,,,,,,,0,0,-,-,1" >>"$CSV"; return; }
  fi

  taskset -c "$wrk_cpus" wrk -t2 -c"$conns" -d"$WARMUP" "$@" "$url" >/dev/null 2>&1
  sleep 0.2

  local j0=0 t0 t1 j1 cpu_pct="-" rss_mb="-"
  if [ -n "$PROXY_PID" ]; then j0=$(tree_jiffies "$PROXY_PID"); fi
  t0=$(date +%s.%N)
  taskset -c "$wrk_cpus" wrk -t2 -c"$conns" -d"$DUR" --latency -s "$DIR/report.lua" "$@" "$url" >"$rawf" 2>&1
  t1=$(date +%s.%N)
  if [ -n "$PROXY_PID" ]; then
    j1=$(tree_jiffies "$PROXY_PID")
    cpu_pct=$(echo "$j0 $j1 $t0 $t1 $HZ" | awk '{printf "%.0f", ($2-$1)/$5/($4-$3)*100}')
    rss_mb=$(tree_rss_kb "$PROXY_PID" | awk '{printf "%.1f", $1/1024}')
  fi

  local wedged=0
  if [ "$PROXY_KIND" != none ]; then
    curl -s -o /dev/null --max-time 2 "$PROXY_URL" || wedged=1
  fi

  local rps transfer pctl errs non2xx
  rps=$(awk '/^Requests\/sec/{print $2}' "$rawf")
  transfer=$(awk '/^Transfer\/sec/{print $2}' "$rawf")
  pctl=$(awk -F, '/^PCTL/{printf "%s,%s,%s,%s,%s,%s", $2,$3,$4,$5,$6,$7}' "$rawf")
  errs=$(awk -F, '/^ERRS/{print $2+$3+$4+$6}' "$rawf")
  non2xx=$(awk -F, '/^ERRS/{print $5}' "$rawf")
  [ -z "$pctl" ] && pctl=",,,,,"
  echo "$target,$cpus,$payload$tag,$conns,${rps:-0},${transfer:-0},$pctl,${errs:-0},${non2xx:-0},$cpu_pct,$rss_mb,$wedged" >>"$CSV"
  log "$target ${cpus}cpu $payload$tag c=$conns -> ${rps:-0} rps, cpu ${cpu_pct}%, rss ${rss_mb}MB$( [ $wedged = 1 ] && echo ' [WEDGED]')"
}

matrix() { # target cpus port wrk_cpus
  local t=$1 c=$2 p=$3 w=$4 n r
  for n in 16 64 256 1024; do
    for r in 1 2; do run_case "$t" "$c" "$p" / "$n" "$w"; done
  done
  for n in 64 256; do run_case "$t" "$c" "$p" /4k.bin "$n" "$w"; done
  for n in 64 256; do run_case "$t" "$c" "$p" /64k.bin "$n" "$w"; done
  run_case "$t" "$c" "$p" / 64 "$w" -H "Connection: close"
}

# ----------------------------------------------------------------- the suite

trap 'stop_proxy; [ -f "$DIR/logs/backend.pid" ] && kill "$(cat "$DIR/logs/backend.pid")" 2>/dev/null' EXIT

sysctl -qw net.core.somaxconn=4096 2>/dev/null
sysctl -qw net.ipv4.ip_local_port_range="1024 65535" 2>/dev/null
ulimit -n 65535 2>/dev/null

echo "target,cpus,payload,conns,rps,transfer_per_s,lat_mean_us,p50_us,p90_us,p99_us,p999_us,max_us,sock_errors,non2xx,proxy_cpu_pct,proxy_rss_mb,wedged" >"$CSV"

pkill -x rproxy 2>/dev/null; pkill -x rproxy-fixed 2>/dev/null; pkill -x nginx 2>/dev/null; sleep 0.5

mkdir -p "$DIR/www"
[ -f "$DIR/www/4k.bin" ] || head -c 4096 /dev/urandom | base64 | head -c 4096 >"$DIR/www/4k.bin"
[ -f "$DIR/www/64k.bin" ] || head -c 65536 /dev/urandom | base64 | head -c 65536 >"$DIR/www/64k.bin"

log "starting backend nginx on CPU $BACKEND_CPU"
taskset -c $BACKEND_CPU nginx -c "$DIR/nginx-backend.conf" >"$DIR/logs/backend.out" 2>&1 &
wait_ready http://127.0.0.1:9001/ || exit 1

log "=== direct backend (ceiling) ==="
PROXY_KIND=none
matrix direct - 9001 "2,3"

log "=== SFL rproxy (stock runtime), 1 CPU ==="
PROXY_KIND=sfl PROXY_CPUS=1
matrix sfl 1 8001 "$WRK_CPU"

log "=== SFL rproxy (stock runtime), 2 CPUs ==="
PROXY_KIND=sfl PROXY_CPUS=1,2
matrix sfl 2 8001 "$WRK_CPU"

if [ -x "$DIR/rproxy-fixed" ]; then
  log "=== SFL rproxy (fixed runtime), 1 CPU ==="
  PROXY_KIND=sflfix PROXY_CPUS=1
  matrix sflfix 1 8001 "$WRK_CPU"

  log "=== SFL rproxy (fixed runtime), 2 CPUs ==="
  PROXY_KIND=sflfix PROXY_CPUS=1,2
  matrix sflfix 2 8001 "$WRK_CPU"
fi

log "=== nginx proxy, 1 worker, 1 CPU ==="
PROXY_KIND=ngx PROXY_CPUS=1
matrix ngx 1 8002 "$WRK_CPU"

log "=== nginx proxy, 2 workers, 2 CPUs ==="
PROXY_KIND=ngx PROXY_CPUS=1,2
matrix ngx 2 8002 "$WRK_CPU"

stop_proxy
log "done -> $CSV"
