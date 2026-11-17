#!/usr/bin/env bash
# Reproducibility check (invariant P17): clones a commit into two fresh directories at different paths, builds the unsigned
# release APK in each, and compares the SHA-256. Usage: tools/verify-repro.sh [commit]   (default: HEAD)
# Output: both hashes and "REPRODUCIBLE" (exit 0) or "NOT REPRODUCIBLE" (exit 1).
# Uncommitted changes are not part of the check: only the commit is built.
# Environment: GRADLE_OPTS_EXTRA (e.g. "--offline"; default --offline), TAEN_REPRO_DIR (work dir, default ~/.cache/taen-repro).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
commit="$(git -C "$root" rev-parse --verify "${1:-HEAD}^{commit}")"
work="${TAEN_REPRO_DIR:-$HOME/.cache/taen-repro}"   # on disk, never /tmp (tmpfs)
gradle_opts="${GRADLE_OPTS_EXTRA:---offline}"

if [ -n "$(git -C "$root" status --porcelain --untracked-files=no)" ]; then
  echo "note: the working tree has uncommitted changes; only commit ${commit:0:12} is verified" >&2
fi

sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk_dir" ] && [ -f "$root/local.properties" ]; then
  sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$root/local.properties")"
fi
[ -n "$sdk_dir" ] || { echo "error: set ANDROID_HOME or sdk.dir in local.properties" >&2; exit 2; }

# Memory: stop this project's own idle Gradle daemons (about 4.7 GB each) before the two builds, which run with --no-daemon.
"$root/gradlew" --stop > /dev/null 2>&1 || true

rm -rf "$work"
mkdir -p "$work"
hashes=()
for n in 1 2; do
  dir="$work/build-$n/taen-src-$n"       # different path per build: the result must not depend on it
  mkdir -p "$(dirname "$dir")"
  git clone --quiet --no-hardlinks "$root" "$dir"
  git -C "$dir" checkout --quiet "$commit"
  printf 'sdk.dir=%s\n' "$sdk_dir" > "$dir/local.properties"
  echo "build $n: $dir"
  # shellcheck disable=SC2086
  (cd "$dir" && ./gradlew --console=plain --no-daemon --no-build-cache $gradle_opts :app:assembleRelease > "$work/build-$n.log" 2>&1) \
    || { echo "build $n failed, see $work/build-$n.log" >&2; exit 2; }
  apk="$(ls "$dir"/app/build/outputs/apk/release/*.apk)"
  hashes+=("$(sha256sum "$apk" | cut -d' ' -f1)")
  echo "build $n sha256: ${hashes[$((n-1))]}  $(basename "$apk")"
done

if [ "${hashes[0]}" = "${hashes[1]}" ]; then
  echo "REPRODUCIBLE ${hashes[0]}"
else
  echo "NOT REPRODUCIBLE: ${hashes[0]} != ${hashes[1]}  (compare with: unzip both APKs and diff, or diffoscope)" >&2
  exit 1
fi
