#!/bin/sh
# SFL installer and self-updater.
#
#   curl -fsSL https://raw.githubusercontent.com/alex82831/SFL/main/install.sh | sh
#
# Downloads the sfl binary for this platform from the GitHub releases, verifies
# its checksum, and installs it. Run again (or `sfl` itself) to update — with
# --update it replaces an existing sfl only when the release is newer.
#
# Options (flags or environment):
#   --version <v>     / SFL_VERSION      a release tag to install (default: latest)
#   --dir <path>      / SFL_INSTALL_DIR  where to install (default: /usr/local/bin
#                                        if writable, else ~/.local/bin)
#   --update                             self-update: only replace if newer
#   --dry-run         / SFL_DRY_RUN=1    print what would happen, download nothing
set -eu

REPO="alex82831/SFL"
GH="https://github.com/$REPO"
API="https://api.github.com/repos/$REPO"

version="${SFL_VERSION:-}"
dir="${SFL_INSTALL_DIR:-}"
update=0
dry="${SFL_DRY_RUN:-0}"

while [ $# -gt 0 ]; do
  case "$1" in
    --version) version="${2:?--version needs a value}"; shift 2 ;;
    --version=*) version="${1#--version=}"; shift ;;
    --dir) dir="${2:?--dir needs a value}"; shift 2 ;;
    --dir=*) dir="${1#--dir=}"; shift ;;
    --update) update=1; shift ;;
    --dry-run) dry=1; shift ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "sfl install: unknown option '$1'" >&2; exit 2 ;;
  esac
done

say()  { printf '%s\n' "$*" >&2; }
die()  { printf 'sfl install: %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# --- fetch helpers: curl or wget, whichever is present -----------------------
have curl || have wget || die "need curl or wget"
fetch()      { if have curl; then curl -fsSL "$1"; else wget -qO- "$1"; fi; }
fetch_to()   { if have curl; then curl -fsSL -o "$2" "$1"; else wget -qO "$2" "$1"; fi; }
# The URL a redirect resolves to, without downloading the body.
resolved()   {
  if have curl; then curl -fsSLI -o /dev/null -w '%{url_effective}' "$1"
  else wget -q --max-redirect=10 -S --spider "$1" 2>&1 | awk '/^  Location: /{u=$2} END{print u}'
  fi
}

# --- platform ----------------------------------------------------------------
os=$(uname -s); arch=$(uname -m)
case "$os" in
  Darwin) os=macos ;;
  Linux)  os=linux ;;
  *) die "unsupported OS '$os' (macOS and Linux have binaries; build from source otherwise)" ;;
esac
case "$arch" in
  arm64|aarch64) arch=arm64 ;;
  x86_64|amd64)  arch=x86_64 ;;
  *) die "unsupported architecture '$arch'" ;;
esac
# The one build published per pair; a linux-arm64 user builds from source for now.
asset="sfl-$os-$arch"
[ "$os" = linux ] && [ "$arch" = arm64 ] && die "no linux-arm64 binary yet; build from source ($GH)"

# --- resolve the version -----------------------------------------------------
# The latest release's tag, from the redirect the 'latest' link resolves to.
latest_tag() {
  t=$(resolved "$GH/releases/latest" 2>/dev/null || true)
  t=${t##*/tag/}
  [ -n "$t" ] && [ "$t" != "$GH/releases/latest" ] && printf '%s' "$t"
}

if [ -z "$version" ]; then
  version=$(latest_tag || true)
  [ -n "$version" ] || die "cannot determine the latest release; pass --version, or see $GH/releases"
fi
case "$version" in v*) tag="$version" ;; *) tag="v$version" ;; esac

# --- self-update short-circuit ----------------------------------------------
if [ "$update" = 1 ]; then
  have sfl || die "no sfl on PATH to update; run without --update to install"
  cur=$(sfl --version 2>/dev/null | awk '{print $2}')
  if [ "v$cur" = "$tag" ]; then
    say "sfl $cur is already the latest ($tag)"; exit 0
  fi
  say "updating sfl $cur -> ${tag#v}"
  # Replace the binary where it already lives, if we can write there.
  existing=$(command -v sfl)
  target_dir=$(CDPATH= cd -- "$(dirname -- "$existing")" && pwd)
  [ -w "$target_dir" ] || die "cannot write $target_dir; re-run with sudo, or --dir <writable>"
  dir="$target_dir"
fi

# --- choose an install dir ---------------------------------------------------
if [ -z "$dir" ]; then
  if [ -w /usr/local/bin ] 2>/dev/null; then dir=/usr/local/bin
  else dir="$HOME/.local/bin"
  fi
fi

url="$GH/releases/download/$tag/$asset"
sums_url="$GH/releases/download/$tag/SHA256SUMS"

if [ "$dry" = 1 ]; then
  say "platform : $os-$arch  ($asset)"
  say "version  : $tag"
  say "download : $url"
  say "checksum : $sums_url"
  say "install  : $dir/sfl"
  exit 0
fi

# --- download, verify, install ----------------------------------------------
tmp=$(mktemp -d "${TMPDIR:-/tmp}/sfl-install.XXXXXX")
trap 'rm -rf "$tmp"' EXIT INT TERM

say "downloading $asset ($tag)..."
fetch_to "$url" "$tmp/sfl" || die "download failed: $url (does the release have this asset?)"

# Verify against SHA256SUMS when the tools are available; warn if they are not.
if fetch_to "$sums_url" "$tmp/SHA256SUMS" 2>/dev/null; then
  want=$(awk -v a="$asset" '$2 == a || $2 == "*"a {print $1}' "$tmp/SHA256SUMS" | head -1)
  if [ -n "$want" ]; then
    if have sha256sum; then got=$(sha256sum "$tmp/sfl" | awk '{print $1}')
    elif have shasum; then got=$(shasum -a 256 "$tmp/sfl" | awk '{print $1}')
    else got=""; say "warning: no sha256 tool; skipping checksum"; fi
    [ -z "$got" ] || [ "$got" = "$want" ] || die "checksum mismatch for $asset (expected $want, got $got)"
    [ -z "$got" ] || say "checksum ok"
  fi
else
  say "warning: no SHA256SUMS in the release; skipping checksum"
fi

chmod +x "$tmp/sfl"
mkdir -p "$dir" || die "cannot create $dir"
if [ -w "$dir" ]; then
  mv "$tmp/sfl" "$dir/sfl"
elif have sudo; then
  say "installing to $dir needs sudo"
  sudo mv "$tmp/sfl" "$dir/sfl"
else
  die "cannot write $dir; set --dir to a writable path"
fi

say "installed sfl ${tag#v} -> $dir/sfl"
case ":$PATH:" in
  *":$dir:"*) : ;;
  *) say "note: $dir is not on your PATH — add it, e.g.:"; say "  export PATH=\"$dir:\$PATH\"" ;;
esac
"$dir/sfl" --version 2>/dev/null || true
