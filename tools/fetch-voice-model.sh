#!/bin/sh
# Fetches the voice model on the builder's PC (the app itself has no network): make fetch-voice-model
# usage: tools/fetch-voice-model.sh [--archive] [output dir, default dist/voice-model]
# Without --archive: downloads the four files listed in tools/voice-model.lock (resumable), checks size and SHA-256 of each and fails on any difference.
# With --archive: downloads the one archive of tools/voice-model-archive.lock (487 MB, resumable) and checks its size and SHA-256.
# Then copy the result to the phone and install it: Settings > Voice typing > Install downloaded model (BUILDING.md section 8).
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
mode=files
if [ "${1:-}" = "--archive" ]; then mode=archive; shift; fi
out="${1:-$root/dist/voice-model}"
mkdir -p "$out"
if [ "$mode" = archive ]; then lock="$root/tools/voice-model-archive.lock"; else lock="$root/tools/voice-model.lock"; fi
grep -v '^#' "$lock" | while IFS="$(printf '\t')" read -r name size sha url _folder; do
    [ -n "$name" ] || continue
    file="$out/$name"
    if [ -f "$file" ] && [ "$(wc -c < "$file")" -eq "$size" ] && [ "$(sha256sum "$file" | cut -d' ' -f1)" = "$sha" ]; then
        echo "ok (already here) $name"
        continue
    fi
    curl --fail --location --retry 3 --continue-at - --output "$file" "$url"
    got=$(sha256sum "$file" | cut -d' ' -f1)
    if [ "$got" != "$sha" ] || [ "$(wc -c < "$file")" -ne "$size" ]; then
        echo "FAILED: $name does not match $(basename "$lock") (expected $sha, got $got); removed" >&2
        rm -f "$file"
        exit 1
    fi
    echo "ok $name"
done
echo "voice model download is in $out"
echo "  copy it to the phone, for example: adb push $out /sdcard/Download/taen-voice"
echo "  then in Taen: Settings > Voice typing > Install downloaded model, select the archive or any of the files (any file names)"
