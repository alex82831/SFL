#!/bin/bash
# Per-request syscall profile: run N keep-alive requests through each proxy
# under strace -cf and print the syscall totals. Run only when the main
# benchmark is idle — strace slows the traced process drastically.
set -u
DIR=$(cd "$(dirname "$0")" && pwd)
N=${N:-2000}

pgrep -f nginx-backend.conf >/dev/null || {
  taskset -c 0 nginx -c "$DIR/nginx-backend.conf" >/dev/null 2>&1 &
  sleep 0.5
}

profile() { # name port cmd...
  local name=$1 port=$2
  shift 2
  strace -cf -o "$DIR/logs/strace-$name.txt" "$@" >/dev/null 2>&1 &
  local tracer=$!
  sleep 1.2
  # One keep-alive connection, N requests on it, from a tiny python client.
  python3 - "$port" "$N" <<'PY'
import socket, sys
port, n = int(sys.argv[1]), int(sys.argv[2])
s = socket.create_connection(("127.0.0.1", port))
s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
req = b"GET / HTTP/1.1\r\nhost: x\r\nuser-agent: sysprof\r\naccept: */*\r\n\r\n"
buf = b""
for i in range(n):
    s.sendall(req)
    while b"hello from backend\n" not in buf:
        d = s.recv(65536)
        if not d: raise SystemExit("peer closed early at %d" % i)
        buf += d
    buf = b""
s.close()
PY
  kill -INT $tracer 2>/dev/null
  sleep 1
  pkill -x rproxy 2>/dev/null; pkill -x rproxy-fixed 2>/dev/null; pkill -f "nginx-syscall" 2>/dev/null
  sleep 0.3
  echo "=== $name: top syscalls for $N requests (calls, per-request):"
  awk -v n="$N" '/^ *[0-9]/ && $4+0 > n/2 {printf "  %-16s %8d  %5.1f/req\n", $NF, $4, $4/n}' \
    "$DIR/logs/strace-$name.txt" | sort -k2 -rn | head -12
  awk '/total/ {print "  total calls:", $4}' "$DIR/logs/strace-$name.txt"
}

# The fixed-runtime build: identical I/O path; the stock build deadlocks too
# often under strace's slowdown to finish a profile.
profile sfl 8005 "$DIR/rproxy-fixed" 8005 127.0.0.1:9001 127.0.0.1:9002

sed -e "s/worker_processes 2;/worker_processes 1;/" -e "s/listen 8002 /listen 8006 /" \
  -e "s|logs/proxy.pid|logs/nginx-syscall.pid|" "$DIR/nginx-proxy.conf" >"$DIR/logs/nginx-syscall.conf"
profile ngx 8006 nginx -c "$DIR/logs/nginx-syscall.conf"
