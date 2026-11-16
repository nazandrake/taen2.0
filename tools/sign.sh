#!/usr/bin/env bash
# Signs an unsigned APK with a key that lives OUTSIDE the repository.
# Usage: tools/sign.sh <unsigned.apk> <signed.apk>
# Environment:
#   TAEN_KEYSTORE        keystore path (default ~/.config/taen/release.p12)
#   TAEN_KEY_ALIAS       key alias (default taen)
#   TAEN_KS_PASS_FILE    optional file holding the keystore password; without it apksigner asks on the terminal
# Signature schemes: v1 off, v2 and v3 requested; apksigner wrote v3 only for minSdk 30 (verified on device: apkSigningVersion=3).
set -euo pipefail

[ $# -eq 2 ] || { echo "usage: $0 <unsigned.apk> <signed.apk>" >&2; exit 2; }
in="$1"; out="$2"
ks="${TAEN_KEYSTORE:-$HOME/.config/taen/release.p12}"
alias_name="${TAEN_KEY_ALIAS:-taen}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

[ -f "$in" ] || { echo "error: $in not found" >&2; exit 2; }
[ -f "$ks" ] || { echo "error: keystore $ks not found; create one with tools/keygen.sh" >&2; exit 2; }
case "$(cd "$(dirname "$ks")" && pwd)/" in
  "$root"/*) echo "error: the keystore must not be inside the repository" >&2; exit 2;;
esac

sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk_dir" ] && [ -f "$root/local.properties" ]; then
  sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$root/local.properties")"
fi
bt="$(sed -n 's/^ *buildToolsVersion = "\(.*\)".*/\1/p' "$root/app/build.gradle.kts")"
apksigner="$sdk_dir/build-tools/$bt/apksigner"
[ -x "$apksigner" ] || { echo "error: $apksigner not found (install build-tools $bt)" >&2; exit 2; }

pass_args=()
if [ -n "${TAEN_KS_PASS_FILE:-}" ]; then pass_args=(--ks-pass "file:$TAEN_KS_PASS_FILE"); fi

mkdir -p "$(dirname "$out")"
"$apksigner" sign --ks "$ks" --ks-key-alias "$alias_name" "${pass_args[@]}" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$out" "$in"
"$apksigner" verify --verbose "$out" | grep -E "^(Verifies|Verified using)"
echo "certificate SHA-256:"
"$apksigner" verify --print-certs "$out" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
