package taen

/**
 * Text rules for app/src/main/java (the source part of privacyCheck) and the permission denylist of the merged manifest.
 * A rule matches when its pattern occurs in a file whose path (relative to app/src/main/java, with '/') is not in
 * [SourceRule.allowedIn]. Kept free of Gradle types so it is unit-tested (SourceRulesTest).
 */
class SourceRule(val pattern: String, val reason: String, val allowedIn: Set<String> = emptySet(), val regex: Regex? = null) {
    /** The text of the match of the rule in [text], or null. A regex rule is used when set, otherwise the plain [pattern]. */
    fun find(text: String): String? = if (regex != null) regex.find(text)?.value else pattern.takeIf { text.contains(it) }
}

object SourceRules {
    /** The only place that may touch the old plaintext files and the platform preference and database classes. */
    private const val ACCESSOR = "helium314/keyboard/latin/privacy/Privacy.kt"
    private val accessorOnly = setOf(ACCESSOR)
    private val providerAccessors = setOf(
        "helium314/keyboard/latin/common/FileUtils.java",
        "helium314/keyboard/settings/VoiceModelAccess.kt",
        "helium314/keyboard/settings/screens/VoiceScreen.kt",
        "helium314/keyboard/latin/media/MediaShareProvider.kt", // it is the provider (openFile is its override), the one that serves a picture to the receiving app
    )

    val forbiddenPermissions = listOf(
        "INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_NETWORK_STATE", "CHANGE_WIFI_STATE", // P1
        "READ_CONTACTS", "WRITE_CONTACTS", "QUERY_ALL_PACKAGES", "SYSTEM_ALERT_WINDOW", "POST_NOTIFICATIONS", // P2
        "READ_USER_DICTIONARY", "WRITE_USER_DICTIONARY", // P2, P7: the personal dictionary is encrypted in the app
        "BIND_ACCESSIBILITY_SERVICE", // P13: the app declares no accessibility service
        // P20: RECORD_AUDIO (voice typing) is the one runtime permission of the app; every other sensitive one stays out
        "CAMERA", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE",
        "READ_PHONE_STATE", "READ_SMS", "SEND_SMS", "READ_CALL_LOG", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "BODY_SENSORS",
        "ACTIVITY_RECOGNITION", "BLUETOOTH_CONNECT",
    ).map { "android.permission.$it" }

    /** Providers that may appear in the merged manifest (the only provider of our own is MediaShareProvider). None may be exported. */
    const val MEDIA_PROVIDER = "helium314.keyboard.latin.media.MediaShareProvider"
    val allowedProviders = setOf("androidx.startup.InitializationProvider", MEDIA_PROVIDER)

    /** The only provider that may carry grantUriPermissions="true" (it hands out one picture per token to the app that received it). */
    val grantingProviders = setOf(MEDIA_PROVIDER)

    // setPrimaryClip is for copy (RichInputConnection), clearing (ClipboardManagerCompat) and theme export (ColorsScreen);
    // pasting from the history inserts text with commitText and never touches the system clipboard (P10)
    private val clipSetters = setOf(
        "helium314/keyboard/latin/RichInputConnection.java",
        "helium314/keyboard/compat/ClipboardManagerCompat.java",
        "helium314/keyboard/settings/screens/ColorsScreen.kt",
    )

    // the native dictionary engine reads and writes dictionary files by path. Learned words must not use that: the
    // engine keeps them in memory and they are saved encrypted through BinaryDictionary.serialize(). Only the wrapper
    // class that declares the file calls may mention them (it refuses them for memory dictionaries).
    // Ver4DictEncoder writes a dictionary file from a user-imported word list (a converter, no typed text).
    private val nativeFileReason = "P7: native dictionary file access for learned words (use BinaryDictionary.serialize and the encrypted storage)"
    private val nativeFileOwners = setOf("com/android/inputmethod/latin/BinaryDictionary.java", "helium314/keyboard/latin/makedict/Ver4DictEncoder.java")
    private val nativeFileRules = listOf("flushWithGC", "flushNative", "openNative", "migrateNative").map {
        SourceRule(it, nativeFileReason, nativeFileOwners)
    } + SourceRule("any dictionary .flush(", nativeFileReason, setOf("com/android/inputmethod/latin/BinaryDictionary.java"),
        // x.flush() / x().flush() / x\n.flush() on anything named like a dictionary (dict, mDictionary, getBinaryDictionary())
        Regex("""[Dd]ict\w*\s*(\(\s*\))?\s*\.\s*flush\s*\(""")) + SourceRule("\"UserHistoryDictionary", "P7: the old readable learned-word file (only the legacy mover, the delete-all routine and the wipe name it)",
        setOf("helium314/keyboard/latin/personalization/UserHistoryDictionary.java", "helium314/keyboard/latin/personalization/PersonalizationHelper.java",
            "helium314/keyboard/latin/privacy/Wiper.kt")) // the wipe deletes a leftover of that file too (FileWiper)

    // a zip is only built or read inside the encrypted export payload (ExportBundle), never as a plain file
    private val exportRules = listOf("ZipOutputStream", "ZipInputStream").map {
        SourceRule(it, "P16: plain zip backup (the export is encrypted, build it with ExportBundle and ExportCodec)",
            setOf("helium314/keyboard/latin/privacy/ExportBundle.kt"))
    }

