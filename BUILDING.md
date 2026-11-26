# Building Taen

All building, checking and signing happens on your own machine. There is no cloud CI, and every dependency is open source
and pinned by checksum. Linux is the tested platform; macOS and Windows notes are at the end and are not tested.

## 1. What you get, and the trust model

You build the APK from source and sign it with a key only you hold. Nobody else's binary is needed. The unsigned APK is
reproducible: two clean builds of the same commit give the same SHA-256 (section 5), so you can check that a signed APK
somebody gives you matches the source.

## 2. Requirements (Linux)

| What | Version used | Notes |
|---|---|---|
| JDK | 21 (tested with OpenJDK 21.0.12) | Gradle runs on it; code compiles for Java 17. F-Droid builds HeliBoard with JDK 25; not tested here. |
| Android SDK | platform `android-37.0`, build-tools `36.0.0`, NDK `28.0.13004108` | `buildToolsVersion` and `ndkVersion` are pinned in `app/build.gradle.kts`. Android Gradle Plugin installs missing parts if the licences are accepted. |
| Android command-line tools | 19.0 (`commandlinetools-linux-13114758_latest.zip`) | Only to install the SDK. Not 23.0: its `sdkmanager` downloads the "Android CLI" with usage metrics and accepts licences silently. |
| Gradle | 9.7.1 | The wrapper downloads it and checks `distributionSha256Sum`. |
| git, make, bash | any recent | |
| podman or docker | optional | Only for the container build (section 3). |

Setup:

1. Install the SDK to a directory you own, for example `~/Android/Sdk`, and accept the licences:
   `sdkmanager --licenses` (accepting is your decision).
2. Write `sdk.dir=/path/to/Sdk` into `local.properties` (git-ignored), or set `ANDROID_HOME`.
3. Gradle caches live in `~/.gradle`. Keep them on disk, not in a RAM-backed `/tmp`.
4. The build needs about 6 GB of free RAM; stop the daemon afterwards with `./gradlew --stop`.

## 3. The one-command build

### 3.1 From scratch on a fresh machine (Linux and Windows)

`tools/build-linux.sh` and `tools/build-windows.ps1` install nothing system-wide, apart from base tools on Linux (below). They download a pinned
toolchain into one folder, build the unsigned APK, print the SHA-256 and clean up. Read the plan first: `tools/build-linux.sh --dry-run`
or `.\tools\build-windows.ps1 -DryRun` (checks the machine, prints every URL and checksum, downloads nothing).

```
tools/build-linux.sh [--prefix DIR] [--yes] [--no-system-packages] [--accept-licenses] [--with-adb]
                     [--keygen] [--sign] [--no-build] [--remove-toolchain] [--dry-run]
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build-windows.ps1 [-Prefix DIR] [-AcceptLicenses] [-WithAdb]
                     [-Keygen] [-Sign] [-NoBuild] [-RemoveToolchain] [-DryRun]
```

What it does, in order: (1) checks CPU (x86_64; aarch64 JDK pinned but the Android build-tools and NDK are x86_64-only, untested), glibc (Alpine and other
musl systems are refused), at least 10 GB free disk at the prefix, a RAM note (the build wants about 6 GB free), that the prefix is new, empty or made by this
script (`--remove-toolchain` deletes the prefix, so `/`, your home, the source folder and unknown non-empty folders are refused); (2) Linux only: detects the
distribution from `/etc/os-release` (`ID`, `ID_LIKE`: apt, dnf, pacman, zypper families; any other distribution gets the list of tools to install yourself)
and, if curl, unzip, tar, gzip, sha256sum or git are missing, shows the exact `sudo` command and asks (`--yes` answers for you, `--no-system-packages`
forbids it); (3) downloads the JDK and the Android command-line tools and compares each with its pinned SHA-256 (a mismatch deletes the file and stops);
(4) `sdkmanager` installs exactly platform 37.0, build-tools 36.0.0, NDK 28.0.13004108 (and platform-tools with `--with-adb`) after you read and accept the SDK
licences (`--accept-licenses` accepts them for you; the text is saved in the prefix); (5) `gradlew --no-daemon :app:assembleRelease` with `JAVA_HOME`,
`ANDROID_HOME`, `GRADLE_USER_HOME` and `ANDROID_USER_HOME` set for that process only (the build also downloads Gradle and the dependencies, which are pinned
by `gradlew` and `gradle/verification-metadata.xml`, from services.gradle.org, Maven Central, Google Maven and jitpack.io); (6) copies the APK to
`dist/taen-<version>-unsigned.apk`, writes `dist/SHA256SUMS`, prints the hash; (7) with `--keygen` / `--sign` runs `tools/keygen.sh` / `tools/sign.sh`
(Windows: keytool, apksigner), which ask for the password and keep the key outside the repository; (8) stops Gradle and deletes the downloaded archives
and temp files. The toolchain stays in the prefix for the next build unless `--remove-toolchain`. On a failure it prints the step and what to do; a re-run
continues from what is already installed and verified.

