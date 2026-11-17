# Invariants

The rules below hold for every release. Code comments refer to them by number (P1 to P24). Each rule names the place in the code that
owns it and the check that fails when it is broken. `PRIVACY.md` explains the same rules for users.

A change to a rule, or to the check that guards it, follows `CONTRIBUTING.md`: plant the violation, see the check fail, then fix.

## Platform and build

- **P1 No network.** The merged manifest has none of INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE; the
  `tools:node="remove"` lines in the manifest drop the ones libraries merge in. The release dex has no network-capable class.
  Guards: `privacyCheck` (manifest and sources), `apkScanRelease` with `privacy/dex-rules.txt` (`buildSrc/ApkRules`).
- **P2 Exact permissions.** No READ_CONTACTS, no contacts code, no READ/WRITE_USER_DICTIONARY and no `android.provider.UserDictionary`
  (the personal dictionary is the encrypted `snippets.enc`). RECORD_AUDIO is the one runtime permission (P20).
  **P2b No installed-app list:** no `<queries>`, no QUERY_ALL_PACKAGES, no `queryIntentActivities`, `getInstalledApplications` or
  `getInstalledPackages`. Guard: `privacyCheck` (manifest list and `buildSrc` SourceRules).
- **P3 Exported components and providers.** One content provider of our own, `MediaShareProvider` (P24), and AndroidX's
  `InitializationProvider`; neither is exported. Guard: `privacyCheck` provider allowlist.
- **P4 No backup or transfer.** `allowBackup=false`; `res/xml/backup_rules.xml` and `data_extraction_rules.xml` exclude every domain and
  stay in sync with the manifest attributes. Guard: `privacyCheck`.
- **P5 No trackers.** No analytics, crash reporting, Google Play services, Firebase or ML Kit classes. Guard: `apkScanRelease`.
- **P6 No downloaded or closed native code.** Only the libraries in `privacy/native-libs.allow`: `libjni_latinime.so` built from
  `app/src/main/jni`, AndroidX's `libandroidx.graphics.path.so` and the pinned ONNX Runtime libraries. `JniUtils.java` is the only
  loader; no `System.load`, `DexClassLoader`, `PathClassLoader`, `register*Library` or execution-provider call. Guards: SourceRules,
  `apkScanRelease`, leak audit S8.
- **P17 Reproducible.** Two clean builds of one commit give the same unsigned APK. Check: `make verify-repro`.
- **P18 Supply chain.** `gradle/verification-metadata.xml` (SHA-256) is strict and the wrapper has `distributionSha256Sum`. A new
  dependency needs `--write-verification-metadata` and a reviewed diff.
- **Package names.** The Kotlin and Java packages stay `helium314.keyboard.*` (namespace `helium314.keyboard.latin`); only the
  application ID, strings, icon, manifest and docs are Taen's. The HeliBoard name and icon are not shipped.

## Stored data

- **P7 Ciphertext at rest.** Preferences, clipboard history, personal dictionary and snippets, learned words, background images and
  pictures exist only in AES-GCM stores of `helium314.keyboard.latin.privacy` (Keystore-wrapped data keys, files in `no_backup/taen`).
  Only a failed authentication tag, a broken format or a permanently lost key (`VaultKeyException`) deletes a store; a transient
  Keystore or I/O error (`VaultUnavailableException`) never does: the store is unavailable until it works. The credential-encrypted
  (CE) tier is empty and read-only while the user is locked. New user-data storage goes through `Privacy.kt`, never a platform file,
  preferences file or database. Guards: SourceRules (no `getSharedPreferences`, `PreferenceManager`, `SQLiteOpenHelper` or
  `FileProvider` outside `Privacy.kt`, no plain file writers or dictionary flush outside their owners); `CanaryAtRestTest`,
  `PrivacyWiringTest`, `DataKeysTest`, `VaultErrorsTest`.
- **P7 Learned words.** The native user-history dictionary lives in memory only and is saved as one AES-GCM store per language
  (`learned_<tag>.enc`, CE tier). `LearnedStorage` owns the rules: nothing is loaded, saved or learned while locked; a damaged store
  means the words are lost. The native flush, open and migrate calls are allowed in `BinaryDictionary.java` only.
  Tests: `LearnedStorageTest`, `make native-test`.