    // User data must reach a file only through the encrypted stores (SecureStore). These classes write other files, none of
    // which holds typed text: imported layouts/fonts/blacklists (ExportService), the layout editor, the converter of imported
    // word lists (DictIO, Ver2DictEncoder, FileUtils copy), and the plain blacklist of main-dictionary words the user removed
    // (DictionaryFacilitatorImpl: a known gap listed in PRIVACY.md). Diagnostics are not an owner: the app keeps no log, crash
    // report or other diagnostic file (P11).
    private val fileWriterOwners = setOf(
        "helium314/keyboard/latin/privacy/SecureStore.kt",
        "helium314/keyboard/latin/privacy/ExportService.kt",
        "helium314/keyboard/settings/dialogs/LayoutEditDialog.kt",
        "helium314/keyboard/latin/makedict/DictIO.kt",
        "helium314/keyboard/latin/makedict/Ver2DictEncoder.java",
        "helium314/keyboard/latin/common/FileUtils.java",
        "helium314/keyboard/latin/DictionaryFacilitatorImpl.kt",
        "helium314/keyboard/latin/voice/VoiceModelStore.kt", // the public voice model files, not user data (P20)
        "helium314/keyboard/latin/downloads/PinnedInstaller.kt", // the public pinned files of Settings > Downloads (a dictionary), staged as .part and renamed after the hash matched
        "helium314/keyboard/latin/downloads/ModelFiles.kt", // the speed result of a next-word model on this phone: two numbers, nothing typed
    )
    private val fileWriterRules = listOf("FileOutputStream", "openFileOutput", "FileWriter", "Files.write", ".writeText(", ".writeBytes(",
        ".appendText(", ".appendBytes(", ".outputStream(", ".bufferedWriter(", ".printWriter(").map {
        SourceRule(it, "P7: plain file write (user data goes through SecureStore; add a reviewed exception only for data that is not typed text)",
            fileWriterOwners + if (it == ".writeText(") setOf("helium314/keyboard/latin/privacy/Codec.kt") else emptySet())
    }

    // Java 11 stream methods that Android has only from API 33 (not desugared): they crash older devices (minSdk is 30)
    private val missingStreamMethods = listOf("readNBytes(", "readAllBytes(", "transferTo(").map {
        SourceRule(it, "API 33 method on minSdk 30 (NoSuchMethodError on Android 11 and 12): use readAtMost from the privacy package or a read loop")
    }

    // P11: no diagnostics retention. Any retained crash report, log buffer or stack trace can capture typed text
    // (exception messages carry it), so the app has none: crashes go to the system handler only, and logs are fixed strings.
    private const val NO_DIAGNOSTICS = "P11: no diagnostics retention (crashes go to the system handler, logs are fixed strings)"
    private val diagnosticsRules = listOf(
        "setDefaultUncaughtExceptionHandler", "printStackTrace", "System.out", "System.err", "stackTraceToString", "getStackTraceString",
        "getExternalFilesDir", "getExternalCacheDir", "getExternalStorage", "getExternalMediaDirs", "externalCacheDir", "externalMediaDirs",
        "externalFilesDir", "crash_report",
    ).map { SourceRule(it, NO_DIAGNOSTICS) }

    // Round 2 follow-up: the Log-call rule only sees calls written `Log.x(`. An alias, a method reference or a static import would
    // call the wrapper without being checked, and a write to the shared media store is a diagnostics file outside our own files.
    private val bypassRules = listOf(
        SourceRule("a Log alias, method reference or static import", "P11: the Log wrapper is only called as Log.x(tag, \"literal\") (no alias, no Log::x, no static import)", emptySet(),
            Regex("""\bLog\s+as\s+\w|=\s*[\w.]*\bLog\b(?![\w.(<:])|\bLog\s*::|import\s+(?:static\s+)?[\w.]*\bLog\.[\w*]""")),
        SourceRule("MediaStore", NO_DIAGNOSTICS),
        SourceRule("a ContentResolver insert", "P11: no writes to the shared media store or other providers (no diagnostics or user-data files outside the app)", emptySet(),
            Regex("""[rR]esolver\s*(?:\(\s*\))?\s*\.\s*insert\s*\(""")),
    )

    // Anti-snooping (P9, P12, P13, P14). The privacy decision has one owner; FLAG_SECURE has one owner; no accessibility service and
    // no overlay window of our own (every activity must call SecureWindow.apply: see [activityProblems]).
    private const val POLICY = "helium314/keyboard/latin/privacy/PrivacyPolicy.kt"
    private const val SECURE_WINDOW = "helium314/keyboard/latin/privacy/SecureWindow.kt"
    private val antiSnoopingRules = listOf(
        SourceRule("IME_FLAG_NO_PERSONALIZED_LEARNING", "P9: the no-personalized-learning flag is read in PrivacyPolicy only", setOf(POLICY)),
        SourceRule("PREF_INCOGNITO_APPS", "P9: the per-app incognito list is read through PrivacyPolicy (SettingsValues builds the decision, PrivacyScreen edits the list)",
            setOf("helium314/keyboard/latin/settings/Settings.java", "helium314/keyboard/latin/settings/Defaults.kt",
                "helium314/keyboard/latin/settings/SettingsValues.java", "helium314/keyboard/settings/screens/PrivacyScreen.kt")),
        // layout variants and spoken feedback look at the password variation for their own reasons (a different key, no speech); they decide nothing about storage
        SourceRule("a password input type test", "P9: password fields are recognised by PrivacyPolicy (layout and speech code may ask InputTypeUtils for their own reasons)",
            setOf(POLICY, "helium314/keyboard/latin/utils/InputTypeUtils.java", "helium314/keyboard/latin/InputAttributes.java",
                "helium314/keyboard/keyboard/KeyboardId.kt", "helium314/keyboard/accessibility/AccessibilityUtils.kt"),
            Regex("""\bis(?:Any|Visible)?PasswordInputType\b""")),
        SourceRule("FLAG_SECURE", "P12: FLAG_SECURE is set and cleared in SecureWindow only", setOf(SECURE_WINDOW)),
        SourceRule("an AccessibilityService", "P13: the app declares no accessibility service", emptySet(), Regex("""\bAccessibilityService\b""")),
        SourceRule("an overlay window", "P14: no overlay or system-alert window of our own", emptySet(),
            Regex("""TYPE_APPLICATION_OVERLAY|TYPE_SYSTEM_ALERT|[wW]indowManager\s*\.\s*addView\s*\(""")),
    )