Prefix and size: default `~/.cache/taen-build` (Windows `C:\taen-build`), about 6 GB with the Gradle cache. The script writes `local.properties` (git-ignored)
with `sdk.dir` when there is none; if you already have one with another `sdk.dir` it stops, because Gradle prefers that file over `ANDROID_HOME`: rename it for the run.

| What | Pinned value | Official source of the checksum |
|---|---|---|
| Temurin JDK 21.0.13+9, Linux x64 | `d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c` | api.adoptium.net/v3/assets/latest/21/hotspot, same as the `.sha256.txt` of the GitHub release `jdk-21.0.13+9` |
| Temurin JDK 21.0.13+9, Linux aarch64 | `e3522e707fc7dbe2a72c30746b1e7068301a0213ecd213d4491f35a14eb55578` | same |
| Temurin JDK 21.0.13+9, Windows x64 zip | `a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9` | same |
| Command-line tools 19.0 (13114758), Linux zip | `7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee` | dl.google.com publishes only SHA-1 `5fdcc763...` (repository2-3.xml), matched; SHA-256 computed from that file |
| Command-line tools 19.0 (13114758), Windows zip | `98b565cb657b012dae6794cefc0f66ae1efb4690c699b78a614b4a6a3505b003` | SHA-1 `54a582f3...` (repository2-3.xml) matched; SHA-256 computed from that file |

To move a pin, take the new value from the same source, change it in both scripts (and the Containerfile for the Linux zip) and run `tools/test-build-scripts.sh`.

After the build:

- Compare: `sha256sum -c SHA256SUMS --ignore-missing` against a published `SHA256SUMS` (PowerShell: `Get-FileHash -Algorithm SHA256 dist\taen-<version>-unsigned.apk`).
  The unsigned APK is reproducible on Linux (same commit and a JDK 21 give the same hash; `make verify-repro` checks it with the installed toolchain). Windows
  builds are not claimed to match Linux bytes.
- Sign: Android refuses an unsigned APK. `tools/build-linux.sh --no-build --keygen --sign` (key `~/.config/taen/release.p12`, Windows `%USERPROFILE%\.config\taen\release.p12`;
  `TAEN_KEYSTORE`, `TAEN_KEY_ALIAS` apply). Keep two offline copies of the key and the certificate SHA-256 it prints (section 6).
- Install and enable: section 7 (`adb install -r dist/taen-<version>-signed.apk`, then `adb shell ime enable ...`), or copy the signed APK to the phone and open it.
  `--with-adb` puts adb into `<prefix>/android-sdk/platform-tools`.
- Voice typing: on the phone, Taen > Voice typing > Download the model (one file), then Install downloaded model (section 8).
- Remove everything again: `--remove-toolchain` (or delete the prefix folder; delete `local.properties` if the script wrote it).

Tests of the scripts: `tools/test-build-scripts.sh` (`make test-build-scripts`) covers distribution detection (Debian, Ubuntu, Mint, Fedora, RHEL-like, Arch, CachyOS,
Manjaro, openSUSE, Alpine, unknown), the option parser, prefix and checksum safety, the sudo flow with stubs, both dry runs and, when `pwsh` is installed
(or `TAEN_PWSH` points to one), the PowerShell script's parse and `-DryRun`. Status: the Linux script was run end to end (2026-11-14) from a clean clone with an empty prefix (Temurin 21.0.13+9, fresh SDK and Gradle cache, 5 min
build): every file of the APK equals the APK built with the developer's own JDK 21.0.12 and SDK, except `META-INF/version-control-info.textproto`, which
AGP fills with the git HEAD revision (so a build from another commit differs by design); with the same HEAD the SHA-256 was identical. The Windows script has
been parsed, dry-run and tested only (PowerShell 7 on Linux; its sdkmanager step with a stub, which must fail on the Android CLI traces analytics.settings or cli, like the Linux script) and has not been run on a real Windows machine.

