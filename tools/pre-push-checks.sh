#!/usr/bin/env bash
# Build gate run by githooks/pre-push for each pushed ref (there is no cloud CI):
# 1. refuses key files in the pushed range, 2. buildSrc, engine and unit tests with privacyCheck (offline),
# 3. reproducibility of the pushed commit.
# Usage: tools/pre-push-checks.sh <rev-list range> <tip sha>
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
range="$1"
tip="$2"

keys="$(git -C "$root" log --diff-filter=AM --name-only --pretty=format: $range -- 2>/dev/null \
  | grep -Ei '\.(p12|pfx|jks|keystore)$|(^|/)id_(rsa|ed25519)$|\.pem$' | sort -u || true)"
if [ -n "$keys" ]; then
  echo "pre-push: refusing to push key material:" >&2
  echo "$keys" >&2
  exit 1
fi

echo "pre-push: privacyCheck, buildSrc and engine tests, unit tests (offline)"
"$root/gradlew" --console=plain --offline -p "$root" :buildSrc:test :voice-engine:test :nextword-engine:test
"$root/gradlew" --console=plain --offline -p "$root" privacyCheck :app:testRunTestsUnitTest

echo "pre-push: reproducibility of ${tip:0:12}"
"$root/tools/verify-repro.sh" "$tip"
