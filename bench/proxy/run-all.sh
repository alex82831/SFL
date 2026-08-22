#!/bin/bash
# The whole measurement pipeline, in order. Run from bench/proxy.
set -u
DIR=$(cd "$(dirname "$0")" && pwd)
cd "$DIR"

echo "=== [1/4] main matrix ==="
./bench.sh

echo "=== [2/4] GC threshold sweep (fixed build) ==="
./gc-sweep.sh 2>&1 | tee logs/gc-sweep.txt

echo "=== [3/4] syscall profile ==="
./syscalls.sh 2>&1 | tee logs/syscalls.txt

echo "=== [4/4] 120s soak of the fixed build ==="
./soak.sh rproxy-fixed 120 256 2>&1 | tee logs/soak-final.txt

pkill -x nginx 2>/dev/null
echo "ALL DONE"
