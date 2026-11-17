#!/usr/bin/env bash
# Taen: one-command build for Linux. Detects the distribution, downloads a pinned JDK and Android SDK into ONE folder
# (default ~/.cache/taen-build), builds the unsigned release APK, prints its SHA-256, cleans up.
# Your own JDK, Android SDK, shell profile and PATH are never touched. Run with --help for the options and --dry-run for the plan.
# Tests: tools/test-build-scripts.sh. Documentation: BUILDING.md, section "One-command build".
set -euo pipefail

TAEN_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---- Pins. Every archive is checked against its SHA-256 before it is unpacked. Re-pin only from the official source. ----
# Temurin JDK 21 (Eclipse Adoptium). SHA-256 from https://api.adoptium.net/v3/assets/latest/21/hotspot (package.checksum), equal to the
# <archive>.sha256.txt next to each file on https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.13%2B9 (checked 2026-11-14).
TAEN_JDK_VERSION="21.0.13+9"
TAEN_JDK_X64_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz"
TAEN_JDK_X64_SHA256="d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c"
TAEN_JDK_AARCH64_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.13_9.tar.gz"
TAEN_JDK_AARCH64_SHA256="e3522e707fc7dbe2a72c30746b1e7068301a0213ecd213d4491f35a14eb55578"
# Android command-line tools 19.0 (build 13114758). Google publishes only a SHA-1 (5fdcc763663eefb86a5b8879697aa6088b041e70 in
# https://dl.google.com/android/repository/repository2-3.xml, equal to the downloaded file); the SHA-256 is of the same file (same value
# as tools/container/Containerfile). NOT the newest 23.0: its sdkmanager downloads the "Android CLI" (usage metrics on unless --no-metrics,
# a user id in ANDROID_USER_HOME) and accepts the SDK licences without asking (observed 2026-11-14), which breaks "no telemetry".
TAEN_CMDLINE_BUILD="13114758"
TAEN_CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
TAEN_CMDLINE_SHA256="7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee"
# SDK packages installed by sdkmanager: the versions the build files pin (compileSdk 37, buildToolsVersion, ndkVersion).
TAEN_SDK_PLATFORM="platforms;android-37.0"
TAEN_SDK_BUILD_TOOLS="build-tools;36.0.0"
TAEN_SDK_NDK="ndk;28.0.13004108"
TAEN_SDK_PLATFORM_TOOLS="platform-tools"   # adb only, --with-adb; not part of the APK, version not pinned
TAEN_MIN_DISK_KB=10485760                   # 10 GiB

CURRENT_STEP="start"
TRAP_ACTIVE=0
CREATED_LOCAL_PROPERTIES=0
TAEN_SIGNED_APK=""

# ---- output helpers ----
say()  { printf '%s\n' "$*"; }
step() { CURRENT_STEP="$*"; printf '\n==> %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

# ---- pure helpers (tested in tools/test-build-scripts.sh) ----
taen_detect_distro() { # <os-release file>: prints debian|fedora|arch|suse|alpine|unknown
  local file="$1" id="" like="" tok
  [ -r "$file" ] || { echo unknown; return 0; }
  id="$(sed -n 's/^ID=//p' "$file" | head -n1 | tr -d "\"'" | tr 'A-Z' 'a-z')"
  like="$(sed -n 's/^ID_LIKE=//p' "$file" | head -n1 | tr -d "\"'" | tr 'A-Z' 'a-z')"
  for tok in $id $like; do
    case "$tok" in
      debian|ubuntu|linuxmint|pop|raspbian|kali) echo debian; return 0;;
      fedora|rhel|centos|rocky|almalinux|ol|amzn) echo fedora; return 0;;
      arch|archlinux|manjaro|cachyos|endeavouros|garuda) echo arch; return 0;;
      suse|opensuse*|sles|sled) echo suse; return 0;;
      alpine) echo alpine; return 0;;
    esac
  done
  echo unknown
}

taen_pkg_names() { # <family> <tool>...: package names for the missing tools
  local family="$1" t out=""; shift
  for t in "$@"; do
    case "$t" in sha256sum) t=coreutils;; esac
    out="${out:+$out }$t"
  done
  : "$family"
  printf '%s\n' "$out"
}

taen_install_cmds() { # <family> <package>...: the sudo command lines, one per line (nothing for an unknown family)
  local family="$1"; shift
  case "$family" in
    debian) printf 'sudo apt-get update\nsudo apt-get install -y %s\n' "$*";;
    fedora) printf 'sudo dnf install -y %s\n' "$*";;
    arch)   printf 'sudo pacman -S --needed --noconfirm %s\n' "$*";;
    suse)   printf 'sudo zypper --non-interactive install %s\n' "$*";;
  esac
}

