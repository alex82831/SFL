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
for suite in language builtins edges closures functional concurrency ipc async http httpd diagnostics imports regression stdlib namespaces shadowing patterns tuples sugar system protocols mysql postgres mongodb redis smtp jwt template toml markdown datetime smallpkgs utilpkgs; do
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

# Tail calls reuse the frame, so recursion through one is unbounded where the
# same depth of ordinary recursion would be refused.
expect "a tail call recurses without limit" 0 "done" "$SFL" -e 'def loop(n) = if (n == 0) then "done" else loop(n - 1)
print(loop(500000))'
expect "a tail call through a chain of two" 0 "0" "$SFL" -e 'def even(n) = if (n == 0) then "0" else odd(n - 1)
def odd(n) = if (n == 0) then "1" else even(n - 1)
print(even(200000))'

# The environment variables the manual lists.
expect "SFL_MAX_DEPTH raises the limit" 0 "3000" env SFL_MAX_DEPTH=6000 "$SFL" -e 'def f(n) = if (n == 0) then 0 else 1 + f(n - 1)
print(f(3000))'
expect "SFL_MAX_DEPTH lowers it too" 1 "depth 50" env SFL_MAX_DEPTH=50 "$SFL" -e 'def f(n) = 1 + f(n + 1)
f(0)'
expect "the default depth is 1200" 1 "depth 1200" "$SFL" -e 'def f(n) = 1 + f(n + 1)
f(0)'
out=$(env NO_COLOR=1 "$SFL" -e '1/0' 2>&1)
if [[ "$out" != *$'\e['* ]]; then pass "NO_COLOR turns colour off"; else fail "NO_COLOR turns colour off"; fi
expect "COLUMNS is read for the terminal width" 0 "" env COLUMNS=40 "$SFL" -e 'print("")'

# SFL_HOME/libs is the last place import looks.
mkdir -p "$tmp/home/libs"
printf 'def fromHomeLib() = "home lib"\n' > "$tmp/home/libs/homelib.sfl"
printf 'import "homelib"\nprint(fromHomeLib())\n' > "$tmp/usehome.sfl"
expect "import finds a module under SFL_HOME/libs" 0 "home lib" \
  env SFL_HOME="$tmp/home" "$SFL" "$tmp/usehome.sfl"
expect "and does not without it" 1 "cannot find module" "$SFL" "$tmp/usehome.sfl"

# Errors carry a line number from the middle of a file.
printf 'val a = 1\nval b = 2\nval c = undefinedName\n' > "$tmp/line.sfl"
expect "error reports the right line" 1 "line.sfl:3" "$SFL" "$tmp/line.sfl"

# Standard input: readLine one line at a time, readAll in one go, and the two
# sharing the same stream. The fixture lives under lib/ so the suite loop and the
# differential run do not meet it without a pipe.
out=$(printf 'one\ntwo\n' | "$SFL" lib/stdio_probe.sfl lines)
want='[0] one
[1] two
lines: 2
after the end: null'
if [[ "$out" == "$want" ]]; then pass "readLine reads a line at a time"; else fail "readLine reads a line at a time ($out)"; fi

out=$(printf 'a\nb' | "$SFL" lib/stdio_probe.sfl lines)
if [[ "$out" == *"[1] b"* && "$out" == *"lines: 2"* ]]; then
  pass "readLine reads a last line with no newline"
else fail "readLine reads a last line with no newline ($out)"; fi

out=$(printf '' | "$SFL" lib/stdio_probe.sfl lines)
if [[ "$out" == "lines: 0"*"after the end: null" ]]; then
  pass "readLine at end of input is null"
else fail "readLine at end of input is null ($out)"; fi

out=$(printf 'x\ny\n' | "$SFL" lib/stdio_probe.sfl all)
if [[ "$out" == *"bytes: 4"* && "$out" == *"lines: 3"* && "$out" == *'text: "x\ny\n"'* &&
      "$out" == *'again: ""'* ]]; then
  pass "readAll takes the whole stream"
else fail "readAll takes the whole stream ($out)"; fi

out=$(printf '中\n' | "$SFL" lib/stdio_probe.sfl all)
if [[ "$out" == *"bytes: 4"* && "$out" == *"chars: 2"* ]]; then
  pass "readAll decodes UTF-8"
else fail "readAll decodes UTF-8 ($out)"; fi

