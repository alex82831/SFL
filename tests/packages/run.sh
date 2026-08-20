#!/usr/bin/env bash
#
# Versioned packages: the sfl.pkg manifest, semver ranges, the flat one-version-per-run
# model, the `sfl pkg` verbs — and the proof that a compiled program resolves packages
# byte-for-byte like the interpreter, because imports are inlined at parse time.
#
# Every fixture is built here, in a temp dir with a temp SFL_HOME, so the suite leaves
# nothing behind and depends on nothing but the binary.
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
export SFL_HOME="$work/home"
mkdir -p "$SFL_HOME"

pass=0
fail=0
ok()  { pass=$((pass + 1)); printf '  ok    %s\n' "$1"; }
bad() { fail=$((fail + 1)); printf '  FAIL  %s\n' "$1"; shift
        for line in "$@"; do printf '        %s\n' "$line"; done; }

# The project directory every program runs from: ./sfl_packages and ./sfl.pkg are
# relative to the working directory, so the tests fix it.
proj="$work/proj"
mkdir -p "$proj"
in_proj() { (cd "$proj" && "$@"); }

# Expects: name, expected exit code, expected substring, then the command.
expect() {
  local name=$1 want_code=$2 want_text=$3; shift 3
  local out code
  out=$("$@" 2>&1); code=$?
  if [[ $code -ne $want_code ]]; then
    bad "$name (exit $code, wanted $want_code)" "$out"
  elif [[ -n "$want_text" && "$out" != *"$want_text"* ]]; then
    bad "$name (output did not contain '$want_text')" "$out"
  else
    ok "$name"
  fi
}

# ------------------------------------------------------------------- fixtures

# mathx in two versions. The bodies differ so a test can see which one loaded,
# and geo.sfl leans on a file-private helper to prove privacy works in packages.
make_mathx() { # version, reported label
  local dir="$work/src/mathx-$1"
  mkdir -p "$dir"
  printf '{ "name": "mathx", "version": "%s", "main": "main" }\n' "$1" > "$dir/sfl.pkg"
  printf 'def version() = "%s"\ndef twice(x) = x * 2\n' "$2" > "$dir/main.sfl"
  printf 'def _unit() = 1\ndef area(w, h) = w * h * _unit()\n' > "$dir/geo.sfl"
}
make_mathx 1.0.0 1.0.0
make_mathx 1.2.0 1.2.0

echo "packages: sfl pkg build / install"

# build from inside the package dir (no argument) …
expect "pkg build (cwd)" 0 "mathx-1.0.0.sflpkg" \
  bash -c "cd '$work/src/mathx-1.0.0' && '$SFL' pkg build"
# … and with an explicit directory.
expect "pkg build [dir]" 0 "mathx-1.2.0.sflpkg" \
  bash -c "cd '$work' && '$SFL' pkg build 'src/mathx-1.2.0'"

expect "pkg install a .sflpkg" 0 "installed mathx 1.0.0" \
  "$SFL" pkg install "$work/src/mathx-1.0.0/mathx-1.0.0.sflpkg"
expect "pkg install a directory" 0 "installed mathx 1.2.0" \
  "$SFL" pkg install "$work/src/mathx-1.2.0"
expect "reinstall refused without --force" 1 "already installed" \
  "$SFL" pkg install "$work/src/mathx-1.2.0"
expect "reinstall allowed with --force" 0 "installed mathx 1.2.0" \
  "$SFL" pkg install --force "$work/src/mathx-1.2.0"
expect "install of a missing source" 1 "cannot open" \
  "$SFL" pkg install "$work/nope.sflpkg"
expect "unknown subcommand is bad usage" 2 "unknown pkg command" "$SFL" pkg frobnicate
expect "bad usage: install with no source" 2 "" "$SFL" pkg install

echo
echo "packages: import resolution"

printf 'import "mathx"\nprint(mathx.version())\n' > "$proj/bare.sfl"
expect "bare import picks the highest version" 0 "1.2.0" in_proj "$SFL" bare.sfl

printf 'import "mathx/geo"\nprint(mathx.area(3, 4))\n' > "$proj/bymod.sfl"
expect "import by module" 0 "12" in_proj "$SFL" bymod.sfl

printf 'import "mathx/geo" as g\nprint(g.area(6, 7))\n' > "$proj/qual.sfl"
expect "qualified package import" 0 "42" in_proj "$SFL" qual.sfl

printf 'import "mathx/nosuch"\n' > "$proj/nomod.sfl"
expect "missing module inside a package" 1 "has no module 'nosuch'" in_proj "$SFL" nomod.sfl