taen_missing_tools() { # prints the missing base tools, space separated
  local t out=""
  for t in curl unzip tar gzip sha256sum git; do
    command -v "$t" > /dev/null 2>&1 || out="${out:+$out }$t"
  done
  printf '%s\n' "$out"
}

taen_jdk_arch() { # <uname -m>: x64 or aarch64, else return 1
  case "$1" in
    x86_64|amd64) echo x64;;
    aarch64|arm64) echo aarch64;;
    *) return 1;;
  esac
}

taen_glibc_check() { # <getconf GNU_LIBC_VERSION output>: 0 ok, 1 no glibc, 3 older than 2.28 (untested)
  local v="${1#glibc }" major minor
  case "$1" in glibc\ [0-9]*.[0-9]*) ;; *) return 1;; esac
  major="${v%%.*}"; minor="${v#*.}"; minor="${minor%%.*}"
  if [ "$major" -gt 2 ] || { [ "$major" -eq 2 ] && [ "$minor" -ge 28 ]; }; then return 0; fi
  return 3
}

taen_enough_disk() { # <free KiB>: 0 when >= 10 GiB; empty or non-numeric fails (closed)
  case "${1:-}" in ''|*[!0-9]*) return 1;; esac
  [ "$1" -ge "$TAEN_MIN_DISK_KB" ]
}

taen_free_kb() { # <path that may not exist yet>
  local p="$1"
  while [ ! -d "$p" ] && [ "$p" != "/" ]; do p="$(dirname "$p")"; done
  df -Pk "$p" 2> /dev/null | awk 'NR==2 {print $4}'
}

taen_version_from_gradle() { # <app/build.gradle.kts>
  sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$1" | head -n1
}

taen_assert_no_android_cli() { # <ANDROID_USER_HOME>: the Android CLI (usage metrics) leaves these two; their presence is a failure
  local d="$1"
  if [ -e "$d/analytics.settings" ] || [ -e "$d/cli" ]; then
    echo "error: sdkmanager installed the Android CLI (usage metrics) in $d. Re-pin the command-line tools to a version whose sdkmanager does not (BUILDING.md section 3.1)." >&2
    return 1
  fi
}

taen_sha256_of() { sha256sum "$1" | cut -d' ' -f1; }

taen_verify_sha256() { # <file> <expected>: a file that does not match is deleted
  local f="$1" want="$2" got
  [ -f "$f" ] || { echo "error: $f not found" >&2; return 1; }
  got="$(taen_sha256_of "$f")"
  if [ "$got" = "$want" ]; then echo "sha256 OK: $(basename "$f")"; return 0; fi
  rm -f "$f"
  echo "error: SHA-256 mismatch for $(basename "$f"): expected $want, got $got. The file was deleted." >&2
  return 1
}

taen_parse_args() {
  OPT_PREFIX="$HOME/.cache/taen-build"
  OPT_YES=0; OPT_NO_SYSTEM=0; OPT_ACCEPT_LICENSES=0; OPT_WITH_ADB=0; OPT_KEYGEN=0; OPT_SIGN=0
  OPT_REMOVE_TOOLCHAIN=0; OPT_DRY_RUN=0; OPT_NO_BUILD=0; OPT_HELP=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --prefix)
        if [ $# -lt 2 ] || [ -z "$2" ] || [ "${2#--}" != "$2" ]; then echo "error: --prefix needs a directory" >&2; return 2; fi
        OPT_PREFIX="$2"; shift;;
      --prefix=*)
        OPT_PREFIX="${1#--prefix=}"
        [ -n "$OPT_PREFIX" ] || { echo "error: --prefix needs a directory" >&2; return 2; };;
      --yes) OPT_YES=1;;
      --no-system-packages) OPT_NO_SYSTEM=1;;
      --accept-licenses|--accept-licences) OPT_ACCEPT_LICENSES=1;;
      --with-adb) OPT_WITH_ADB=1;;
      --keygen) OPT_KEYGEN=1;;
      --sign) OPT_SIGN=1;;
      --remove-toolchain) OPT_REMOVE_TOOLCHAIN=1;;
      --dry-run) OPT_DRY_RUN=1;;
      --no-build) OPT_NO_BUILD=1;;
      -h|--help) OPT_HELP=1;;
      -*) echo "unknown option: $1" >&2; return 2;;
      *) echo "unexpected argument: $1" >&2; return 2;;
    esac
    shift
  done
}