- **P7 Screen lock.** `Privacy.lock` (screen off, from the receiver in `LatinIME` through `ScreenLockPolicy`) stops opening the tier,
  writes a queued preference edit, wipes the CE preferences, personal dictionary and registered copies (clipboard history) from memory,
  then forgets the data key; learned words are saved first. A later edit is refused, never kept only in memory. `Privacy.unlock`
  reopens. Tests: `ScreenLockTest`, `EncryptedPrefsTest`, `PersonalDictionaryTest`, `ClipboardHistoryTest`.
- **P8 Keys.** Data keys are wrapped by non-exportable Android Keystore keys; the CE key needs an unlocked device; deleting the keys
  makes every store unreadable.
- **P16 Encrypted export only.** `ExportCodec`: magic `TAENBK1`, Argon2id (64 MiB, 3 passes, 1 lane, Bouncy Castle) and AES-256-GCM
  with the header as associated data; the passphrase (at least 12 characters) is asked for and never stored. The payload zip is built
  only in `ExportBundle`; `ExportService` applies an import after full verification, with rollback. Tests: `ExportCodecTest`
  (independent known-answer vectors), `ExportBundleTest`, `ExportServiceTest`.
- **P21 Profiles and templates.** `ProfileKeys` is the only classification of every settings key (INCLUDED, PRIVACY, EXCLUDED; unknown
  means EXCLUDED). `ProfileStore` owns the saved profiles (CE preferences: exported encrypted, wiped with the settings, read-only while
  locked). `TemplateCatalog` owns the read-only templates. `SettingsApply` only makes PRIVACY keys stricter. Tests: `ProfileKeysTest`,
  `ProfileStoreTest`, `TemplateCatalogTest`, `QuickPanelTest`.

## Typing and fields

- **P9 Incognito.** `PrivacyPolicy.decide` is the only place that decides what may be remembered in a field. Password fields (four
  variations), `IME_FLAG_NO_PERSONALIZED_LEARNING` in any input class, force-incognito, apps on the per-app list and a locked device
  give incognito: no learning, no clipboard capture, no recents, no learned or personal word as a suggestion or glide source. Password
  fields also get no suggestion strip, key previews or clipboard suggestion. The flag, the password tests and the list are read through
  the policy only (SourceRules). Tests: `PrivacyPolicyTest`, `AntiSnoopingWiringTest`, `PersonalSourcesPrivacyTest`.
- **P10 Clipboard.** Text only. A copy flagged sensitive, made in a password or no-learning field, or made while the store is locked is
  never stored (`ClipboardCapture`). Entries expire at the retention time (default 1 hour, 5 minutes to 7 days), at most 50 unpinned.
  Pasting from the history uses `commitText`; `setPrimaryClip` is allowed only for copy, clear and theme export.
- **P11 No diagnostics retention.** No crash report, log buffer, stack trace or diagnostic file. `utils/Log.kt` forwards one tag and one
  fixed string literal (debug builds); the release build strips `android.util.Log` (R8, `app/proguard-release.pro`) and
  `apkScanRelease` denies it; native code logs nothing (one named exception: `libonnxruntime.so` links liblog). Guards: SourceRules,
  `SourceRulesTest`, `tools/device-scan.py` (external directories must be empty).
- **P12 to P14 Anti-snooping.** `FLAG_SECURE` is set only in `SecureWindow` (every activity, and the keyboard window by the default-on
  setting). No accessibility service, no overlay window of our own. `A11yWatch` warns about services that read the screen and are not
  tools. `OverlayGuard.dispatch` is the only touch entry: covered touches are warned about or dropped per gesture. Tests:
  `A11yWatchTest`, `OverlayGuardTest`, `OverlayWiringTest`, `AntiSnoopingWiringTest`.
- **P19 Lock state.** Before the first unlock and while the screen is locked there is no learning, no clipboard history, no recents and
  no personal suggestion (`PrivacyPolicy`).
- **Glide typing.** Nothing records swipe or typing samples. The engine is open-source Kotlin in `helium314.keyboard.latin.gesture`
  and may not use files, network, logger or preferences (SourceRules). User words join only through
  `DictionaryFacilitatorImpl.userWordsAllowed`. `Defaults.PREF_GESTURE_INPUT` is true exactly when `GlideQualityGateTest` passes.
  The English gate is 79.5 % (Bulgarian 70 %): with the licensed dictionaries the English sample measures 79.6 %, which is within the
  noise of a 500-word sample, and switching off a feature people use costs more than that gap. Improving the English figure is planned.
