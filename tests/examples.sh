#!/usr/bin/env bash
#
# The example projects, built and tested the way a reader would.
#
# Each example under examples/ is a real `sfl build` project: a manifest, a main,
# a suite, and — where it imports one — a `setup` task that installs the packages
# from this checkout. This runs `sfl build setup` (when there is one), `sfl build`
# and `sfl build test` for every one of them, so a change to the language, the
# build tool or a package cannot quietly break the code the documentation points
# people at.
#
# Everything happens in a copy under a temporary SFL_HOME, so the working tree
# keeps no build/ or sfl_packages/ directories and two runs cannot collide. The
# suites that want a live server (MySQL, Postgres, Redis, MongoDB, an SMTP relay)
# skip themselves when none answers, so this is green on a machine with nothing
# installed.
set -uo pipefail
cd "$(dirname "$0")"

SFL=${SFL:-../target/scala-3.8.4/sfl}
if [[ ! -x "$SFL" ]]; then
  echo "no binary at $SFL — run: sbt nativeLink" >&2
  exit 1
fi
SFL=$(cd "$(dirname "$SFL")" && pwd)/$(basename "$SFL")
repo=$(cd .. && pwd)

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export SFL_HOME="$work/home"
mkdir -p "$SFL_HOME"
# The GUI example serves a real page; it must not try to open a browser.
export SFL_GUI_NO_WINDOW=1

pass=0
fail=0
ok()  { pass=$((pass + 1)); printf '  ok    %s\n' "$1"; }
bad() { fail=$((fail + 1)); printf '  FAIL  %s\n' "$1"; shift
        for line in "$@"; do printf '        %s\n' "$line"; done; }

echo "examples: every project built and tested"

# A copy, so setup and build write into the temporary tree rather than the repo.
# The packages come along because each setup task installs them from
# ../../packages, which is where they sit relative to an example.
cp -R "$repo/examples" "$work/examples"
cp -R "$repo/packages" "$work/packages"

for dir in "$work"/examples/*/; do
  name=$(basename "$dir")
  [[ -f "$dir/build.sfl" ]] || continue

  if grep -q 'task("setup"' "$dir/build.sfl"; then
    if ! out=$(cd "$dir" && "$SFL" build setup 2>&1); then
      bad "$name: setup" "$(printf '%s' "$out" | tail -4)"
      continue
    fi
  fi

  # A library project declares no main, so there is nothing to compile; its
  # suite is still the thing that matters.
  if grep -q '"main"' "$dir/build.sfl"; then
    if ! out=$(cd "$dir" && "$SFL" build 2>&1); then
      bad "$name: build" "$(printf '%s' "$out" | tail -6)"
      continue
    fi
  fi

  if out=$(cd "$dir" && "$SFL" build test 2>&1); then
    ok "$name"
  else
    bad "$name: test" "$(printf '%s' "$out" | tail -8)"
  fi
done

echo
if [[ $fail -eq 0 ]]; then
  echo "examples: $pass projects passed"
else
  echo "examples: $pass passed, $fail failed"
fi
[[ $fail -eq 0 ]]
