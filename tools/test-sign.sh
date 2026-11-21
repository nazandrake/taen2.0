#!/usr/bin/env bash
# Tests of tools/sign.sh: signing without a prompt from a password file or the desktop keyring, and the refusals.
# Uses a throw-away key, a tiny APK built from tools/leak_audit/helper and a stub for secret-tool; nothing of the real key is read.
# Usage: tools/test-sign.sh
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sign="${TAEN_SIGN_SCRIPT:-$root/tools/sign.sh}"
fails=0

check() { # name expected actual
  if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; fails=$((fails + 1)); fi
}
contains() { # name haystack needle
  case "$2" in *"$3"*) echo "ok   $1";; *) echo "FAIL $1: output lacks [$3]"; fails=$((fails + 1));; esac
}

mkdir -p "$HOME/.cache"
tmp="$(mktemp -d "$HOME/.cache/taen-test-sign.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT

pass="throwaway-$(date +%s)-pass"
printf '%s\n' "$pass" > "$tmp/pass"
chmod 600 "$tmp/pass"
keytool -genkeypair -alias taen -keyalg RSA -keysize 2048 -validity 30 -storetype PKCS12 -keystore "$tmp/test.p12" \
  -storepass:file "$tmp/pass" -keypass:file "$tmp/pass" -dname "CN=sign test" > /dev/null 2>&1

python3 - "$root" "$tmp" <<'EOF'
import sys
sys.path.insert(0, sys.argv[1] + "/tools/leak_audit")
import helper_build
helper_build.build(sys.argv[1], sys.argv[2])
EOF
cp "$tmp/helper/unsigned.apk" "$tmp/in.apk"

export HOME="$tmp/home"
mkdir -p "$HOME/.config/taen"
export XDG_RUNTIME_DIR="$tmp/run"
mkdir -p "$XDG_RUNTIME_DIR"
export TAEN_KEYSTORE="$tmp/test.p12"
export TAEN_KEY_ALIAS=taen

# without any password source the script stops with the setup command instead of asking
out="$("$sign" "$tmp/in.apk" "$tmp/none.apk" < /dev/null 2>&1)"; rc=$?
check "no password source: refused" "2" "$rc"
contains "no password source: names the setup command" "$out" "make keystore-pass"
check "no password source: nothing signed" "no" "$([ -e "$tmp/none.apk" ] && echo yes || echo no)"

# a password file signs with no prompt
out="$(TAEN_KS_PASS_FILE="$tmp/pass" "$sign" "$tmp/in.apk" "$tmp/file.apk" < /dev/null 2>&1)"; rc=$?
check "password file: signed" "0" "$rc"
contains "password file: verified" "$out" "Verifies"

# the password is not printed, on success or on refusal
case "$out" in *"$pass"*) echo "FAIL password file: the password is in the output"; fails=$((fails + 1));; *) echo "ok   password file: password not printed";; esac

# the default password file is found without any variable
install -m 600 "$tmp/pass" "$HOME/.config/taen/release.pass"
out="$("$sign" "$tmp/in.apk" "$tmp/default.apk" < /dev/null 2>&1)"; rc=$?
check "default password file: signed" "0" "$rc"
rm -f "$HOME/.config/taen/release.pass"

# a password file other users can read is refused, nothing is signed
install -m 644 "$tmp/pass" "$tmp/loose"
out="$(TAEN_KS_PASS_FILE="$tmp/loose" "$sign" "$tmp/in.apk" "$tmp/loose.apk" < /dev/null 2>&1)"; rc=$?
check "mode 644 password file: refused" "2" "$rc"
contains "mode 644 password file: says why" "$out" "mode 644"
check "mode 644 password file: nothing signed" "no" "$([ -e "$tmp/loose.apk" ] && echo yes || echo no)"

# a password file inside the repository is refused
inrepo="$root/tools/.test-sign-pass.$$"
install -m 600 "$tmp/pass" "$inrepo"
out="$(TAEN_KS_PASS_FILE="$inrepo" "$sign" "$tmp/in.apk" "$tmp/inrepo.apk" < /dev/null 2>&1)"; rc=$?
rm -f "$inrepo"
check "password file in the repository: refused" "2" "$rc"
contains "password file in the repository: says why" "$out" "inside the repository"

