# Taen

Taen (Bulgarian: "secret") is an offline Android keyboard that keeps what you type on your phone. It is a fork of
[HeliBoard](https://github.com/HeliBorg/HeliBoard) v4.2, which is based on OpenBoard and the Android Open Source Project (AOSP)
keyboard.

Version 2.0.0 (versionCode 9). Android 11 and newer, 64-bit ARM. Application ID `io.github.nazandrake.taen`.

## What Taen does

- Never uses the network: the merged manifest has no `INTERNET` permission, and the build fails if one appears.
- No contacts access, no installed-app list, no Android backup or device transfer, no closed-source or downloaded native code.
- Stores user data as ciphertext: preferences, learned words, personal dictionary, snippets, clipboard history, pictures and background
  images live in AES-GCM stores with Android Keystore keys, and are wiped from memory when the screen locks.
- Incognito in password fields, per app and on demand; a clipboard history that never keeps sensitive copies; an optional app lock;
  screenshots blocked while the keyboard is open; warnings about accessibility services and windows drawn over the keyboard.
- Self-destruct: a trigger word, a duress PIN, wrong-PIN and inactivity triggers and a panic shortcut delete the keys first, then the files.
- Glide typing, emoji search and smart quotes work offline (an open-source engine and CLDR data). English and Bulgarian (phonetic and BDS)
  are enabled on a new install; the other HeliBoard languages are available.
- Voice typing on the phone: ONNX Runtime and the NVIDIA Parakeet TDT 0.6B v3 model run locally. The model is not in the APK and the app
  never downloads it: Settings > Voice typing > Download the model opens a pinned link in your browser, and Install downloaded model checks
  the file before use (`BUILDING.md` section 8).
- Optional downloads by choice (Settings > Downloads, the same browser download and checked install): dictionaries and a next-word model
  per language (SmolLM2-135M for English, Qwen3-0.6B for Bulgarian), each used only on a phone that passes its own speed
  and memory test and only while the phone has the memory free. The model only reorders suggestions from your dictionaries and never runs in password, incognito or no-learning fields.
- Cursor navigation on the space bar: one swipe moves across characters and up and down lines; hold Shift with another finger,
  or switch on the selection-mode toolbar key, to select. Toolbar keys add the next occurrence of a word (like Ctrl+G in an IDE)
  or a cursor at the same column on the next line; typed text and backspace then edit every cursor at once.
- On Android 16 and later with gesture navigation, Taen asks the system to leave out its bar below the keyboard (the keyboard-switch
  and hide-keyboard buttons), but only when there is something to switch to: another Taen language or another keyboard. Taen's language
  key is then the switcher and is shown even when the setting hides it: a tap cycles Taen's languages, or goes to the next keyboard when
  Taen has only one language; a long press opens the system's keyboard picker. Without the bar there is no hide-keyboard button: the
  back gesture closes the keyboard.
- Offline GIFs and stickers from your own pictures (metadata removed), emoji on a held key, a quick settings panel, templates and saved
  profiles of the settings, and an encrypted export.

`PRIVACY.md` describes each guarantee, how it is checked and what a keyboard cannot protect against. `INVARIANTS.md` lists the rules
the code keeps, for anyone changing it.

## Build it yourself

One script downloads everything it needs into a single folder (pinned versions, every archive checked against a SHA-256), builds the
unsigned APK into `dist/` and prints its SHA-256. It does not touch your own JDK, Android SDK, shell profile or PATH. Use `--dry-run` /
`-DryRun` first to see the plan.

```
# Linux (Debian/Ubuntu/Mint, Fedora/RHEL, Arch/Manjaro, openSUSE; needs 10 GB free disk and about 6 GB free RAM)
tools/build-linux.sh --dry-run
tools/build-linux.sh
tools/build-linux.sh --keygen --sign      # also create your own signing key (outside the repository) and sign the APK

# Windows (PowerShell 5.1 or 7, no administrator rights; put the source in a short folder such as C:\taen)
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build-windows.ps1 -DryRun
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\build-windows.ps1
```

The Windows script has not been tested on a real Windows machine yet. Details and options: `BUILDING.md`.

## Build and check

```
make build              # unsigned release APK in dist/
make check              # privacyCheck (manifest, backup rules, sources, release APK scan) and the unit tests
make native-test        # host build of the native dictionary engine and its tests
make verify-repro       # two clean builds must give the same unsigned APK hash
make leak-audit         # static leak checks of the release APK with planted-failure proofs
make leak-audit-device  # scripted session on a phone over adb
make keygen sign        # your own signing key, kept outside the repository
```

Needs JDK 21 and the Android SDK (`sdk.dir` in `local.properties`). All checks run locally. Contributions: `CONTRIBUTING.md`.
Security reports: `SECURITY.md`.

## Known limitations

- Glide typing is on by default. With the bundled CC BY English dictionary 79.6 % of words are right on a synthetic sample
  (gate 79.5 %); improving this is planned. It can be switched off in Settings > Gesture typing.
- Only English, Bulgarian and Bengali dictionaries are bundled; other languages need a dictionary file (Settings > Languages).
- Voice typing and the next-word models are measured on one low-end reference phone and one desktop; other phones are not measured.
- Line moves and selection by arrow keys depend on the app: some web and custom editors ignore them. Several cursors need an app
  that shares its whole text (at most 20 000 characters) and are off in password and incognito fields (`PRIVACY.md`, "Cursor navigation").

## Upstream and credits

- HeliBoard (GPL-3.0-only), forked at tag `v4.2`. Copyright notices of HeliBoard, OpenBoard and AOSP contributors are kept in the
  source headers.
- AOSP keyboard (Apache-2.0), see `LICENSE-Apache-2.0`.
- Dictionaries: Leipzig Corpora Collection word lists (CC BY 4.0) through the HeliBoard
  [dictionaries repository](https://codeberg.org/Helium314/aosp-dictionaries); see `NOTICE`.
- The glide engine is a rewrite of FlorisBoard v0.4.0 code (Apache-2.0); voice typing uses ONNX Runtime (MIT) and NVIDIA Parakeet
  (CC BY 4.0, not shipped); the optional next-word models are SmolLM2 and Qwen3 (Apache-2.0, not shipped). See `NOTICE`.
- The HeliBoard name and icon are not used.

## License

GNU General Public License v3.0 only (`LICENSE`). Code derived from AOSP is also available under Apache-2.0 (`LICENSE-Apache-2.0`).
Third-party data and libraries are listed in `NOTICE`.