    // Self-destruct: keys are destroyed in one orchestrated place. Only the wipe (SelfDestruct), the data keys (permanent loss of a
    // vault key) and the Keystore wrapper delete a key; only the wipe and the two existing restarts kill the process.
    private const val SELF_DESTRUCT = "helium314/keyboard/latin/privacy/SelfDestruct.kt"
    private val selfDestructRules = listOf(
        SourceRule(".deleteKey(", "self-destruct: key deletion belongs to the wipe (SelfDestruct) and DataKeys", setOf(SELF_DESTRUCT, "helium314/keyboard/latin/privacy/DataKeys.kt")),
        SourceRule("deleteEntry(", "self-destruct: Keystore entries are deleted by AndroidKeyVault only", setOf("helium314/keyboard/latin/privacy/AndroidKeyVault.kt")),
        // every way to end the process: Process.killProcess / sendSignal, Runtime.exit / halt (also on a variable, over lines), System.exit, exitProcess
        SourceRule("a call that ends the process", "self-destruct: the process is ended by the wipe and the two existing restarts only (killProcess, sendSignal, exit, halt, System.exit, exitProcess)",
            setOf(SELF_DESTRUCT, "helium314/keyboard/latin/SystemBroadcastReceiver.java", "helium314/keyboard/latin/LatinIME.java"),
            Regex("""\b(?:killProcess|exitProcess|sendSignal(?:Quiet)?)\b|\.\s*(?:exit|halt)\s*\(|\bSystem\s*\.\s*exit\s*\(""")),
    )

    // P12: a class that extends a platform activity class must call SecureWindow.apply, or the screen can be captured. A subclass of an app activity inherits it.
    private val activityBase = Regex(
        """(?m)^(?!\s*(?://|\*)).*(?::\s*|\bextends\s+)(?:[\w.]+\.)?(?:Component|AppCompat|Fragment)?Activity\b\s*(?:\(|\{|\bimplements\b|$)""")
    private val secureCall = Regex("""SecureWindow(?:\.INSTANCE)?\.apply\(""")

    private fun activityProblems(relativePath: String, text: String): List<String> =
        if (activityBase.containsMatchIn(text) && !secureCall.containsMatchIn(text))
            listOf("P12: an activity must call SecureWindow.apply(window) (no screenshots or recordings): found an activity class in $relativePath")
        else emptyList()

    // App lock, a HINT only (the guard is AppLock.update: it throws on the main thread, AppLockMainThreadTest): the writers (enable, changePin,
    // disable, setTimeoutMinutes, cycleTimeoutMinutes, verify) hash a PIN under the process-wide lock, so on the main thread they would freeze the
    // screen. This textual rule catches the common slip early (a call with a usual receiver name outside a withContext/launch on Default or IO, an
    // executor's execute or a thread); it is bypassable (other names, wrappers, a main-thread hop inside a background block) and proves nothing.
    private const val APP_LOCK_FILE = "helium314/keyboard/latin/privacy/AppLock.kt"
    private val appLockWriter = Regex(
        """(?<!\w)(?:lock|appLock|Privacy\s*\.\s*appLock\s*\([^()]*\))\s*\.\s*(enable|changePin|disable|setTimeoutMinutes|cycleTimeoutMinutes|verify)\s*\(""")
    private val offMainBlock = Regex(
        """(?:withContext|launch)\s*\(\s*Dispatchers\s*\.\s*(?:Default|IO)\b|\.execute\s*\{|\bthread\s*(?:\([^)]*\))?\s*\{""")

    private fun appLockThreadProblems(relativePath: String, text: String): List<String> {
        if (relativePath == APP_LOCK_FILE) return emptyList()
        return appLockWriter.findAll(text).mapNotNull { m ->
            val lineStart = text.lastIndexOf('\n', m.range.first) + 1
            val before = text.substring(lineStart, m.range.first)
            if (before.contains("//") || before.trimStart().startsWith("*")) return@mapNotNull null // a comment
            if (runsOffMainThread(text, m.range.first)) null
            else "App lock: ${m.value.trimEnd('(')}( must run off the main thread (inside withContext(Dispatchers.Default or IO), launch(Dispatchers.Default or IO), an executor's execute or a thread): found in $relativePath"
        }.toList()
    }

    /** True when one of the blocks around [position] is opened by a call that runs its body on another thread. */
    private fun runsOffMainThread(text: String, position: Int): Boolean {
        var depth = 0
        for (i in position - 1 downTo 0) {
            when (text[i]) {
                '}' -> depth++
                '{' -> if (depth > 0) depth-- else {
                    val headerStart = text.lastIndexOf('\n', i) + 1
                    if (offMainBlock.containsMatchIn(text.substring(headerStart, i + 1))) return true
                }
            }
        }
        return false
    }

    // Glide typing: the gesture package reads the touch points of a gesture, scores them and drops them. It has no file or
    // network class, no logger, no storage owner, no preferences and no stream output, so a gesture can neither be kept nor sent.
    private const val GESTURE_PACKAGE = "helium314/keyboard/latin/gesture/"
    private val gestureForbidden = Regex(
        """\bjava\.(?:io|nio|net)\.|\bFile\w*\b|\bLog\b|\bprintln\b|\bPrivacy\b|SharedPreferences|\bprefs\s*\(|\bSecureStore\b|\bandroid\.util\.Log\b""")

    private fun gesturePackageProblems(relativePath: String, text: String): List<String> =
        if (!relativePath.startsWith(GESTURE_PACKAGE)) emptyList()
        else gestureForbidden.find(text)?.let {
            listOf("gesture: the gesture package never stores, logs or sends a gesture (no file, network, log, preference or storage class): found ${it.value} in $relativePath")
        } ?: emptyList()