out=$(printf 'first\nsecond\nthird\n' | "$SFL" lib/stdio_probe.sfl mixed)
if [[ "$out" == *"first: first"* && "$out" == *'rest: "second\nthird\n"'* ]]; then
  pass "readLine and readAll share one stream"
else fail "readLine and readAll share one stream ($out)"; fi

# The prompt goes to standard output, ahead of the answer, and never into it.
out=$(printf 'bob\n' | "$SFL" lib/stdio_probe.sfl prompt)
if [[ "$out" == 'name? got: "bob"' ]]; then
  pass "readLine's prompt is printed, not read back"
else fail "readLine's prompt is printed, not read back ($out)"; fi

# help(): the same reference table from either engine, byte for byte.
hout=$("$SFL" lib/stdio_probe.sfl help)
if [[ "$hout" == *"math"* && "$hout" == *"hypot(x, y)"* &&
      "$hout" == *"Length of the hypotenuse"* &&
      "$hout" == *"no builtin matches 'no such builtin anywhere'"* ]]; then
  pass "help prints the grouped reference"
else fail "help prints the grouped reference"; fi
if "$SFL" -c lib/stdio_probe.sfl -o "$tmp/stdio" >/dev/null 2>&1; then
  if [[ "$("$tmp/stdio" help)" == "$hout" ]]; then
    pass "help is identical compiled"
  else
    fail "help is identical compiled"
    diff <(printf '%s\n' "$hout") <(printf '%s\n' "$("$tmp/stdio" help)") | head -8
  fi
  cout=$(printf 'one\ntwo\n' | "$tmp/stdio" lines)
  if [[ "$cout" == "$want" ]]; then pass "readLine is identical compiled"
  else fail "readLine is identical compiled ($cout)"; fi
else
  fail "the stdio fixture compiles"
fi

# The compiler's own command line, in a cache of its own so the shared one is
# neither rebuilt nor removed under the rest of the run.
ctmp="$tmp/cache"
expect "--build-runtime builds the archives" 0 "library modules in" \
  env SFL_CACHE="$ctmp" "$SFL" --build-runtime
expect "--build-runtime again only reports where they are" 0 "$ctmp" \
  env SFL_CACHE="$ctmp" "$SFL" --build-runtime
expect "--clean-cache removes them" 0 "removed the compiler cache" \
  env SFL_CACHE="$ctmp" "$SFL" --clean-cache
expect "--clean-cache with nothing to remove" 0 "" env SFL_CACHE="$ctmp" "$SFL" --clean-cache

printf 'println(6 * 7)\n' > "$tmp/opt.sfl"
for level in -O0 -O1 -O2 -O3; do
  if "$SFL" -c "$tmp/opt.sfl" $level -o "$tmp/opt$level" >/dev/null 2>&1 &&
     [[ "$("$tmp/opt$level")" == "42" ]]; then
    pass "$level compiles and runs"
  else
    fail "$level compiles and runs"
  fi
done
if "$SFL" -c "$tmp/opt.sfl" --keep -o "$tmp/kept" >/dev/null 2>&1 && [[ -f "$tmp/kept.ll" ]]; then
  pass "--keep leaves the LLVM IR beside the executable"
else
  fail "--keep leaves the LLVM IR beside the executable"
fi
expect "--eval is the long spelling of -e" 0 "42" "$SFL" --eval 'print(6 * 7)'
expect "-source is the historical spelling" 0 "42" "$SFL" -source "$tmp/opt.sfl"
out=$("$SFL" --time -e 'print("x")' 2>&1)
if [[ "$out" == *"x"* && "$out" == *"parse"* ]]; then
  pass "--time reports the timings"
else fail "--time reports the timings ($out)"; fi
out=$("$SFL" --time -e 'print("x")' 2>/dev/null)
if [[ "$out" == "x" ]]; then pass "--time keeps stdout clean"; else fail "--time keeps stdout clean"; fi

# ------------------------------------------------------------------ the REPL

# Driven over a pipe, with a HOME of its own so the session history neither reads
# nor writes the one belonging to whoever is running the tests.
oldhome=$HOME
export HOME="$tmp/replhome"
mkdir -p "$HOME"
repl() { printf '%b' "$1" | "$SFL" -repl 2>&1; }
replcheck() {
  local name=$1 want=$2 input=$3
  local out
  out=$(repl "$input")
  if [[ "$out" == *"$want"* ]]; then pass "$name"; else fail "$name (wanted '$want' in: $out)"; fi
}

