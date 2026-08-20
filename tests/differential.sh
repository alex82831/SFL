#!/usr/bin/env bash
#
# Runs the language's own test suites through the compiler.
#
# Every suite here is a real program of a few hundred assertions, so compiling it and
# demanding byte-identical output is a far stronger check than any set of hand-written
# compiler cases: it exercises the whole language against the interpreter that defines
# what the language means. A suite listed in SKIP names why it cannot be compiled.
set -uo pipefail
cd "$(dirname "$0")"

SFL=${SFL:-../target/scala-3.8.4/sfl}
if [[ ! -x "$SFL" ]]; then
  echo "no binary at $SFL — run: sbt nativeLink" >&2
  exit 1
fi
SFL=$(cd "$(dirname "$SFL")" && pwd)/$(basename "$SFL")

# suite:reason — these use something a compiled binary cannot carry.
SKIP=(
  "language:uses eval(), which needs the interpreter"
  "regression:uses eval(), which needs the interpreter"
  "diagnostics:uses eval(), which needs the interpreter"
  "concurrency:asserts that eval() works on a spawned thread; compiled concurrency is covered by tests/async.sfl and tests/compile/accept/threads.sfl"
  "httpd:drives real servers with random WebSocket/HTTP2 masking and an optional openssl, so output is not byte-reproducible; compiled coverage is tests/compile/accept/httpd_compiles.sfl"
  "mysql:drives an in-process fake server on spawned threads; compiled coverage is tests/compile/accept/db_clients_compile.sfl"
  "postgres:drives an in-process fake server on spawned threads; compiled coverage is tests/compile/accept/db_clients_compile.sfl"
  "smtp:drives an in-process fake server on spawned threads plus an optional openssl certificate, so output is not byte-reproducible"
)

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

pass=0
fail=0
skipped=0

echo "differential: the language's own suites, compiled"
for src in *.sfl; do
  name=$(basename "$src" .sfl)

  reason=""
  for entry in "${SKIP[@]}"; do
    [[ "${entry%%:*}" == "$name" ]] && reason="${entry#*:}"
  done
  if [[ -n "$reason" ]]; then
    skipped=$((skipped + 1))
    printf '  skip  %-14s %s\n' "$name" "$reason"
    continue
  fi

  if ! build=$("$SFL" -c "$src" -o "$work/$name" 2>&1); then
    fail=$((fail + 1))
    printf '  FAIL  %s: compilation failed\n' "$name"
    printf '%s\n' "$build" | sed 's/^/        /' | head -6
    continue
  fi

  interp=$("$SFL" "$src" 2>&1); icode=$?
  comp=$("$work/$name" 2>&1); ccode=$?

  if [[ "$interp" != "$comp" ]]; then
    fail=$((fail + 1))
    printf '  FAIL  %s: output differs\n' "$name"
    diff <(printf '%s\n' "$interp") <(printf '%s\n' "$comp") | sed 's/^/        /' | head -12
  elif [[ "$icode" != "$ccode" ]]; then
    fail=$((fail + 1))
    printf '  FAIL  %s: exit code differs (interpreted %s, compiled %s)\n' "$name" "$icode" "$ccode"
  else
    pass=$((pass + 1))
    printf '  ok    %-14s %s\n' "$name" "$(printf '%s' "$interp" | tail -1 | cut -c1-46)"
  fi
done

echo
echo "differential: $pass identical, $fail differing, $skipped skipped"
[[ $fail -eq 0 ]]
