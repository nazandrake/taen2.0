#!/usr/bin/env bash
# Tests of tools/build-linux.sh (distro detection, argument parser, safety rules, dry run, system-package flow) and, when pwsh
# exists, of tools/build-windows.ps1 (parse + -DryRun). No network, no sudo, nothing outside a temporary directory.
# Usage: tools/test-build-scripts.sh
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="$root/tools/build-linux.sh"
ps1="$root/tools/build-windows.ps1"
fails=0
total=0

check() { # name expected actual
  total=$((total + 1))
  if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected [$2] got [$3]"; fails=$((fails + 1)); fi
}
contains() { # name haystack needle
  total=$((total + 1))
  case "$2" in *"$3"*) echo "ok   $1";; *) echo "FAIL $1: output lacks [$3]"; fails=$((fails + 1));; esac
}
lacks() { # name haystack needle
  total=$((total + 1))
  case "$2" in *"$3"*) echo "FAIL $1: output has [$3]"; fails=$((fails + 1));; *) echo "ok   $1";; esac
}

# shellcheck source=build-linux.sh
. "$script"
set +e

# On disk, not in a RAM-backed /tmp: the script checks 10 GiB free at the prefix.
mkdir -p "$HOME/.cache"
tmp="$(mktemp -d "$HOME/.cache/taen-test.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT

osr() { printf '%s\n' "$@" > "$tmp/os-release"; echo "$tmp/os-release"; }

# ---- distro detection (ID and ID_LIKE of real distributions) ----
check "detect debian" "debian" "$(taen_detect_distro "$(osr 'ID=debian')")"
check "detect ubuntu" "debian" "$(taen_detect_distro "$(osr 'ID=ubuntu' 'ID_LIKE=debian')")"
check "detect mint (ID_LIKE ubuntu debian)" "debian" "$(taen_detect_distro "$(osr 'NAME="Linux Mint"' 'ID=linuxmint' 'ID_LIKE="ubuntu debian"')")"
check "detect fedora" "fedora" "$(taen_detect_distro "$(osr 'ID=fedora')")"
check "detect rhel-like rocky" "fedora" "$(taen_detect_distro "$(osr 'ID="rocky"' 'ID_LIKE="rhel centos fedora"')")"
check "detect arch" "arch" "$(taen_detect_distro "$(osr 'ID=arch')")"
check "detect cachyos (ID_LIKE arch)" "arch" "$(taen_detect_distro "$(osr 'ID=cachyos' 'ID_LIKE=arch')")"
check "detect manjaro" "arch" "$(taen_detect_distro "$(osr 'ID=manjaro' 'ID_LIKE=arch')")"
check "detect opensuse-tumbleweed" "suse" "$(taen_detect_distro "$(osr 'ID="opensuse-tumbleweed"' 'ID_LIKE="opensuse suse"')")"
check "detect opensuse-leap" "suse" "$(taen_detect_distro "$(osr 'ID="opensuse-leap"' 'ID_LIKE="suse opensuse"')")"
check "detect alpine" "alpine" "$(taen_detect_distro "$(osr 'ID=alpine')")"
check "detect unknown id" "unknown" "$(taen_detect_distro "$(osr 'ID=gentoo')")"
check "detect empty file" "unknown" "$(taen_detect_distro "$(osr '')")"
check "detect missing file" "unknown" "$(taen_detect_distro "$tmp/none")"
check "detect ignores ID_LIKE of other keys (VARIANT_ID)" "unknown" "$(taen_detect_distro "$(osr 'ID=gentoo' 'VARIANT_ID=debian')")"

