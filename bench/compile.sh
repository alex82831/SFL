#!/usr/bin/env bash
#
# Compares the interpreter against the LLVM compiler on the numeric benchmarks.
#
#   ./bench/compile.sh
#
# Each program is run interpreted and compiled; both outputs must match, so a "speedup"
# can never come from the compiled version doing less work.
set -uo pipefail
cd "$(dirname "$0")/.."

SFL=${SFL:-target/scala-3.8.4/sfl}
REPS=${REPS:-3}

if [[ ! -x "$SFL" ]]; then
  echo "no binary at $SFL — run: sbt nativeLink" >&2
  exit 1
fi
SFL=$(cd "$(dirname "$SFL")" && pwd)/$(basename "$SFL")

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

cat > "$work/fib.sfl" <<'EOF'
def fib(n) = if (n < 2) then n else fib(n - 1) + fib(n - 2)
println(fib(32))
EOF

cat > "$work/loop.sfl" <<'EOF'
var s = 0
var i = 0
while (i < 20000000) {
  s = s + i % 7
  i = i + 1
}
println(s)
EOF

cat > "$work/float.sfl" <<'EOF'
var x = 0.0
var y = 1.0
for (i in range(10000000)) {
  x = x + y * 0.0001
  y = y - x * 0.0001
}
println(round(x * 1000.0), round(y * 1000.0))
EOF

cat > "$work/collatz.sfl" <<'EOF'
def steps(n) {
  var c = 0
  var v = n
  while (v != 1) {
    if (v % 2 == 0) { v = v / 2 } else { v = 3 * v + 1 }
    c = c + 1
  }
  c
}
var best = 0
var arg = 0
for (i in range(1, 300000)) {
  val s = steps(i)
  if (s > best) { best = s; arg = i }
}
println(arg, best)
EOF

cat > "$work/strings.sfl" <<'EOF'
var parts = []
for (i in range(200000)) { push(parts, "item-" + (i % 1000)) }
var total = 0
for (p in parts) { total += length(p) }
println(total, size(frequencies(parts)))
EOF

cat > "$work/closures.sfl" <<'EOF'
def adder(n) = (x) -> x + n
val fns = map(range(2000), (i) -> adder(i))
var total = 0
for (f in fns) { for (k in range(500)) { total += f(k) } }
println(total)
EOF

cat > "$work/objects.sfl" <<'EOF'
var rows = []
for (i in range(120000)) { push(rows, {"id": i, "bucket": i % 97, "w": i * 3}) }
val byBucket = groupBy(rows, (r) -> toString(r.bucket))
var total = 0
for (k in byBucket) { total += sumBy(byBucket[k], (r) -> r.w) }
println(size(byBucket), total)
EOF

# mean wall-clock seconds over REPS runs, measured in one perl process
mean_s() {
  local reps=$1; shift
  perl -MTime::HiRes=time -e '
    my $reps = shift @ARGV;
    open(my $null, ">", "/dev/null");
    my $t0 = time;
    for (1..$reps) {
      my $pid = fork();
      if ($pid == 0) { open(STDOUT, ">&", $null); exec(@ARGV); exit 127; }
      waitpid($pid, 0);
    }
    printf "%.3f", (time - $t0) / $reps;
  ' "$reps" "$@"
}

echo "the first four are statically typed; the last three are not, and pay for boxing"
echo
printf '%-10s %13s %12s %10s  %s\n' "program" "interpreted" "compiled" "speedup" "output"
fails=0
for name in fib loop float collatz strings closures objects; do
  src="$work/$name.sfl"
  exe="$work/$name"
  if ! out=$("$SFL" -c "$src" -o "$exe" 2>&1); then
    printf '%-10s  compile failed: %s\n' "$name" "$(echo "$out" | head -1)"
    fails=$((fails + 1))
    continue
  fi

  # Correctness gate: a speedup only counts if the answers agree.
  interp_out=$("$SFL" "$src" 2>&1)
  comp_out=$("$exe" 2>&1)
  if [[ "$interp_out" != "$comp_out" ]]; then
    printf '%-10s  MISMATCH interpreted=%q compiled=%q\n' "$name" "$interp_out" "$comp_out"
    fails=$((fails + 1))
    continue
  fi

  i_s=$(mean_s "$REPS" "$SFL" "$src")
  c_s=$(mean_s "$REPS" "$exe")
  speed=$(perl -e 'printf "%.1f", $ARGV[1] > 0 ? $ARGV[0]/$ARGV[1] : 0' "$i_s" "$c_s")
  printf '%-10s %11ss %10ss %9sx  %s\n' "$name" "$i_s" "$c_s" "$speed" "$interp_out"
done

echo
if [[ $fails -eq 0 ]]; then
  echo "all programs compiled and agreed with the interpreter"
else
  echo "$fails program(s) failed"
  exit 1
fi