### 3.2 With your own toolchain

```
make build            # runs privacyCheck, then writes dist/taen-<version>-unsigned.apk and SHA256SUMS
```

Without make: `./gradlew :app:assembleRelease` (the APK is `app/build/outputs/apk/release/Taen_<version>-release.apk`,
unsigned on purpose). `./gradlew :app:assembleDebug` builds a debug APK (application ID with the suffix `.debug`).
The release build refuses to run if `privacyCheck` fails.

Container (the reference environment): `tools/container/Containerfile` pins the base image by digest, JDK 21, the
command-line tools zip by SHA-256 and the SDK package versions.

```
make container            # builds the image locally, no registry push
make container-build      # builds the committed HEAD inside it, writes dist/*.apk
make container-verify-repro
```

Status: the Containerfile was written on a machine without podman or docker and has not been built yet. Treat it as
unverified until you have run `make container` once.

## 4. Offline builds

`gradle/verification-metadata.xml` holds a SHA-256 for every dependency, plugin and build-logic artifact. Gradle refuses an
artifact that is unknown or has changed. After one online build has filled `~/.gradle`:

```
make offline-build        # ./gradlew --offline :app:assembleRelease
```

To add or update a dependency, change the build file, run
`./gradlew --write-verification-metadata sha256 :app:assembleDebug :app:assembleRelease :app:assembleDebugNoMinify :app:assembleRunTests :app:testRunTestsUnitTest privacyCheck`
(without `--offline` and `--configuration-cache`) and review the diff of the metadata file like source code.
Every new artifact needs a reason; trackers and Google Play services are denied by `privacy/dex-rules.txt` anyway.

## 5. Checking reproducibility

```
make verify-repro         # tools/verify-repro.sh [commit]
```

The script clones the commit twice into different directories under `~/.cache/taen-repro`, builds the release APK in each
with `--offline --no-daemon`, and compares the SHA-256 of the unsigned APKs. Only committed files count. It prints
`REPRODUCIBLE <hash>` or exits with 1. To find a difference, unzip both APKs and compare, or use `diffoscope`.