    // Space-bar navigation: the navigation package reads the selection and, for several cursors, the field text, and works on it
    // in memory. It has no file, network, clipboard or storage class, no logger and no preferences, so field text is never kept or sent.
    private const val NAVIGATION_PACKAGE = "helium314/keyboard/latin/navigation/"
    private val navigationForbidden = Regex(
        """\bjava\.(?:io|nio|net)\.|\bFile\w*\b|\bLog\b|\bprintln\b|\bPrivacy\b|SharedPreferences|\bprefs\s*\(|\bSecureStore\b|\bClip(?:boardManager|Data)\b|\bandroid\.util\.Log\b""")

    private fun navigationPackageProblems(relativePath: String, text: String): List<String> =
        if (!relativePath.startsWith(NAVIGATION_PACKAGE)) emptyList()
        else navigationForbidden.find(text)?.let {
            listOf("navigation: the navigation package never stores, logs, copies or sends field text (no file, network, clipboard, log, preference or storage class): found ${it.value} in $relativePath")
        } ?: emptyList()

    // Vim layer: the vim package works on the field text it is handed (and keeps one unnamed register in a plain String). It is an ALLOW
    // list, because a deny list of writers failed twice (a process that runs `log` was never named): the package may import only kotlin.*
    // (not kotlin.io, system, concurrent or reflect) and its own package, and may name no java., javax., jdk., sun., dalvik., android.,
    // androidx. or other class by its full name. Kotlin imports java.lang.* and kotlin.io and kotlin.jvm by itself, so the names in them
    // that do I/O, run a process or thread, or reach another class are forbidden by name. The one exception: java.text.BreakIterator
    // and java.util.Locale (VimText finds the character boundaries with them; pure computation on a String, no I/O). Anything else
    // that the engine needs from the platform belongs in the adapter (keyboard/VimInput.kt), outside this package.
    private const val VIM_PACKAGE = "helium314/keyboard/latin/vim/"
    private const val VIM_OWN = "helium314.keyboard.latin.vim"
    private val vimPureJava = setOf("java.text.BreakIterator", "java.util.Locale")
    private val vimBannedKotlin = listOf("kotlin.io", "kotlin.system", "kotlin.concurrent", "kotlin.reflect")
    private val vimImport = Regex("""(?m)(?:^|;)[ \t]*import[ \t]+([\w.`]+)""")
    private val vimQualified = Regex("""(?<![\w.`])((?:java|javax|jdk|sun|dalvik|android|androidx|kotlin|kotlinx|org|com|helium314)\.[\w.]*)""")
    private val vimForbidden = Regex(
        """\bjava\.(?:io|nio|net)\.|\bFile\w*\b|\bLog\b|\bprintln\b|\bPrivacy\b|\bSettings\b|SharedPreferences|\bprefs\s*\(|\bSecureStore\b|\bClip(?:boardManager|Data)\b|\bandroid\.|\bprintStackTrace\b""" +
            // every writer to stdout or stderr (Kotlin print and println reach logcat through System.out), and java.util.logging
            """|\bprint\b|\bPrint(?:Stream|Writer)\b|\bdumpStack\b|\bSystem\.(?:out|err|console|setOut|setErr|getLogger)\b|\bjava\.util\.logging\b|\bLogger\b""" +
            // the java.lang and kotlin names that do I/O, run a process or a thread, or reach any class by name (reflection)
            """|\b(?:System|Runtime|ProcessBuilder|ProcessHandle|Process|Thread|ClassLoader|javaClass|getClass|KClass|readLine|readln)\b""" +
            """|\bClass\s*\.\s*forName\b|::\s*class\s*\.\s*java|\.\s*java\b|\.\s*invoke\s*\(""")

    private fun vimNameAllowed(name: String): Boolean {
        val n = name.trimEnd('.')
        if (n == "kotlin" || n.startsWith("kotlin.")) return vimBannedKotlin.none { n == it || n.startsWith("$it.") }
        if (n == VIM_OWN || n.startsWith("$VIM_OWN.")) return true
        return vimPureJava.any { n == it || n.startsWith("$it.") }
    }

    // An early lint only (it fails a plain mistake before a build): the guard that counts is VimBytecode on the compiled classes, which does
    // not depend on how a name is spelled. Backticks are stripped first, since `java`.io.File is the same name.
    private fun vimPackageProblems(relativePath: String, source: String): List<String> {
        if (!relativePath.startsWith(VIM_PACKAGE)) return emptyList()
        val text = source.replace("`", "")
        val found = vimForbidden.find(text)?.value
            ?: vimImport.findAll(text).map { it.groupValues[1] }.firstOrNull { !vimNameAllowed(it) }
            ?: vimQualified.findAll(text).map { it.groupValues[1] }.firstOrNull { !vimNameAllowed(it) }
            ?: return emptyList()
        return listOf("vim: the vim package may import only kotlin.* (not kotlin.io, system, concurrent, reflect), its own package, java.text.BreakIterator and java.util.Locale, " +
            "and has no file, network, clipboard, log, process, thread, reflection, preference or storage class: found $found in $relativePath")
    }

    // the emoji search (public CLDR keywords read from the app's assets, a query typed in the search field or the word being typed)
    // and the smart quotes (one character and the one before it) work in memory on what they are given. They have no file or
    // network class, no logger, no storage owner and no preferences, so a typed query can neither be kept nor sent.
    private val m8StatelessFiles = setOf(
        "helium314/keyboard/keyboard/emoji/EmojiSearchIndex.kt",
        "helium314/keyboard/latin/dictionary/EmojiKeywordDictionary.kt",
        "helium314/keyboard/latin/inputlogic/SmartQuotes.kt",
    )

    private fun m8StatelessProblems(relativePath: String, text: String): List<String> =
        if (relativePath !in m8StatelessFiles) emptyList()
        else gestureForbidden.find(text)?.let {
            listOf("emoji search: the emoji search and smart quote classes never store, log or send what is typed (no file, network, log, preference or storage class): found ${it.value} in $relativePath")
        } ?: emptyList()

