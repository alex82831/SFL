#!/usr/bin/env bash
# Compiles the runtime on its own, for checking it without going through sbt.
# The compiler does the same thing at run time, into its cache directory.
set -euo pipefail
cd "$(dirname "$0")"
CC=${CC:-$(command -v clang)}
FLAGS="-std=c11 -O2 -Wall -Wextra -Wno-unused-parameter"
if [[ "$(uname)" == "Darwin" ]]; then
  CC=$(xcrun --find clang)
  FLAGS="$FLAGS -isysroot $(xcrun --show-sdk-path)"
fi
out=${1:-/tmp/sfl-rt}
mkdir -p "$out"
for f in *.c; do
  echo "  $f"
  $CC $FLAGS -c "$f" -o "$out/${f%.c}.o"
done
ar rcs "$out/libsflrt.a" "$out"/*.o
echo "built $out/libsflrt.a"
