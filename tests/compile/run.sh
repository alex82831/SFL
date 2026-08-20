#!/usr/bin/env bash
#
# Differential tests for the LLVM compiler.
#
# For every program under accept/, the compiled executable must produce byte-for-byte the
# same output as the interpreter — that is the whole correctness criterion, and it is
# checked rather than assumed. For every program under reject/, compilation must fail
# with the message named on the file's first line, so unsupported constructs stay
# diagnosed instead of silently miscompiled.
set -uo pipefail
cd "$(dirname "$0")"

SFL=${SFL:-../../target/scala-3.8.4/sfl}
if [[ ! -x "$SFL" ]]; then
  echo "no binary at $SFL — run: sbt nativeLink" >&2
  exit 1
fi
SFL=$(cd "$(dirname "$SFL")" && pwd)/$(basename "$SFL")

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

pass=0
fail=0
ok()   { pass=$((pass + 1)); }
bad()  { fail=$((fail + 1)); printf '  FAIL  %s\n' "$1"; }

echo "compiler: accepted programs (compiled output must equal interpreted output)"
for src in accept/*.sfl; do
  name=$(basename "$src" .sfl)
  exe="$work/$name"

  if ! build=$("$SFL" -c "$src" -o "$exe" 2>&1); then
    bad "$name: compilation failed"
    echo "$build" | sed 's/^/        /' | head -6
    continue
  fi

  interp=$("$SFL" "$src" 2>&1); interp_code=$?
  comp=$("$exe" 2>&1); comp_code=$?

  if [[ "$interp" != "$comp" ]]; then
    bad "$name: output differs"
    diff <(printf '%s\n' "$interp") <(printf '%s\n' "$comp") | sed 's/^/        /' | head -10
  elif [[ "$interp_code" != "$comp_code" ]]; then
    bad "$name: exit code differs (interpreted $interp_code, compiled $comp_code)"
  else
    ok
    printf '  ok    %-14s %s\n' "$name" "$(printf '%s' "$interp" | head -1 | cut -c1-46)"
  fi
done

echo
echo "compiler: refused programs (must fail with a specific message)"
for src in reject/*.sfl; do
  name=$(basename "$src" .sfl)
  want=$(head -1 "$src" | sed -n 's|^// EXPECT: ||p')
  if [[ -z "$want" ]]; then
    bad "$name: the file has no '// EXPECT:' line"
    continue
  fi

  if out=$("$SFL" -c "$src" -o "$work/$name" 2>&1); then
    bad "$name: expected the program to be refused, but it built"
  elif [[ "$out" != *"$want"* ]]; then
    bad "$name: wanted a message containing '$want'"
    echo "$out" | sed 's/^/        /' | head -4
  elif [[ "$out" != *"Compile error"* && "$out" != *"Syntax error"* ]]; then
    bad "$name: the failure was not reported as an error the user can act on"
  else
    ok
    printf '  ok    %-20s %s\n' "$name" "$want"
  fi
done

echo
echo "compiler: $pass passed, $fail failed"
[[ $fail -eq 0 ]]
