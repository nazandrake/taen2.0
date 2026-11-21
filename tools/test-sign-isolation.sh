#!/usr/bin/env bash
# Guard for tools/test-sign.sh: the signing tests must not reach anything of the caller's environment.
# It exports every TAEN_* variable the signing scripts and their test read (found by searching the scripts, so a new option is covered at once)
# as a decoy outside the test's scratch directory: files that hold the throw-away key's password (a leak would make a refusal case pass), a
# valid keystore, a secret-tool that answers with the password. Then it runs the whole of tools/test-sign.sh and checks that the run passed, and
# that no decoy was changed (content, inode, size, mode, modification time) or read (access time, set far in the past before the run; the decoys
# live on tmpfs where reads update it, and a control read proves that here).
# Usage: tools/test-sign-isolation.sh
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fails=0
check() { # name expected actual
  if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; fails=$((fails + 1)); fi
}

base="/dev/shm"
[ -d "$base" ] && [ -w "$base" ] || base="${XDG_RUNTIME_DIR:-}"
[ -n "$base" ] && [ -d "$base" ] || { echo "error: no tmpfs directory for the decoys" >&2; exit 2; }
dd="$(mktemp -d "$base/taen-decoys.XXXXXX")"
trap 'rm -rf "$dd"' EXIT

throwaway_pass="$(sed -n 's/^throwaway_pass="\(.*\)"$/\1/p' "$root/tools/test-sign.sh")"
[ -n "$throwaway_pass" ] || { echo "error: cannot find throwaway_pass in tools/test-sign.sh" >&2; exit 2; }

# every TAEN_* name the scripts read; TAEN_SIGN_SCRIPT is the test's own input and stays unset
names="$(cat "$root/tools/sign.sh" "$root/tools/keystore-pass.sh" "$root/tools/test-sign.sh" "$root/tools/leak_audit/helper_build.py" \
  | grep -oE 'TAEN_[A-Z0-9_]+' | sort -u | grep -vx 'TAEN_SIGN_SCRIPT')"
for need in TAEN_PASS_FILE TAEN_KS_PASS_FILE TAEN_KEYSTORE TAEN_KEY_ALIAS TAEN_SECRET_TOOL; do
  case " $(echo $names) " in *" $need "*) ;; *) echo "FAIL the search missed $need"; fails=$((fails + 1));; esac
done

# the old time: a read moves the access time off it, a write moves the modification time off its own old value
old_atime="2000-01-01 00:00:00"
old_mtime="2001-01-01 00:00:00"
decoy_files=()
exports=()
for n in $names; do
  case "$n" in
    *_TOOL)
      f="$dd/$n"
      printf '#!/usr/bin/env bash\necho "$@" >> "%s.calls"\nprintf "%%s" "%s"\n' "$f" "$throwaway_pass" > "$f"
      chmod 700 "$f"; decoy_files+=("$f"); exports+=("$n=$f");;
    *KEYSTORE)
      f="$dd/$n"
      printf '%s\n' "$throwaway_pass" > "$dd/ks.pass"
      keytool -genkeypair -alias taen -keyalg RSA -keysize 2048 -validity 30 -storetype PKCS12 -keystore "$f" \
        -storepass:file "$dd/ks.pass" -keypass:file "$dd/ks.pass" -dname "CN=decoy" > /dev/null 2>&1
      rm -f "$dd/ks.pass"
      chmod 600 "$f"; decoy_files+=("$f"); exports+=("$n=$f");;
    *_FILE)
      f="$dd/$n"
      printf '%s\n' "$throwaway_pass" > "$f"
      chmod 600 "$f"; decoy_files+=("$f"); exports+=("$n=$f");;
    *)
      exports+=("$n=decoy-$n");;
  esac
done
check "decoys made for every name" "$(echo $names | wc -w | tr -d ' ')" "${#exports[@]}"

state() { # file -> the part that must not change on any read or write: inode, size, mode, mtime, content
  printf '%s\n' "$(stat -c '%i %s %a %Y' "$1") $(sha256sum < "$1" | cut -d' ' -f1)"
}
atime_of() { stat -c '%X' "$1"; }
rewind() { touch -a -d "$old_atime" "$1" && touch -m -d "$old_mtime" "$1" && touch -a -d "$old_atime" "$1"; }

# a control read proves that reads are visible here
ctl="$dd/control"
printf 'control\n' > "$ctl"
rewind "$ctl"
before_ctl="$(atime_of "$ctl")"
cat "$ctl" > /dev/null
check "control: a read moves the access time (so a read of a decoy would show)" "moved" "$([ "$(atime_of "$ctl")" != "$before_ctl" ] && echo moved || echo same)"
rm -f "$ctl"

declare -A states atimes
for f in "${decoy_files[@]}"; do
  rewind "$f"
  states["$f"]="$(state "$f")"
  touch -a -d "$old_atime" "$f"
  atimes["$f"]="$(atime_of "$f")"
done

log="$dd/test-sign.log"
env "${exports[@]}" "$root/tools/test-sign.sh" > "$log" 2>&1
rc=$?
check "test-sign passes with every TAEN_* variable set to a decoy" "0" "$rc"
check "test-sign ends with no failure" "0 failure(s)" "$(tail -n 1 "$log")"
[ "$rc" -eq 0 ] || sed -n '/^FAIL/p' "$log" | head -20

declare -A atimes_after
for f in "${decoy_files[@]}"; do atimes_after["$f"]="$(atime_of "$f")"; done  # before state() reads the files itself
for f in "${decoy_files[@]}"; do
  name="$(basename "$f")"
  check "decoy $name: not read" "${atimes["$f"]}" "${atimes_after["$f"]}"
  check "decoy $name: not changed or replaced" "${states["$f"]}" "$(state "$f")"
done
for f in "${decoy_files[@]}"; do
  check "decoy $(basename "$f"): no call recorded" "no" "$([ -e "$f.calls" ] && echo yes || echo no)"
done
check "no decoy file appeared or went missing" "$(( ${#decoy_files[@]} ))" "$(ls -A "$dd" | grep -vc -e '\.calls$' -e '^test-sign\.log$')"

echo "$fails failure(s)"
[ "$fails" -eq 0 ]
