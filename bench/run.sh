#!/usr/bin/env bash
#
# Benchmarks the interpreter, optionally against a build of the previous one.
#
#   ./bench/run.sh                 # time the current build on the full-size workloads
#   ./bench/run.sh /path/to/oldsfl # also run a head-to-head on smaller, identical inputs
#
# Timing runs each program REPS times inside a single perl process and reports the
# mean, so the harness itself does not dominate a millisecond-scale measurement.
set -uo pipefail
cd "$(dirname "$0")/.."

NEW=${NEW:-target/scala-3.8.4/sfl}
OLD=${1:-}
REPS=${REPS:-10}

if [[ ! -x "$NEW" ]]; then
  echo "no binary at $NEW — run: sbt nativeLink" >&2
  exit 1
fi

# mean wall-clock milliseconds per run
#
# A freshly linked binary is not yet in the page cache, and faulting in ~8 MB costs
# tens of milliseconds — enough to swamp a short benchmark. One untimed run first
# removes that from the measurement.
mean_ms() {
  local reps=$1; shift
  "$@" >/dev/null 2>&1
  perl -MTime::HiRes=time -e '
    my $reps = shift @ARGV;
    open(my $null, ">", "/dev/null");
    my $t0 = time;
    for (1..$reps) {
      my $pid = fork();
      if ($pid == 0) { open(STDOUT, ">&", $null); open(STDERR, ">&", $null); exec(@ARGV); exit 127; }
      waitpid($pid, 0);
    }
    printf "%.1f", (time - $t0) * 1000 / $reps;
  ' "$reps" "$@"
}

echo "== current build, full size =="
printf '%-10s %12s\n' "benchmark" "mean"
for bench in fib loop nbody; do
  printf '%-10s %9s ms\n' "$bench" "$(mean_ms "$REPS" "$NEW" "bench/$bench.sfl")"
done
printf '%-10s %9s ms\n' "startup" "$(mean_ms "$REPS" "$NEW" -e 'null')"

if [[ -n "$OLD" ]]; then
  echo
  echo "== head to head, identical inputs =="
  echo "   (sized so the legacy build finishes; note it rejects a trailing newline,"
  echo "    so these copies are written without one)"
  tmp=$(mktemp -d)
  printf 'def fib(n) {\n  if (n < 2) then n else fib(n - 1) + fib(n - 2)\n}\nprint(fib(20))' > "$tmp/fib.sfl"
  printf 'var i = 0\nvar s = 0\nwhile (i < 100000) {\n  s = s + i\n  i = i + 1\n}\nprint(s)' > "$tmp/loop.sfl"
  printf 'var x = 0.0\nvar y = 1.0\nvar i = 0\nwhile (i < 30000) {\n  x = x + y * 0.001\n  y = y - x * 0.001\n  i = i + 1\n}\nprint(i)' > "$tmp/nbody.sfl"

  printf '%-10s %12s %14s %10s\n' "benchmark" "current" "legacy" "speedup"
  for bench in fib loop nbody; do
    # The legacy interpreter is slow enough that repeating it is pure waste.
    new_ms=$(mean_ms "$REPS" "$NEW" "$tmp/$bench.sfl")
    old_ms=$(mean_ms 1 "$OLD" -source "$tmp/$bench.sfl")
    speed=$(perl -e 'printf "%.0f", $ARGV[1] / ($ARGV[0] > 0 ? $ARGV[0] : 1)' "$new_ms" "$old_ms")
    printf '%-10s %9s ms %11s ms %9sx\n' "$bench" "$new_ms" "$old_ms" "$speed"
  done
  rm -rf "$tmp"
fi
