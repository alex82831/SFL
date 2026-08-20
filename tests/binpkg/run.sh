#!/usr/bin/env bash
#
# Binary packages: `sfl pkg build --bin` compiles a package into a per-target
# archive beside its source, and a program that imports it links the archive
# instead of recompiling the source — while the interpreter still runs the
# source, and a stale or absent archive falls back to it. The one thing that
# must hold throughout: the program behaves byte-for-byte the same whichever
# path is taken.
#
# Everything is built here in a temp dir with a temp SFL_HOME, so the suite
# leaves nothing behind and depends only on the binary.
set -uo pipefail
cd "$(dirname "$0")"

SFL=${SFL:-../../target/scala-3.8.4/sfl}
if [[ ! -x "$SFL" ]]; then
  echo "no binary at $SFL — run: sbt nativeLink" >&2
  exit 1
fi
SFL=$(cd "$(dirname "$SFL")" && pwd)/$(basename "$SFL")

# Compiling needs a C toolchain; without one this suite cannot run at all, so it
# reports that and passes rather than failing a checkout that only interprets.
probe=$(mktemp -d)
echo 'println(6 * 7)' > "$probe/p.sfl"
if ! "$SFL" -c "$probe/p.sfl" -o "$probe/p" 2>/dev/null || [[ "$("$probe/p" 2>/dev/null)" != "42" ]]; then
  echo "binpkg: no working C toolchain for -c; skipping" >&2
  rm -rf "$probe"
  exit 0
fi
rm -rf "$probe"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export SFL_HOME="$work/home"
mkdir -p "$SFL_HOME"

pass=0
fail=0
ok()  { pass=$((pass + 1)); printf '  ok    %s\n' "$1"; }
bad() { fail=$((fail + 1)); printf '  FAIL  %s\n' "$1"; shift
        for line in "$@"; do printf '        %s\n' "$line"; done; }

# ------------------------------------------------------------------- a fixture

# calc: two modules, so sibling references cross a module boundary. main imports
# helper and calls it; helper keeps file-private state that its own init sets, to
# prove a package's private globals live in the archive and start up correctly.
src="$work/src/calc"
mkdir -p "$src"
printf '{ "name": "calc", "version": "1.0.0", "main": "main" }\n' > "$src/sfl.pkg"
cat > "$src/helper.sfl" <<'SFL'
val _base = 100
def scaled(x) = x * _base
def _priv(x) = x + 1
def bump(x) = _priv(x) * 10
SFL
cat > "$src/main.sfl" <<'SFL'
import "helper"
def compute(xs) {
  var total = 0
  for (x in xs) { total += scaled(x) + bump(x) }
  total
}
def describe(n) = "calc(" + toString(n) + ")"
SFL

# A program that uses the package as any consumer would: bare-name import, a
# public function called, another handed around as a value, and an error caught
# at the boundary so its text (not its traceback) is what is compared.
prog="$work/use.sfl"
cat > "$prog" <<'SFL'
import "calc"
println(compute([1, 2, 3]))
println(describe(compute([5])))
val f = describe
println(map([1, 2], f))
println(scaled(7) + bump(0))
SFL

build_and_install() {
  ( cd "$src" && "$SFL" pkg build --bin . ) > "$work/build.log" 2>&1 || {
    bad "pkg build --bin" "$(cat "$work/build.log")"; return 1; }
  "$SFL" pkg install "$src/calc-1.0.0.sflpkg" --force > "$work/install.log" 2>&1 || {
    bad "pkg install" "$(cat "$work/install.log")"; return 1; }
  return 0
}

# --------------------------------------------------------------------- the run

if build_and_install; then
  ok "pkg build --bin produces an archive"
  archive_dir="$SFL_HOME/packages/calc/1.0.0/bin"
  if ls "$archive_dir"/*/lib.a >/dev/null 2>&1 && ls "$archive_dir"/*/abi.json >/dev/null 2>&1; then
    ok "the archive and abi travel with the install"
  else
    bad "the archive is missing after install" "$(ls -R "$archive_dir" 2>&1)"
  fi

  # The three ways to run the program, which must all agree.
  interp=$("$SFL" "$prog" 2>&1); icode=$?

  "$SFL" -c "$prog" -o "$work/use.bin" > "$work/c1.log" 2>&1
  linked=$("$work/use.bin" 2>&1); lcode=$?

  "$SFL" --pkg-source -c "$prog" -o "$work/use.src" > "$work/c2.log" 2>&1
  fromsrc=$("$work/use.src" 2>&1); scode=$?

  if [[ $icode -eq 0 && "$interp" == "$linked" && "$interp" == "$fromsrc" ]]; then
    ok "interpreted, binary-linked and source-compiled agree"
  else
    bad "the three runs diverged" "interp($icode): $interp" \
        "linked($lcode): $linked" "source($scode): $fromsrc"
  fi

  # It really linked the archive: the emitted IR references the package's
  # function entries and calls each module's initialiser.
  ir=$("$SFL" --emit-llvm "$prog" 2>/dev/null)
  if grep -q 'sflpkg_calc_.*_f_' <<<"$ir" && grep -q 'call void @sflpkg_calc_.*_init()' <<<"$ir"; then
    ok "the linked build references the archive's symbols"
  else
    bad "the linked build did not reference the archive"
  fi

  # And --pkg-source really did not.
  if "$SFL" --pkg-source --emit-llvm "$prog" 2>/dev/null | grep -q 'sflpkg_calc_'; then
    bad "--pkg-source still referenced the archive"
  else
    ok "--pkg-source compiles from source, not the archive"
  fi

  # Fallback: a toolchain fingerprint that no longer matches must send the
  # compiler back to the source, still producing a correct program.
  abifile=$(ls "$archive_dir"/*/abi.json 2>/dev/null | head -1)
  if [[ -n "$abifile" ]]; then
    cp "$abifile" "$work/abi.bak"
    "$SFL" -e "val d = jsonParse(readFile(\"$abifile\")); d.buildId = \"0000000000000000\"; writeFile(\"$abifile\", jsonStringify(d))" 2>/dev/null
    if "$SFL" --emit-llvm "$prog" 2>/dev/null | grep -q 'sflpkg_calc_'; then
      bad "a mismatched archive was linked anyway"
    else
      "$SFL" -c "$prog" -o "$work/use.fb" > "$work/c3.log" 2>&1
      fb=$("$work/use.fb" 2>&1)
      [[ "$fb" == "$interp" ]] && ok "a mismatched archive falls back to source" \
        || bad "the fallback build was wrong" "$fb"
    fi
    cp "$work/abi.bak" "$abifile"
  fi

  # Source drift: touching the installed source must invalidate the archive.
  cp "$SFL_HOME/packages/calc/1.0.0/main.sfl" "$work/main.bak"
  printf '\ndef drift() = 1\n' >> "$SFL_HOME/packages/calc/1.0.0/main.sfl"
  if "$SFL" --emit-llvm "$prog" 2>/dev/null | grep -q 'sflpkg_calc_'; then
    bad "a drifted source still linked the stale archive"
  else
    ok "an edited source invalidates the archive"
  fi
  cp "$work/main.bak" "$SFL_HOME/packages/calc/1.0.0/main.sfl"
fi

# ----------------------------------------------------------------------- report

if [[ $fail -eq 0 ]]; then
  printf 'binpkg: %d passed, 0 failed\n' "$pass"
  exit 0
else
  printf 'binpkg: %d passed, %d failed\n' "$pass" "$fail"
  exit 1
fi