taen_usage() {
  cat <<'EOF'
Usage: tools/build-linux.sh [options]

Builds the unsigned Taen release APK into dist/. Downloads a pinned JDK 21 and the Android SDK into one folder
(your own JDK, SDK, shell profile and PATH are never changed), builds, prints the SHA-256 and cleans up.

  --prefix DIR           where the toolchain goes (default ~/.cache/taen-build; about 6 GB, 10 GB free disk needed)
  --dry-run              print the plan and the system checks, download and change nothing
  --yes                  install missing base tools (curl, unzip, tar, gzip, git) with sudo without asking again
  --no-system-packages   never use sudo; stop if a needed base tool is missing
  --accept-licenses      accept the Android SDK licences without asking (otherwise you read and answer them)
  --with-adb             also install platform-tools (adb) into the folder
  --keygen               after the build create your signing key (tools/keygen.sh, key outside the repo, you type the password)
  --sign                 after the build sign the APK (tools/sign.sh, you type the password)
  --no-build             do not build; only (re)install what keygen/sign need and run them on the existing dist APK
  --remove-toolchain     at the end delete the whole prefix (JDK, SDK, Gradle cache)
  -h, --help             this text

The build itself downloads Gradle and the dependencies named in gradle/verification-metadata.xml (checksum pinned) from
services.gradle.org, Maven Central, Google Maven and jitpack.io. There is no telemetry and no other download.
EOF
}