# a wrong password signs nothing
printf '%s\n' "not-the-password" > "$tmp/wrong"; chmod 600 "$tmp/wrong"
out="$(TAEN_KS_PASS_FILE="$tmp/wrong" "$sign" "$tmp/in.apk" "$tmp/wrong.apk" < /dev/null 2>&1)"; rc=$?
check "wrong password: refused" "1" "$([ "$rc" -ne 0 ] && echo 1 || echo 0)"
check "wrong password: nothing signed" "no" "$([ -e "$tmp/wrong.apk" ] && echo yes || echo no)"

# the keyring (a stub of secret-tool that answers like the real one) signs, and an empty keyring is refused with the setup command
cat > "$tmp/secret-tool-ok" <<EOF
#!/usr/bin/env bash
[ "\$1 \$2 \$3" = "lookup service taen-release-keystore" ] && cat "$tmp/pass"
EOF
cat > "$tmp/secret-tool-empty" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
chmod +x "$tmp/secret-tool-ok" "$tmp/secret-tool-empty"
out="$(TAEN_SECRET_TOOL="$tmp/secret-tool-ok" "$sign" "$tmp/in.apk" "$tmp/keyring.apk" < /dev/null 2>&1)"; rc=$?
check "keyring: signed" "0" "$rc"
case "$out" in *"$pass"*) echo "FAIL keyring: the password is in the output"; fails=$((fails + 1));; *) echo "ok   keyring: password not printed";; esac
out="$(TAEN_SECRET_TOOL="$tmp/secret-tool-empty" "$sign" "$tmp/in.apk" "$tmp/nokeyring.apk" < /dev/null 2>&1)"; rc=$?
check "empty keyring: refused" "2" "$rc"
contains "empty keyring: names the setup command" "$out" "make keystore-pass"
check "keyring: no temporary password file is left" "0" "$(ls "$XDG_RUNTIME_DIR" | wc -l)"

# the setup script types the password once into a terminal, refuses a wrong one and stores the right one with mode 600
setup="$root/tools/keystore-pass.sh"
out="$("$setup" file < /dev/null 2>&1)"; rc=$?
check "setup without a terminal: refused" "2" "$rc"
drive() { # password mode -> exit status of the setup script run on a pseudo terminal
  python3 - "$setup" "$1" "$2" <<'EOF'
import os, pty, sys, time
setup, password, mode = sys.argv[1:4]
pid, fd = pty.fork()
if pid == 0:
    os.execv(setup, [setup, mode])
time.sleep(1.0)
os.write(fd, (password + "\n").encode())
out = b""
while True:
    try:
        chunk = os.read(fd, 1024)
    except OSError:
        break
    if not chunk:
        break
    out += chunk
_, status = os.waitpid(pid, 0)
if password.encode() in out.replace(b"\r\n", b"\n").replace(password.encode() + b"\n", b"", 1):
    print("password echoed")
sys.exit(os.waitstatus_to_exitcode(status))
EOF
}
out="$(drive "not-the-password" file 2>&1)"; rc=$?
check "setup with a wrong password: refused" "1" "$rc"
check "setup with a wrong password: nothing stored" "no" "$([ -e "$HOME/.config/taen/release.pass" ] && echo yes || echo no)"
out="$(drive "$pass" file 2>&1)"; rc=$?
check "setup with the right password: stored" "0" "$rc"
check "setup: the password was not echoed" "" "$(printf '%s' "$out" | grep -c 'password echoed' | grep -v '^0$' || true)"
check "setup: file mode" "600" "$(stat -c '%a' "$HOME/.config/taen/release.pass" 2> /dev/null)"
check "setup: file content" "$pass" "$(cat "$HOME/.config/taen/release.pass" 2> /dev/null)"
out="$("$sign" "$tmp/in.apk" "$tmp/afterwards.apk" < /dev/null 2>&1)"; rc=$?
check "signing after the setup needs nothing" "0" "$rc"

echo "$fails failure(s)"
[ "$fails" -eq 0 ]