    // Voice typing (P20). The microphone has one owner (MicCapture) and the recorder class that writes files is never used; the
    // microphone permission is named in one place (VoicePermissionActivity); ONNX Runtime is reached through one wrapper (OrtParakeet) and never
    // told to write a file; there is no hand-over to another voice input method. The voice package keeps audio and text in memory: it has no
    // file, network, log, preference or storage class and no dictionary or personal-word class (the text is committed like a paste and never
    // learned), except that the model store is the one owner of the public model files.
    private const val VOICE_PACKAGE = "helium314/keyboard/latin/voice/"
    private const val VOICE_MODEL_STORE = "${VOICE_PACKAGE}VoiceModelStore.kt"
    private val voiceRules = listOf(
        SourceRule("AudioRecord", "P20: the microphone is opened by MicCapture only", setOf("${VOICE_PACKAGE}MicCapture.kt")),
        SourceRule("MediaRecorder", "P20: MediaRecorder records to files; the microphone is read into memory by MicCapture"),
        SourceRule("RECORD_AUDIO", "P20: the microphone permission is checked and requested in VoicePermissionActivity only", setOf("${VOICE_PACKAGE}VoicePermissionActivity.kt")),
        SourceRule("ai.onnxruntime", "P20: ONNX Runtime is used by voice/engine/OrtParakeet.kt and nextword/engine/OrtNextWord.kt only",
            setOf("${VOICE_PACKAGE}engine/OrtParakeet.kt", "${NEXTWORD_PACKAGE}engine/OrtNextWord.kt")),
        SourceRule("setOptimizedModelFilePath", "P20: ONNX Runtime must not write an optimised model file"),
        SourceRule("enableProfiling", "P20: ONNX Runtime must not write a profile file"),
        SourceRule("switchToShortcutIme", "P20: no hand-over to another voice input method (voice typing is on this device)", setOf("helium314/keyboard/latin/RichInputMethodManager.kt")),
    )

    private val voiceNoFiles = Regex("""\bjava\.io\.|\bjava\.nio\.(?:file|channels)\.|\bFile\w*\b|\.(?:outputStream|inputStream)\s*\(|\bFiles\s*\.|openFileOutput""")
    private val voiceNoLeaks = Regex(
        """\bjava\.net\.|\bLog\b|\bprintln\b|\bPrivacy\b|SharedPreferences|\bprefs\s*\(|\bSecureStore\b|\bandroid\.util\.Log\b|UserHistory|PersonalDictionary|\bDictionary\w*\b""")

    private fun voicePackageProblems(relativePath: String, text: String): List<String> {
        if (!relativePath.startsWith(VOICE_PACKAGE)) return emptyList()
        val found = ArrayList<String>()
        voiceNoLeaks.find(text)?.let { found.add("P20: the voice package never stores, logs or sends audio or text and never learns from it (no network, log, preference, storage or dictionary class): found ${it.value} in $relativePath") }
        if (relativePath != VOICE_MODEL_STORE) voiceNoFiles.find(text)?.let { found.add("P20: the voice package writes no file except the model store (VoiceModelStore): found ${it.value} in $relativePath") }
        return found
    }

    // The next-word model. It reads the text before the cursor (only where PrivacyPolicy allows personalized suggestions) and keeps it in memory: the
    // nextword package has no file, network, log, preference or storage class and no learning class (a word of the model is never learned). The model files
    // and the speed result of this phone are in the downloads package (ModelFiles); the folder is named by Privacy only.
    private const val NEXTWORD_PACKAGE = "helium314/keyboard/latin/nextword/"
    private val nextwordRules = listOf(
        SourceRule("taen-models", "the folder of the next-word models is named by Privacy only", setOf("helium314/keyboard/latin/privacy/Privacy.kt")),
    )
    private val nextwordNoLeaks = Regex(
        """\bjava\.net\.|\bLog\b|\bprintln\b|\bPrivacy\b|SharedPreferences|\bprefs\s*\(|getSharedPreferences|PreferenceManager|\bSecureStore\b|\bandroid\.util\.Log\b|UserHistory|PersonalDictionary|LearnedStorage""")

    private fun nextwordPackageProblems(relativePath: String, text: String): List<String> {
        if (!relativePath.startsWith(NEXTWORD_PACKAGE)) return emptyList()
        val found = ArrayList<String>()
        nextwordNoLeaks.find(text)?.let { found.add("the next-word package never stores, logs or sends what the model reads and never learns from it (no network, log, preference, storage or learning class): found ${it.value} in $relativePath") }
        voiceNoFiles.find(text)?.let { found.add("the next-word package has no file class (the model files are in the downloads package): found ${it.value} in $relativePath") }
        return found
    }

    // P21 (profiles and templates). The saved profiles are ONE value of the credential-encrypted preferences and ProfileStore is the only code that names
    // it; the template files are read through TemplateCatalog only. The profiles package and the quick-settings package work on the SharedPreferences they are handed:
    // they have no file, network or log class and never reach the platform preferences or the storage owner themselves.
    private const val PROFILES_PACKAGE = "helium314/keyboard/latin/profiles/"
    private const val QUICK_PACKAGE = "helium314/keyboard/latin/quick/"
    private val profileRules = listOf(
        SourceRule("taen_profiles", "P21: the saved-profiles key is named by ProfileStore only", setOf("${PROFILES_PACKAGE}ProfileStore.kt")),
        SourceRule("\"templates", "P21: the template assets are read by TemplateCatalog only", setOf("${PROFILES_PACKAGE}TemplateCatalog.kt")),
    )
    private val profilesNoFiles = Regex("""\bjava\.nio\.|\bFile\w*\b|\.(?:outputStream|inputStream|readText|writeText|readBytes|writeBytes)\s*\(|\bFiles\s*\.|openFileOutput|\bRandomAccessFile\b""")
    private val profilesNoLeaks = Regex("""\bjava\.net\.|\bLog\b|\bprintln\b|\bprintStackTrace\b|getSharedPreferences|PreferenceManager|\bPrivacy\b|\bSecureStore\b|\bandroid\.util\.Log\b""")