- **Emoji search and smart quotes.** CLDR names and keywords from `assets/emoji_search`; `EmojiSearchIndex`, `EmojiKeywordDictionary`
  and `SmartQuotes` have no file, network, log, preference or storage class (SourceRules). An emoji is never auto-corrected to.

- **Space-bar navigation.** `SpaceSwipe2D` splits one swipe into character and key-row steps; `SpaceSelection` sends up and down
  only in multi-line fields and selects with `setSelection(anchor, focus)` or shift and arrow keys; `MultiCursor` holds several
  cursors in memory, reads the field text only when `PrivacyPolicy.Decision.fieldEditing` allows (no password or incognito field),
  refuses a missing, partial or over-20 000-character text, writes one region in one batch edit after checking it is unchanged,
  holds at most 100 cursors and drops everything at field change and screen lock (undo: two minutes, and only while the whole field
  is exactly as the edit left it: any other change ends it; backspace removes a whole grapheme). The navigation package has
  no file, network, clipboard, log or preference class (SourceRules). Tests: `SpaceSwipe2DTest`, `SpaceSelectionTest`,
  `MultiCursorTest`, `NavigationWiringTest`.

## App lock and self-destruct

- **App lock.** PIN of 4 to 16 digits, Argon2id, record in the device-protected `security` store, never exported. The attempt is stored
  before the PIN is compared; waits start at the fifth failure (30 s doubling to 1 hour); unreadable or unwritable state means closed.
  Every writer of the record is one step of `AppLock.update` (one process-wide lock). Documents of other apps are read only through
  `FileUtils` (asynchronous, 64 MiB, 60 s). Tests: `AppLockTest`, `AppLockWiringTest`, `AppLockMainThreadTest`.
- **P15 Self-destruct.** Triggers: duress PIN, trigger word (Enter or Space only, 12 to 64 characters, one Argon2id check, never learned
  or stored in plain text), wrong PINs (5 to 20), inactivity (3 to 365 days), panic. `SelfDestructPolicy` decides, `SelfDestructEngine`
  applies, `Wiper` runs the order: writers refuse, marker, keys deleted, files deleted, clipboard cleared, kept configuration written
  back, marker removed, restart. A failure keeps the marker and `Wiper.resume` finishes the job. Only `SelfDestruct.kt` and `DataKeys`
  delete keys; only the wipe and the two existing restarts end the process (SourceRules). Tests: `SelfDestruct*Test`, `WiperTest`,
  `FileWiperTest`, `TriggerWordTest`.

## Voice, downloads and pictures

- **P20 Voice typing on the device.** `VoiceController` (`VoiceGate`: setting on, not a password field, not locked, model present, then
  permission) to `VoiceSession` to `MicCapture` (the only `AudioRecord`) to the ONNX Runtime engine. RECORD_AUDIO is asked only by
  `VoicePermissionActivity`. Audio and everything derived from it stay in memory, are zeroed after use and are never written or logged.
  The text is committed as plain text and never learned. The model is installed by the user, checked against its SHA-256 and kept in
  `no_backup/taen-voice`. Tests: `:voice-engine:test`, `Voice*Test`.
- **P22 Optional downloads.** `assets/catalog/downloads.tsv` equals `tools/downloads.lock` (https, commit-pinned, SHA-256 and size).
  The browser downloads; `DownloadAccess` is the only intent; `PinnedInstaller` is the only writer (hash while copying, size cap,
  rename after the hash matches). Tests: `DownloadCatalogTest`, `PinnedInstallerTest`.
- **P23 Next-word model.** `NextWordGate` decides where a model runs (switch, strip, `PrivacyPolicy` personalized, speed and memory
  gates). The nextword package has no file, log, preference or learning class; only dictionary words are shown; late results are
  dropped. The free memory is checked before every load; a load writes a marker that the next process reads as a crash if the
  load never returned, and holds the per-model lock of `ModelFiles` from the marker to the end of the load, so loads of one model (speed
  test and keyboard) never overlap. Tests: `:nextword-engine:test`, `NextWord*Test`, `ModelFilesTest`.
- **P24 Pictures.** GIF, PNG, JPEG and WebP from the picker, metadata stripped without re-encoding, stored in AES-GCM stores
  (`MediaVault`, CE tier). Sent only by `MediaInserter` through `MediaShareProvider` (not exported, per-grant tokens in memory, 10
  minutes, revoked at lock, wipe and field change). Guards: SourceRules, leak audit S2.