replcheck "repl over a pipe" "= 2 : int" '1 + 1\n:quit\n'
replcheck "repl echoes a value with its type" '= "x" : string' '"x"\n:quit\n'
replcheck "repl says goodbye" "bye" ':quit\n'
replcheck "repl banner counts the builtins" "367 builtins loaded" ':quit\n'
replcheck "repl keeps definitions between lines" "= 7 : int" 'val a = 7\na\n:quit\n'
replcheck "repl reports an error and carries on" "division by zero" '1/0\n:quit\n'
replcheck "repl continues an unfinished line" "= 3 : int" 'val s = 1 +\n2\ns\n:quit\n'
replcheck "repl continues an unfinished string" "= \"ab\" : string" '"a${\n"b"}"\n:quit\n'
replcheck ":help lists the commands" ":builtins  Show the builtin reference" ':help\n:quit\n'
replcheck ":vars is empty to begin with" "no variables defined yet" ':vars\n:quit\n'
replcheck ":vars lists what was defined" "a" 'val a = 1\n:vars\n:quit\n'
replcheck ":funcs lists what was defined" "f" 'def f(x) = x\n:funcs\n:quit\n'
replcheck ":type reports a type" "object" ':type {"k": 1}\n:quit\n'
replcheck ":doc shows one builtin" "Hex SHA-256 digest" ':doc sha256\n:quit\n'
replcheck ":doc says when there is no such name" "no builtin" ':doc nosuchname\n:quit\n'
replcheck ":builtins filters by name" "sha256(s)" ':builtins sha\n:quit\n'
replcheck ":builtins filters by group" "bitAnd(a, b)" ':builtins bits\n:quit\n'
replcheck ":ast shows the tree" "Arith" ':ast 1 + n\n:quit\n'
replcheck ":time reports how long it took" "elapsed:" ':time sum(range(10))\n:quit\n'
replcheck ":bench reports three numbers" "3 runs: min" ':bench 3 1+1\n:quit\n'
replcheck ":depth shows the limit" "max call depth: 1200" ':depth\n:quit\n'
replcheck ":depth sets the limit" "max call depth: 900" ':depth 900\n:quit\n'
replcheck ":pretty off says so" "pretty printing off" ':pretty off\n:quit\n'
replcheck ":pretty on says so" "pretty printing on" ':pretty on\n:quit\n'
replcheck ":color off says so" "colour off" ':color off\n:quit\n'
replcheck ":threads knows there are none" "no threads started" ':threads\n:quit\n'
replcheck ":trace shows the last error again" "division by zero" '1/0\n:trace\n:quit\n'
replcheck ":trace with nothing to show" "no error yet" ':trace\n:quit\n'
replcheck ":reset forgets the definitions" "no variables defined yet" \
  'val a = 1\n:reset\n:vars\n:quit\n'
replcheck ":history shows what was typed" "val a = 1" 'val a = 1\n:history\n:quit\n'
replcheck "an unknown command is named" "unknown command" ':nosuchcommand\n:quit\n'

# :load runs a file into the session; :save writes the session out.
printf 'val loaded = 41\n' > "$tmp/loadme.sfl"
replcheck ":load brings a file in" "= 42 : int" ":load $tmp/loadme.sfl\nloaded + 1\n:quit\n"
replcheck ":load names a file it cannot read" "no such file" ":load $tmp/nosuchfile.sfl\n:quit\n"
repl "val saved = 1\n:save $tmp/session.sfl\n:quit\n" >/dev/null
if [[ -f "$tmp/session.sfl" ]] && grep -q "val saved = 1" "$tmp/session.sfl"; then
  pass ":save writes the session out"
else
  fail ":save writes the session out"
fi

# History is kept between sessions, in a file under $HOME.
repl 'val remembered = 1\n:quit\n' >/dev/null
if [[ -f "$HOME/.sfl_history" ]] && grep -q "val remembered = 1" "$HOME/.sfl_history"; then
  pass "history is written under \$HOME"
else
  fail "history is written under \$HOME"
fi
replcheck "history is read back next session" "val remembered = 1" ':history\n:quit\n'
export HOME="$oldhome"