    private fun profilePackageProblems(relativePath: String, text: String): List<String> {
        if (!relativePath.startsWith(PROFILES_PACKAGE) && !relativePath.startsWith(QUICK_PACKAGE)) return emptyList()
        val found = ArrayList<String>()
        profilesNoFiles.find(text)?.let { found.add("P21: the profiles and quick-settings packages have no file class (they use the preferences they are given): found ${it.value} in $relativePath") }
        profilesNoLeaks.find(text)?.let { found.add("P21: the profiles and quick-settings packages have no network, log, platform-preferences or storage class: found ${it.value} in $relativePath") }
        return found
    }

    // native code enters the process only through System.loadLibrary of the built-in library (JniUtils, by name) and through ONNX
    // Runtime's own loading of its two libraries (privacy/native-libs.allow). No load from a path, no other loadLibrary, no custom-operator library and no execution
    // provider (NNAPI, XNNPACK, CUDA, QNN ...): each ends in dlopen of code that is not in the APK scan. The release dex has the same rule (leak audit S8, check_native_loading).
    private const val LOAD_PREFIX = """(?:\bSystem\s*\.|getRuntime\s*\(\s*\)\s*\.|\bRuntime\s*\.)\s*"""
    private const val LOAD_REF_PREFIX = """\b(?:System|Runtime)\s*::\s*"""
    private val nativeLoadRules = listOf(
        SourceRule("System.load / Runtime.load of a path", "P6: loading native code from a path (System.loadLibrary of the built-in library by name is the only loader)", emptySet(),
            Regex(LOAD_PREFIX + """load\s*\(|""" + LOAD_REF_PREFIX + """load\b""")),
        SourceRule("System.loadLibrary / Runtime.loadLibrary", "P6: only JniUtils loads the built-in native library", setOf("helium314/keyboard/latin/utils/JniUtils.java"),
            Regex(LOAD_PREFIX + """loadLibrary\s*\(|""" + LOAD_REF_PREFIX + """loadLibrary\b""")),
        SourceRule("a native library or execution-provider registration (register...Library, addExecutionProvider, addNnapi, addXnnpack, addCUDA, ...)",
            "P6: ONNX Runtime custom-operator or execution-provider libraries are dlopen-style code loading (CPU provider only)", emptySet(),
            Regex("""\b(?:register\w*Library|addExecutionProvider\w*|add(?:Nnapi|Xnnpack|XNNPACK|Cuda|CUDA|Tensorrt|TensorRT|CoreML|Qnn|QNN|Rocm|ROCM|DirectML|Dnnl|OpenVINO|Acl|ACL|ArmNN|Armnn|Cann|CANN|Snpe|SNPE|Tvm|TVM|Rknpu|MIGraphX|Vitis\w*|WebGPU|Webgpu)\w*)\s*\(""")),
    )


    // Optional downloads and offline pictures. The address of a download is handed to the browser in DownloadAccess only; the picture
    // collection reaches the platform through three owners: MediaInserter (commitContent), MediaShareProvider (the pipe) and MediaVault (the encrypted stores). The
    // media and downloads packages have no network, log or preference class; the media package has no file class at all (its stores are SecureStore objects of the
    // storage owner), except the exception type that a content provider must throw.
    private const val MEDIA_PACKAGE = "helium314/keyboard/latin/media/"
    private const val DOWNLOADS_PACKAGE = "helium314/keyboard/latin/downloads/"
    private val downloadMediaRules = listOf(
        SourceRule("Intent.ACTION_VIEW", "a download address is given to the browser by DownloadAccess only (About links, the incoming link of SettingsActivity and the panic shortcut action are the others)", setOf(
            "helium314/keyboard/settings/DownloadAccess.kt", "helium314/keyboard/settings/screens/AboutScreen.kt", "helium314/keyboard/settings/SettingsActivity.kt",
            "helium314/keyboard/latin/privacy/SelfDestruct.kt")), // SelfDestruct: the action of the panic shortcut (no address)
        SourceRule("commitContent(", "pictures are sent into a field by MediaInserter only", setOf("${MEDIA_PACKAGE}MediaInserter.kt")),
        SourceRule("a pipe or socket pair (createPipe, createReliablePipe, createSocketPair)", "the pipe that serves a picture is made by MediaShareProvider only (it writes no file)", setOf("${MEDIA_PACKAGE}MediaShareProvider.kt"),
            Regex("""\b(?:createPipe|createReliablePipe|createSocketPair)\b""")),
        // "media_<id>" store names; the preference name "media_starter_pack" is not a store
        SourceRule("a \"media_ store name", "the store names of the picture collection are known to MediaVault and the wipe only", setOf("${MEDIA_PACKAGE}MediaVault.kt", "helium314/keyboard/latin/privacy/Wiper.kt"),
            Regex("""\"media_(?!starter_pack\")""")),
        SourceRule("taen-downloads", "the staging folder of downloads is named by Privacy only", setOf("helium314/keyboard/latin/privacy/Privacy.kt")),
    )

    private val mediaNoFiles = Regex("""\bFile(?!NotFoundException\b)\w*\b|\bjava\.nio\.(?:file|channels)\.|\.(?:outputStream|inputStream)\s*\(|\bFiles\s*\.|openFileOutput|\bRandomAccessFile\b""")
    private val mediaNoLeaks = Regex("""\bjava\.net\.|\bLog\b|\bprintln\b|\bprintStackTrace\b|SharedPreferences|\bprefs\s*\(|getSharedPreferences|PreferenceManager|\bandroid\.util\.Log\b""")

    private fun mediaDownloadsPackageProblems(relativePath: String, text: String): List<String> {
        val found = ArrayList<String>()
        if (relativePath.startsWith(MEDIA_PACKAGE)) {
            mediaNoLeaks.find(text)?.let { found.add("the media package has no network, log or preference class: found ${it.value} in $relativePath") }
            mediaNoFiles.find(text)?.let { found.add("the media package has no file class (its stores are SecureStore objects made by Privacy): found ${it.value} in $relativePath") }
        }
        if (relativePath.startsWith(DOWNLOADS_PACKAGE))
            mediaNoLeaks.find(text)?.let { found.add("the downloads package has no network, log or preference class: found ${it.value} in $relativePath") }
        return found
    }

