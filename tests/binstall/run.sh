#!/usr/bin/env bash
#
# Installing from a source: a git repository, or a registry name that resolves to
# one. The transport is `git clone`, so the whole suite runs against local git
# repositories over `git+file://` and a `file://` registry — no network, the same
# hermetic footing the rest of the tests keep.
#
# What it pins: a git URL installs, a subdirectory selects a package (both the
# github-style path and a `#fragment`), a registry name resolves through
# SFL_REGISTRY, a version-tag ref is checked against the manifest so a package
# cannot install under a version it does not claim, a subdirectory cannot escape
# the clone, and every failure is a clean message rather than a raw exit code.
set -uo pipefail
cd "$(dirname "$0")"

SFL=${SFL:-../../target/scala-3.8.4/sfl}
if [[ ! -x "$SFL" ]]; then
  echo "no binary at $SFL — run: sbt nativeLink" >&2
  exit 1
fi
SFL=$(cd "$(dirname "$SFL")" && pwd)/$(basename "$SFL")

if ! command -v git >/dev/null 2>&1; then
  echo "binstall: git not available; skipping" >&2
  exit 0
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export SFL_HOME="$work/home"
mkdir -p "$SFL_HOME"

# git needs an identity to commit; set a repo-local one on each fixture rather than
# touching the user's global config.
gitq() { git -C "$1" -c user.email=t@t -c user.name=t "${@:2}"; }

pass=0; fail=0
ok()  { pass=$((pass + 1)); printf '  ok    %s\n' "$1"; }
bad() { fail=$((fail + 1)); printf '  FAIL  %s\n' "$1"; shift
        for line in "$@"; do printf '        %s\n' "$line"; done; }
want() { # name, expected-substring, actual
  if [[ "$3" == *"$2"* ]]; then ok "$1"; else bad "$1" "wanted: $2" "got: $3"; fi
}

# ------------------------------------------------------------------- fixtures

# A single-package repo, tagged with a matching and a mismatched version.
solo="$work/solo"
mkdir -p "$solo"
printf '{ "name": "solo", "version": "1.0.0", "main": "main" }\n' > "$solo/sfl.pkg"
printf 'def hi() = "solo v1"\n' > "$solo/main.sfl"
gitq "$solo" init -q
gitq "$solo" add -A
gitq "$solo" commit -qm init
gitq "$solo" tag v1.0.0
gitq "$solo" tag v9.9.9   # deliberately does not match the manifest

# A monorepo of two packages under packages/.
mono="$work/mono"
mkdir -p "$mono/packages/foo" "$mono/packages/bar"
printf '{ "name": "foo", "version": "2.1.0", "main": "main" }\n' > "$mono/packages/foo/sfl.pkg"
printf 'def foo() = 42\n' > "$mono/packages/foo/main.sfl"
printf '{ "name": "bar", "version": "0.3.0", "main": "main" }\n' > "$mono/packages/bar/sfl.pkg"
printf 'def bar() = "b"\n' > "$mono/packages/bar/main.sfl"
gitq "$mono" init -q
gitq "$mono" add -A
gitq "$mono" commit -qm init

# A registry mapping names to the monorepo's subdirectories.
cat > "$work/reg.json" <<EOF
{ "packages": {
  "foo": { "git": "git+file://$mono", "subdir": "packages/foo" },
  "bar": { "git": "git+file://$mono", "subdir": "packages/bar" }
} }
EOF
reg="file://$work/reg.json"

# --------------------------------------------------------------------- checks

want "a git URL installs the repository"        "installed solo 1.0.0" "$("$SFL" install "git+file://$solo" 2>&1)"
prog="$work/u.sfl"; printf 'import "solo"\nprintln(solo.hi())\n' > "$prog"
want "the installed package imports"            "solo v1"             "$("$SFL" "$prog" 2>&1)"

want "a registry name resolves and installs"    "installed foo 2.1.0" "$(SFL_REGISTRY="$reg" "$SFL" install foo 2>&1)"
printf 'import "foo"\nprintln(foo.foo())\n' > "$prog"
want "the registry package imports"             "42"                  "$("$SFL" "$prog" 2>&1)"

want "a #fragment selects a subdirectory"       "installed bar 0.3.0" "$("$SFL" install "git+file://$mono#packages/bar" 2>&1)"

want "a matching version tag installs"          "installed solo 1.0.0" "$("$SFL" install "git+file://$solo@v1.0.0" --force 2>&1)"
out=$("$SFL" install "git+file://$solo@v9.9.9" --force 2>&1)
if [[ "$out" == *"asks for 9.9.9"* && "$out" == *"says 1.0.0"* ]]; then
  ok "a mismatched version tag is refused"
else
  bad "a mismatched version tag is refused" "$out"
fi

want "a subdirectory cannot escape the clone"   "unsafe package subdirectory" "$("$SFL" install "git+file://$mono#../../etc" 2>&1)"
want "an unknown registry name is reported"     "not in the registry"         "$(SFL_REGISTRY="$reg" "$SFL" install nope 2>&1)"
want "an unreachable registry is reported"      "cannot fetch the package registry" "$(SFL_REGISTRY="file:///no/such.json" "$SFL" install foo 2>&1)"
want "a bad git URL is reported"                "cannot clone"                "$("$SFL" install "git+file:///no/such/repo" 2>&1)"
want "a source that is neither is reported"     "cannot open"                 "$("$SFL" install "not a source at all" 2>&1)"

# ----------------------------------------------------------------------- report

if [[ $fail -eq 0 ]]; then
  printf 'binstall: %d passed, 0 failed\n' "$pass"
  exit 0
else
  printf 'binstall: %d passed, %d failed\n' "$pass" "$fail"
  exit 1
fi
