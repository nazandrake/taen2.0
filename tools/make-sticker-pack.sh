#!/usr/bin/env bash
# Rebuilds app/src/main/assets/stickers from tools/sticker-pack.lock (static PNG) and tools/sticker-gifs.lock (animated GIF). Build PC only: the app has no network.
# Every PNG is downloaded from its pinned commit URL, checked against the size and SHA-256 of the lock, and only then written.
# Every GIF source is downloaded, checked against the source size and SHA-256 of the lock, converted here with ffmpeg (the exact filter chain below, the ffmpeg version is in the lock) and the result is
# checked against the size and SHA-256 of the lock before it is written. The total of all files must stay within 4 MiB.
# Usage: tools/make-sticker-pack.sh [--check]   (--check compares the files already in the assets and downloads nothing)
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$root/app/src/main/assets/stickers"
check_only=false
[ "${1:-}" = "--check" ] && check_only=true
mkdir -p "$dest"
total=0
count=0
scratch="${TMPDIR:-$HOME/.cache}"

verify() { # file size sha label
  [ "$(stat -c %s "$1")" = "$2" ] || { echo "size mismatch for $4" >&2; exit 1; }
  [ "$(sha256sum "$1" | cut -d' ' -f1)" = "$3" ] || { echo "SHA-256 mismatch for $4" >&2; exit 1; }
}

while IFS=$'\t' read -r name size sha url; do
  case "$name" in ''|'#'*) continue ;; esac
  file="$dest/$name"
  if ! $check_only; then
    tmp="$(mktemp -p "$scratch" sticker.XXXXXX)"
    trap 'rm -f "$tmp"' EXIT
    curl -sfL -o "$tmp" "$url"
    verify "$tmp" "$size" "$sha" "$name"
    mv "$tmp" "$file"
    trap - EXIT
  fi
  verify "$file" "$size" "$sha" "$name in the assets"
  total=$((total + size))
  count=$((count + 1))
done < "$root/tools/sticker-pack.lock"

# Columns: name, size, SHA-256 of the GIF in the assets, source URL, source size, source SHA-256.
while IFS=$'\t' read -r name size sha url ssize ssha; do
  case "$name" in ''|'#'*) continue ;; esac
  file="$dest/$name"
  if ! $check_only; then
    src="$(mktemp -p "$scratch" sticker-src.XXXXXX)"
    tmp="$(mktemp -p "$scratch" sticker.XXXXXX)"
    trap 'rm -f "$src" "$tmp"' EXIT
    curl -sfL -o "$src" "$url"
    verify "$src" "$ssize" "$ssha" "the source of $name"
    # 128 px, 10 frames/s, 64 colours with one transparent entry, looping forever; the same chain made every file of the lock.
    ffmpeg -nostdin -v error -y -f gif -i "$src" -filter_complex "[0:v]fps=10,scale=128:128:flags=lanczos,split[a][b];[a]palettegen=reserve_transparent=1:max_colors=64:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:alpha_threshold=128:diff_mode=rectangle" -loop 0 -f gif "$tmp" \
      || { echo "ffmpeg failed for $name" >&2; exit 1; }
    verify "$tmp" "$size" "$sha" "$name (is ffmpeg the version named in tools/sticker-gifs.lock?)"
    mv "$tmp" "$file"
    rm -f "$src"
    trap - EXIT
  fi
  verify "$file" "$size" "$sha" "$name in the assets"
  total=$((total + size))
  count=$((count + 1))
done < "$root/tools/sticker-gifs.lock"

[ "$total" -le $((4 * 1024 * 1024)) ] || { echo "the pack is larger than 4 MiB: $total" >&2; exit 1; }
[ "$(ls "$dest" | wc -l)" = "$count" ] || { echo "the assets folder holds files that are not in the locks" >&2; exit 1; }
echo "sticker pack OK: $count files, $total bytes"