    val rules = listOf(
        SourceRule("DexClassLoader", "P6: dynamic code loading"),
        SourceRule("PathClassLoader", "P6: dynamic code loading"),
        SourceRule("ContactsContract", "P2: contacts access"),
        SourceRule("READ_CONTACTS", "P2: contacts permission"),
        SourceRule("GestureDataGathering", "no gesture data gathering"),
        SourceRule("BackgroundGatheringCache", "no gesture data gathering"),
        SourceRule("java.net.", "P1: networking classes"),
        SourceRule("HttpURLConnection", "P1: networking classes"),
        SourceRule("URLConnection", "P1: networking classes"),
        SourceRule("SocketChannel", "P1: networking classes"),
        SourceRule("okhttp", "P1: networking library"),
        SourceRule("WebView", "P1: web content"),
        SourceRule("DownloadManager", "P1: downloads"),
        SourceRule("ConnectivityManager", "P1: network state"),
        SourceRule("android.net.wifi", "P1: network state"),
        SourceRule("android.net.http", "P1: networking classes"),
        SourceRule("QUERY_ALL_PACKAGES", "P2: installed-app enumeration"),
        SourceRule("queryIntentActivities", "P2: installed-app enumeration"),
        SourceRule("getInstalledApplications", "P2: installed-app enumeration"),
        SourceRule("getInstalledPackages", "P2: installed-app enumeration"),
        // storage and clipboard
        SourceRule("UserDictionary", "P2/P7: system user dictionary (the personal dictionary is stored encrypted in the app)"),
        SourceRule("getSharedPreferences(", "P7: plain SharedPreferences (use Context.prefs() or protectedPrefs(), which are encrypted)", accessorOnly),
        SourceRule("PreferenceManager", "P7: plain SharedPreferences (use Context.prefs() or protectedPrefs(), which are encrypted)"),
        SourceRule("SQLiteOpenHelper", "P7: plain database (user data lives in encrypted stores, see the privacy package)"),
        SourceRule("openOrCreateDatabase", "P7: plain database (user data lives in encrypted stores, see the privacy package)"),
        SourceRule("SQLiteDatabase.openDatabase", "P7: plain database (only the legacy clipboard reader in Privacy.kt)", accessorOnly),
        SourceRule("FileProvider", "P3: content provider (clipboard files are no longer shared)"),
        SourceRule("custom_background_image", "P7: plain background image file (only the legacy migration in Privacy.kt)", accessorOnly),
        SourceRule("heliboard.db", "P7: plain clipboard database (only the legacy migration in Privacy.kt)", accessorOnly),
        SourceRule("setPrimaryClip(", "P10: system clipboard write outside copy, clear and theme export", clipSetters),
        // a provider of another app can stall for ever: every read, write or query of a document goes through FileUtils (one worker, size cap, time
        // limit, cancel; ProviderJobs on the screens). The voice model import and its installer run on Dispatchers.IO by design (files of 600 MB).
        // every stream-opening, thumbnail and provider-client method of ContentResolver, ContentProviderClient and DocumentsContract
        // (a method name that only a provider API has, on any receiver), and the querying calls of a ContentResolver (on a resolver receiver)
        SourceRule("a direct provider stream or query (openInputStream, openOutputStream, openFileDescriptor, openAssetFileDescriptor, openTypedAssetFileDescriptor, openTypedAssetFile, openAssetFile, openFile, loadThumbnail, acquire(Unstable)ContentProviderClient, DocumentsContract thumbnails, ContentResolver.query/call/getStreamTypes/canonicalize)",
            "documents of other apps are accessed by FileUtils only (ProviderJobs from the screens), never on the main thread", providerAccessors,
            // a call (`name(`) or a Kotlin method reference (`::name`); best-effort lint only (aliases and reflection are not seen): the real owner is FileUtils/ProviderJobs
            Regex("""\b(?:openInputStream|openOutputStream|openFileDescriptor|openAssetFileDescriptor|openTypedAssetFileDescriptor|openTypedAssetFile|openAssetFile|openFile|loadThumbnail|acquireContentProviderClient|acquireUnstableContentProviderClient|openDocumentThumbnail|getDocumentThumbnail)\s*\(|::\s*(?:openInputStream|openOutputStream|openFileDescriptor|openAssetFileDescriptor|openTypedAssetFileDescriptor|openTypedAssetFile|openAssetFile|openFile|loadThumbnail|acquireContentProviderClient|acquireUnstableContentProviderClient|openDocumentThumbnail|getDocumentThumbnail)\b|(?:contentResolver|getContentResolver\s*\(\s*\)|\bcr|\bresolver)\s*\??\s*(?:\.\s*(?:query|call|getStreamTypes|canonicalize|uncanonicalize|refresh)\s*\(|::\s*(?:query|call|getStreamTypes|canonicalize|uncanonicalize|refresh)\b)""")),
    ) + nativeFileRules + fileWriterRules + exportRules + missingStreamMethods + diagnosticsRules + bypassRules + antiSnoopingRules + selfDestructRules + voiceRules + profileRules + nativeLoadRules + downloadMediaRules + nextwordRules

    // ---- logging: only fixed strings ------------------------------

    /** Only the wrapper (helium314.keyboard.latin.utils.Log) may call the platform logger. */
    private const val LOG_WRAPPER = "helium314/keyboard/latin/utils/Log.kt"

    /** The wrapper holds no exception, trace or buffer: a retained log line could hold text. */
    private val wrapperForbidden = Regex("""Throwable|Exception|stackTrace|StackTrace|LogLine|getLog|logLines|List<|ArrayList|StringBuilder|StringBuffer""")

