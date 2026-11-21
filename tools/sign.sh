#!/usr/bin/env bash
# Signs an unsigned APK with a key that lives OUTSIDE the repository, without asking for the password.
# Usage: tools/sign.sh <unsigned.apk> <signed.apk>
# The keystore password comes from the first of these that exists:
#   1. TAEN_KS_PASS_FILE  a file holding the password (mode 600, outside the repository)
#   2. ~/.config/taen/release.pass   the same kind of file, the default place (written by tools/keystore-pass.sh file)
#   3. the desktop keyring (secret-tool, entry "service taen-release-keystore", written by tools/keystore-pass.sh keyring)
# Without any of them the script stops and prints the one-time setup command (make keystore-pass); it never asks on the terminal.
# The password is never put on a command line or printed.
# Environment:
#   TAEN_KEYSTORE        keystore path (default ~/.config/taen/release.p12)
#   TAEN_KEY_ALIAS       key alias (default taen)
#   TAEN_SECRET_TOOL     the secret-tool program (default: secret-tool; tests replace it)
# Signature schemes: v1 off, v2 and v3 requested; apksigner wrote v3 only for minSdk 30 (verified on device: apkSigningVersion=3).
set -euo pipefail

[ $# -eq 2 ] || { echo "usage: $0 <unsigned.apk> <signed.apk>" >&2; exit 2; }
in="$1"; out="$2"
ks="${TAEN_KEYSTORE:-$HOME/.config/taen/release.p12}"
alias_name="${TAEN_KEY_ALIAS:-taen}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
secret_tool="${TAEN_SECRET_TOOL:-secret-tool}"
keyring_service="taen-release-keystore"
setup_hint="run once: make keystore-pass   (it asks for the keystore password one time and keeps it in your keyring, or in a file only you can read)"

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

# A password file must be a regular file of this user, readable by nobody else, outside the repository.
check_pass_file() {
  local f="$1" dir mode owner
  [ -f "$f" ] || { echo "error: password file $f is not a regular file" >&2; return 1; }
  dir="$(cd "$(dirname "$f")" && pwd)"
  case "$dir/" in "$root"/*) echo "error: the password file must not be inside the repository" >&2; return 1;; esac
  mode="$(stat -c '%a' "$f")"; owner="$(stat -c '%u' "$f")"
  if [ "$mode" != "600" ] && [ "$mode" != "400" ]; then echo "error: password file $f has mode $mode, it must be 600 (chmod 600 $f)" >&2; return 1; fi
  [ "$owner" = "$(id -u)" ] || { echo "error: password file $f belongs to another user" >&2; return 1; }
  [ -s "$f" ] || { echo "error: password file $f is empty" >&2; return 1; }
}

pass_file=""
tmp_pass=""
cleanup() { [ -z "$tmp_pass" ] || rm -f "$tmp_pass"; }
trap cleanup EXIT

if [ -n "${TAEN_KS_PASS_FILE:-}" ]; then
  check_pass_file "$TAEN_KS_PASS_FILE" || exit 2
  pass_file="$TAEN_KS_PASS_FILE"
elif [ -e "$HOME/.config/taen/release.pass" ]; then
  check_pass_file "$HOME/.config/taen/release.pass" || exit 2
  pass_file="$HOME/.config/taen/release.pass"
elif command -v "$secret_tool" > /dev/null 2>&1; then
  # the keyring answer goes to a file that only this user can read, on the RAM-backed runtime directory, and is removed on exit
  run_dir="${XDG_RUNTIME_DIR:-}"
  [ -n "$run_dir" ] && [ -d "$run_dir" ] || run_dir="$HOME/.cache"
  mkdir -p "$run_dir"
  tmp_pass="$(umask 077 && mktemp "$run_dir/taen-sign.XXXXXX")"
  if "$secret_tool" lookup service "$keyring_service" > "$tmp_pass" 2> /dev/null && [ -s "$tmp_pass" ]; then
    pass_file="$tmp_pass"
  fi
fi

[ -n "$pass_file" ] || { echo "error: no keystore password source: $setup_hint" >&2; exit 2; }

mkdir -p "$(dirname "$out")"
"$apksigner" sign --ks "$ks" --ks-key-alias "$alias_name" --ks-pass "file:$pass_file" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$out" "$in"
"$apksigner" verify --verbose "$out" | grep -E "^(Verifies|Verified using)"
echo "certificate SHA-256:"
"$apksigner" verify --print-certs "$out" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p'