# ---- package names and the exact sudo command per family ----
check "pkg names debian" "curl unzip" "$(taen_pkg_names debian curl unzip)"
check "pkg names arch sha256sum -> coreutils" "coreutils curl" "$(taen_pkg_names arch sha256sum curl)"
check "pkg names dedupe tar+gzip? (separate packages)" "tar gzip" "$(taen_pkg_names fedora tar gzip)"
check "install cmd debian" "sudo apt-get update
sudo apt-get install -y curl unzip" "$(taen_install_cmds debian curl unzip)"
check "install cmd fedora" "sudo dnf install -y git" "$(taen_install_cmds fedora git)"
check "install cmd arch (no -Sy: no partial upgrade)" "sudo pacman -S --needed --noconfirm unzip" "$(taen_install_cmds arch unzip)"
check "install cmd suse" "sudo zypper --non-interactive install curl" "$(taen_install_cmds suse curl)"
check "install cmd unknown prints nothing" "" "$(taen_install_cmds unknown curl)"

# ---- machine checks ----
check "arch x86_64" "x64" "$(taen_jdk_arch x86_64)"
check "arch aarch64" "aarch64" "$(taen_jdk_arch aarch64)"
check "arch arm64 alias" "aarch64" "$(taen_jdk_arch arm64)"
taen_jdk_arch armv7l > /dev/null 2>&1; check "arch armv7l refused" "1" "$?"
taen_jdk_arch i686 > /dev/null 2>&1; check "arch i686 refused" "1" "$?"
taen_glibc_check "glibc 2.39" > /dev/null; check "glibc 2.39 ok" "0" "$?"
taen_glibc_check "glibc 2.28" > /dev/null; check "glibc 2.28 ok (boundary)" "0" "$?"
taen_glibc_check "glibc 2.27" > /dev/null; check "glibc 2.27 warns (3)" "3" "$?"
taen_glibc_check "glibc 2.100" > /dev/null; check "glibc 2.100 ok (numeric compare)" "0" "$?"
taen_glibc_check "glibc 3.0" > /dev/null; check "glibc 3.0 ok (major compare)" "0" "$?"
taen_glibc_check "" > /dev/null; check "no glibc refused" "1" "$?"
taen_enough_disk 10485760; check "disk 10 GiB ok" "0" "$?"
taen_enough_disk 10485759; check "disk 10 GiB - 1 KiB refused" "1" "$?"
taen_enough_disk 0; check "disk 0 refused" "1" "$?"
taen_enough_disk ""; check "disk unknown refused (fail closed)" "1" "$?"

# ---- version, pins and consistency with the project files ----
printf '    defaultConfig {\n        versionCode = 6\n        versionName = "9.8.7"\n    }\n' > "$tmp/b.kts"
check "version from gradle file" "9.8.7" "$(taen_version_from_gradle "$tmp/b.kts")"
check "version real file is a version" "1" "$(taen_version_from_gradle "$root/app/build.gradle.kts" | grep -Ec '^[0-9]+\.[0-9]+\.[0-9]+')"
for v in TAEN_JDK_X64_SHA256 TAEN_JDK_AARCH64_SHA256 TAEN_CMDLINE_SHA256; do
  check "pin $v is 64 hex" "1" "$(printf '%s' "${!v}" | grep -Ec '^[0-9a-f]{64}$')"