# A local file named like a package must still win: full backward compatibility.
printf 'def localVersion() = "the file"\n' > "$proj/mathx.sfl"
printf 'import "mathx"\nprint(localVersion())\n' > "$proj/shadowed.sfl"
expect "a plain file shadows a package" 0 "the file" in_proj "$SFL" shadowed.sfl
rm "$proj/mathx.sfl" "$proj/shadowed.sfl"

echo
echo "packages: ranges from ./sfl.pkg"

printf '{ "name": "app", "version": "0.0.1", "deps": { "mathx": "^1.0.0" } }\n' > "$proj/sfl.pkg"
expect "^1.0.0 picks 1.2.0" 0 "1.2.0" in_proj "$SFL" bare.sfl

printf '{ "name": "app", "version": "0.0.1", "deps": { "mathx": "1.0.0" } }\n' > "$proj/sfl.pkg"
expect "exact 1.0.0 pins 1.0.0" 0 "1.0.0" in_proj "$SFL" bare.sfl

printf '{ "name": "app", "version": "0.0.1", "deps": { "mathx": "~1.0.0" } }\n' > "$proj/sfl.pkg"
expect "~1.0.0 excludes 1.2.0" 0 "1.0.0" in_proj "$SFL" bare.sfl

printf '{ "name": "app", "version": "0.0.1", "deps": { "mathx": ">=1.0.0 <1.1.0" } }\n' > "$proj/sfl.pkg"
expect "conjunction range" 0 "1.0.0" in_proj "$SFL" bare.sfl

# The documented error when nothing installed satisfies the range.
printf '{ "name": "app", "version": "0.0.1", "deps": { "mathx": "^3.0.0" } }\n' > "$proj/sfl.pkg"
out=$(in_proj "$SFL" bare.sfl 2>&1); code=$?
if [[ $code -eq 1 && "$out" == *"1.0.0 and 1.2.0 are installed"* \
      && "$out" == *"needs ^3.0.0"* && "$out" == *"sfl pkg install"* ]]; then
  ok "unsatisfiable range names versions, requirer and the fix"
else
  bad "unsatisfiable range names versions, requirer and the fix" "$out"
fi

printf '{ "name": "app", "version": "0.0.1", "deps": { "mathx": "1.x" } }\n' > "$proj/sfl.pkg"
expect "malformed range is refused" 1 "unsupported version range" in_proj "$SFL" bare.sfl
rm "$proj/sfl.pkg"

echo
echo "packages: transitive dependencies"

# alpha declares mathx and imports a module of it; beta imports clrs undeclared.
mkdir -p "$work/src/alpha" "$work/src/beta" "$work/src/clrs"
printf '{ "name": "alpha", "version": "1.0.0", "deps": { "mathx": "^1.0.0" } }\n' > "$work/src/alpha/sfl.pkg"
printf 'import "mathx/geo"\ndef alphaArea(w, h) = mathx.area(w, h)\n' > "$work/src/alpha/main.sfl"
printf '{ "name": "clrs", "version": "1.0.0" }\n' > "$work/src/clrs/sfl.pkg"
printf 'def red() = "red"\n' > "$work/src/clrs/main.sfl"
printf '{ "name": "beta", "version": "1.0.0" }\n' > "$work/src/beta/sfl.pkg"
printf 'import "clrs"\ndef betaRed() = clrs.red()\n' > "$work/src/beta/main.sfl"
"$SFL" pkg install "$work/src/alpha" > /dev/null 2>&1
"$SFL" pkg install "$work/src/beta"  > /dev/null 2>&1
"$SFL" pkg install "$work/src/clrs"  > /dev/null 2>&1

printf 'import "alpha"\nprint(alpha.alphaArea(2, 3))\n' > "$proj/trans.sfl"
expect "a package's declared dep resolves" 0 "6" in_proj "$SFL" trans.sfl

printf 'import "beta"\n' > "$proj/undecl.sfl"
out=$(in_proj "$SFL" undecl.sfl 2>&1); code=$?
if [[ $code -eq 1 && "$out" == *"package 'beta' does not declare a dependency on 'clrs'"* \
      && "$out" == *'"deps": {"clrs": "^1.0.0"}'* ]]; then
  ok "undeclared dependency errors with the deps help line"
else
  bad "undeclared dependency errors with the deps help line" "$out"
fi

echo
echo "packages: the flat model"