# -------------------------------------------------------------------- tooling

echo
echo "tooling"

expect "syntax dumps JSON" 0 '"keywords"' "$SFL" syntax
expect "check accepts a clean file" 0 "" "$SFL" check language.sfl
printf 'val y = ]\n' > "$tmp/broken.sfl"
expect "check rejects a broken file" 1 "unexpected ']'" "$SFL" check "$tmp/broken.sfl"
expect "check --json locates the error" 1 '"line": 1' "$SFL" check --json "$tmp/broken.sfl"
expect "check without files is a usage error" 2 "Usage" "$SFL" check

# The manual's builtin reference is generated from the interpreter's own registry,
# so regenerating it must change nothing: a builtin added without rebuilding the
# manual would leave the two out of step. The file is put back either way.
manual=$(cd .. && pwd)/docs/SFL-使用手册.md
cp "$manual" "$tmp/manual.before"
if out=$(cd .. && SFL="$SFL" ./tools/build-manual.sh 2>&1); then
  if cmp -s "$manual" "$tmp/manual.before"; then
    pass "the manual's builtin reference is up to date"
  else
    fail "the manual's builtin reference is up to date (run tools/build-manual.sh)"
    diff "$tmp/manual.before" "$manual" | head -8 | sed 's/^/        /'
  fi
else
  fail "the manual regenerates ($out)"
fi
cp "$tmp/manual.before" "$manual"

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
expect "build tasks lists them" 0 "describe" env -C "$btmp" "$SFL" build tasks
expect "build --force recompiles" 0 "built build/demo" env -C "$btmp" "$SFL" build --force
expect "build build --force is the long spelling" 0 "built build/demo" \
  env -C "$btmp" "$SFL" build build --force
expect "build clean removes the output" 0 "removed build/" env -C "$btmp" "$SFL" build clean
if [[ ! -d "$btmp/build" ]]; then pass "and the directory is gone"; else fail "and the directory is gone"; fi
expect "build clean with nothing to remove" 0 "" env -C "$btmp" "$SFL" build clean
expect "build after a clean compiles again" 0 "built build/demo" env -C "$btmp" "$SFL" build
# Editing a source file makes the next build do the work again.
printf '\n// touched\n' >> "$btmp/src/main.sfl"
expect "build notices a changed source" 0 "built build/demo" env -C "$btmp" "$SFL" build
expect "and is fresh again straight after" 0 "up to date" env -C "$btmp" "$SFL" build
printf '{"name":"demo","version":"0.1.0","main":"main"}\n' > "$btmp/sfl.pkg"
expect "build pkg packs the project" 0 "packed demo 0.1.0" env -C "$btmp" "$SFL" build pkg
if [[ -f "$btmp/demo-0.1.0.sflpkg" ]]; then pass "and writes the archive"; else fail "and writes the archive"; fi
expect "build --help explains itself" 0 "sfl build <task>" env -C "$btmp" "$SFL" build --help
expect "build -h does too" 0 "sfl build <task>" env -C "$btmp" "$SFL" build -h

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
  # The TextMate grammar, tokenized by the real engine. Needs the extension's own
  # dev dependencies, so it is skipped rather than installed on the fly.
  if [[ -d ../editors/vscode/node_modules ]]; then
    if out=$(cd ../editors/vscode && node test/grammar.js 2>&1); then
      pass "vscode grammar ($(printf '%s\n' "$out" | grep -c '^ok') checks)"
    else
      printf '%s\n' "$out"
      fail "vscode grammar"
    fi
  else
    echo "  skip  vscode grammar (run 'npm install' in editors/vscode)"
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

# The example projects, built and tested the way a reader would: `sfl build
# setup`, `sfl build`, `sfl build test` for each one.
echo
if out=$(./examples.sh 2>&1); then
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

# ------------------------------------------------------------------ coverage

# The gates: every builtin, and every function a package exports, has to be
# reached by something above.
echo
for gate in coverage pkgcoverage syntax; do
  if out=$(SFL="$SFL" "$SFL" "$gate.sfl" 2>&1); then
    printf '  %s\n' "$out"
  else
    printf '  %s\n' "$out"
    fails=$((fails + 1))
  fi
done

echo
if [[ $fails -eq 0 ]]; then
  echo "all command-line checks passed"
else
  echo "$fails command-line check(s) failed"
  exit 1
fi