The APK embeds the git commit it was built from (`META-INF/version-control-info.textproto`, filled in by the Android Gradle plugin). A hash
comparison therefore needs the same commit: a build of another commit differs by design, and so does a build made before a later commit even when the
sources are identical. Compare the hash of `make build` (or of a signed APK's unsigned twin) with `verify-repro` of the same commit, never across commits.

To check a signed APK against your own build, copy its signature onto your unsigned build with
[apksigcopier](https://github.com/obfusk/apksigcopier) (`apksigcopier copy signed.apk unsigned.apk out.apk`, then compare
`out.apk` with `signed.apk`). Not run yet in this project.

## 6. Your own key and signing

The key is generated outside the repository and never committed (`.gitignore` blocks `*.p12 *.jks *.keystore`, `tools/sign.sh`
refuses a keystore inside the repo, the pre-push hook refuses key files). Only you hold it: nobody else generates, stores or
sees your release key or its passphrase.

Release steps, run by you on your machine (the version is `versionName` in `app/build.gradle.kts`: 2.0.5 now):

```
make keygen               # once: ~/.config/taen/release.p12 (PKCS12, RSA 4096, mode 600); keytool asks for the password and a name
make keystore-pass        # once: asks for the keystore password one time and keeps it in your desktop keyring (or a mode 600 file)
make verify-repro         # two clean builds of HEAD must print REPRODUCIBLE <hash>
make build                # runs privacyCheck, writes dist/taen-2.0.5-unsigned.apk (same hash as verify-repro) and SHA256SUMS
make leak-audit           # static leak checks and the planted-failure proofs (section 9)
make sign                 # dist/taen-2.0.5-signed.apk; no prompt: the password comes from the keyring or the file of make keystore-pass
```

Before a release, `CHANGELOG.md` needs an entry `## <versionName>` whose first lines (up to the first blank line, 3 to 6 lines) say what is new in plain
words: the app shows exactly those lines once in a "What's new" box (the build writes them into an asset, `taen.WhatsNew`; Markdown markers are removed).
The release build fails without such an entry; put the details after a blank line.

`make sign` reads the keystore password from the first of: the file named by `TAEN_KS_PASS_FILE`, `~/.config/taen/release.pass`, the desktop keyring
(`secret-tool`, entry `service taen-release-keystore`). A password file must be a regular file of yours with mode 600, outside the repository, and
is refused otherwise. The password is passed to `apksigner` as a file, never on a command line, and is never printed; a keyring answer is written to a
mode 600 file in `$XDG_RUNTIME_DIR` for the one call and removed. With none of the three the script stops and names `make keystore-pass`; it never asks
on the terminal. `make keystore-pass` checks the password against the keystore before it stores it (`tools/keystore-pass.sh keyring|file`); a
file is written with mode 600 as a new file and moved into place, and is refused inside the repository (also in a directory that does not exist yet)
and when the target is a directory.
`make test-sign` tests all of this with a throw-away key and stubs of `secret-tool`. It runs every script in an empty environment (own home
directory, own `PATH` entry for the stub, only the `TAEN_*` values of the case), so your keyring, your `TAEN_*` variables and your password files are
never reached; `tools/test-sign-isolation.sh` proves it by exporting decoys for every `TAEN_*` variable the scripts read and checking that none was
read or changed.

Write down the certificate SHA-256 that `make sign` prints and keep two offline copies of the keystore: an update must be signed with the
same key, and losing it means uninstalling every installed copy (use the encrypted export first).

Environment: `TAEN_KEYSTORE` (path), `TAEN_KEY_ALIAS` (default `taen`), `TAEN_KS_PASS_FILE` (a mode 600 file with the password). The v1 scheme is off; apksigner requests v2 and v3 and produced a v3
signature (apkSigningVersion=3 on Android 12).

The device leak audit runs on an APK signed with a throw-away test key made for that purpose (the audit installs and removes it on the
phone); the APK it checked is the unsigned build, which your key signs without changing it (`apksigner` adds only the signature block).

## 7. Installing

```
adb install -r dist/taen-<version>-signed.apk
adb shell ime enable io.github.nazandrake.taen/helium314.keyboard.latin.LatinIME
adb shell ime set io.github.nazandrake.taen/helium314.keyboard.latin.LatinIME
```

Write down the current default keyboard first (`adb shell settings get secure default_input_method`) so you can switch back.

## 8. Voice typing and the voice model

Voice typing recognises speech on the phone: ONNX Runtime 1.27.0 (MIT) runs the int8 export of NVIDIA Parakeet TDT 0.6B v3 (CC BY 4.0; English,
Bulgarian and 23 other European languages, the language is detected from the speech). The APK has no network permission and does not contain the
model; the keyboard never downloads anything (the browser does). On the phone, with Wi-Fi:

```
Taen > Voice typing > Download the model       # the browser downloads ONE file (487 MB): the official sherpa-onnx archive of the model
Taen > Voice typing > Install downloaded model # in the picker select that file in the Download folder; Taen checks it, unpacks the four model files and installs them
```

Install checks the archive's size and SHA-256 (pinned in `VoiceModelSpec.archive`, equal to `tools/voice-model-archive.lock`), reads the four model files out of
it (only those; a link, a path outside the folder, an unexpected entry or a wrong size ends in an error and nothing is installed), checks each file's SHA-256
against `tools/voice-model.lock`, installs them and then deletes the downloaded archive when "Delete the downloaded copies after installing" is on (default).
It shows progress and has a Cancel button; a document provider that sends no data for 60 seconds is given up on. About 1.2 GB of free space is needed while
the archive and the unpacked model exist together (the archive is deleted afterwards). Decoding bzip2 is done by `Bzip2InputStream` (no library, no new dependency;
tested against libbz2 output and the real archive).

Fallback, the four single files (the model page lists each with its state and a "Download this file" button for every missing one; any subset can be installed
per round, in any number of rounds, and the model is complete and usable as soon as all four are installed):

- The links carry `?download=true`, so Hugging Face sends the three `.onnx` files as attachments. **`tokens.txt` is the exception**: Hugging Face answers it as inline
  `text/plain` even with that parameter (measured 2026-11-14 with `curl -sIL`: `content-disposition: inline`), so a phone browser shows the text instead of saving it; this is why the
  four-file path failed on the Pixel in 1.0.1 (three files downloaded, the fourth never arrived). Use the archive, or save `tokens.txt` by hand (the page text, any file name; the content decides).
- Install accepts files under any name ("tokens (1).txt" is fine): the content (size, then SHA-256) decides, a wrong file is reported and does not undo the good ones.
- The picker is a one-time grant for that selection; no folder permission is kept. (A folder picker is not used: Android 11 and newer refuse the Download folder itself.)
- The voice key without a model opens this settings page directly.

Without a browser download, from the build machine instead:

```
tools/fetch-voice-model.sh --archive dist/voice-model   # 487 MB, resumable; checked against tools/voice-model-archive.lock (or make fetch-voice-model: the four files, 670 MB)
adb push dist/voice-model /sdcard/Download/taen-voice
# on the phone: Taen > Voice typing > Install downloaded model > select the archive (or the files) in the picker
python3 -I tools/voice/check_archive.py <archive>     # independent check (Python tarfile) that the archive and each file in it match the locks
```

- The app hashes each picked file against the SHA-256 values pinned in `VoiceModelSpec.kt` (equal to the lock files, `VoicePolicyTest`) before it writes
  anything; a wrong file (for example a photo) is rejected without being copied. Before each load the four files are hashed again.
- The model is stored in `no_backup/taen-voice` of the app's device-protected storage, **not** encrypted: it is public data, and ONNX Runtime maps
  the 650 MB encoder file itself (decrypting it into the Java heap is not possible). A partial self-destruct keeps it, a shred removes it. Delete it
  in the same settings screen (frees 670 MB). Install deletes the downloaded copies by default; otherwise remove them from `/sdcard/Download` yourself.
- The voice key is in the suggestion strip (pinned by default; also in the toolbar). Tap: start, tap again: stop. Hold it: record while held. It is
  red and pulses while the microphone is open, amber while the text is worked out. The microphone permission is asked for at the first tap that can
  record (after the model is imported), never at install or start. Password fields hide the key; the text is typed like a paste and never learned.
- Pixel 8 Pro: not tested (no such phone was available). Speed was measured on a reference phone (PRIVACY.md, "Voice typing").
- Host tests: `./gradlew :voice-engine:test` (part of `make check`) runs the engine pieces against hand-calculated and numpy-oracle values. The smoke test
  with the real model needs the FLEURS samples (public, CC BY 4.0, never committed): download `data/bg_bg/audio/test.tar.gz`, `data/bg_bg/test.tsv` and the
  `en_us` pair from https://huggingface.co/datasets/google/fleurs, run `tools/voice/prepare_samples.py <tar> <tsv> ~/.cache/taen-voice-samples/bg` (and `/en`),
  then `make voice-smoke` (it fails above EN 10 % or BG 20 % WER and prints the transcripts). `tools/voice/parakeet_ref.py` is the independent numpy oracle.

## 9. Privacy checks

```
./gradlew privacyCheck        # per variant: merged manifest permissions, no <queries>, backup attributes and rules, source scan
                              # then apkScanRelease: classes in the release APK, native library names
./gradlew :buildSrc:test      # unit tests of the APK scanner
```

Rules live in `privacy/dex-rules.txt` (denied class prefixes with allow exceptions, including `android/util/Log`: the release build
removes every platform log call through `app/proguard-release.pro`) and `privacy/native-libs.allow`.
`INVARIANTS.md` lists each invariant; `CONTRIBUTING.md` says how a rule is changed (plant the violation, see the check fail, then fix).

`make native-test` compiles the native dictionary core with g++ on the host (no Android needed) and runs the in-memory
serialization and word-walk tests (`app/src/main/jni/host_test`); `make native-test-tsan` and `make native-test-asan` build it with ThreadSanitizer or AddressSanitizer. `tools/device-scan.py` scans the data directories of a debuggable build
on a phone for plaintext canaries (`--selftest` checks the scanner without a phone).

### Leak audit

```
make leak-audit           # static checks of the release APK, then the planted-failure proofs; needs the release APK (make build)
make leak-audit-device    # builds the audit APK, then the scripted session on the phone; needs adb and your signed release APK dist/taen-<version>-signed.apk from make sign (the audit never signs; a missing or unsigned file is refused before the phone is touched; --release-apk names another file); the screen is kept on during the session and the previous setting restored
```

Over adb on Wi-Fi (the serial is host:port) the audit runs every check except the traffic counters: D1, T-D1 and D1r switch the phone's Wi-Fi off and on, which would end the session, so they are GAP with the reason "needs USB: switches Wi-Fi off". Their process half switches nothing off and runs over Wi-Fi as D1s (audit build) and D1sr (release): no Taen process holds the inet group and no row of `/proc/net/{tcp,tcp6,udp,udp6}` belongs to the Taen uid (a package without a running process or a readable uid fails); its control T-D1s shows that the same readers flag the shell's inet group and find the adb session's own socket row (uid 2000). Over USB nothing is skipped (D1 holds the same two checks). A release may pass with those GAPs only when static S1 (no INTERNET permission), S3 and S8 (no network code) PASS on the same APK (run `python3 tools/leak_audit static --apk dist/taen-<version>-signed.apk` into the same `--out` directory); the report header says so in one line. The report and `results.json` name only the kind of the adb transport (USB cable or Wi-Fi), never the serial or the address of the phone. The audit switches the Wi-Fi on again however it ends. `make leak-audit-test` runs the tool's own tests.

One report: `build/leak-audit/leak-audit-report.md` (raw results in `results.json` next to it). The exit status is 1 when any check FAILs, and
when the network checks are GAPs over Wi-Fi and the "may pass" rule above is not met (a static run into the same directory afterwards is judged
the same way); any other GAP is a named check that could not be run. What is checked:

- Static (`tools/leak_audit/static_checks.py`, on the release APK): S1 permissions equal the allowlist, no INTERNET; S2 merged manifest (backup off
  and every domain excluded, not debuggable, no `<queries>`, exported components and providers against an allowlist with reasons); S3 dex has no
  network, tracker, class-loader or `android.util.Log` class (the rules of `privacy/dex-rules.txt`, scanned with a second, independent dex parser);
  S4 file-writer APIs against an allowlist; S5 no log call (Log, java.util.logging, PrintStream, printStackTrace); S6 no radio, location, camera,
  recorder or speech-service, contacts or account API (the microphone only through `AudioRecord`); S7 URL strings; S8 native libraries (no socket, DNS, log or console import; one named per-library allowance for ONNX Runtime, never for a network import, file writers unreachable from
  Java); S9 dependency list with licences and a tracker denylist (from the Gradle runtime classpath and the cached POMs); S10 dependency
  verification and wrapper checksum.
- Device (`tools/leak_audit/device_*.py`, reference phone, no root): D1 per-UID traffic zero over the whole session (`dumpsys netstats`, a Wi-Fi toggle forces the
  platform to persist the counters), the process holds no inet group, no socket rows; D2 logcat, streamed through the session, has no canary;
  D3 no canary in any encoding in both data directories and the external directories (`run-as` on the audit build, which is the release code with
  `debuggable=true`; on the release build itself it is the GAP D3r, because without root nothing can read the data of a non-debuggable app); D4 `bmgr backupnow` refuses and `adb backup` yields no user data (on the release build); D5 screenshot and screen recording are
  refused or black with the keyboard and the settings screen on screen; D6 clipboard (normal copy kept, sensitive and password-field copies not, paste
  leaves the system clip, other UIDs cannot read the stores; D6p sets the retention to Forever and pins a copied entry with its pin button); D6c turns "Clipboard keys in password fields" on: a number password field gets its paste key (not without the setting), and a copy made in a password field with the keyboard's copy key reaches the system clipboard flagged sensitive and is not in the history; D7 accessibility and overlay warnings fire (helper app); D8 granted permissions; D9
  `dumpsys` holds no canary; D10 the encrypted export; D11 typing session with incognito; D12 screen lock; D13 self-destruct test mode and real wipe (the pinned Forever entry of D6p must be in the history before and gone after).
- Voice (`tools/leak_audit/device_voice.py`, audit build, needs the model files and the FLEURS samples of section 8; the model is copied into the audit build
  with `adb push` and `run-as`): V1 the microphone permission is requested but not granted at install, the microphone is idle, and the first tap on the voice key
  shows the system dialog; V2 a recording ends in text in the field (the PC plays the sample through its speaker: the output volume is raised for the playback and
  restored; when the phone does not hear it the row is INFO or GAP, the accuracy being the bench's); V3 `dumpsys audio` shows an active input only while
  recording (control: it does) and a screen-off ends it; V4 no voice key in a password field; V5 no audio file by name or header in the data directories and none
  new in `/sdcard` or `/data/local/tmp` (control: a planted `.wav`); V6 the model directory holds exactly the four pinned files; V7 logcat holds none of the
  spoken or recognised words and no ONNX Runtime line (control: a planted line). Network (D1), logcat (D2) and the data scan (D3, which leaves the model directory
  out and lists it in V6) cover the voice session too, because it runs before them. `tools/leak_audit/lang_switch_timing.py` measures D15 (first language switch).
- Planted failures (`T-*`): every check category is run on a deliberately broken input and must FAIL, and on a clean control and must PASS. Static
  ones build a planted APK (`tools/leak_audit/planted/`: INTERNET, a tracker class, `android.util.Log`, a socket, writer APIs, a native socket call);
  device ones use the phone itself (the shell UID's own download is seen by the same counters, a planted canary line, file and archive are found).

The pre-push hook runs the buildSrc tests, `privacyCheck`, the unit tests and `verify-repro` (about 10 minutes):

```
make hooks                # git config core.hooksPath githooks
```

## 10. Next-word models

The two models are optional downloads of Settings > Downloads (`tools/downloads.lock`); the app never fetches them. Their tokenizer files are in the APK
(`assets/nextword`, pinned in `tools/nextword/tokenizers.lock`; `tools/nextword/make_token_fixtures.py` checks that they equal the models' `tokenizer.json`
and writes the reference token ids of `BpeTokenizerTest`).

```
./gradlew :nextword-engine:test   # host tests of the tokenizer, the window of the attention cache and the ranking (part of make check)
make nextword-eval                # fetches the pinned models (about 750 MB), the Leipzig text and the Bulgarian dictionary to ~/.cache/taen-nextword,
                                  # writes the 1000 contexts per language (tools/nextword/contexts.py) and prints one EVAL line per model
```

On the phone (debugNoMinify build installed; push a model, its tokenizer folder and the eval folder of `make nextword-eval`):

```
adb shell 'CLASSPATH=<base.apk> LD_LIBRARY_PATH=<nativeLibraryDir> app_process / helium314.keyboard.bench.NextWordBench \
  /data/local/tmp/nw/model-en.onnx /data/local/tmp/nw/smollm2-135m SMOLLM2 /data/local/tmp/nw/en 4 [contexts]'
```

It prints the top-3 hits, the step times (p50, p95) and the resident memory before the model and at its peak. The gate of a model is: at least 5 points
more top-3 hits than the dictionary alone, p95 at most 150 ms and at most 300 MB added on the reference phone; a model checked on each phone (Bulgarian) needs the
accuracy part here and passes the same speed limits in the app's own speed test on the user's phone (Settings > Downloads > Run speed test).

## 11. Troubleshooting

- `SDK location not found`: set `sdk.dir` in `local.properties` or `ANDROID_HOME`.
- `Failed to install the following Android SDK packages as some licences have not been accepted`: run `sdkmanager --licenses`.
- `NDK ... did not have a source.properties` or wrong NDK: install `ndk;28.0.13004108`.
- `Dependency verification failed`: the artifact differs from `gradle/verification-metadata.xml`. Do not update the file to make
  it pass until you know why (changed upstream file, corrupted cache, or an attack). Delete the cache entry and retry first.
- Out of memory during the build: close other programs; the build wants about 6 GB free.
- Different APK hashes in `verify-repro`: both build logs are in `~/.cache/taen-repro/`. Compare the APKs with `diffoscope`.

## macOS and Windows (not tested)

- macOS: the same steps with a JDK 21 and the SDK from Android command-line tools for macOS; `make` and `bash` exist. The
  container route works with Docker Desktop or podman machine. Reproducibility between macOS and Linux is not claimed:
  compare hashes built on the same OS or in the container.
- Windows: `tools/build-windows.ps1` (section 3.1, not yet run on real Windows), or WSL2 with Ubuntu and `tools/build-linux.sh` (reproducible with Linux builds),
  or call `gradlew.bat` directly instead of `make` (`gradlew.bat :app:assembleRelease`, `gradlew.bat privacyCheck`). The other shell scripts in `tools/` and the hook need bash.
