#!/usr/bin/env bash
#
# Runs the whole test suite: the .sfl suites plus command-line behaviour checks.
#
#   ./tests/run.sh            # uses target/scala-3.8.4/sfl
#   SFL=/path/to/sfl ./tests/run.sh
set -uo pipefail
cd "$(dirname "$0")"

SFL=${SFL:-../target/scala-3.8.4/sfl}
if [[ ! -x "$SFL" ]]; then
  echo "no binary at $SFL — run: sbt nativeLink" >&2
  exit 1
fi
SFL=$(cd "$(dirname "$SFL")" && pwd)/$(basename "$SFL")

fails=0
pass() { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; fails=$((fails + 1)); }

# ---------------------------------------------------------------- .sfl suites

echo "script suites"
for suite in language builtins closures functional concurrency ipc async http httpd diagnostics imports regression stdlib namespaces patterns tuples sugar; do
  if out=$("$SFL" "$suite.sfl" 2>&1); then
    printf '  %s\n' "$out"
  else
    printf '  %s\n' "$out"
    fails=$((fails + 1))
  fi
done

# --------------------------------------------------------- command line tests

echo
echo "command line"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Expects: name, expected exit code, expected substring of combined output, then argv.
expect() {
  local name=$1 want_code=$2 want_text=$3; shift 3
  local out code
  out=$("$@" 2>&1); code=$?
  if [[ $code -ne $want_code ]]; then
    fail "$name (exit $code, wanted $want_code)"
  elif [[ -n "$want_text" && "$out" != *"$want_text"* ]]; then
    fail "$name (output did not contain '$want_text': $out)"
  else
    pass "$name"
  fi
}

expect "--version" 0 "sfl 0." "$SFL" --version
expect "--help" 0 "Usage" "$SFL" --help
expect "-e evaluates" 0 "7" "$SFL" -e 'print(3 + 4)'
# Literal arithmetic is constant-folded, so use an expression that survives to run time.
expect "--ast prints a tree" 0 "Arith" "$SFL" --ast -e 'val n = 1
n + 2'
expect "--ast folds constants" 0 "Lit 3" "$SFL" --ast -e '1 + 2'
expect "unknown option" 2 "unknown option" "$SFL" --nope
expect "missing file" 66 "cannot open" "$SFL" "$tmp/absent.sfl"
expect "runtime error exits 1" 1 "division by zero" "$SFL" -e '1 / 0'
expect "syntax error exits 1" 1 "Syntax error" "$SFL" -e 'val ='
expect "exit(3) is honoured" 3 "" "$SFL" -e 'exit(3)'
expect "exit(0) is honoured" 0 "" "$SFL" -e 'exit(0)'

# A file that ends with a newline: the previous implementation refused these.
printf 'print("trailing newline ok")\n' > "$tmp/nl.sfl"
expect "file ending in a newline" 0 "trailing newline ok" "$SFL" "$tmp/nl.sfl"
printf 'print("no trailing newline ok")' > "$tmp/nonl.sfl"
expect "file without a trailing newline" 0 "no trailing newline ok" "$SFL" "$tmp/nonl.sfl"
printf '' > "$tmp/empty.sfl"
expect "empty file" 0 "" "$SFL" "$tmp/empty.sfl"
printf '\n\n// only a comment\n\n' > "$tmp/comment.sfl"
expect "comment-only file" 0 "" "$SFL" "$tmp/comment.sfl"

# Script arguments.
printf 'print(join(args(), ","))\n' > "$tmp/args.sfl"
expect "args passthrough" 0 "a,b c" "$SFL" "$tmp/args.sfl" a "b c"

# Errors go to stderr and stdout stays clean.
out=$("$SFL" -e 'print("kept"); 1/0' 2>/dev/null)
if [[ "$out" == "kept" ]]; then pass "errors go to stderr"; else fail "errors go to stderr (got '$out')"; fi

# Colour is suppressed when redirected, and by --no-color.
if [[ "$("$SFL" -e 'print("x")' | cat)" == "x" ]]; then
  pass "no escapes when piped"
else
  fail "no escapes when piped"
fi

# Circular imports are reported rather than looping forever.
printf 'import "cyc_b"\nprint("a")\n' > "$tmp/cyc_a.sfl"
printf 'import "cyc_a"\nprint("b")\n' > "$tmp/cyc_b.sfl"
expect "circular import detected" 1 "circular import" "$SFL" "$tmp/cyc_a.sfl"

# A missing module names the search path.
printf 'import "no_such_module"\n' > "$tmp/badimport.sfl"
expect "missing module" 1 "cannot find module" "$SFL" "$tmp/badimport.sfl"

# Deep NON-tail recursion is reported, not a crash (a segfault would give exit 139).
# A tail call uses no stack, so it must not be used here — see the tail-call tests.
expect "recursion guard" 1 "stack overflow" "$SFL" -e 'def f(n) = 1 + f(n+1)
f(0)'

# Errors carry a line number from the middle of a file.
printf 'val a = 1\nval b = 2\nval c = undefinedName\n' > "$tmp/line.sfl"
expect "error reports the right line" 1 "line.sfl:3" "$SFL" "$tmp/line.sfl"

# The REPL still works without a terminal.
out=$(printf '1 + 1\n:quit\n' | "$SFL" -repl 2>&1)
if [[ "$out" == *"= 2 : int"* ]]; then pass "repl over a pipe"; else fail "repl over a pipe"; fi