# pone accepts any 1.x of mathx, ptwo pins exactly 1.0.0. Importing pone first
# binds mathx to 1.2.0, so ptwo's range must be reported as unsatisfiable.
mkdir -p "$work/src/pone" "$work/src/ptwo"
printf '{ "name": "pone", "version": "1.0.0", "deps": { "mathx": "^1.0.0" } }\n' > "$work/src/pone/sfl.pkg"
printf 'import "mathx"\ndef oneV() = mathx.version()\n' > "$work/src/pone/main.sfl"
printf '{ "name": "ptwo", "version": "1.0.0", "deps": { "mathx": "1.0.0" } }\n' > "$work/src/ptwo/sfl.pkg"
printf 'import "mathx"\ndef twoV() = mathx.version()\n' > "$work/src/ptwo/main.sfl"
"$SFL" pkg install "$work/src/pone" > /dev/null 2>&1
"$SFL" pkg install "$work/src/ptwo" > /dev/null 2>&1

printf 'import "pone"\nimport "ptwo"\n' > "$proj/conflict.sfl"
out=$(in_proj "$SFL" conflict.sfl 2>&1); code=$?
if [[ $code -eq 1 && "$out" == *"package 'mathx' is already loaded at version 1.2.0"* \
      && "$out" == *"package 'ptwo'"* && "$out" == *"needs 1.0.0"* \
      && "$out" == *"package 'pone'"* && "$out" == *"^1.0.0"* ]]; then
  ok "version conflict names both requirers and both ranges"
else
  bad "version conflict names both requirers and both ranges" "$out"
fi

# Compatible ranges agree on one version, so the same pair with a workable pin runs.
printf 'import "pone"\nimport "mathx"\nprint(mathx.version())\n' > "$proj/agree.sfl"
expect "compatible requirers share one version" 0 "1.2.0" in_proj "$SFL" agree.sfl

echo
echo "packages: the local root"

# A --local 1.1.0 shadows the global root entirely, even though global holds 1.2.0.
make_mathx 1.1.0 "1.1.0-local"
expect "pkg install --local" 0 "installed mathx 1.1.0" \
  bash -c "cd '$proj' && '$SFL' pkg install --local '$work/src/mathx-1.1.0'"
expect "the local root shadows the global one" 0 "1.1.0-local" in_proj "$SFL" bare.sfl

echo
echo "packages: interpreted and compiled are byte-identical"

printf 'import "mathx"\nimport "mathx/geo" as g\nprint(mathx.version())\nprint(mathx.twice(21))\nprint(g.area(5, 8))\n' > "$proj/diff.sfl"
in_proj "$SFL" diff.sfl > "$work/interp.txt" 2>&1
interp_code=$?
if build=$(in_proj "$SFL" -c diff.sfl -o "$work/diff_bin" 2>&1); then
  (cd "$proj" && "$work/diff_bin") > "$work/comp.txt" 2>&1
  comp_code=$?
  if [[ $interp_code -eq $comp_code ]] && cmp -s "$work/interp.txt" "$work/comp.txt"; then
    ok "compiled output equals interpreted output"
  else
    bad "compiled output equals interpreted output" \
      "interpreted ($interp_code): $(cat "$work/interp.txt")" \
      "compiled ($comp_code): $(cat "$work/comp.txt")"
  fi
else
  bad "compiled output equals interpreted output (compilation failed)" "$build"
fi

echo
echo "packages: sfl pkg list / remove"

out=$(in_proj "$SFL" pkg list 2>&1); code=$?
first_root=$(printf '%s\n' "$out" | head -1)
if [[ $code -eq 0 && "$out" == *"mathx 1.0.0"* && "$out" == *"mathx 1.2.0"* \
      && "$out" == *"mathx 1.1.0"* && "$first_root" == *sfl_packages* ]]; then
  ok "pkg list shows both roots, the project root first"
else
  bad "pkg list shows both roots, the project root first" "$out"
fi

expect "pkg remove one version" 0 "removed" in_proj "$SFL" pkg remove mathx@1.0.0
out=$(in_proj "$SFL" pkg list 2>&1)
if [[ "$out" != *"mathx 1.0.0"* && "$out" == *"mathx 1.2.0"* ]]; then
  ok "the removed version is gone, the others stay"
else
  bad "the removed version is gone, the others stay" "$out"
fi

expect "pkg remove every version" 0 "removed" in_proj "$SFL" pkg remove mathx
expect "removing what is absent fails" 1 "is not installed" in_proj "$SFL" pkg remove mathx
expect "an import after removal reports a missing module" 1 "cannot find module" \
  in_proj "$SFL" bare.sfl

echo
echo "packages: $pass passed, $fail failed"
[[ $fail -eq 0 ]]
