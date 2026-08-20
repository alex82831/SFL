#!/usr/bin/env bash
#
# The gui package, end to end: installed into a temp SFL_HOME exactly as a
# user would install it (httpd first, gui on top), then driven headlessly —
# real servers on ephemeral ports, a hand-rolled WebSocket client, patches
# asserted op by op. No browser is needed; the embedded client runtime is
# asserted as content and exercised separately by the protocol it speaks.
#
# When a C toolchain is present the parity fixture also runs compiled
# (sfl -c) and its output must equal the interpreter's byte for byte.
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
export SFL_GUI_NO_WINDOW=1

fails=0

"$SFL" pkg install ../../packages/httpd >/dev/null || { echo "  FAIL  installing httpd"; exit 1; }
"$SFL" pkg install ../../packages/gui >/dev/null || { echo "  FAIL  installing gui"; exit 1; }

# The project the suites run from: a manifest pinning the deps, the shared
# assert helper, and the WebSocket client.
proj="$work/proj"
mkdir -p "$proj/lib"
printf '{ "name": "guitests", "version": "0.0.1", "deps": { "gui": "^0.1.0", "httpd": "^0.1.0" } }\n' > "$proj/sfl.pkg"
cp ../lib/assert.sfl "$proj/lib/assert.sfl"
cp wsc.sfl "$proj/lib/wsc.sfl"
cp core.sfl app.sfl demo.sfl parity.sfl "$proj/"

parts=0
for suite in core app demo; do
  parts=$((parts + 1))
  if out=$(cd "$proj" && "$SFL" "$suite.sfl" 2>&1); then
    printf '  %s\n' "$out"
  else
    printf '  %s\n' "$out"
    fails=$((fails + 1))
  fi
done

# ------------------------------------------------- interpreted vs compiled

# The probe compiles a program that links two stdlib modules with top-level
# lambdas (http + coll); binaries older than the exported-symbol fix cannot,
# and the parity check is skipped rather than failed on them.
probe="$work/probe"
mkdir -p "$probe"
printf 'val f = httpGet\nprintln(find([1, 2, 3], (x) -> x == 2), isFunction(f))\n' > "$probe/p.sfl"
if ! "$SFL" -c "$probe/p.sfl" -o "$probe/p" >/dev/null 2>&1 \
   || [[ "$("$probe/p" 2>/dev/null)" != "2 true" ]]; then
  echo "  skip  compile parity (no C toolchain, or a binary without the stdlib lambda-export fix)"
else
  (cd "$proj" && "$SFL" parity.sfl) > "$work/interp.txt" 2>&1
  interp_code=$?
  if build=$(cd "$proj" && "$SFL" -c parity.sfl -o "$work/parity_bin" 2>&1); then
    (cd "$proj" && "$work/parity_bin") > "$work/comp.txt" 2>&1
    comp_code=$?
    if [[ $interp_code -eq $comp_code ]] && cmp -s "$work/interp.txt" "$work/comp.txt"; then
      echo "  ok    compiled gui session equals interpreted, byte for byte"
    else
      echo "  FAIL  compiled gui output diverges"
      echo "        interpreted ($interp_code): $(head -c 300 "$work/interp.txt")"
      echo "        compiled ($comp_code): $(head -c 300 "$work/comp.txt")"
      fails=$((fails + 1))
    fi
  else
    echo "  FAIL  parity fixture did not compile"
    printf '%s\n' "$build" | head -5
    fails=$((fails + 1))
  fi
  parts=$((parts + 1))
fi

echo "gui: $((parts - fails)) of $parts parts passed"
exit $((fails > 0))
