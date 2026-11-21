#!/usr/bin/env bash
# One-time setup so that tools/sign.sh (make sign) signs without asking for the keystore password.
# Usage: tools/keystore-pass.sh [keyring|file]        (default: keyring when secret-tool and a keyring are there, else file)
#   keyring   keeps the password in the desktop keyring (secret-tool, entry "service taen-release-keystore")
#   file      keeps it in ~/.config/taen/release.pass, mode 600, outside the repository
# The password is typed here once (it is not shown), checked against the keystore, and stored. It is never printed or put on a command line.
# Environment: TAEN_KEYSTORE (default ~/.config/taen/release.p12), TAEN_SECRET_TOOL (default secret-tool), TAEN_PASS_FILE (default ~/.config/taen/release.pass).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ks="${TAEN_KEYSTORE:-$HOME/.config/taen/release.p12}"
secret_tool="${TAEN_SECRET_TOOL:-secret-tool}"
pass_file="${TAEN_PASS_FILE:-$HOME/.config/taen/release.pass}"
keyring_service="taen-release-keystore"
mode="${1:-}"

[ -f "$ks" ] || { echo "error: keystore $ks not found; create one with tools/keygen.sh first" >&2; exit 2; }
if [ -z "$mode" ]; then
  if command -v "$secret_tool" > /dev/null 2>&1; then mode=keyring; else mode=file; fi
fi
case "$mode" in keyring|file) ;; *) echo "usage: $0 [keyring|file]" >&2; exit 2;; esac
case "$(cd "$(dirname "$pass_file")" 2> /dev/null && pwd || echo none)/" in
  "$root"/*) echo "error: the password file must not be inside the repository" >&2; exit 2;;
esac
[ -t 0 ] || { echo "error: run this in a terminal: it asks for the password once" >&2; exit 2; }

read -r -s -p "Keystore password (not shown): " password; echo
[ -n "$password" ] || { echo "error: empty password" >&2; exit 2; }

run_dir="${XDG_RUNTIME_DIR:-}"
[ -n "$run_dir" ] && [ -d "$run_dir" ] || run_dir="$HOME/.cache"
mkdir -p "$run_dir"
check="$(umask 077 && mktemp "$run_dir/taen-pass.XXXXXX")"
trap 'rm -f "$check"; password=""' EXIT
printf '%s\n' "$password" > "$check"
if ! keytool -list -keystore "$ks" -storepass:file "$check" > /dev/null 2>&1; then
  echo "error: that is not the password of $ks (nothing was stored)" >&2
  exit 1
fi

if [ "$mode" = keyring ]; then
  printf '%s' "$password" | "$secret_tool" store --label="Taen release keystore" service "$keyring_service"
  echo "stored in the keyring (service $keyring_service). make sign now needs no password."
else
  mkdir -p "$(dirname "$pass_file")"
  ( umask 077 && printf '%s\n' "$password" > "$pass_file" )
  chmod 600 "$pass_file"
  echo "stored in $pass_file (mode 600). make sign now needs no password."
fi