done
ndk="$(sed -n 's/^ *ndkVersion = "\(.*\)".*/\1/p' "$root/app/build.gradle.kts")"
bt="$(sed -n 's/^ *buildToolsVersion = "\([^"]*\)".*/\1/p' "$root/app/build.gradle.kts")"
check "NDK pin = app/build.gradle.kts" "ndk;$ndk" "$TAEN_SDK_NDK"
check "build-tools pin = app/build.gradle.kts" "build-tools;$bt" "$TAEN_SDK_BUILD_TOOLS"
check "cmdline sha = Containerfile" "1" "$(grep -c "$TAEN_CMDLINE_SHA256" "$root/tools/container/Containerfile")"
check "cmdline build = Containerfile" "1" "$(grep -c "commandlinetools-linux-${TAEN_CMDLINE_BUILD}_latest.zip" "$root/tools/container/Containerfile")"
check "Containerfile SDK packages = pins" "1" "$(grep -c "\"$TAEN_SDK_PLATFORM\" \"$TAEN_SDK_BUILD_TOOLS\" \"$TAEN_SDK_NDK\"" "$root/tools/container/Containerfile")"
if [ -f "$ps1" ]; then
  ps1text="$(cat "$ps1")"
  contains "ps1 has JDK version" "$ps1text" "${TAEN_JDK_VERSION%%+*}"
  contains "ps1 has cmdline build" "$ps1text" "$TAEN_CMDLINE_BUILD"
  contains "ps1 has platform pin" "$ps1text" "$TAEN_SDK_PLATFORM"
  contains "ps1 has build-tools pin" "$ps1text" "$TAEN_SDK_BUILD_TOOLS"
  contains "ps1 has NDK pin" "$ps1text" "$TAEN_SDK_NDK"
else
  check "ps1 exists" "present" "missing"
fi

# ---- the Android CLI of sdkmanager 23.0 (usage metrics) must be detected ----
mkdir -p "$tmp/au-clean" "$tmp/au-cli/cli" "$tmp/au-an" ; : > "$tmp/au-an/analytics.settings"
taen_assert_no_android_cli "$tmp/au-clean" > /dev/null 2>&1; check "no Android CLI traces: ok" "0" "$?"
taen_assert_no_android_cli "$tmp/au-cli" > /dev/null 2>&1; check "Android CLI folder detected" "1" "$?"
taen_assert_no_android_cli "$tmp/au-an" > /dev/null 2>&1; check "analytics.settings detected" "1" "$?"

# ---- sha256 verification (NIST vector: SHA-256("abc")) ----
printf 'abc' > "$tmp/abc.bin"
abc="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
taen_verify_sha256 "$tmp/abc.bin" "$abc" > /dev/null; check "sha256 match" "0" "$?"
check "sha256 match keeps the file" "1" "$([ -f "$tmp/abc.bin" ] && echo 1 || echo 0)"
taen_verify_sha256 "$tmp/abc.bin" "${abc:0:63}0" > /dev/null 2>&1; check "sha256 mismatch fails" "1" "$?"
check "sha256 mismatch deletes the file" "0" "$([ -f "$tmp/abc.bin" ] && echo 1 || echo 0)"
taen_verify_sha256 "$tmp/absent" "$abc" > /dev/null 2>&1; check "sha256 missing file fails" "1" "$?"

# ---- argument parser ----
parse() { taen_parse_args "$@"; }
parse; check "defaults: prefix" "$HOME/.cache/taen-build" "$OPT_PREFIX"
check "defaults: flags off" "0000000000" "$OPT_YES$OPT_NO_SYSTEM$OPT_ACCEPT_LICENSES$OPT_WITH_ADB$OPT_KEYGEN$OPT_SIGN$OPT_REMOVE_TOOLCHAIN$OPT_DRY_RUN$OPT_NO_BUILD$OPT_HELP"
parse --prefix /x/y --dry-run; check "parse --prefix" "/x/y" "$OPT_PREFIX"; check "parse --dry-run" "1" "$OPT_DRY_RUN"
parse --prefix=/a/b; check "parse --prefix=" "/a/b" "$OPT_PREFIX"
parse --yes --no-system-packages --accept-licenses --with-adb --keygen --sign --remove-toolchain --dry-run --no-build -h
check "parse all flags on" "1111111111" "$OPT_YES$OPT_NO_SYSTEM$OPT_ACCEPT_LICENSES$OPT_WITH_ADB$OPT_KEYGEN$OPT_SIGN$OPT_REMOVE_TOOLCHAIN$OPT_DRY_RUN$OPT_NO_BUILD$OPT_HELP" ;
parse --bogus 2> "$tmp/err"; check "unknown option returns 2" "2" "$?"; contains "unknown option message" "$(cat "$tmp/err")" "unknown option: --bogus"
parse --prefix 2> "$tmp/err"; check "--prefix without value returns 2" "2" "$?"
parse --prefix= 2> "$tmp/err"; check "--prefix= empty returns 2" "2" "$?"
parse positional 2> "$tmp/err"; check "positional argument returns 2" "2" "$?"

# ---- prefix safety (the prefix can be deleted by --remove-toolchain) ----
safe() { taen_prefix_safe "$1" "$root" > /dev/null 2>&1; echo $?; }
mkdir -p "$tmp/p-empty" "$tmp/p-full" "$tmp/p-marked"
echo x > "$tmp/p-full/file"; : > "$tmp/p-marked/.taen-build-prefix"; echo x > "$tmp/p-marked/file"
check "prefix new dir ok" "0" "$(safe "$tmp/new/dir")"
check "prefix empty dir ok" "0" "$(safe "$tmp/p-empty")"
check "prefix marked dir ok" "0" "$(safe "$tmp/p-marked")"
check "prefix non-empty unmarked refused" "1" "$(safe "$tmp/p-full")"
check "prefix / refused" "1" "$(safe /)"
check "prefix HOME refused" "1" "$(safe "$HOME")"
check "prefix repo root refused" "1" "$(safe "$root")"
check "prefix parent of repo refused" "1" "$(safe "$(dirname "$root")")"
check "prefix inside repo refused" "1" "$(safe "$root/build-prefix")"
check "prefix relative refused" "1" "$(safe "rel/dir")"
# each rule alone (the repo and HOME here are fake, so that the other rules do not also refuse)
mkdir -p "$tmp/fr/repo" "$tmp/fh"; : > "$tmp/fr/.taen-build-prefix"; : > "$tmp/fh/.taen-build-prefix"
taen_prefix_safe "$tmp/fr" "$tmp/fr/repo" > /dev/null 2>&1; check "marked parent of the repo refused (contains rule alone)" "1" "$?"
( HOME="$tmp/fh"; taen_prefix_safe "$tmp/fh" "$tmp/fr/repo" > /dev/null 2>&1 ); check "marked HOME refused (HOME rule alone)" "1" "$?"
taen_prefix_safe "$tmp/fr/other" "$tmp/fr/repo" > /dev/null 2>&1; check "sibling of the repo ok (positive control)" "0" "$?"

# ---- cleanup: archives and temp files go, the toolchain stays unless asked ----
mkpfx() { rm -rf "$tmp/c"; mkdir -p "$tmp/c/downloads" "$tmp/c/tmp" "$tmp/c/jdk" "$tmp/c/android-sdk"; : > "$tmp/c/.taen-build-prefix"; : > "$tmp/c/downloads/a.zip"; : > "$tmp/c/tmp/t"; }
mkpfx; taen_cleanup "$tmp/c" 0 > /dev/null
check "cleanup removes downloads" "0" "$([ -e "$tmp/c/downloads" ] && echo 1 || echo 0)"
check "cleanup removes tmp" "0" "$([ -e "$tmp/c/tmp" ] && echo 1 || echo 0)"
check "cleanup keeps jdk and sdk" "11" "$([ -d "$tmp/c/jdk" ] && echo 1)$([ -d "$tmp/c/android-sdk" ] && echo 1)"
mkpfx; taen_cleanup "$tmp/c" 1 > /dev/null
check "cleanup --remove-toolchain removes the prefix" "0" "$([ -e "$tmp/c" ] && echo 1 || echo 0)"
mkpfx; rm "$tmp/c/.taen-build-prefix"; taen_cleanup "$tmp/c" 1 > /dev/null 2>&1
check "remove-toolchain refuses an unmarked dir" "1" "$([ -d "$tmp/c/jdk" ] && echo 1 || echo 0)"
mkpfx; rm "$tmp/c/.taen-build-prefix"; taen_cleanup "$tmp/c" 0 > /dev/null 2>&1
check "plain cleanup leaves an unmarked dir alone" "1" "$([ -d "$tmp/c/downloads" ] && echo 1 || echo 0)"

# ---- summary text ----
sum="$(taen_print_summary /d/taen-1.0.1-unsigned.apk "$abc" 1.0.1 /p 2>&1)"
contains "summary has the path" "$sum" "/d/taen-1.0.1-unsigned.apk"
contains "summary has the hash" "$sum" "$abc"
contains "summary compares with SHA256SUMS" "$sum" "SHA256SUMS"
contains "summary explains signing" "$sum" "--keygen"
contains "summary explains adb" "$sum" "adb install"
contains "summary enables the keyboard" "$sum" "adb shell ime enable io.github.nazandrake.taen/helium314.keyboard.latin.LatinIME"
contains "summary voice typing step 1" "$sum" "Settings > Voice typing > Download the model"
contains "summary voice typing step 2" "$sum" "Install downloaded model"

# ---- whole-script behaviour in a controlled PATH ----
real_tools="bash env sed tr head tail df cat mkdir rm date uname getconf awk grep tar gzip sha256sum id readlink dirname basename ls wc cut sort mktemp ln chmod mv cp stat tee sleep printf cmp uniq xargs find du yes"
stubcmd() { # dir name exit-code: a command that only logs its call (never writes through a symlink)
  rm -f "$1/$2"; printf '#!/bin/sh\nprintf "%%s %%s\\n" "$(basename "$0")" "$*" >> "%s"\nexit %s\n' "$tmp/calls.log" "${3:-0}" > "$1/$2"; chmod +x "$1/$2"
}
mkbin() { # dir, then tools to leave out; curl is always a logging stub that fails (no network in tests)
  local d="$1" t s skip p; shift; rm -rf "$d"; mkdir -p "$d"
  for t in $real_tools unzip git; do
    skip=0; for s in "$@"; do [ "$s" = "$t" ] && skip=1; done
    if [ "$skip" = 0 ]; then p="$(command -v "$t" 2>/dev/null || true)"; if [ -n "$p" ] && [ -x "$p" ]; then ln -s "$p" "$d/$t"; fi; fi
  done
  stubcmd "$d" curl 22
}

# dry run: plan printed, nothing created, no download tool called
mkbin "$tmp/bin1"; : > "$tmp/calls.log"
out="$(PATH="$tmp/bin1" TAEN_OS_RELEASE="$(osr 'ID=debian')" bash "$script" --dry-run --prefix "$tmp/dry" --no-system-packages 2>&1)"; rc=$?
check "dry-run exit 0" "0" "$rc"
contains "dry-run says so" "$out" "DRY RUN"
contains "dry-run lists JDK url" "$out" "$TAEN_JDK_X64_URL"
contains "dry-run lists cmdline url" "$out" "$TAEN_CMDLINE_URL"
contains "dry-run lists SDK platform" "$out" "$TAEN_SDK_PLATFORM"
contains "dry-run lists NDK" "$out" "$TAEN_SDK_NDK"
contains "dry-run lists build step" "$out" ":app:assembleRelease"
lacks "dry-run without --with-adb has no platform-tools" "$out" "platform-tools"
lacks "dry-run without --keygen has no keygen step" "$out" "tools/keygen.sh"
check "dry-run creates no prefix" "0" "$([ -e "$tmp/dry" ] && echo 1 || echo 0)"
check "dry-run calls no network tool" "0" "$(wc -l < "$tmp/calls.log")"
out="$(PATH="$tmp/bin1" TAEN_OS_RELEASE="$(osr 'ID=debian')" bash "$script" --dry-run --prefix "$tmp/dry" --no-system-packages --with-adb --keygen --sign --remove-toolchain 2>&1)"
contains "dry-run with --with-adb lists platform-tools" "$out" "platform-tools"
contains "dry-run with --keygen lists keygen" "$out" "tools/keygen.sh"
contains "dry-run with --sign lists sign" "$out" "tools/sign.sh"
contains "dry-run with --remove-toolchain says deletion" "$out" "delete the whole prefix"

# alpine refused, unknown distro prints the manual list, bad arguments exit 2
out="$(PATH="$tmp/bin1" TAEN_OS_RELEASE="$(osr 'ID=alpine')" bash "$script" --dry-run --prefix "$tmp/dry" 2>&1)"; rc=$?
check "alpine exit 1" "1" "$rc"; contains "alpine message" "$out" "Alpine"
out="$(PATH="$tmp/bin1" TAEN_OS_RELEASE="$(osr 'ID=gentoo')" bash "$script" --dry-run --prefix "$tmp/dry" 2>&1)"; rc=$?
check "unknown distro dry-run exit 0" "0" "$rc"
out="$(PATH="$tmp/bin1" bash "$script" --bogus 2>&1)"; rc=$?
check "bad option exit 2" "2" "$rc"; contains "bad option shows usage" "$out" "Usage:"
out="$(PATH="$tmp/bin1" bash "$script" --help 2>&1)"; rc=$?
check "--help exit 0" "0" "$rc"; contains "--help lists --remove-toolchain" "$out" "--remove-toolchain"

# missing base tools: the exact command is shown; sudo runs only with --yes; --no-system-packages never runs it
mkbin "$tmp/bin2" unzip git; stubcmd "$tmp/bin2" sudo 0
check "missing tools detected" "unzip git" "$(PATH="$tmp/bin2" taen_missing_tools)"
: > "$tmp/calls.log"
out="$(PATH="$tmp/bin2" TAEN_OS_RELEASE="$(osr 'ID=ubuntu' 'ID_LIKE=debian')" bash "$script" --dry-run --prefix "$tmp/dry2" 2>&1)"; rc=$?
check "dry-run with missing tools exit 0" "0" "$rc"
contains "dry-run shows the sudo command" "$out" "sudo apt-get install -y unzip git"
check "dry-run did not run sudo" "0" "$(wc -l < "$tmp/calls.log")"
: > "$tmp/calls.log"
out="$(PATH="$tmp/bin2" TAEN_OS_RELEASE="$(osr 'ID=ubuntu' 'ID_LIKE=debian')" bash "$script" --prefix "$tmp/r1" --no-system-packages < /dev/null 2>&1)"; rc=$?
check "--no-system-packages with missing unzip fails" "1" "$rc"; contains "tells which tool is missing" "$out" "unzip"
check "--no-system-packages did not run sudo" "0" "$(wc -l < "$tmp/calls.log")"
: > "$tmp/calls.log"
out="$(PATH="$tmp/bin2" TAEN_OS_RELEASE="$(osr 'ID=ubuntu' 'ID_LIKE=debian')" bash "$script" --prefix "$tmp/r1b" --yes --no-system-packages < /dev/null 2>&1)"; rc=$?
check "--yes with --no-system-packages still fails" "1" "$rc"
check "--yes with --no-system-packages did not run sudo" "0" "$(wc -l < "$tmp/calls.log")"
: > "$tmp/calls.log"
out="$(PATH="$tmp/bin2" TAEN_OS_RELEASE="$(osr 'ID=ubuntu' 'ID_LIKE=debian')" bash "$script" --prefix "$tmp/r2" < /dev/null 2>&1)"; rc=$?
check "no tty and no --yes: does not install, fails" "1" "$rc"; contains "asks for --yes" "$out" "--yes"
check "no tty and no --yes: did not run sudo" "0" "$(wc -l < "$tmp/calls.log")"
: > "$tmp/calls.log"
out="$(PATH="$tmp/bin2" TAEN_OS_RELEASE="$(osr 'ID=ubuntu' 'ID_LIKE=debian')" bash "$script" --prefix "$tmp/r3" --yes --accept-licenses < /dev/null 2>&1)"; rc=$?
contains "--yes ran sudo update" "$(cat "$tmp/calls.log")" "sudo apt-get update"
contains "--yes ran sudo install of exactly the missing tools" "$(cat "$tmp/calls.log")" "sudo apt-get install -y unzip git"
check "tool still missing after the (stub) install is a failure" "1" "$rc"
contains "failure prints what to do" "$out" "What to do"
contains "failure names the prefix" "$out" "$tmp/r3"

# a failed download (stub curl exits 22): failure summary, no archive left, the exit status is not 0
: > "$tmp/calls.log"
out="$(PATH="$tmp/bin1" TAEN_OS_RELEASE="$(osr 'ID=debian')" bash "$script" --prefix "$tmp/r4" --no-system-packages --accept-licenses < /dev/null 2>&1)"; rc=$?
check "failed download exits with curl's status (22)" "22" "$rc"
contains "failed download names the step" "$out" "FAILED during: Temurin JDK"
contains "failed download prints what to do" "$out" "What to do"
contains "curl was called for the pinned JDK url" "$(cat "$tmp/calls.log")" "$TAEN_JDK_X64_URL"
contains "curl uses https only" "$(cat "$tmp/calls.log")" "--proto =https"
check "failed download leaves no archive folder" "0" "$([ -e "$tmp/r4/downloads" ] && echo 1 || echo 0)"
check "failed download leaves the marker (prefix reusable)" "1" "$([ -f "$tmp/r4/.taen-build-prefix" ] && echo 1 || echo 0)"

# unsafe prefix is refused before anything is written
mkdir -p "$tmp/unsafe"; echo x > "$tmp/unsafe/keep"
out="$(PATH="$tmp/bin1" TAEN_OS_RELEASE="$(osr 'ID=debian')" bash "$script" --prefix "$tmp/unsafe" --no-system-packages 2>&1)"; rc=$?
check "non-empty unmarked prefix exit 1" "1" "$rc"; check "unmarked prefix content untouched" "keep" "$(ls "$tmp/unsafe")"

# ---- Windows script: only with pwsh (PowerShell 7) available ----
pwsh_bin="${TAEN_PWSH:-$(command -v pwsh 2>/dev/null || true)}"
if [ -n "$pwsh_bin" ] && [ -x "$pwsh_bin" ]; then
  out="$(TAEN_PS1="$ps1" "$pwsh_bin" -NoProfile -Command '$e=$null; [void][System.Management.Automation.Language.Parser]::ParseFile($env:TAEN_PS1,[ref]$null,[ref]$e); $e.Count' 2>&1)"
  check "ps1 parses without errors" "0" "$out"
  out="$("$pwsh_bin" -NoProfile -File "$ps1" -DryRun -Prefix "$tmp/winprefix" 2>&1)"; rc=$?
  check "ps1 -DryRun exit 0" "0" "$rc"; contains "ps1 -DryRun says so" "$out" "DRY RUN"
  contains "ps1 -DryRun lists the JDK url" "$out" "OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip"
  contains "ps1 -DryRun lists the cmdline url" "$out" "commandlinetools-win-${TAEN_CMDLINE_BUILD}_latest.zip"
  contains "ps1 -DryRun lists the NDK" "$out" "$TAEN_SDK_NDK"
  lacks "ps1 -DryRun without -WithAdb has no platform-tools" "$out" "platform-tools"
  check "ps1 -DryRun creates nothing" "0" "$([ -e "$tmp/winprefix" ] && echo 1 || echo 0)"
  out="$("$pwsh_bin" -NoProfile -File "$ps1" -DryRun -Prefix "$tmp/winprefix" -WithAdb -Keygen -Sign -RemoveToolchain 2>&1)"
  contains "ps1 -WithAdb lists platform-tools" "$out" "platform-tools"
  contains "ps1 -Keygen lists keytool" "$out" "keytool"
  contains "ps1 -Sign lists apksigner" "$out" "apksigner"
  contains "ps1 -RemoveToolchain says deletion" "$out" "delete the whole prefix"
  out="$("$pwsh_bin" -NoProfile -File "$ps1" -DryRun -Prefix "$tmp/unsafe" 2>&1)"; rc=$?
  check "ps1 non-empty unmarked prefix exit 1" "1" "$rc"; check "ps1 unmarked prefix content untouched" "keep" "$(ls "$tmp/unsafe")"
  out="$("$pwsh_bin" -NoProfile -File "$ps1" -DryRun -Prefix "$tmp/with space" 2>&1)"; rc=$?
  check "ps1 prefix with a space exit 1" "1" "$rc"
  out="$("$pwsh_bin" -NoProfile -File "$ps1" -DryRun -Prefix "$root/inside" 2>&1)"; rc=$?
  check "ps1 prefix inside the repo exit 1" "1" "$rc"
  out="$("$pwsh_bin" -NoProfile -File "$ps1" -Prefix "$tmp/winprefix2" 2>&1)"; rc=$?
  check "ps1 real run on a non-Windows host exit 1" "1" "$rc"; contains "ps1 real run says Windows only" "$out" "this script is for Windows"
  check "ps1 real run on a non-Windows host creates nothing" "0" "$([ -e "$tmp/winprefix2" ] && echo 1 || echo 0)"

  # ---- the Android CLI guard of the Windows script (real path: Install-SdkPackages runs a stub sdkmanager, then the guard) ----
  # Dot-sourcing defines the functions only. The stub plays sdkmanager 19.0 (leaves nothing) or 23.0 (leaves the CLI traces).
  export POWERSHELL_TELEMETRY_OPTOUT=1 POWERSHELL_UPDATECHECK=Off DOTNET_CLI_TELEMETRY_OPTOUT=1
  printf '%s\n' '#!/bin/sh' 'mkdir -p "$ANDROID_USER_HOME"' 'case "$FAKE_CLI" in analytics) : > "$ANDROID_USER_HOME/analytics.settings";; folder) mkdir -p "$ANDROID_USER_HOME/cli";; esac' 'echo "$@" > "$ANDROID_USER_HOME/../sdkmanager-args.txt"' > "$tmp/fake-sdkmanager.bat"
  chmod +x "$tmp/fake-sdkmanager.bat"
  ps_sdk() { # <FAKE_CLI mode> <user home dir>
    FAKE_CLI="$1" ANDROID_USER_HOME="$2" TAEN_PS1="$ps1" TAEN_SM="$tmp/fake-sdkmanager.bat" TAEN_UH="$2" "$pwsh_bin" -NoProfile -Command \
      'try { . $env:TAEN_PS1; Install-SdkPackages $env:TAEN_SM "/sdk" @("platforms;android-37.0","ndk;28.0.13004108") $env:TAEN_UH; Write-Output "SDK-STEP-DONE" } catch { Write-Output "ERR: $($_.Exception.Message)"; exit 1 }' 2>&1
  }
  out="$(ps_sdk none "$tmp/psu-clean/android-user")"; rc=$?
  check "ps1 sdk step without Android CLI traces exit 0" "0" "$rc"; contains "ps1 sdk step finishes when clean" "$out" "SDK-STEP-DONE"
  contains "ps1 sdk step passes the pinned packages to sdkmanager" "$(cat "$tmp/psu-clean/sdkmanager-args.txt")" "--sdk_root=/sdk platforms;android-37.0 ndk;28.0.13004108"
  out="$(ps_sdk analytics "$tmp/psu-an/android-user")"; rc=$?
  check "ps1 analytics.settings after sdkmanager exit 1" "1" "$rc"; contains "ps1 analytics.settings is named" "$out" "installed the Android CLI"
  lacks "ps1 analytics.settings stops before the step is done" "$out" "SDK-STEP-DONE"
  out="$(ps_sdk folder "$tmp/psu-cli/android-user")"; rc=$?
  check "ps1 cli folder after sdkmanager exit 1" "1" "$rc"; contains "ps1 cli folder is named" "$out" "installed the Android CLI"
  # the main flow uses that function and no longer calls sdkmanager for the packages directly
  contains "ps1 main installs the packages through Install-SdkPackages" "$ps1text" 'Install-SdkPackages $sm $sdk $pkgs (Join-Path $Prefix '"'"'android-user'"'"')'
  lacks "ps1 main has no unguarded package install" "$ps1text" 'Invoke-Native $sm (@("--sdk_root=$sdk") + $pkgs)'
else
  echo "SKIP Windows script: pwsh not found on this machine (NOT VERIFIED)"
fi

echo "$((total - fails))/$total passed"
[ "$fails" -eq 0 ]
