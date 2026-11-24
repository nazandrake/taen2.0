package taen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRulesTest {
    private val privacyKt = "helium314/keyboard/latin/privacy/Privacy.kt"
    private val other = "helium314/keyboard/latin/ClipboardHistoryManager.kt"

    private fun problems(path: String, text: String) = SourceRules.check(path, text)

    // ---- the storage and clipboard invariants are enforced -------------------------------------------

    @Test fun plainSharedPreferencesAreRejectedOutsideTheEncryptedAccessor() {
        val text = "val p = ctx.getSharedPreferences(\"x\", 0)"
        assertEquals(1, problems(other, text).size)
        assertTrue(problems(other, text)[0].contains("P7"))
        assertEquals(emptyList<String>(), problems(privacyKt, text)) // the legacy migration may read the old file
    }

    @Test fun systemUserDictionaryIsRejectedEverywhere() {
        assertEquals(1, problems(other, "import android.provider.UserDictionary").size)
        assertEquals(1, problems(privacyKt, "UserDictionary.Words.addWord(ctx, w, 1, null, l)").size)
    }

    // ---- every rule, its allowlist and a clean control --------------------------------------------------------

    @Test fun databasesOfUserDataAreRejected() {
        for (text in listOf("class X : SQLiteOpenHelper(ctx, \"n\", null, 1)", "ctx.openOrCreateDatabase(\"n\", 0, null)"))
            for (path in listOf(other, privacyKt))
                assertEquals("$text in $path", 1, problems(path, text).size)
    }

    @Test fun readOnlyLegacyDatabaseAccessIsAllowedOnlyInTheAccessor() {
        val text = "SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)"
        assertEquals(1, problems(other, text).size)
        assertEquals(emptyList<String>(), problems(privacyKt, text))
    }

    @Test fun preferenceManagerAndFileProviderAreRejectedEverywhere() {
        for (text in listOf("PreferenceManager.getDefaultSharedPreferences(ctx)", "class P : FileProvider()"))
            for (path in listOf(other, privacyKt))
                assertEquals("$text in $path", 1, problems(path, text).size)
    }

    @Test fun legacyFileNamesStayInTheAccessor() {
        for (text in listOf("File(dir, \"custom_background_image\")", "ctx.getDatabasePath(\"heliboard.db\")")) {
            assertEquals(text, 1, problems(other, text).size)
            assertEquals(text, emptyList<String>(), problems(privacyKt, text))
        }
    }

    @Test fun setPrimaryClipIsAllowedOnlyForCopyClearAndThemeExport() {
        val text = "cm.setPrimaryClip(clip)"
        assertEquals(1, problems(other, text).size) // the history manager must paste with commitText
        assertEquals(1, problems("helium314/keyboard/keyboard/clipboard/ClipboardHistoryView.kt", text).size)
        assertEquals(1, problems("helium314/keyboard/latin/privacy/ClipboardHistory.kt", text).size)
        for (allowed in listOf("helium314/keyboard/latin/RichInputConnection.java", "helium314/keyboard/compat/ClipboardManagerCompat.java",
            "helium314/keyboard/settings/screens/ColorsScreen.kt"))
            assertEquals(allowed, emptyList<String>(), problems(allowed, text))
    }

    // ---- learned words and the export -----------------------------------------------------------------------------

    private val binaryDictionary = "com/android/inputmethod/latin/BinaryDictionary.java"
    private val expandable = "helium314/keyboard/latin/dictionary/ExpandableBinaryDictionary.java"
    private val bundle = "helium314/keyboard/latin/privacy/ExportBundle.kt"

    @Test fun nativeDictionaryFileFlushesStayInTheEngineWrapper() { // the learned words are never flushed to a native file
        for (text in listOf("mBinaryDictionary.flushWithGC();", "binaryDictionary.flushWithGCIfHasUpdated();", "flushNative(mNativeDict, path)",
            "mBinaryDictionary.flush();", "openNative(path, 0, 0, true)", "migrateNative(d, p, 403)")) {
            val found = problems(expandable, text)
            assertEquals("$text in $expandable", 1, found.size)
            assertTrue(found[0].contains("P7"))
            assertEquals("$text in $binaryDictionary", emptyList<String>(), problems(binaryDictionary, text))
        }
        assertEquals(emptyList<String>(), problems(expandable, "dictionary.serialize(withGC)")) // the memory path is fine
        // the converter of imported word lists writes a dictionary file by design
        assertEquals(emptyList<String>(), problems("helium314/keyboard/latin/makedict/Ver4DictEncoder.java", "binaryDict.flushWithGC()"))
    }

    @Test fun streamMethodsMissingOnAndroidBelowApi33AreRejected() { // found on the reference phone: readNBytes crashed the import
        for (text in listOf("it.readNBytes(n)", "stream.readAllBytes()", "input.transferTo(out)"))
            assertEquals(text, 1, problems(other, text).size)
        assertEquals(emptyList<String>(), problems(other, "input.readAtMost(n)"))
    }

    @Test fun noZipWriterOutsideTheExportPayloadNotEvenForDiagnostics() { // the crash-report zip in SettingsActivity is gone
        val text = "val z = ZipOutputStream(bos)"
        assertEquals(1, problems("helium314/keyboard/settings/SettingsActivity.kt", text).size)
        assertEquals(1, problems("helium314/keyboard/settings/screens/AdvancedScreen.kt", text).size)
    }

    @Test fun plainZipOutputIsAllowedOnlyForTheEncryptedExportPayload() { // no plain backup zip
        val text = "val z = ZipOutputStream(out)"
        assertEquals(1, problems("helium314/keyboard/settings/preferences/BackupRestorePreference.kt", text).size)
        assertEquals(1, problems(other, text).size)
        assertTrue(problems(other, text)[0].contains("P16"))
        assertEquals(emptyList<String>(), problems(bundle, text)) // the payload is built in memory and encrypted by ExportCodec
    }

    @Test fun plainZipInputIsAllowedOnlyForTheDecryptedExportPayload() { // no restore from a plain zip either
        val text = "ZipInputStream(inputStream).use { zip -> }"
        assertEquals(1, problems("helium314/keyboard/settings/preferences/BackupRestorePreference.kt", text).size)
        assertEquals(1, problems(other, text).size)
        assertTrue(problems(other, text)[0].contains("P16"))
        assertEquals(emptyList<String>(), problems(bundle, text))
    }

    @Test fun learnedWordFilesAreNotNamedOutsideTheirOwner() { // the old readable file name only appears in the legacy mover
        val text = "File(filesDir, \"UserHistoryDictionary.en.dict\")"
        assertEquals(1, problems(other, text).size)
        assertEquals(emptyList<String>(), problems("helium314/keyboard/latin/personalization/UserHistoryDictionary.java", text))
        assertEquals(emptyList<String>(), problems("helium314/keyboard/latin/personalization/PersonalizationHelper.java", text))
        assertEquals(emptyList<String>(), problems("helium314/keyboard/latin/privacy/Wiper.kt", text)) // the wipe deletes that leftover too
    }

    // ---- ----

    @Test fun anyDictionaryFlushIsRejectedOutsideTheEngineWrapper() { // the rule once matched only "inaryDictionary.flush()"
        for (text in listOf("mDictionary.flush()", "dict.flush();", "userHistoryDictionary.flush()", "getBinaryDictionary().flush()",
            "mBinaryDictionary\n    .flush()", "dictionary.flushWithGCIfHasUpdated()", "binaryDict.flush ()")) {
            val found = problems(expandable, text)
            assertEquals("$text in $expandable", 1, found.size)
            assertTrue(found[0].contains("P7"))
            assertEquals(text, emptyList<String>(), problems(binaryDictionary, text))
        }
        assertEquals(emptyList<String>(), problems(expandable, "out.flush()")) // a stream is not a dictionary
    }

    @Test fun fileWritersAreRejectedOutsideTheirOwners() { // user data must not be written to a plain file
        val texts = listOf("FileOutputStream(f)", "ctx.openFileOutput(\"n\", 0)", "File(d, \"n\").writeText(s)", "f.writeBytes(b)",
            "f.appendText(s)", "FileWriter(f)", "Files.write(p, b)", "f.outputStream().use { }", "f.bufferedWriter()")
        for (text in texts) {
            val found = problems(other, text)
            assertEquals("$text in $other", 1, found.size)
            assertTrue(found[0].contains("P7"))
            assertEquals(text, emptyList<String>(), problems("helium314/keyboard/latin/privacy/SecureStore.kt", text)) // the encrypted store
        }
        assertEquals(emptyList<String>(), problems(other, "val x = f.readBytes()")) // reading is not writing
    }

    @Test fun knownFileWritersThatHoldNoTypedTextAreListedByPath() {
        // imported layouts, fonts and blacklists; custom layout editor; the word-list converter
        for (path in listOf("helium314/keyboard/latin/privacy/ExportService.kt", "helium314/keyboard/settings/dialogs/LayoutEditDialog.kt",
            "helium314/keyboard/latin/makedict/DictIO.kt"))
            assertEquals(path, emptyList<String>(), problems(path, "f.writeText(s)\nFileOutputStream(f)"))
    }

    @Test fun diagnosticsAreNotAFileWriterOwner() { // DebugFlags wrote the crash reports (stack trace and log lines) as plain files
        for (path in listOf("helium314/keyboard/latin/define/DebugFlags.kt", "helium314/keyboard/latin/utils/Log.kt",
            "helium314/keyboard/settings/SettingsActivity.kt", "helium314/keyboard/latin/App.kt"))
            assertEquals(path, 2, problems(path, "f.appendText(s)\nFileWriter(f)").size)
    }

    @Test fun userTextInALogCallIsRejected() { // RichInputConnection once logged the typed and the editor text
        val java = listOf(
            "Log.w(TAG, \"did set \" + text + \", but got \" + mIC.getTextBeforeCursor(text.length(), 0) + \" as last character\");",
            "Log.d(TAG, \"--- word: \\\"\" + tempWord + \"\\\"\");",
            "Log.e(TAG, \"Cannot add unigram entry. word: \" + word);",
            "Log.d(TAG, \"commitChosenWord() : [\" + chosenWord + \"]\");",
            "Log.e(TAG, \"unknown event, key code: \"+keyCode+\", codepoint \"+event.getCodePoint());",
            "Log.d(TAG, \"onKeyDown(): \" + keyEvent);",
            "Log.d(TAG, \"onPress: \" + Constants.printableCode(key.getCode()));",
            "Log.w(TAG, \"x\"\n    + text);", // the call continues on the next line
            "android.util.Log.d(TAG, \"x\")", // the raw logger is only used by the wrapper
        )
        val kotlin = listOf(
            "Log.i(TAG, \"onGetSuggestionsInternal() : [\$text] is a valid word\")",
            "Log.e(TAG, \"Exception while trying to add word \\\"\${word}\\\" to blacklist\", e)",
            "Log.d(TAG, \"onPressKey: code=\${Constants.printableCode(code)}\")",
            "Log.d(TAG, \"adding \" + wordProperty.mWord)",
        )
        for (text in java + kotlin) {
            val found = problems(other, text)
            assertEquals("$text in $other", 1, found.size)
            assertTrue(found[0], found[0].contains("P11") && found[0].contains("log") || found[0].contains("android.util.Log"))
        }
    }

    @Test fun onlyAFixedStringLiteralIsAcceptedAsLogMessage() { // no throwable, no extra argument, no value in any log call
        val rejected = listOf(
            "Log.w(TAG, \"x\", e)", "Log.e(TAG, \"x\", e);", "Log.d(TAG, \"x\", throwable)", // a throwable: its message and trace can hold text
            "Log.e(TAG, e.toString())", "Log.e(TAG, e.message!!)", "Log.d(TAG, msg)", "Log.wtf(TAG, s)", // not a literal (wtf: three letters)
            "Log.d(TAG, \"a\" + \"b\")", "Log.d(TAG, \"a\".plus(b))", "Log.d(TAG, String.format(\"x\"))", "Log.d(TAG, \"x\" + n)",
            "Log.d(TAG, \"n=\$n\")", "Log.d(TAG, \"n=\${list.size}\")", "Log.d(TAG, \"\"\"x\"\"\")", // a value inside the literal, a raw string
            "Log.d(TAG)", "Log.d(TAG, \"x\", \"y\")", "Log.v(\"\$a\", \"x\")", "Log.getLog(10)", "Log.d(word, \"x\")", // arity, tag with a value, the old reader
        )
        for (text in rejected) {
            val found = problems(other, text)
            assertEquals("$text in $other", 1, found.size)
            assertTrue(found[0], found[0].contains("P11"))
        }
    }

    @Test fun fixedLiteralLogCallsAreAccepted() { // positive controls for the rule above
        for (text in listOf(
            "Log.d(TAG, \"committing text\");", "Log.w(\"BackupRestore\", \"restore failed (see settings), retry\")",
            "Log.e(javaClass.simpleName, \"Unknown hover event\")", "Log.i(SettingsSubtype::class.simpleName, \"x\")",
            "Log.d(TAG, \"quote \\\" and a close paren ) in a literal\")", "Log.d(\n    TAG,\n    \"split over lines\"\n)", "Log.wtf(TAG, \"bad state\")",
            "val textOnly = text + word // not a log call",
        ))
            assertEquals(text, emptyList<String>(), problems(other, text))
        // the wrapper itself calls the raw logger, with the two arguments it gets
        assertEquals(emptyList<String>(), problems("helium314/keyboard/latin/utils/Log.kt",
            "object Log { @JvmStatic fun d(tag: String?, message: String) { android.util.Log.d(tag, message) } }"))
    }

    @Test fun theLogWrapperKeepsNothingAndTakesNoThrowable() { // no in-memory log, no exception overloads
        val wrapper = "helium314/keyboard/latin/utils/Log.kt"
        for (text in listOf("fun e(tag: String?, message: String, e: Throwable?) { android.util.Log.e(tag, message, e) }",
            "private val logLines: MutableList<LogLine> = ArrayList(2000)", "fun getLog() = logLines", "x.stackTraceToString()",
            "class LogLine(val message: String)", "val sb = StringBuilder()")) {
            val found = problems(wrapper, text)
            assertTrue("$text: $found", found.any { it.contains("P11") })
        }
    }

    @Test fun diagnosticsSinksAreRejectedEverywhere() { // crash handler, stack traces, console output, external storage, crash files
        val paths = listOf(other, "helium314/keyboard/latin/define/DebugFlags.kt", "helium314/keyboard/latin/utils/ExecutorUtils.java",
            "helium314/keyboard/latin/utils/Log.kt", "helium314/keyboard/settings/SettingsActivity.kt")
        for (text in listOf("Thread.setDefaultUncaughtExceptionHandler(this)", "Thread.setDefaultUncaughtExceptionHandler(h);",
            "e.printStackTrace()", "e.printStackTrace(PrintWriter(sw))", "System.out.println(x)", "System.err.println(x)",
            "val s = e.stackTraceToString()", "android.util.Log.getStackTraceString(e)", "ctx.getExternalFilesDir(null)",
            "context.externalCacheDir", "context.getExternalCacheDir()", "Environment.getExternalStorageDirectory()", "ctx.getExternalMediaDirs()",
            "File(dir, \"crash_report_x.txt\")"))
            for (path in paths) {
                val found = problems(path, text)
                assertTrue("$text in $path: $found", found.any { it.contains("P11") })
            }
        assertEquals(emptyList<String>(), problems(other, "val d = ctx.filesDir; val t = e.javaClass.simpleName")) // clean control
    }

    @Test fun cleanCodeHasNoProblems() {
        val text = "val p = context.prefs()\nclipboardManager.clearPrimaryClip()\nval d = Privacy.personalDictionary(ctx)\nclass UserBinaryDictionary"
        assertEquals(emptyList<String>(), problems(other, text))
    }

    @Test fun theEarlierRulesStillApply() { // P1, P2, P6 and the gesture-data rules
        for (text in listOf("System.load(x)", "DexClassLoader(a)", "ContactsContract.Contacts", "GestureDataGathering.x", "java.net.Socket", "WebView(ctx)",
            "getInstalledPackages(0)", "QUERY_ALL_PACKAGES"))
            assertEquals(text, 1, problems(other, text).size)
    }

    @Test fun oneTextCanBreakSeveralRulesAndEachIsReported() {
        val text = "ctx.getSharedPreferences(\"a\", 0)\nUserDictionary.Words.CONTENT_URI"
        assertEquals(2, problems(other, text).size)
    }

    @Test fun providedPermissionNamesAreRejected() {
        assertTrue(SourceRules.forbiddenPermissions.contains("android.permission.READ_USER_DICTIONARY"))
        assertTrue(SourceRules.forbiddenPermissions.contains("android.permission.WRITE_USER_DICTIONARY"))
        assertTrue(SourceRules.forbiddenPermissions.contains("android.permission.INTERNET"))
        assertTrue(SourceRules.forbiddenPermissions.contains("android.permission.READ_CONTACTS"))
    }

    // ---- aliases of the logger, media writes, native logging --------------------------------------

    @Test fun logAliasesAndStaticImportsAreRejected() { // calls through an alias are not seen by the Log-call rule
        for (text in listOf("import helium314.keyboard.latin.utils.Log as L", "import android.util.Log as AndroidLog", "typealias L = Log",
            "val l = Log", "val logger = helium314.keyboard.latin.utils.Log\nlogger.d(TAG, \"x\")", "private val f = Log::d", "list.forEach(Log::d)",
            "import helium314.keyboard.latin.utils.Log.i", "import static helium314.keyboard.latin.utils.Log.d;", "import helium314.keyboard.latin.utils.Log.*")) {
            val found = problems(other, text)
            assertTrue("$text: $found", found.any { it.contains("P11") })
        }
        // positive controls: the normal import and a fixed call, a name that only ends in Log, a property named log
        for (text in listOf("import helium314.keyboard.latin.utils.Log", "Log.d(TAG, \"x\")", "val x = ChangeLog", "val y = Catalog.Log2", "val z = log"))
            assertEquals(text, emptyList<String>(), problems(other, text))
    }

    @Test fun mediaStoreAndContentResolverInsertAreRejected() { // a write to shared storage outside our files
        for (text in listOf("val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)", "import android.provider.MediaStore",
            "ctx.contentResolver.insert(uri, values)", "getContentResolver().insert(uri, values);", "resolver.insert(uri, v) // ContentResolver", "context.getContentResolver()\n    .insert(u, v)")) {
            val found = problems(other, text)
            assertTrue("$text: $found", found.any { it.contains("P11") })
        }
        // positive controls: reading and the user's own SAF stream are not this rule
        // (the stream itself belongs to FileUtils, a separate rule; the P11 rule still does not flag it)
        assertEquals(emptyList<String>(), problems(other, "contentResolver.openOutputStream(uri)").filter { it.startsWith("P11") })
        assertEquals(emptyList<String>(), problems(other, "list.insert(1, x)"))
    }

    // ---- native sources (app/src/main/jni) ------------------------------------------------------------------------------

    // ---- anti-snooping controls -------------------------------------------------------------------------------------

    private val policy = "helium314/keyboard/latin/privacy/PrivacyPolicy.kt"
    private val secureWindow = "helium314/keyboard/latin/privacy/SecureWindow.kt"

    @Test fun everyActivityMustCallSecureWindowApply() { // (P12): an activity without it can be screenshotted
        val kotlinActivity = "class PinActivity : ComponentActivity() {\n override fun onCreate(b: Bundle?) { super.onCreate(b) }\n}"
        val found = problems("helium314/keyboard/settings/PinActivity.kt", kotlinActivity)
        assertEquals(found.toString(), 1, found.size)
        assertTrue(found[0].contains("P12"))
        assertEquals(emptyList<String>(), problems("helium314/keyboard/settings/PinActivity.kt", kotlinActivity.replace("super.onCreate(b)", "super.onCreate(b); SecureWindow.apply(window)")))
    }

    @Test fun everyKindOfActivityBaseClassIsRecognised() {
        for (base in listOf("ComponentActivity()", "Activity()", "AppCompatActivity()", "FragmentActivity()", "android.app.Activity()", "ComponentActivity ()")) {
            val text = "class A : $base { }"
            assertEquals(text, 1, problems("x/A.kt", text).count { it.contains("P12") })
        }
        assertEquals(1, problems("x/A.java", "public class A extends Activity { }").count { it.contains("P12") })
        assertEquals(1, problems("x/A.java", "public final class A extends AppCompatActivity { }").count { it.contains("P12") })
        // controls: a subclass of an app activity inherits the flag; ordinary classes and mentions are fine
        for (text in listOf("class SettingsActivity2 : SettingsActivity()", "val a = ctx.getActivity() as? SettingsActivity", "class ActivityLog", "// extends Activity"))
            assertEquals(text, 0, problems("x/B.kt", text).count { it.contains("P12") })
        assertEquals(0, problems("x/A.java", "public class A extends Activity { void f() { SecureWindow.INSTANCE.apply(getWindow()); } }").count { it.contains("P12") })
    }

    @Test fun flagSecureIsOwnedBySecureWindow() { // P12
        for (text in listOf("window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)", "window.clearFlags(FLAG_SECURE)"))
            assertEquals(text, 1, problems(other, text).count { it.contains("P12") })
        assertEquals(emptyList<String>(), problems(secureWindow, "window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)"))
    }

    @Test fun noAccessibilityServiceAndNoOverlayWindowOfOurOwn() { // P13, P14
        for (text in listOf("class Spy : AccessibilityService() {", "public class Spy extends AccessibilityService {", "import android.accessibilityservice.AccessibilityService\n"))
            assertEquals(text, 1, problems(other, text).count { it.contains("P13") })
        for (text in listOf("val info: AccessibilityServiceInfo? = null", "import android.accessibilityservice.AccessibilityServiceInfo", "AccessibilityServiceConnection"))
            assertEquals(text, 0, problems("helium314/keyboard/latin/privacy/A11yWatch.kt", text).count { it.contains("P13") })
        for (text in listOf("lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY", "TYPE_SYSTEM_ALERT", "windowManager.addView(v, lp)"))
            assertEquals(text, 1, problems(other, text).count { it.contains("P14") })
        assertTrue(SourceRules.forbiddenPermissions.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        assertTrue(SourceRules.forbiddenPermissions.contains("android.permission.SYSTEM_ALERT_WINDOW"))
    }

    @Test fun thePrivacyDecisionsHaveOneOwner() { // P9: no other code reads the flag, the password variations or the app list
        for (text in listOf("(editorInfo.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0", "imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING"))
            for (path in listOf(other, "helium314/keyboard/latin/InputAttributes.java", privacyKt)) {
                assertEquals("$text in $path", 1, problems(path, text).count { it.contains("P9") })
            }
        assertEquals(emptyList<String>(), problems(policy, "(imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0"))
        for (text in listOf("InputTypeUtils.isAnyPasswordInputType(t)", "InputTypeUtils.isPasswordInputType(t)", "InputTypeUtils.isVisiblePasswordInputType(t)"))
            assertEquals(text, 1, problems(other, text).count { it.contains("P9") })
        // the layout and spoken-feedback code uses the password variation for other reasons (a different key, no speech): allowed
        for (path in listOf(policy, "helium314/keyboard/latin/utils/InputTypeUtils.java", "helium314/keyboard/latin/InputAttributes.java",
            "helium314/keyboard/keyboard/KeyboardId.kt", "helium314/keyboard/accessibility/AccessibilityUtils.kt"))
            assertEquals(path, 0, problems(path, "InputTypeUtils.isAnyPasswordInputType(t)").count { it.contains("P9") })
        // the per-app list is read through the policy only
        assertEquals(1, problems(other, "prefs.getString(Settings.PREF_INCOGNITO_APPS, \"\")").count { it.contains("P9") })
        assertEquals(0, problems("helium314/keyboard/settings/screens/PrivacyScreen.kt", "appPrefs.getString(Settings.PREF_INCOGNITO_APPS, \"\")").count { it.contains("P9") })
        assertEquals(0, problems("helium314/keyboard/latin/settings/SettingsValues.java", "getString(Settings.PREF_INCOGNITO_APPS, Defaults.PREF_INCOGNITO_APPS)").count { it.contains("P9") })
    }

    @Test fun theSelfDestructHasOwnersForKeyDeletionAndProcessKill() { // only the wipe, the data keys and the Keystore wrapper delete keys; only the wipe and the two restarts kill the process
        val selfDestruct = "helium314/keyboard/latin/privacy/SelfDestruct.kt"
        for (text in listOf("vault.deleteKey(Tier.CE)", "ports.vault.deleteKey(t)"))
            assertEquals(text, 1, problems(other, text).count { it.startsWith("self-destruct:") })
        for (path in listOf(selfDestruct, "helium314/keyboard/latin/privacy/DataKeys.kt"))
            assertEquals(path, 0, problems(path, "vault.deleteKey(tier)").count { it.startsWith("self-destruct:") })
        assertEquals(1, problems(other, "keyStore.deleteEntry(alias)").count { it.startsWith("self-destruct:") })
        assertEquals(1, problems(selfDestruct, "keyStore.deleteEntry(alias)").count { it.startsWith("self-destruct:") })
        assertEquals(0, problems("helium314/keyboard/latin/privacy/AndroidKeyVault.kt", "keyStore.deleteEntry(tier.alias)").count { it.startsWith("self-destruct:") })
        assertEquals(1, problems(other, "Process.killProcess(Process.myPid())").count { it.startsWith("self-destruct:") })
        for (path in listOf(selfDestruct, "helium314/keyboard/latin/SystemBroadcastReceiver.java", "helium314/keyboard/latin/LatinIME.java"))
            assertEquals(path, 0, problems(path, "Process.killProcess(myPid);").count { it.startsWith("self-destruct:") })
        // the declaration of the interface method is not a call
        assertEquals(0, problems("helium314/keyboard/latin/privacy/KeyVault.kt", "    fun deleteKey(tier: Tier)").count { it.startsWith("self-destruct:") })
    }

    @Test fun everyWayToEndTheProcessIsRejectedOutsideTheOwners() { // Runtime.exit in DebugScreen was not matched
        val selfDestruct = "helium314/keyboard/latin/privacy/SelfDestruct.kt"
        val screen = "helium314/keyboard/settings/screens/DebugScreen.kt"
        val enders = listOf(
            "Process.killProcess(Process.myPid())", "android.os.Process.killProcess(pid)", "import android.os.Process.killProcess",
            "Runtime.getRuntime().exit(0)", "Runtime.getRuntime().halt(1)", "Runtime.getRuntime()\n        .exit(0)", "Runtime.getRuntime().\n  halt(0);",
            "val r = Runtime.getRuntime()\nr.exit(0)", "System.exit(0);", "System . exit (2)", "kotlin.system.exitProcess(1)", "exitProcess(0)", "import kotlin.system.exitProcess",
            "Process.sendSignal(pid, Process.SIGNAL_KILL)", "android.os.Process.sendSignal(Process.myPid(), 9)",
        )
        for (text in enders) {
            val found = problems(screen, text).count { it.startsWith("self-destruct:") }
            assertEquals("not rejected in a screen: $text", 1, found)
            assertEquals("$text in the wipe", 0, problems(selfDestruct, text).count { it.startsWith("self-destruct:") }) // positive control: the owner may
        }
        for (text in listOf("activity.finish()", "val exitTransition = fade", "fun exitAnimation() = Unit", "onExit(item)", "systemExit = 1", "stopHalting()", "// the process ends"))
            assertEquals("wrongly rejected: $text", 0, problems(screen, text).count { it.startsWith("self-destruct:") })
    }

    private fun native(path: String, text: String) = SourceRules.checkNative(path, text)

    @Test fun nativeLoggingIsRejected() {
        for (text in listOf("env->FindClass(\"android/util/Log\");", "__android_log_print(ANDROID_LOG_INFO, TAG, \"x\");", "#include <android/log.h>",
            "printf(\"%s\\n\", word);", "fprintf(stderr, \"x\");", "std::printf(\"x\");", "vprintf(f, args);", "puts(buf);", "std::cout << x;", "std::cerr << x;",
            "LOCAL_LDFLAGS += -llog", "__android_log_write(6, t, m);", "ALOGE(\"x\");", "dprintf(2, \"x\");")) {
            val found = native("src/utils/log_utils.cpp", text)
            assertEquals("$text: $found", 1, found.size)
            assertTrue(found[0], found[0].contains("P11"))
        }
        // positive controls: string formatting into a buffer is not logging; ordinary code; names that merely contain the words
        for (text in listOf("snprintf(buf, n, \"%d\", v);", "vsnprintf(b, n, f, a);", "int v = 1;", "const char *blogName;", "// the printf family is not used"))
            assertEquals(text, emptyList<String>(), native("src/utils/char_utils.cpp", text))
    }

    @Test fun nativeLogUtilsKeepsNoJavaReflection() { // the old logToJava reflected into android.util.Log
        val reflecting = "const jclass c = env->FindClass(\"android/util/Log\"); env->GetStaticMethodID(c, \"i\", \"(Ljava/lang/String;Ljava/lang/String;)I\");"
        assertTrue(native("src/utils/log_utils.cpp", reflecting).isNotEmpty())
        assertEquals(emptyList<String>(), native("src/utils/log_utils.cpp", "void LogUtils::logToJava(JNIEnv *const, const char *const, ...) {}"))
    }

    // ---- Space-bar navigation: the navigation package never stores, logs, copies or sends field text ---------------------

    private val multiCursor = "helium314/keyboard/latin/navigation/MultiCursor.kt"

    @Test fun theNavigationPackageHasNoStorageLoggingClipboardOrNetwork() {
        for (text in listOf("import java.io.File", "val f = java.io.FileOutputStream(p)", "Log.d(TAG, \"x\")", "println(text)", "val p = Privacy.prefs",
            "ctx.getSharedPreferences(\"n\", 0)", "import java.net.Socket", "val c = ClipData.newPlainText(a, b)", "cm as ClipboardManager")) {
            val found = problems(multiCursor, text).filter { it.startsWith("navigation:") }
            assertEquals("$text: $found", 1, found.size)
        }
    }

    @Test fun theNavigationRuleOnlyAppliesToThatPackageAndLetsOrdinaryCodeThrough() {
        // positive controls: the same text elsewhere is no navigation problem; clean navigation code passes
        assertTrue(problems("helium314/keyboard/latin/ClipboardHistoryManager.kt", "val c = ClipData.newPlainText(a, b)").none { it.startsWith("navigation:") })
        assertEquals(emptyList<String>(), problems(multiCursor, "class FieldText(val text: CharSequence, val startOffset: Int)\nfun type(insert: String) = edit(insert)"))
    }

    // ---- Vim layer: the vim package works on field text in memory and has no Android, file, network, clipboard, log or storage class ---

    private val vimEngine = "helium314/keyboard/latin/vim/VimEngine.kt"

    @Test fun theVimPackageHasNoStorageLoggingClipboardNetworkOrAndroidClass() {
        for (text in listOf("import java.io.File", "val f = java.io.FileOutputStream(p)", "Log.d(TAG, \"x\")", "println(text)", "val p = Privacy.prefs", "val s = Settings.getInstance()",
            "ctx.getSharedPreferences(\"n\", 0)", "import java.net.Socket", "val c = ClipData.newPlainText(a, b)", "cm as ClipboardManager", "import android.content.Context",
            "System.out.println(register)", "e.printStackTrace()", "val x = SecureStore(f)",
            // every other writer to stdout or stderr (Kotlin print reaches logcat through System.out)
            "print(registerText)", "print (registerText)", "kotlin.io.print(registerText)", "list.forEach(::print)", "System.err.print(x)", "System.console()?.printf(x)", "System.setOut(null)",
            "val w = PrintStream(o)", "val w = PrintWriter(o)", "Thread.dumpStack()", "val l = java.util.logging.Logger.getLogger(\"v\")", "val l = Logger.getLogger(\"v\")")) {
            val found = problems(vimEngine, text).filter { it.startsWith("vim:") }
            assertEquals("$text: $found", 1, found.size)
        }
    }

    /** The vim package is an allow list: planted violations that no deny list named (a process that runs `log`, reflection, other imports). */
    @Test fun theVimPackageMayUseOnlyKotlinItsOwnPackageAndTwoPureJavaClasses() {
        for (text in listOf(
            "ProcessBuilder(\"log\", \"-t\", \"v\", registerText).start()", "java.lang.ProcessBuilder(\"log\", registerText).start()",
            "Runtime.getRuntime().exec(arrayOf(\"log\", registerText))", "val p = Runtime . getRuntime()", "import java.io.File", "import java.io.*",
            "android.util.Log.d(\"v\", registerText)", "val c = Class.forName(\"android.util.Log\")", "val c = Class . forName(n)",
            "import java.lang.reflect.Method", "val k = VimBuf::class.java", "val k = VimBuf :: class . java", "val k = x.javaClass", "val k = x.getClass()",
            "Thread { register = null }.start()", "val l = ClassLoader.getSystemClassLoader()", "val t = System.currentTimeMillis()", "System.exit(1)",
            "import kotlin.io.println", "import kotlin.io.*", "import kotlin.system.exitProcess", "import kotlin.concurrent.thread", "import kotlin.reflect.full.memberFunctions",
            "val x = kotlin.io.readLine()", "val line = readLine()", "val line = readln()", "f.invoke(registerText)", "f?.invoke(registerText)",
            "import org.example.Sink", "import helium314.keyboard.latin.utils.Sink", "import helium314.keyboard.latin.common.Constants", "import okhttp3.OkHttpClient",
            "val s = javax.crypto.Cipher.getInstance(a)", "val s = dalvik.system.DexClassLoader(a, b, c, d)", "val s = androidx.core.util.Pair(a, b)",
            "val s = jdk.internal.misc.Unsafe.getUnsafe()", "import java.util.concurrent.Executors", "val d = java.util.Date()", "import java.text.Normalizer",
            "val r = foo.java", "package helium314.keyboard.latin.vim; import okhttp3.OkHttpClient", "kotlin.system.exitProcess(1)", "val f = kotlin.io.path.Path(x)",
            "val s = org.example.Sink()", "helium314.keyboard.latin.utils.Sink.send(t)", "val p = Process.myPid()", "val k: KClass<*> = x",
            // backticked segments are the same names (the lint strips them; the bytecode guard does not care about spelling at all)
            "`java`.io.RandomAccessFile(\"/data/data/x/files/r\", \"rw\").write(registerText.toByteArray())", "import `java`.io.File", "`kotlin`.io.println(x)",
            "val s = `android`.util.Log.d(a, b)", "`java`.lang.`ProcessBuilder`(\"log\", t).start()", "val c = `Class`.forName(n)",
        )) {
            val found = problems(vimEngine, text).filter { it.startsWith("vim:") }
            assertEquals("$text: $found", 1, found.size)
        }
        // positive controls: what the engine may use stays clean
        for (text in listOf(
            "import kotlin.math.max", "import kotlin.collections.ArrayList", "import kotlin.*", "val m = kotlin.math.abs(x)", "package helium314.keyboard.latin.vim",
            "import helium314.keyboard.latin.vim.VimBuf", "val n = helium314.keyboard.latin.vim.VimNav.LEFT", "import java.text.BreakIterator", "import java.util.Locale",
            "val i = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)", "import java.util.Locale as L",
            "val systematic = processed + threshold + runtimeOf(x)", "val readLineCount = 1 // threads are not needed; the system stays out", "class VimBuf(val text: String) { fun next(p: Int) = p + 1 }",
            "val n = person.org.name + node.com.size", // members that only look like a package
            "val m = `kotlin`.math.abs(x)", "fun `a test name`() = 1", "val `is` = 1",
        )) assertEquals(text, emptyList<String>(), problems(vimEngine, text))
        // the rule is for the vim package only
        for (text in listOf("ProcessBuilder(\"log\", t).start()", "import org.example.Sink", "val k = x.javaClass")) assertTrue(text, problems("helium314/keyboard/keyboard/VimInput.kt", text).none { it.startsWith("vim:") })
        assertEquals(emptyList<String>(), problems("helium314/keyboard/latin/vim/VimText.kt", "import kotlin.math.min\nimport java.text.BreakIterator\nimport java.util.Locale"))
    }

    @Test fun theVimRuleOnlyAppliesToThatPackageAndLetsOrdinaryCodeThrough() {
        // positive controls: the same text elsewhere is no vim problem; clean vim code (a register as a plain String, a text buffer) passes
        assertTrue(problems("helium314/keyboard/keyboard/VimInput.kt", "import android.text.InputType\nval c = Settings.getInstance()").none { it.startsWith("vim:") })
        assertEquals(emptyList<String>(), problems(vimEngine, "private var registerText: String? = null\nclass VimBuf(val text: String) { fun next(p: Int) = p + 1 }"))
        assertEquals(emptyList<String>(), problems(vimEngine, "val printable = blueprint.pending\nfun printed() = 1 // words that only contain the letters are no writer"))
        assertEquals(emptyList<String>(), problems("helium314/keyboard/latin/vim/VimText.kt", "import java.text.BreakIterator\nimport java.util.Locale"))
    }

    // ---- Glide typing: the gesture package never stores, logs or sends a gesture ----------------------------------------

    private val engine = "helium314/keyboard/latin/gesture/GlideEngine.kt"
    private val suggester = "helium314/keyboard/latin/gesture/GlideSuggester.kt"

    @Test fun theGesturePackageHasNoStorageLoggingOrNetwork() {
        for (text in listOf("import java.io.File", "val f = java.io.FileOutputStream(p)", "import java.nio.file.Files", "Log.d(TAG, \"x\")", "import helium314.keyboard.latin.utils.Log",
            "println(xs[0])", "val p = Privacy.prefs", "ctx.getSharedPreferences(\"g\", 0)", "import java.net.Socket", "val r = File(dir, \"g\")")) {
            for (path in listOf(engine, suggester)) {
                val found = problems(path, text).filter { it.startsWith("gesture:") }
                assertEquals("$text in $path: $found", 1, found.size)
            }
        }
    }

    @Test fun theGesturePackageRuleOnlyAppliesToThatPackageAndLetsOrdinaryCodeThrough() {
        // positive controls: the same text elsewhere is not a gesture-package problem; clean engine code passes
        assertTrue(problems("helium314/keyboard/latin/Suggest.kt", "import java.io.File").none { it.startsWith("gesture:") })
        assertEquals(emptyList<String>(), problems(engine, "val cost = shape * 0.5f + hypot(dx, dy)\nclass IntList { var data = IntArray(4) }"))
        // a name that only contains one of the words is fine
        assertEquals(emptyList<String>(), problems(engine, "val profile = 1\nval blog = 2\nval catalog = 3"))
    }

    // ---- the emoji search and the smart quotes work on public data and on one character; they keep and send nothing ----------

    private val m8Files = listOf(
        "helium314/keyboard/keyboard/emoji/EmojiSearchIndex.kt",
        "helium314/keyboard/latin/dictionary/EmojiKeywordDictionary.kt",
        "helium314/keyboard/latin/inputlogic/SmartQuotes.kt",
    )

    @Test fun theEmojiSearchAndSmartQuoteClassesHaveNoStorageLoggingOrNetwork() {
        for (text in listOf("import java.io.File", "val f = java.io.FileWriter(p)", "import java.nio.file.Files", "Log.d(TAG, \"x\")", "import helium314.keyboard.latin.utils.Log",
            "println(query)", "val p = Privacy.prefs", "ctx.getSharedPreferences(\"e\", 0)", "import java.net.Socket", "val r = File(dir, \"e\")", "ctx.prefs().edit()", "SecureStore(x)")) {
            for (path in m8Files) {
                val found = problems(path, text).filter { it.startsWith("emoji search:") }
                assertEquals("$text in $path: $found", 1, found.size)
            }
        }
    }

    @Test fun theM8RuleOnlyAppliesToThoseFilesAndLetsTheirOwnCodeThrough() {
        assertTrue(problems("helium314/keyboard/keyboard/emoji/EmojiPageKeyboardView.kt", "import java.io.File").none { it.startsWith("emoji search:") })
        assertTrue(problems("helium314/keyboard/latin/inputlogic/InputLogic.java", "Log.d(TAG, \"x\")").none { it.startsWith("emoji search:") })
        val clean = "val tokens = query.trim().lowercase(locale).split(WHITESPACE)\ncontext.assets.open(\"emoji_search/en.txt\").bufferedReader(Charsets.UTF_8).use { it.readText() }"
        for (path in m8Files) assertEquals(emptyList<String>(), problems(path, clean))
        // names that only contain one of the words
        for (path in m8Files) assertEquals(emptyList<String>(), problems(path, "val profile = 1\nval blog = 2\nval catalog = 3"))
    }

    // ---- Voice typing (P20): the microphone has one owner, the audio and the recognised text are kept in memory and sent nowhere --------

    private val voice = "helium314/keyboard/latin/voice/"
    private val mic = "${voice}MicCapture.kt"
    private val session = "${voice}VoiceSession.kt"
    private val modelStore = "${voice}VoiceModelStore.kt"
    private val permission = "${voice}VoicePermissionActivity.kt"
    private val ort = "${voice}engine/OrtParakeet.kt"
    private val fbank = "${voice}engine/Fbank.kt"

    @Test fun onlyMicCaptureTouchesAudioRecord() {
        val text = "val r = AudioRecord(6, 16000, 16, 2, 4096)"
        assertEquals(emptyList<String>(), problems(mic, text).filter { it.startsWith("P20:") }) // positive control: the owner may
        for (path in listOf(session, other, "helium314/keyboard/latin/VoiceTyping.kt", ort))
            assertEquals("$path", 1, problems(path, text).count { it.startsWith("P20:") })
    }

    @Test fun mediaRecorderIsForbiddenEverywhereBecauseItWritesFiles() {
        val text = "val r = MediaRecorder()"
        for (path in listOf(mic, session, other)) assertEquals("$path", 1, problems(path, text).count { it.startsWith("P20:") })
    }

    @Test fun theMicrophonePermissionIsNamedOnlyInThePermissionActivity() {
        val text = "ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)"
        assertEquals(emptyList<String>(), problems(permission, text).filter { it.startsWith("P20:") })
        for (path in listOf(mic, session, other, "helium314/keyboard/latin/VoiceTyping.kt"))
            assertEquals("$path", 1, problems(path, text).count { it.startsWith("P20:") })
    }

    @Test fun onnxRuntimeIsUsedOnlyByTheEngineWrapperAndNeverWritesAFile() {
        val text = "import ai.onnxruntime.OrtSession"
        assertEquals(emptyList<String>(), problems(ort, text).filter { it.startsWith("P20:") })
        for (path in listOf(fbank, session, other, "helium314/keyboard/latin/VoiceTyping.kt")) assertEquals("$path", 1, problems(path, text).count { it.startsWith("P20:") })
        for (call in listOf("opts.setOptimizedModelFilePath(p)", "opts.enableProfiling(p)")) // both make ONNX Runtime write a file
            for (path in listOf(ort, other)) assertEquals("$call in $path", 1, problems(path, call).count { it.startsWith("P20:") })
    }

    @Test fun theVoicePackageHasNoStorageLoggingOrNetworkExceptTheModelStore() {
        for (text in listOf("import java.io.File", "val f = java.io.FileOutputStream(p)", "import java.nio.file.Files", "Log.d(TAG, \"x\")", "import helium314.keyboard.latin.utils.Log",
            "println(text)", "val p = Privacy.prefs", "ctx.getSharedPreferences(\"v\", 0)", "import java.net.Socket", "val r = File(dir, \"v\")", "ctx.prefs().edit()", "SecureStore(x)",
            "dictionary.addToUserHistory(text)", "PersonalDictionary.add(w)", "UserHistoryDictionary.add(w)")) {
            for (path in listOf(session, fbank, ort, permission, "${voice}VoiceController.kt")) {
                val found = problems(path, text).filter { it.startsWith("P20:") }
                assertEquals("$text in $path: $found", 1, found.size)
            }
        }
        // the model store is the one owner of the model files: it may use files (nothing else of the list)
        assertEquals(emptyList<String>(), problems(modelStore, "import java.io.File\nval o = f.outputStream()\nFiles.move(a, b)").filter { it.startsWith("P20:") })
        assertEquals(1, problems(modelStore, "Log.d(TAG, \"x\")").count { it.startsWith("P20:") })
    }

    @Test fun theVoiceRulesOnlyApplyToTheVoicePackageAndLetOrdinaryEngineCodeThrough() {
        assertTrue(problems("helium314/keyboard/latin/Suggest.kt", "import java.io.File").none { it.startsWith("P20:") })
        val clean = "val frames = samples / HOP\nfor (m in 0 until N_MELS) out[m * frames + t] = v.toFloat()\nclass Encoded(val data: FloatArray)"
        for (path in listOf(session, fbank, ort, modelStore)) assertEquals(path, emptyList<String>(), problems(path, clean))
        assertEquals(emptyList<String>(), problems(session, "val profile = 1\nval blog = 2\nval catalog = 3\nval prefsLike = 4"))
    }

    @Test fun theHandOverToAnotherVoiceInputMethodIsGone() {
        assertEquals(1, problems("helium314/keyboard/latin/LatinIME.java", "mRichImm.switchToShortcutIme(this);").count { it.startsWith("P20:") })
        assertEquals(emptyList<String>(), problems("helium314/keyboard/latin/RichInputMethodManager.kt", "fun switchToShortcutIme(service: InputMethodService) = 1").filter { it.startsWith("P20:") })
    }

    @Test fun onlyTheMicrophoneIsALegitimateRuntimePermission() {
        val forbidden = SourceRules.forbiddenPermissions
        assertFalse("android.permission.RECORD_AUDIO", "android.permission.RECORD_AUDIO" in forbidden) // P20: the one runtime permission of the app
        for (p in listOf("CAMERA", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE",
            "READ_PHONE_STATE", "READ_SMS", "SEND_SMS", "READ_CALL_LOG", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "BODY_SENSORS", "ACTIVITY_RECOGNITION", "BLUETOOTH_CONNECT"))
            assertTrue(p, "android.permission.$p" in forbidden)
    }

    @Test fun nativeSourceFilesAreCodeAndBuildFilesButNotHostTests() {
        for (ok in listOf("src/utils/log_utils.cpp", "src/defines.h", "Android.mk", "Android.bp", "jni_common.cc", "src/x.hpp", "a.c"))
            assertTrue(ok, SourceRules.isNativeSource(ok))
        for (no in listOf("host_test/memory_dict_test.cpp", "README.md", "src/data.bin", "notes.txt"))
            assertFalse(no, SourceRules.isNativeSource(no))
    }

    // ---- no dlopen-style registration of native code outside the allowlist ---------------------------------

    private val jniUtils = "helium314/keyboard/latin/utils/JniUtils.java"

    @Test fun loadingNativeCodeFromAPathOrByNameIsRejectedOutsideTheJniWrapper() {
        // positive control: the built-in library is loaded by the wrapper, by name
        assertEquals(emptyList<String>(), problems(jniUtils, "System.loadLibrary(JNI_LIB_NAME);").filter { it.startsWith("P6:") })
        for (text in listOf("System.loadLibrary(\"x\")", "System.loadLibrary( name )", "Runtime.getRuntime().loadLibrary(\"x\")", "Runtime.getRuntime().load(path)",
            "Runtime.getRuntime()\n    .load(path)", "val f = System::loadLibrary", "System :: load", "System.load(path)")) {
            for (path in listOf(other, ort, "helium314/keyboard/latin/VoiceTyping.kt"))
                assertEquals("$text in $path: ${problems(path, text)}", 1, problems(path, text).count { it.startsWith("P6:") })
        }
        // a path load is rejected even in the wrapper: it may load by name only
        assertEquals(1, problems(jniUtils, "System.load(path);").count { it.startsWith("P6:") })
        assertEquals(1, problems(jniUtils, "Runtime.getRuntime().load(path);").count { it.startsWith("P6:") })
    }

    @Test fun libraryAndExecutionProviderRegistrationIsRejectedEverywhere() {
        for (text in listOf("opts.registerCustomOpLibrary(path)", "env.registerExecutionProviderLibrary(\"n\", path)", "opts.addNnapi()", "opts.addXnnpack(map)", "opts.addCUDA(0)",
            "opts.addExecutionProvider(\"QNN\", options)", "opts.addCoreML()", "opts.addTensorrt(0)", "opts.addQnn(m)", "opts\n    .addNnapi(flags)", "opts.addOpenVINO(\"\")")) {
            for (path in listOf(ort, other, jniUtils, "helium314/keyboard/latin/voice/VoiceEngineHolder.kt"))
                assertEquals("$text in $path: ${problems(path, text)}", 1, problems(path, text).count { it.startsWith("P6:") })
        }
    }

    // ---- App lock: a writer of the lock record hashes a PIN under the process-wide lock (hundreds of ms): never on the main thread ----

    private val screen = "helium314/keyboard/settings/screens/PrivacyScreen.kt"

    @Test fun anAppLockWriterOnTheMainThreadIsRejected() {
        val onClick = "Preference(onClick = {\n    lock.setTimeoutMinutes(5)\n    version++\n})"
        val found = problems(screen, onClick).filter { it.contains("App lock") }
        assertEquals(found.toString(), 1, found.size)
        assertTrue(found[0].contains("setTimeoutMinutes") && found[0].contains(screen))
        // the same call inside withContext(Dispatchers.Default) or an executor is fine (positive control that the call was seen above)
        val background = "scope.launch {\n    val r = withContext(Dispatchers.Default) { lock.setTimeoutMinutes(5) }\n}"
        assertEquals(emptyList<String>(), problems(screen, background).filter { it.contains("App lock") })
    }

    @Test fun everyWriterAndEveryBackgroundFormIsRecognised() {
        for (call in listOf("lock.enable(pin)", "lock.changePin(a, b)", "lock.disable(pin)", "lock.setTimeoutMinutes(1)", "lock.cycleTimeoutMinutes()", "lock.verify(pin)",
                "appLock.verify(pin)", "Privacy.appLock(ctx).setTimeoutMinutes(5)", "Privacy.appLock(this).verify(pin)")) {
            assertEquals(call, 1, problems(screen, "fun f() {\n  $call\n}").count { it.contains("App lock") })
            assertEquals(call, 1, problems(screen, "onClick = { $call }").count { it.contains("App lock") }) // a lambda that is not a background one
            assertEquals(call, 1, problems(screen, "withContext(Dispatchers.Main) { $call }").count { it.contains("App lock") })
            for (head in listOf("withContext(Dispatchers.Default) {", "withContext(Dispatchers.IO) {", "scope.launch(Dispatchers.Default) {", "executor.execute {",
                    "ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute {", "thread {"))
                assertEquals("$head $call", emptyList<String>(), problems(screen, "$head\n  val r = if (x) {\n    $call\n  } else 0\n}").filter { it.contains("App lock") })
        }
        // a closed background block does not cover a later call
        assertEquals(1, problems(screen, "withContext(Dispatchers.Default) { a() }\nlock.verify(pin)").count { it.contains("App lock") })
        // controls: reads, other receivers and the lock's own file are not writers on a screen
        for (text in listOf("lock.isEnabled()", "lock.needsUnlock()", "lock.timeoutMinutes()", "lock.touch()", "other.enable(x)", "list.verify(x)", "// lock.verify(pin)"))
            assertEquals(text, 0, problems(screen, "fun f() { $text }").count { it.contains("App lock") })
        assertEquals(0, problems("helium314/keyboard/latin/privacy/AppLock.kt", "fun f() { lock.verify(pin) }").count { it.contains("App lock") })
    }

    @Test fun documentsOfOtherAppsAreAccessedByFileUtilsOnly() { // a provider can stall for ever
        for (text in listOf("ctx.contentResolver.openInputStream(uri)", "cr.openOutputStream(u)", "ctx.getActivity()?.contentResolver?.openInputStream(uri)",
                "resolver.openFileDescriptor(u, \"r\")", "ctx.contentResolver.openAssetFileDescriptor(u, \"r\")", "cr.query(uri, null, null, null, null)",
                "context.getContentResolver().query(u, a, b, c, d)", "ctx.getActivity()?.contentResolver?.query(u)", "getContentResolver().openInputStream(uri)"))
            assertEquals(text, 1, problems(screen, text).count { it.startsWith("documents of other apps") })
        for (owner in listOf("helium314/keyboard/latin/common/FileUtils.java", "helium314/keyboard/settings/VoiceModelAccess.kt", "helium314/keyboard/settings/screens/VoiceScreen.kt"))
            assertEquals(owner, 0, problems(owner, "cr.openInputStream(uri); resolver.query(u, a, null, null, null)").count { it.startsWith("documents of other apps") })
        for (text in listOf("Settings.Global.getFloat(ctx.contentResolver, KEY, 1f)", "db.query(\"table\")", "val q = query(x)", "jobs.read(uri, ctx, 5L) { f, b, n -> }"))
            assertEquals(text, 0, problems(screen, text).count { it.startsWith("documents of other apps") })
    }

    @Test fun everyOtherWayToOpenOrQueryAProviderIsRefusedToo() { // the list of stream and query methods was not complete
        for (text in listOf("cr.openTypedAssetFileDescriptor(u, \"*/*\", null)", "ctx.contentResolver.openTypedAssetFile(u, \"*/*\", null, null)",
                "client.openFile(u, \"r\")", "client.openAssetFile(u, \"r\")", "client.openTypedAssetFile(u, \"*/*\", null)",
                "ctx.contentResolver.loadThumbnail(u, size, null)", "ctx.contentResolver.acquireContentProviderClient(u)",
                "ctx.getContentResolver().acquireUnstableContentProviderClient(\"authority\")", "contentResolver.getStreamTypes(u, \"*/*\")",
                "contentResolver.call(u, \"m\", null, null)", "resolver.canonicalize(u)", "DocumentsContract.openDocumentThumbnail(u)",
                "DocumentsContract.getDocumentThumbnail(cr, u, size, null)", "ctx.contentResolver?.openTypedAssetFileDescriptor(u, t, null)"))
            assertEquals(text, 1, problems(screen, text).count { it.startsWith("documents of other apps") })
        // a Kotlin method reference names the method without a parenthesis
        for (text in listOf("val f = resolver::openTypedAssetFileDescriptor", "run(ctx.contentResolver::openInputStream)", "cr::query", "ctx.contentResolver?::call",
                "x.map(DocumentsContract::openDocumentThumbnail)", "val o = client::openFile", "val g = cr :: loadThumbnail"))
            assertEquals(text, 1, problems(screen, text).count { it.startsWith("documents of other apps") })
        assertEquals(0, problems("helium314/keyboard/latin/common/FileUtils.java", "val f = resolver::openInputStream").count { it.startsWith("documents of other apps") })
        for (text in listOf("val f = list::openFileX", "val g = db::query", "val h = Foo::queryAll", "val i = other::refresh"))
            assertEquals(text, 0, problems(screen, text).count { it.startsWith("documents of other apps") })
        // every allowed owner still passes with the new methods
        for (owner in listOf("helium314/keyboard/latin/common/FileUtils.java", "helium314/keyboard/settings/VoiceModelAccess.kt", "helium314/keyboard/settings/screens/VoiceScreen.kt"))
            assertEquals(owner, 0, problems(owner, "cr.openTypedAssetFileDescriptor(u, t, null); client.openFile(u, m); cr.loadThumbnail(u, s, null)").count { it.startsWith("documents of other apps") })
        // controls: the names of other classes are not mistaken for it
        for (text in listOf("val f = File(dir).openFileOutput()", "myOpenFile(x)", "callback.call(x)", "parser.getDocumentThumbnails(x)", "list.refresh(x)"))
            assertEquals(text, 0, problems(screen, text).count { it.startsWith("documents of other apps") })
    }

    @Test fun ordinaryCodeWithSimilarNamesIsNotMistakenForARegistration() { // negative controls: the rules are specific
        val text = "ctx.registerReceiver(r, f)\nlist.addAll(x)\nlibrary.load(a)\nval addNnapiSupported = false\nSettings.getInstance().load(x)\nbuilder.load(id)\nPrivacy.registerLibraryHook(x)"
        assertEquals(emptyList<String>(), problems(other, text).filter { it.startsWith("P6:") })
    }

    // ---- (profiles and templates): each rule is planted, seen red, then green ------------------------------------

    private val store = "helium314/keyboard/latin/profiles/ProfileStore.kt"
    private val keys = "helium314/keyboard/latin/profiles/ProfileKeys.kt"
    private val catalog = "helium314/keyboard/latin/profiles/TemplateCatalog.kt"

    private fun p21(path: String, text: String) = problems(path, text).filter { it.contains("P21") }

    @Test fun theProfilesKeyIsNamedOnlyByTheProfileStore() {
        val text = "const val KEY = \"taen_profiles\""
        assertEquals(emptyList<String>(), p21(store, text)) // the owner
        assertEquals(1, p21(keys, text).size) // planted elsewhere in the same package
        assertEquals(1, p21("helium314/keyboard/settings/screens/ProfilesScreen.kt", "prefs.getString(\"taen_profiles\", null)").size)
        assertEquals(emptyList<String>(), p21(keys, "excluded = setOf(ProfileStore.KEY)")) // naming it through the owner is fine
    }

    @Test fun theTemplateAssetsAreReadOnlyByTheCatalog() {
        for (text in listOf("assets.open(\"templates/minimal.json\")", "am.list(\"templates\")")) {
            assertEquals(text, 1, p21("helium314/keyboard/settings/SetupGuideDialog.kt", text).size)
            assertEquals(text, 1, p21(keys, text).size)
            assertEquals(text, emptyList<String>(), p21(catalog, text))
        }
    }

    @Test fun theProfilesAndQuickPackagesHaveNoFileNetworkLogOrPlainPreferencesClass() {
        val planted = listOf(
            "val f = java.io.File(dir, \"x\")", "FileOutputStream(f)", "import java.net.URL", "Log.d(TAG, \"x\")", "ctx.getSharedPreferences(\"p\", 0)",
            "PreferenceManager.getDefaultSharedPreferences(ctx)", "Privacy.protectedPrefs(ctx)", "java.nio.file.Files.write(p, b)", "println(name)", "e.printStackTrace()",
        )
        for (pkg in listOf("profiles/ProfileStore.kt", "profiles/TemplateCatalog.kt", "profiles/SettingsApply.kt", "quick/QuickSettings.kt", "quick/SetupGuide.kt")) {
            val path = "helium314/keyboard/latin/$pkg"
            for (text in planted) assertTrue("$path: $text", p21(path, text).isNotEmpty())
        }
        // clean controls: what the code legitimately uses
        val clean = "import android.content.SharedPreferences\nimport java.io.InputStream\nimport java.io.IOException\nfun f(prefs: SharedPreferences) = prefs.edit().putBoolean(\"a\", true).commit()"
        assertEquals(emptyList<String>(), p21("helium314/keyboard/latin/profiles/ProfileStore.kt", clean))
        assertEquals(emptyList<String>(), p21("helium314/keyboard/settings/screens/ProfilesScreen.kt", "val f = java.io.File(dir, \"x\")")) // the rule is for the two packages
    }
    // ---- optional downloads and offline pictures --------------------------------------------------

    private val inserter = "helium314/keyboard/latin/media/MediaInserter.kt"
    private val provider = "helium314/keyboard/latin/media/MediaShareProvider.kt"
    private val vault = "helium314/keyboard/latin/media/MediaVault.kt"
    private val wiper = "helium314/keyboard/latin/privacy/Wiper.kt"

    @Test fun commitContentIsCalledByTheInserterOnly() {
        val text = "connection.commitContent(info, flags, null)"
        assertEquals(emptyList<String>(), problems(inserter, text))
        assertEquals(1, problems(other, text).size)
        assertEquals(1, problems(provider, text).size)
        assertEquals(1, problems("helium314/keyboard/keyboard/MediaPanelView.kt", "InputConnectionCompat.commitContent(ic, info, content, flags, null)").size)
    }

    @Test fun thePipeThatServesAPictureIsMadeByTheProviderOnly() {
        for (call in listOf("ParcelFileDescriptor.createReliablePipe()", "ParcelFileDescriptor.createPipe()", "ParcelFileDescriptor.createSocketPair()")) {
            assertEquals(call, emptyList<String>(), problems(provider, call))
            assertEquals(call, 1, problems(other, call).size)
            assertEquals(call, 1, problems(inserter, call).size)
        }
    }

    @Test fun theProviderMayDeclareItsOpenFileOverrideButNoOtherFileMayOpenOne() {
        val text = "override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor"
        assertEquals(emptyList<String>(), problems(provider, text))
        assertEquals(1, problems(other, text).size)
    }

    @Test fun aDownloadAddressGoesToTheBrowserInDownloadAccessAndTheAboutLinksOnly() {
        val text = "Intent(Intent.ACTION_VIEW, Uri.parse(url))"
        assertEquals(emptyList<String>(), problems("helium314/keyboard/settings/DownloadAccess.kt", text))
        assertEquals(emptyList<String>(), problems("helium314/keyboard/settings/screens/AboutScreen.kt", text))
        assertEquals(1, problems("helium314/keyboard/settings/screens/DownloadsScreen.kt", text).size)
        assertEquals(1, problems("helium314/keyboard/settings/VoiceModelAccess.kt", text).size)
    }

    @Test fun theStoreNamesOfThePicturesAreKnownToTheVaultAndTheWipeOnly() {
        val text = "val name = \"media_\" + id"
        assertEquals(emptyList<String>(), problems(vault, text))
        assertEquals(emptyList<String>(), problems(wiper, text))
        assertEquals(1, problems(other, text).size)
        assertEquals(1, problems("helium314/keyboard/latin/privacy/Privacy.kt", text).size)
        assertEquals(1, problems("helium314/keyboard/latin/downloads/DownloadsStore.kt", "File(dir, \"taen-downloads\")").size)
        assertEquals(emptyList<String>(), problems("helium314/keyboard/latin/privacy/Privacy.kt", "const val DOWNLOADS_DIR = \"taen-downloads\""))
    }

    @Test fun theMediaPackageHasNoNetworkLogPreferenceOrFileClass() {
        for (text in listOf("Log.d(TAG, \"x\")", "val p = SharedPreferences::class", "ctx.prefs()", "java.net.URL(u)", "println(x)", "val f = File(dir, n)", "FileOutputStream(f)", "bytes.inputStream(x)", "Files.write(p, b)", "RandomAccessFile(f, \"r\")"))
            assertTrue(text, problems("helium314/keyboard/latin/media/MediaTokens.kt", text).any { it.contains("media package") || it.contains("P1") || it.contains("P7") || it.contains("P11") })
        // positive controls: the exception a provider must throw and the descriptor class are fine; the other packages may use files
        assertEquals(emptyList<String>(), problems(provider, "throw FileNotFoundException()\nval pipe: ParcelFileDescriptor = x"))
        assertEquals(emptyList<String>(), problems("helium314/keyboard/latin/utils/Other.kt", "val f = File(dir, n)"))
    }

    @Test fun theDownloadsPackageHasNoNetworkLogOrPreferenceClassButMayUseFiles() {
        for (text in listOf("Log.d(TAG, \"x\")", "ctx.getSharedPreferences(n, 0)", "println(x)"))
            assertTrue(text, problems("helium314/keyboard/latin/downloads/DownloadsStore.kt", text).isNotEmpty())
        assertEquals(emptyList<String>(), problems("helium314/keyboard/latin/downloads/DownloadsStore.kt", "val f = File(dir, n)"))
    }

    // ---- the next-word model: ONNX Runtime through its own wrapper; the nextword package keeps what it reads in memory and learns nothing --------
    private val nextword = "helium314/keyboard/latin/nextword/"
    private val ortNext = "${nextword}engine/OrtNextWord.kt"

    @Test fun onnxRuntimeIsAlsoUsedByTheNextWordWrapperAndNowhereElseInThatPackage() {
        val text = "import ai.onnxruntime.OrtSession"
        assertEquals(emptyList<String>(), problems(ortNext, text))
        for (path in listOf("${nextword}engine/NextWordRanker.kt", "${nextword}NextWordAssist.kt", "helium314/keyboard/latin/NextWordTyping.kt"))
            assertEquals(path, 1, problems(path, text).count { it.contains("ONNX Runtime is used by") })
    }

    @Test fun theNextWordPackageHasNoFileNetworkLogPreferenceOrLearningClass() {
        for (text in listOf("import java.io.InputStream", "val f = File(dir, n)", "Files.write(p, b)", "Log.d(TAG, \"x\")", "println(x)", "ctx.prefs()", "val p = Privacy.prefs",
                "java.net.URL(u)", "UserHistoryDictionary.add(w)", "PersonalDictionary.add(w)", "SecureStore(x)"))
            for (path in listOf("${nextword}NextWordAssist.kt", "${nextword}engine/BpeTokenizer.kt", ortNext))
                assertEquals("$text in $path", 1, problems(path, text).count { it.contains("next-word") })
        // positive controls: buffers and the source type of the strip are fine; the keyboard wiring outside the package may read the preferences
        assertEquals(emptyList<String>(), problems(ortNext, "import java.nio.ByteBuffer\nval d = Dictionary.DICTIONARY_NEXTWORD"))
        assertEquals(emptyList<String>(), problems("helium314/keyboard/latin/NextWordTyping.kt", "ime.prefs().getBoolean(k, d)"))
    }

    @Test fun theModelFolderIsNamedByPrivacyOnlyAndItsSpeedResultIsWrittenByModelFilesOnly() {
        assertEquals(emptyList<String>(), problems("helium314/keyboard/latin/privacy/Privacy.kt", "const val MODELS_DIR = \"taen-models\""))
        assertEquals(1, problems("helium314/keyboard/latin/downloads/ModelFiles.kt", "File(dir, \"taen-models\")").size)
        assertEquals(emptyList<String>(), problems("helium314/keyboard/latin/downloads/ModelFiles.kt", "tmp.writeText(speed.encode())"))
        assertEquals(1, problems("helium314/keyboard/latin/downloads/DownloadsStore.kt", "tmp.writeText(speed.encode())").size)
    }

    @Test fun onlyThePinnedInstallerMayWriteAFileInTheDownloadsPackage() {
        assertEquals(emptyList<String>(), problems("helium314/keyboard/latin/downloads/PinnedInstaller.kt", "part.outputStream().use { }"))
        assertEquals(1, problems("helium314/keyboard/latin/downloads/DownloadsStore.kt", "part.outputStream().use { }").size)
    }

    @Test fun theMediaProviderIsTheOnlyAllowedProviderOfOurOwnAndTheOnlyOneThatGrantsUriPermissions() {
        assertTrue(SourceRules.allowedProviders.contains("helium314.keyboard.latin.media.MediaShareProvider"))
        assertEquals(setOf("androidx.startup.InitializationProvider", "helium314.keyboard.latin.media.MediaShareProvider"), SourceRules.allowedProviders)
        assertEquals(setOf("helium314.keyboard.latin.media.MediaShareProvider"), SourceRules.grantingProviders)
    }
}
