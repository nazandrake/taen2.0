#!/usr/bin/env bash
# Rebuilds app/src/main/assets/stickers from tools/sticker-pack.lock. Build PC only: the app has no network.
# Every file is downloaded from its pinned commit URL, checked against the size and SHA-256 of the lock, and only then written; the total must stay within 4 MiB.
# Usage: tools/make-sticker-pack.sh [--check]   (--check compares the files already in the assets and downloads nothing)
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
lock="$root/tools/sticker-pack.lock"
dest="$root/app/src/main/assets/stickers"
check_only=false
[ "${1:-}" = "--check" ] && check_only=true
mkdir -p "$dest"
total=0
count=0
while IFS=$'\t' read -r name size sha url; do
  case "$name" in ''|'#'*) continue ;; esac
  file="$dest/$name"
  if ! $check_only; then
    tmp="$(mktemp -p "${TMPDIR:-$HOME/.cache}" sticker.XXXXXX)"
    trap 'rm -f "$tmp"' EXIT
    curl -sfL -o "$tmp" "$url"
    [ "$(stat -c %s "$tmp")" = "$size" ] || { echo "size mismatch for $name" >&2; exit 1; }
    [ "$(sha256sum "$tmp" | cut -d' ' -f1)" = "$sha" ] || { echo "SHA-256 mismatch for $name" >&2; exit 1; }
    mv "$tmp" "$file"
    trap - EXIT
  fi
  [ "$(stat -c %s "$file")" = "$size" ] || { echo "size mismatch in the assets for $name" >&2; exit 1; }
  [ "$(sha256sum "$file" | cut -d' ' -f1)" = "$sha" ] || { echo "SHA-256 mismatch in the assets for $name" >&2; exit 1; }
  total=$((total + size))
  count=$((count + 1))
done < "$lock"
[ "$total" -le $((4 * 1024 * 1024)) ] || { echo "the pack is larger than 4 MiB: $total" >&2; exit 1; }
[ "$(ls "$dest" | wc -l)" = "$count" ] || { echo "the assets folder holds files that are not in the lock" >&2; exit 1; }
echo "sticker pack OK: $count files, $total bytes"