# A prefix may be deleted by --remove-toolchain: it must be absolute, free of spaces, outside the repository and not contain it,
# not / or $HOME, and be new, empty or marked by an earlier run of this script.
taen_prefix_safe() { # <prefix> <repo root>
  local p="$1" repo="$2" home c r
  case "$p" in /*) ;; *) echo "error: the prefix must be an absolute path: $p" >&2; return 1;; esac
  case "$p" in *[[:space:]]*) echo "error: spaces in the prefix are not supported: $p" >&2; return 1;; esac
  c="$(readlink -m "$p")"; r="$(readlink -m "$repo")"; home="$(readlink -m "$HOME")"
  if [ "$c" = "/" ] || [ "$c" = "$home" ]; then echo "error: refusing $p as a toolchain folder" >&2; return 1; fi
  case "$r/" in "$c"/*) echo "error: the prefix must not be or contain the source folder" >&2; return 1;; esac
  case "$c/" in "$r"/*) echo "error: the prefix must not be inside the source folder" >&2; return 1;; esac
  if [ -e "$c" ] && [ ! -d "$c" ]; then echo "error: $p exists and is not a folder" >&2; return 1; fi
  if [ -d "$c" ] && [ ! -f "$c/.taen-build-prefix" ] && [ -n "$(ls -A "$c" 2> /dev/null)" ]; then
    echo "error: $p is not empty and was not created by this script; choose another --prefix" >&2; return 1
  fi
  return 0
}

taen_cleanup() { # <prefix> <remove toolchain 0|1>
  local p="$1" remove="$2"
  [ -d "$p" ] || return 0
  [ -f "$p/.taen-build-prefix" ] || { echo "error: $p was not created by this script, not deleting anything there" >&2; return 1; }
  rm -rf "$p/downloads" "$p/tmp"
  if [ "$remove" = 1 ]; then
    taen_prefix_safe "$p" "$TAEN_ROOT" > /dev/null || return 1
    rm -rf "$p"
    echo "removed the whole toolchain folder $p"
  else
    echo "removed downloaded archives and temp files (the toolchain stays in $p for the next build)"
  fi
}

taen_print_summary() { # <apk> <sha256> <version> <prefix>
  local apk="$1" sha="$2" ver="$3" prefix="$4" signed="${TAEN_SIGNED_APK:-}" adbhint="adb" target extra=""
  [ -x "$prefix/android-sdk/platform-tools/adb" ] && adbhint="$prefix/android-sdk/platform-tools/adb"
  target="${signed:-<your signed apk>}"
  [ "$prefix" = "$HOME/.cache/taen-build" ] || extra=" --prefix $prefix"
  cat <<EOF

======================================================================
Taen $ver built.
  APK (unsigned): $apk
  SHA-256:        $sha

Compare with a published build
  The unsigned APK is reproducible: the same source commit built with this script on Linux gives the same SHA-256.
  Put the published SHA256SUMS next to the APK and run:   sha256sum -c SHA256SUMS --ignore-missing
  (dist/SHA256SUMS was written with this build's hash.) A signed APK differs from it only in the signature block
  (BUILDING.md section 5, apksigcopier). Builds from another OS or JDK are not guaranteed to match.

Sign (Android refuses an unsigned APK)
  Your key stays on your machine, outside the repository; you type its password, nothing is stored.
  tools/build-linux.sh$extra --no-build --keygen --sign      (first time: creates ~/.config/taen/release.p12, then signs)
  tools/build-linux.sh$extra --no-build --sign               (later builds: key exists)
  Write down the certificate SHA-256 it prints and keep two offline copies of the key: updates need the same key.
EOF
  if [ -n "$signed" ]; then printf '  Signed APK: %s\n' "$signed"; fi
  cat <<EOF

Install on the phone (Android 11 or newer, 64-bit ARM)
  USB debugging on, phone connected:   $adbhint install -r $target
  or copy the signed APK to the phone, open it in the Files app and allow installs from that app.
  Note the current keyboard first:     $adbhint shell settings get secure default_input_method

Enable the keyboard
  $adbhint shell ime enable io.github.nazandrake.taen/helium314.keyboard.latin.LatinIME
  $adbhint shell ime set io.github.nazandrake.taen/helium314.keyboard.latin.LatinIME
  or on the phone: Settings > System > Languages & input > On-screen keyboard > Manage keyboards > Taen,
  then pick Taen with the keyboard switcher in any text field.

Voice typing (on the phone, optional; the app has no network permission and never downloads anything)
  Open the Taen app, then:
  1. Settings > Voice typing > Download the model  (your browser downloads one file, about 487 MB, Wi-Fi)
  2. Settings > Voice typing > Install downloaded model  (in the picker select the downloaded file in Downloads; it is checked and unpacked on the phone)
  Without a browser on the phone: make fetch-voice-model on this computer, then see BUILDING.md section 8.

More: BUILDING.md (checks, reproducibility, leak audit), PRIVACY.md.
======================================================================
EOF
}

# ---- environment for child processes only (nothing is exported to the caller's shell or profile) ----
taen_env() { # <command>...
  env -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS -u JDK_JAVA_OPTIONS -u GRADLE_OPTS -u ANDROID_SDK_ROOT \
    JAVA_HOME="$OPT_PREFIX/jdk" ANDROID_HOME="$OPT_PREFIX/android-sdk" ANDROID_USER_HOME="$OPT_PREFIX/android-user" \
    GRADLE_USER_HOME="$OPT_PREFIX/gradle-home" PATH="$OPT_PREFIX/jdk/bin:$PATH" "$@"
}

taen_fetch() { # <url> <dest> <sha256>
  local url="$1" dest="$2" sha="$3"
  mkdir -p "$(dirname "$dest")"
  say "downloading $url"
  local quiet=(--silent --show-error)
  [ ! -t 2 ] || quiet=(--progress-bar)   # a progress bar only on a terminal, not in a log file
  curl --fail --location --proto '=https' --tlsv1.2 --retry 3 --connect-timeout 30 "${quiet[@]}" -o "$dest.part" "$url"
  mv "$dest.part" "$dest"
  taen_verify_sha256 "$dest" "$sha"
}

taen_on_exit() {
  local rc=$?
  [ "$TRAP_ACTIVE" = 1 ] || return 0
  [ "$rc" -ne 0 ] || return 0
  TRAP_ACTIVE=0
  { printf '\nFAILED during: %s (exit %s)\nWhat to do:\n' "$CURRENT_STEP" "$rc"
    printf '  1. Read the error above. Nothing outside %s was changed (except base packages, if you let the script install them).\n' "$OPT_PREFIX"
    printf '  2. Fix the cause (network, disk space, missing tool) and run the same command again: finished steps are kept and checked again.\n'
    printf '  3. For a clean start delete the folder %s (or run with --remove-toolchain after a good build) and start again.\n' "$OPT_PREFIX"
    printf '  4. A SHA-256 mismatch means the download was damaged or changed: do not use it; try again later and ask before changing a pin.\n'
  } >&2
  taen_cleanup "$OPT_PREFIX" 0 > /dev/null 2>&1 || true
  return 0
}

taen_ask() { # <question>: asks on the terminal, default no
  local a
  [ -t 0 ] || return 1
  read -r -p "$1 [y/N] " a || return 1
  case "$a" in y|Y|yes|YES) return 0;; *) return 1;; esac
}

# ---- main flow ----
taen_preflight() {
  local os mem glibc rc free
  step "Checking this machine"
  os="$(uname -s)"; [ "$os" = "Linux" ] || die "this script is for Linux (found $os); on Windows use tools/build-windows.ps1"
  TAEN_MACHINE="$(uname -m)"
  TAEN_JDK_ARCH="$(taen_jdk_arch "$TAEN_MACHINE")" || die "unsupported CPU $TAEN_MACHINE (x86_64 and aarch64 only)"
  TAEN_FAMILY="$(taen_detect_distro "${TAEN_OS_RELEASE:-/etc/os-release}")"
  if [ "$TAEN_FAMILY" = alpine ]; then
    die "Alpine Linux (musl libc) is not supported: the JDK, SDK and NDK tools need glibc. Use a glibc distribution, a container or a VM."
  fi
  glibc="$(getconf GNU_LIBC_VERSION 2> /dev/null || true)"
  rc=0; taen_glibc_check "$glibc" || rc=$?
  case "$rc" in
    0) say "glibc: ${glibc#glibc }";;
    3) warn "glibc ${glibc#glibc } is older than 2.28; the Android tools were not tested on it";;
    *) die "no glibc found (musl-based system?): the JDK, SDK and NDK tools need glibc";;
  esac
  say "CPU: $TAEN_MACHINE (JDK $TAEN_JDK_ARCH), distribution family: $TAEN_FAMILY"
  if [ "$TAEN_FAMILY" = unknown ]; then
    say "Distribution not recognised (apt, dnf, pacman and zypper families are). Needed base tools: curl, unzip, tar, gzip, sha256sum (coreutils), git (optional)."
    say "Install the missing ones with your package manager; the script never installs packages on an unknown distribution."
  fi
  if [ "$TAEN_JDK_ARCH" = aarch64 ]; then
    warn "Google ships the Android build-tools and NDK for x86_64 Linux only; on aarch64 they need an x86_64 emulation layer (qemu-user + binfmt). Not tested."
  fi
  [ "$EUID" -ne 0 ] || warn "running as root; a normal user account is better (nothing here needs root)"
  [ -f "$TAEN_ROOT/gradlew" ] && [ -f "$TAEN_ROOT/app/build.gradle.kts" ] || die "run this from a complete Taen source tree (gradlew or app/build.gradle.kts not found in $TAEN_ROOT)"
  case "$OPT_PREFIX" in /*) ;; *) OPT_PREFIX="$PWD/$OPT_PREFIX";; esac
  OPT_PREFIX="$(readlink -m "$OPT_PREFIX")"
  taen_prefix_safe "$OPT_PREFIX" "$TAEN_ROOT" || exit 1
  free="$(taen_free_kb "$OPT_PREFIX")"
  taen_enough_disk "$free" || die "need at least 10 GiB free disk where $OPT_PREFIX goes (found ${free:-unknown} KiB); use --prefix on a bigger disk"
  say "free disk at the prefix: $((free / 1048576)) GiB (needs 10)"
  mem="$(awk '/^MemAvailable:/ {print int($2/1024)}' /proc/meminfo 2> /dev/null || true)"
  if [ -n "$mem" ] && [ "$mem" -lt 6144 ]; then
    warn "only $mem MiB RAM available; the build wants about 6 GiB free (Gradle JVM about 2.5 GB, more during the native build). Close other programs."
  else
    say "RAM available: ${mem:-unknown} MiB (the build wants about 6 GiB free)"
  fi
}

taen_print_plan() {
  local jdk_url jdk_sha apks n=1
  if [ "$TAEN_JDK_ARCH" = x64 ]; then jdk_url="$TAEN_JDK_X64_URL"; jdk_sha="$TAEN_JDK_X64_SHA256"; else jdk_url="$TAEN_JDK_AARCH64_URL"; jdk_sha="$TAEN_JDK_AARCH64_SHA256"; fi
  apks="$TAEN_SDK_PLATFORM, $TAEN_SDK_BUILD_TOOLS, $TAEN_SDK_NDK"
  [ "$OPT_WITH_ADB" = 0 ] || apks="$apks, $TAEN_SDK_PLATFORM_TOOLS"
  step "Plan"
  say "Taen $TAEN_VERSION, source: $TAEN_ROOT"
  say "Everything is downloaded into $OPT_PREFIX. Your JDK, Android SDK, shell profile and PATH are not touched."
  say "The environment variables below are set for the build processes only."
  if [ -n "$TAEN_MISSING" ]; then
    say "$n. Base tools missing: $TAEN_MISSING"; n=$((n + 1))
    if [ "$OPT_NO_SYSTEM" = 1 ]; then say "   --no-system-packages: install them yourself ($TAEN_MANUAL)"
    elif [ -n "$TAEN_INSTALL_CMDS" ]; then say "   would run with sudo, after asking (or with --yes):"; printf '%s\n' "$TAEN_INSTALL_CMDS" | sed 's/^/     /'
    else say "   unknown distribution: install these with your package manager: $TAEN_MANUAL"; fi
  fi
  say "$n. Download Temurin JDK $TAEN_JDK_VERSION: $jdk_url"; say "   expected SHA-256 $jdk_sha"; n=$((n + 1))
  say "$n. Download Android command-line tools $TAEN_CMDLINE_BUILD: $TAEN_CMDLINE_URL"; say "   expected SHA-256 $TAEN_CMDLINE_SHA256"; n=$((n + 1))
  if [ "$OPT_ACCEPT_LICENSES" = 1 ]; then say "$n. Android SDK licences: accepted because of --accept-licenses"; else say "$n. Android SDK licences: shown to you, you answer y or n"; fi; n=$((n + 1))
  say "$n. sdkmanager installs exactly: $apks"; n=$((n + 1))
  if [ "$OPT_NO_BUILD" = 0 ]; then
    say "$n. ./gradlew --no-daemon --console=plain :app:assembleRelease with JAVA_HOME=$OPT_PREFIX/jdk ANDROID_HOME=$OPT_PREFIX/android-sdk GRADLE_USER_HOME=$OPT_PREFIX/gradle-home"
    say "   (Gradle downloads its pinned distribution and the checksum-verified dependencies from services.gradle.org, Maven Central, Google Maven, jitpack.io)"; n=$((n + 1))
    say "$n. Copy the APK to dist/taen-$TAEN_VERSION-unsigned.apk, write dist/SHA256SUMS, print the SHA-256"; n=$((n + 1))
  fi
  [ "$OPT_KEYGEN" = 0 ] || { say "$n. tools/keygen.sh: your signing key outside the repository (you type the password)"; n=$((n + 1)); }
  [ "$OPT_SIGN" = 0 ] || { say "$n. tools/sign.sh: dist/taen-$TAEN_VERSION-signed.apk (you type the password)"; n=$((n + 1)); }
  say "$n. Cleanup: stop Gradle, remove downloaded archives and temp files"
  [ "$OPT_REMOVE_TOOLCHAIN" = 0 ] || say "   --remove-toolchain: also delete the whole prefix $OPT_PREFIX"
  say "No telemetry. No download other than those listed."
}

taen_system_tools() {
  local fatal="" t pkgs line argv
  [ -n "$TAEN_MISSING" ] || { say "base tools: all present"; return 0; }
  for t in $TAEN_MISSING; do [ "$t" = git ] || fatal="${fatal:+$fatal }$t"; done
  step "Base tools missing: $TAEN_MISSING"
  if [ "$OPT_NO_SYSTEM" = 1 ] || [ -z "$TAEN_INSTALL_CMDS" ]; then
    if [ -n "$fatal" ]; then
      die "missing base tools: $fatal. Install them ($TAEN_MANUAL) and run again."
    fi
    warn "git is missing; the build does not need it, but you need it to update the source"
    return 0
  fi
  say "This needs administrator rights and changes your system. The exact commands:"
  printf '%s\n' "$TAEN_INSTALL_CMDS" | sed 's/^/  /'
  if [ "$OPT_YES" = 1 ]; then say "(--yes given)"
  elif [ -t 0 ]; then taen_ask "Run these commands now?" || die "not installed. Install $TAEN_MISSING yourself or run again with --yes."
  else die "not running in a terminal and --yes not given, so nothing was installed. Run the commands above yourself or add --yes."
  fi
  while IFS= read -r line; do
    read -ra argv <<< "$line"
    if [ "$EUID" -eq 0 ]; then argv=("${argv[@]:1}"); fi
    if [ "${argv[0]}" = sudo ] && ! command -v sudo > /dev/null 2>&1; then die "sudo not found; run as root: ${line#sudo }"; fi
    "${argv[@]}"
  done <<< "$TAEN_INSTALL_CMDS"
  pkgs="$(taen_missing_tools)"
  fatal=""; for t in $pkgs; do [ "$t" = git ] || fatal="${fatal:+$fatal }$t"; done
  [ -z "$fatal" ] || die "still missing after the install: $fatal"
}

taen_install_jdk() {
  local url sha arch="$TAEN_JDK_ARCH" d="$OPT_PREFIX/downloads" x="$OPT_PREFIX/tmp/jdk-extract"
  if [ "$arch" = x64 ]; then url="$TAEN_JDK_X64_URL"; sha="$TAEN_JDK_X64_SHA256"; else url="$TAEN_JDK_AARCH64_URL"; sha="$TAEN_JDK_AARCH64_SHA256"; fi
  step "Temurin JDK $TAEN_JDK_VERSION"
  if [ -f "$OPT_PREFIX/jdk/.taen-pin" ] && [ "$(cat "$OPT_PREFIX/jdk/.taen-pin")" = "$sha" ] && [ -x "$OPT_PREFIX/jdk/bin/java" ]; then
    say "already installed in $OPT_PREFIX/jdk"; return 0
  fi
  taen_fetch "$url" "$d/jdk.tar.gz" "$sha"
  rm -rf "$OPT_PREFIX/jdk" "$x"; mkdir -p "$x"
  tar -xzf "$d/jdk.tar.gz" -C "$x"
  [ "$(ls "$x" | wc -l)" -eq 1 ] || die "unexpected JDK archive layout"
  mv "$x"/* "$OPT_PREFIX/jdk"
  printf '%s' "$sha" > "$OPT_PREFIX/jdk/.taen-pin"
  rm -f "$d/jdk.tar.gz"
  "$OPT_PREFIX/jdk/bin/java" -version 2>&1 | head -n1
}

taen_install_cmdline() {
  local d="$OPT_PREFIX/downloads" x="$OPT_PREFIX/tmp/cmdline-extract" sdk="$OPT_PREFIX/android-sdk"
  step "Android command-line tools $TAEN_CMDLINE_BUILD"
  if [ -f "$sdk/cmdline-tools/latest/.taen-pin" ] && [ "$(cat "$sdk/cmdline-tools/latest/.taen-pin")" = "$TAEN_CMDLINE_SHA256" ]; then
    say "already installed"; return 0
  fi
  taen_fetch "$TAEN_CMDLINE_URL" "$d/cmdline-tools.zip" "$TAEN_CMDLINE_SHA256"
  rm -rf "$x" "$sdk/cmdline-tools/latest"; mkdir -p "$x" "$sdk/cmdline-tools"
  unzip -q "$d/cmdline-tools.zip" -d "$x"
  [ -d "$x/cmdline-tools" ] || die "unexpected command-line tools archive layout"
  mv "$x/cmdline-tools" "$sdk/cmdline-tools/latest"
  printf '%s' "$TAEN_CMDLINE_SHA256" > "$sdk/cmdline-tools/latest/.taen-pin"
  rm -f "$d/cmdline-tools.zip"
}

taen_install_sdk() {
  local sdk="$OPT_PREFIX/android-sdk" sm pkgs
  sm="$sdk/cmdline-tools/latest/bin/sdkmanager"
  step "Android SDK licences"
  if [ "$OPT_ACCEPT_LICENSES" = 1 ]; then
    say "accepting the licences because --accept-licenses was given; the text is saved in $OPT_PREFIX/android-sdk-licences.txt"
    taen_env "$sm" --sdk_root="$sdk" --licenses < <(yes) > "$OPT_PREFIX/android-sdk-licences.txt" 2>&1 || true
  elif [ -t 0 ]; then
    say "sdkmanager shows each licence; answer y to accept it or n to stop."
    taen_env "$sm" --sdk_root="$sdk" --licenses || true
  else
    taen_env "$sm" --sdk_root="$sdk" --licenses < /dev/null > /dev/null 2>&1 || true
  fi
  [ -f "$sdk/licenses/android-sdk-license" ] || die "the Android SDK licences are not accepted. Run in a terminal and answer y, or add --accept-licenses."
  step "Android SDK packages"
  pkgs=()
  if [ "$OPT_NO_BUILD" = 1 ]; then pkgs=("$TAEN_SDK_BUILD_TOOLS"); else pkgs=("$TAEN_SDK_PLATFORM" "$TAEN_SDK_BUILD_TOOLS" "$TAEN_SDK_NDK"); fi
  [ "$OPT_WITH_ADB" = 0 ] || pkgs+=("$TAEN_SDK_PLATFORM_TOOLS")
  taen_env "$sm" --sdk_root="$sdk" "${pkgs[@]}"
  taen_assert_no_android_cli "$OPT_PREFIX/android-user"
}

taen_local_properties() {
  local lp="$TAEN_ROOT/local.properties" cur sdk="$OPT_PREFIX/android-sdk"
  if [ -f "$lp" ]; then
    cur="$(sed -n 's/^sdk\.dir=//p' "$lp" | head -n1)"
    [ "$cur" = "$sdk" ] && return 0
    die "local.properties already sets sdk.dir=${cur:-<empty>}, and Gradle prefers it over ANDROID_HOME. Rename it (mv local.properties local.properties.bak), run this script, then move it back. Or build from a fresh clone."
  fi
  printf 'sdk.dir=%s\n' "$sdk" > "$lp"
  CREATED_LOCAL_PROPERTIES=1
  say "wrote local.properties (git-ignored) with sdk.dir=$sdk"
}

taen_build() {
  local apk dist="$TAEN_ROOT/dist" out
  step "Building the unsigned release APK (about 10 to 20 minutes the first time)"
  taen_local_properties
  ( cd "$TAEN_ROOT" && taen_env ./gradlew --no-daemon --console=plain :app:assembleRelease )
  apk="$TAEN_ROOT/app/build/outputs/apk/release/Taen_$TAEN_VERSION-release.apk"
  [ -f "$apk" ] || die "build finished but $apk was not found"
  mkdir -p "$dist"
  out="$dist/taen-$TAEN_VERSION-unsigned.apk"
  cp "$apk" "$out"
  TAEN_APK="$out"
  TAEN_APK_SHA="$(taen_sha256_of "$out")"
  ( cd "$dist" && sha256sum "taen-$TAEN_VERSION-unsigned.apk" > SHA256SUMS )
  say "APK: $out"
  say "SHA-256: $TAEN_APK_SHA"
}

taen_keygen_sign() {
  local unsigned="$TAEN_ROOT/dist/taen-$TAEN_VERSION-unsigned.apk" signed="$TAEN_ROOT/dist/taen-$TAEN_VERSION-signed.apk"
  if [ "$OPT_KEYGEN" = 1 ]; then
    step "Creating your signing key (tools/keygen.sh)"
    taen_env "$TAEN_ROOT/tools/keygen.sh" || warn "key creation failed or the key already exists (it is never overwritten); see the message above"
  fi
  if [ "$OPT_SIGN" = 1 ]; then
    step "Signing (tools/sign.sh)"
    [ -f "$unsigned" ] || { warn "$unsigned not found: build first (run without --no-build)"; return 0; }
    if taen_env "$TAEN_ROOT/tools/sign.sh" "$unsigned" "$signed"; then
      TAEN_SIGNED_APK="$signed"
      ( cd "$TAEN_ROOT/dist" && sha256sum "taen-$TAEN_VERSION-signed.apk" >> SHA256SUMS )
    else
      warn "signing failed; the unsigned APK is untouched. Create a key with --keygen if you have none."
    fi
  fi
}

taen_stop_gradle() {
  [ -x "$OPT_PREFIX/jdk/bin/java" ] || return 0
  say "stopping Gradle"
  ( cd "$TAEN_ROOT" && taen_env ./gradlew --stop > /dev/null 2>&1 ) || true
}

main() {
  taen_parse_args "$@" || { taen_usage >&2; exit 2; }
  if [ "$OPT_HELP" = 1 ]; then taen_usage; exit 0; fi
  TAEN_VERSION="$(taen_version_from_gradle "$TAEN_ROOT/app/build.gradle.kts")"
  [ -n "$TAEN_VERSION" ] || die "cannot read versionName from app/build.gradle.kts"
  taen_preflight
  TAEN_MISSING="$(taen_missing_tools)"
  TAEN_PKGS="$(taen_pkg_names "$TAEN_FAMILY" $TAEN_MISSING)"
  TAEN_INSTALL_CMDS="$(taen_install_cmds "$TAEN_FAMILY" $TAEN_PKGS)"
  TAEN_MANUAL="packages: $(taen_pkg_names "$TAEN_FAMILY" $TAEN_MISSING)"
  TAEN_APK=""; TAEN_APK_SHA=""
  if [ "$OPT_DRY_RUN" = 1 ]; then
    taen_print_plan
    say ""
    say "DRY RUN: nothing was downloaded, installed or changed."
    exit 0
  fi
  taen_print_plan
  TRAP_ACTIVE=1
  trap taen_on_exit EXIT
  taen_system_tools
  step "Preparing $OPT_PREFIX"
  mkdir -p "$OPT_PREFIX/downloads" "$OPT_PREFIX/tmp" "$OPT_PREFIX/android-user" "$OPT_PREFIX/gradle-home"
  : > "$OPT_PREFIX/.taen-build-prefix"
  taen_install_jdk
  taen_install_cmdline
  taen_install_sdk
  if [ "$OPT_NO_BUILD" = 0 ]; then
    taen_build
  elif [ -f "$TAEN_ROOT/dist/taen-$TAEN_VERSION-unsigned.apk" ]; then
    TAEN_APK="$TAEN_ROOT/dist/taen-$TAEN_VERSION-unsigned.apk"; TAEN_APK_SHA="$(taen_sha256_of "$TAEN_APK")"
  fi
  taen_keygen_sign
  step "Cleaning up"
  taen_stop_gradle
  if [ "$OPT_REMOVE_TOOLCHAIN" = 1 ] && [ "$CREATED_LOCAL_PROPERTIES" = 1 ]; then rm -f "$TAEN_ROOT/local.properties"; fi
  TRAP_ACTIVE=0
  taen_cleanup "$OPT_PREFIX" "$OPT_REMOVE_TOOLCHAIN"
  if [ -n "$TAEN_APK" ]; then
    taen_print_summary "$TAEN_APK" "$TAEN_APK_SHA" "$TAEN_VERSION" "$OPT_PREFIX"
  else
    say "No APK was built (--no-build and no dist/taen-$TAEN_VERSION-unsigned.apk)."
  fi
  if [ "$CREATED_LOCAL_PROPERTIES" = 1 ] && [ "$OPT_REMOVE_TOOLCHAIN" = 0 ]; then
    say "Note: local.properties (git-ignored) now points Gradle at $OPT_PREFIX/android-sdk; delete it if you remove the folder yourself."
  fi
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
