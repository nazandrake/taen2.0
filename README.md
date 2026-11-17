# Taen

Taen is an Android keyboard that works without any network access. It is a fork of
[HeliBoard](https://github.com/HeliBorg/HeliBoard) 4.2, which comes from OpenBoard and the AOSP keyboard. The name is
Bulgarian for "secret".

The current version is 2.0.0. It needs Android 11 or newer on a 64-bit ARM phone.

## Changes from HeliBoard

- The app has no `INTERNET` permission. The build fails if a library merges one in, and the release APK is scanned for
  network classes.
- Settings, learned words, the personal dictionary, snippets, clipboard history and pictures are stored in AES-GCM files whose
  keys live in the Android Keystore. When the screen locks, this data is dropped from memory until you unlock again.
- Nothing is learned in password fields, in fields that ask for no personalized learning, in apps on your incognito list, or
  while incognito is switched on.
- Clipboard history keeps text only, for one hour by default. Copies marked sensitive or made in a password field are not kept.
- An optional PIN locks the settings and the clipboard history.
- Self-destruct can delete the keys and the data on a trigger word, a duress PIN, too many wrong PINs, a long period without
  use, or a panic shortcut. Each trigger is off until you switch it on, and there is a test mode.
- The keyboard window is hidden from screenshots by default. Taen warns when an accessibility service can read what you type or
  when another app draws over the keyboard.
- Glide typing uses an open-source engine instead of Google's closed library.
- No contacts access, no list of installed apps, no Android backup. Backups are an encrypted export protected by a passphrase.

English and Bulgarian (phonetic and BDS) are enabled on a new install. Dictionaries for English, Bulgarian and Bengali are
included; the other HeliBoard layouts work once you add a dictionary file.

## Optional downloads

Some files are too large for the APK or not useful to everyone: extra dictionaries, the voice typing model (NVIDIA Parakeet TDT
0.6B v3, run on the phone with ONNX Runtime) and the next-word models (SmolLM2-135M for English, Qwen3-0.6B for Bulgarian).

Taen does not download them. Settings > Downloads and Settings > Voice typing open a commit-pinned link in your browser. You
then pick the downloaded file, and Taen installs it only if its size and SHA-256 match the pinned values. A next-word model is
only used on a phone that passes its speed and memory test.

## Install

Download the APK from the Releases page and check its SHA-256 against the value published with the release. Install it, then
enable Taen in Android's keyboard settings.

## Build

`tools/build-linux.sh` downloads a pinned JDK and Android SDK into its own folder and builds the unsigned APK into `dist/`. It
does not touch your existing JDK, SDK or PATH.

```
tools/build-linux.sh --dry-run   # show what it would download and run
tools/build-linux.sh
```

With JDK 21 and the Android SDK already installed:

```
make build          # unsigned release APK in dist/
make check          # privacy checks and unit tests
make verify-repro   # two clean builds must give the same APK hash
```

The unsigned APK is reproducible, so a release can be checked against the source. A Windows script exists but has not been
run on a real Windows machine yet. Signing, the leak audit and everything else are in `BUILDING.md`.

## Privacy and security

`PRIVACY.md` describes each guarantee, how it is tested, and what a keyboard cannot protect against (the app you type into,
accessibility services, root). `INVARIANTS.md` lists the rules the code has to keep. Security problems should be reported as
described in `SECURITY.md`; other contributions are covered in `CONTRIBUTING.md`.

## Known limitations

- English glide typing gets about 80 % of words right on a synthetic test set. It can be turned off in Settings > Gesture
  typing.
- Voice typing and the next-word models have been measured on one low-end phone and one desktop only.
- Moving between lines and using several cursors depend on the app; some web and custom editors ignore them.

## Credits

- HeliBoard (GPL-3.0-only), forked at tag `v4.2`. Copyright notices of HeliBoard, OpenBoard and AOSP contributors are kept in the
  source headers. The HeliBoard name and icon are not used.
- AOSP keyboard (Apache-2.0), see `LICENSE-Apache-2.0`.
- The glide engine is a rewrite of FlorisBoard v0.4.0 code (Apache-2.0).
- The bundled dictionaries are made from Leipzig Corpora Collection word lists (CC BY 4.0) through HeliBoard's
  [dictionaries repository](https://codeberg.org/Helium314/aosp-dictionaries).
- Voice typing uses ONNX Runtime (MIT) and the NVIDIA Parakeet model (CC BY 4.0). The next-word models are SmolLM2 and Qwen3
  (Apache-2.0). None of the models are in the APK.
- The starter stickers are Microsoft Fluent Emoji (MIT).

`NOTICE` has the full list with versions and sources.

## License

GNU General Public License v3.0 only (`LICENSE`). Code derived from AOSP is also available under Apache-2.0 (`LICENSE-Apache-2.0`).