# -------------------------------------------------------------------- tooling

echo
echo "tooling"

expect "syntax dumps JSON" 0 '"keywords"' "$SFL" syntax
expect "check accepts a clean file" 0 "" "$SFL" check language.sfl
printf 'val y = ]\n' > "$tmp/broken.sfl"
expect "check rejects a broken file" 1 "unexpected ']'" "$SFL" check "$tmp/broken.sfl"
expect "check --json locates the error" 1 '"line": 1' "$SFL" check --json "$tmp/broken.sfl"
expect "check without files is a usage error" 2 "Usage" "$SFL" check

# The generator regenerates the editor assets; run it against a scratch copy and
# require it to succeed and to produce sane JSON.
gen_out=$("$SFL" ../tools/gen-editor-syntax.sfl 2>&1)
if [[ $? -eq 0 && "$gen_out" == *"sfl.tmLanguage.json"* ]]; then
  pass "editor syntax assets regenerate"
else
  fail "editor syntax assets regenerate ($gen_out)"
fi

# sfl build: scaffold, compile, incremental no-op, run, test — in a sandbox.
btmp="$tmp/buildtool-demo"
mkdir -p "$btmp"
( cd "$btmp" && "$SFL" build init demo ) >/dev/null 2>&1
expect "build compiles the scaffold" 0 "built build/demo" env -C "$btmp" "$SFL" build
expect "build again is a no-op" 0 "up to date" env -C "$btmp" "$SFL" build
expect "build run runs main" 0 "hello, demo" env -C "$btmp" "$SFL" build run
expect "build test runs the suite" 0 "all passed" env -C "$btmp" "$SFL" build test
expect "build describe emits the model" 0 '"sourceFiles"' env -C "$btmp" "$SFL" build describe
expect "build rejects unknown tasks" 1 "no task" env -C "$btmp" "$SFL" build nope

# init flags, used by the IDE new-project wizards.
wtmp="$tmp/wizard-demo"
mkdir -p "$wtmp"
expect "init honours --version and --no-tests" 0 "build.sfl, src/main.sfl" \
  env -C "$wtmp" "$SFL" build init wiz --version 2.0.0 --no-tests
if grep -q '"version": "2.0.0"' "$wtmp/build.sfl" && [[ ! -d "$wtmp/tests" ]]; then
  pass "init flags shape the scaffold"
else
  fail "init flags shape the scaffold"
fi

# The LSP and DAP servers, driven over real stdio. Need node; skipped without it.
if command -v node >/dev/null 2>&1; then
  if out=$(node lsp.js "$SFL" 2>&1); then
    pass "lsp end-to-end ($(printf '%s\n' "$out" | grep -c '^ok') checks)"
  else
    printf '%s\n' "$out"
    fail "lsp end-to-end"
  fi
  if out=$(node dap.js "$SFL" 2>&1); then
    pass "dap end-to-end, stdio ($(printf '%s\n' "$out" | grep -c '^ok') checks)"
  else
    printf '%s\n' "$out"
    fail "dap end-to-end, stdio"
  fi
  # The socket transport is what the IntelliJ plugin uses; this also pins the
  # "DAP server listening on port" ready-line its port extractor matches.
  if out=$(node dap.js "$SFL" --socket 2>&1); then
    pass "dap end-to-end, socket ($(printf '%s\n' "$out" | grep -c '^ok') checks)"
  else
    printf '%s\n' "$out"
    fail "dap end-to-end, socket"
  fi
else
  echo "  skip  lsp/dap end-to-end (no node on PATH)"
fi

# ------------------------------------------------------------------- packages

echo
if out=$(./packages/run.sh 2>&1); then
  printf '%s\n' "$out" | tail -1
else
  printf '%s\n' "$out"
  fails=$((fails + 1))
fi

# Binary packages: an archive built beside the source, linked by the compiler and
# ignored by the interpreter, with the two required to agree.
echo
if out=$(./binpkg/run.sh 2>&1); then
  printf '%s\n' "$out" | tail -1
else
  printf '%s\n' "$out"
  fails=$((fails + 1))
fi

# Installing from a git source or a registry name, over local git repositories.
echo
if out=$(./binstall/run.sh 2>&1); then
  printf '%s\n' "$out" | tail -1
else
  printf '%s\n' "$out"
  fails=$((fails + 1))
fi

# The GUI framework: the gui package installed like a user would, its
# reactive core, wire protocol and demo driven headlessly over WebSocket,
# plus interpreter/compiler parity for a whole session.
echo
if out=$(./gui/run.sh 2>&1); then
  printf '%s\n' "$out" | tail -1
else
  printf '%s\n' "$out"
  fails=$((fails + 1))
fi

# --------------------------------------------------------------- the compiler

echo
if out=$(./compile/run.sh 2>&1); then
  printf '%s\n' "$out" | tail -1
else
  printf '%s\n' "$out"
  fails=$((fails + 1))
fi

# The strongest check of the compiler is the suites above, compiled: hundreds of
# assertions each, required to print exactly what the interpreter printed.
echo
if out=$(./differential.sh 2>&1); then
  printf '%s\n' "$out" | tail -1
else
  printf '%s\n' "$out"
  fails=$((fails + 1))
fi

echo
if [[ $fails -eq 0 ]]; then
  echo "all command-line checks passed"
else
  echo "$fails command-line check(s) failed"
  exit 1
fi
