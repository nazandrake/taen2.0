#!/usr/bin/env bash
# Creates a signing key OUTSIDE the repository (default ~/.config/taen/release.p12, mode 600).
# Usage: tools/keygen.sh            keytool asks for the password and the certificate name on the terminal
# Environment: TAEN_KEYSTORE (path), TAEN_KEY_ALIAS (default taen), TAEN_KS_PASS_FILE (read the password from this file
# instead of asking; used for throw-away test keys only).
# Keep two offline copies of the keystore. Losing it means every installed copy has to be uninstalled (export first).
set -euo pipefail

ks="${TAEN_KEYSTORE:-$HOME/.config/taen/release.p12}"
alias_name="${TAEN_KEY_ALIAS:-taen}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

[ ! -e "$ks" ] || { echo "error: $ks already exists, not overwriting" >&2; exit 2; }
case "$(mkdir -p "$(dirname "$ks")" && cd "$(dirname "$ks")" && pwd)/" in
  "$root"/*) echo "error: the keystore must not be inside the repository" >&2; exit 2;;
esac

pass_args=()
if [ -n "${TAEN_KS_PASS_FILE:-}" ]; then pass_args=(-storepass:file "$TAEN_KS_PASS_FILE" -keypass:file "$TAEN_KS_PASS_FILE"); fi

umask 077
keytool -genkeypair -alias "$alias_name" -keyalg RSA -keysize 4096 -validity 36500 \
  -storetype PKCS12 -keystore "$ks" "${pass_args[@]}" ${TAEN_KEY_DNAME:+-dname "$TAEN_KEY_DNAME"}
chmod 600 "$ks"
echo "created $ks"
keytool -list -keystore "$ks" "${pass_args[@]}" -alias "$alias_name" 2>/dev/null | tail -1 || true