    /** Identifiers that hold typed, composed, suggested or editor text or the keys the user presses (compared in lower case). */
    private val userTextNames = setOf(
        "word", "mword", "word1", "tempword", "chosenword", "typedword", "pseudotypedwordinfo", "wordproperty", "consideredword",
        "text", "splittext", "composingtext", "gettextbeforecursor", "gettextaftercursor",
        "suggestion", "suggestions", "completions", "applicationspecifiedcompletions", "ngramcontext", "builder",
        "keyevent", "keycode", "code", "mcode", "codepoint", "getcodepoint", "printablecode", "key", "label", "mlabel",
        "emoji", "getemoji", "pressedkey", "output", "lastkey", "gethoverkeyof", "lasthoverkey", "mlasthoverkey", "oldkey",
    )
    private val logCall = Regex("""\bLog\s*\.\s*[A-Za-z]+\s*\(""")
    /** A message is exactly one string literal: no `$`, no `+`, no call, no raw string, no throwable, no second argument. */
    private val fixedLiteral = Regex("""\s*"(?:[^"\\$\n]|\\.)*"\s*""")
    /** A tag is a literal without a value, or a name / member chain (TAG, mName, javaClass.simpleName, X::class.simpleName). */
    private val tagName = Regex("""\s*(?:"(?:[^"\\$\n]|\\.)*"|[A-Za-z_]\w*(?:\s*(?:\.|::)\s*[A-Za-z_]\w*)*)\s*""")
    private val identifier = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    /** The text between the parentheses of the call that starts at [open] (the index after '('), or the rest of the file. */
    private fun callBody(text: String, open: Int): String {
        var depth = 1
        var i = open
        var inString = false
        while (i < text.length && depth > 0) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(open, maxOf(open, i - 1))
    }

    /** Splits [body] at the commas that are outside string literals and brackets. */
    private fun topLevelArguments(body: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var inString = false
        var start = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) { parts.add(body.substring(start, i)); start = i + 1 }
            }
            i++
        }
        parts.add(body.substring(start))
        return parts
    }

    private fun logLeaks(relativePath: String, text: String): List<String> {
        val problems = ArrayList<String>()
        if (relativePath != LOG_WRAPPER && text.contains("android.util.Log"))
            problems.add("P11: the platform logger (android.util.Log) is used only by the Log wrapper: found android.util.Log in $relativePath")
        if (relativePath == LOG_WRAPPER) {
            wrapperForbidden.find(text)?.let { problems.add("P11: the Log wrapper takes a tag and a fixed message only and keeps nothing: found ${it.value} in $relativePath") }
            return problems
        }
        for (m in logCall.findAll(text)) {
            val line = text.substring(0, m.range.first).count { it == '\n' } + 1
            val args = topLevelArguments(callBody(text, m.range.last + 1))
            val tagOk = tagName.matches(args[0]) && identifier.findAll(args[0]).none { it.value.lowercase() in userTextNames }
            if (args.size == 2 && tagOk && fixedLiteral.matches(args[1])) continue
            problems.add("P11: log call that is not a tag and one fixed string literal (no throwable, no value, no concatenation; " +
                "log lengths or classes by writing a different literal): found at $relativePath:$line")
        }
        return problems
    }

    // ---- native sources (app/src/main/jni): no logging of any kind (P11) ---------------------------------------------------

    private const val NATIVE_NO_LOG = "P11: native code must not log (no android.util.Log reflection, no __android_log, no printf-style output)"
    private val nativeRules = listOf(
        SourceRule("android/util/Log", NATIVE_NO_LOG), SourceRule("__android_log", NATIVE_NO_LOG), SourceRule("android/log.h", NATIVE_NO_LOG),
        SourceRule("-llog", NATIVE_NO_LOG),
        SourceRule("printf-style output (printf, fprintf, vprintf, dprintf)", NATIVE_NO_LOG, emptySet(), Regex("""\b(?:printf|fprintf|vprintf|vfprintf|dprintf)\s*\(""")),
        SourceRule("puts, fputs, perror", NATIVE_NO_LOG, emptySet(), Regex("""\b(?:puts|fputs|perror)\s*\(""")),
        SourceRule("std::cout, cerr, clog", NATIVE_NO_LOG, emptySet(), Regex("""std::(?:cout|cerr|clog)\b""")),
        SourceRule("ALOG macro", NATIVE_NO_LOG, emptySet(), Regex("""\bALOG[VDIWE]\s*\(""")),
    )

    /** Source files of the native build that [checkNative] scans (host-only tests are not part of the app). */
    fun isNativeSource(relativePath: String): Boolean =
        !relativePath.startsWith("host_test/") && relativePath.substringAfterLast('.', "") in setOf("c", "cc", "cpp", "h", "hpp", "mk", "bp")

    /** Returns one problem text for every native logging rule that [text] of the file at [relativePath] (relative to app/src/main/jni) breaks. */
    fun checkNative(relativePath: String, text: String): List<String> =
        nativeRules.filter { relativePath !in it.allowedIn }.mapNotNull { rule -> rule.find(text)?.let { "${rule.reason}: found ${rule.pattern} in jni/$relativePath" } }

    /** Returns one problem text for every rule that [text] of the file at [relativePath] breaks. */
    fun check(relativePath: String, text: String): List<String> =
        rules.filter { relativePath !in it.allowedIn }.mapNotNull { rule -> rule.find(text)?.let { "${rule.reason}: found ${rule.pattern} in $relativePath" } } +
            logLeaks(relativePath, text) + activityProblems(relativePath, text) + gesturePackageProblems(relativePath, text) + navigationPackageProblems(relativePath, text) + vimPackageProblems(relativePath, text) +
            m8StatelessProblems(relativePath, text) + voicePackageProblems(relativePath, text) + profilePackageProblems(relativePath, text) + appLockThreadProblems(relativePath, text) + mediaDownloadsPackageProblems(relativePath, text) + nextwordPackageProblems(relativePath, text)
}
