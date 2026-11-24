package taen

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkScanTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Minimal DEX: the 112-byte header (only the string table fields are filled) plus a string table and string data. */
    private fun dex(vararg strings: String): ByteArray {
        val header = 112
        val idsSize = strings.size * 4
        val data = ByteArrayOutputStream()
        val offsets = strings.map { s ->
            val off = header + idsSize + data.size()
            val b = s.toByteArray(Charsets.UTF_8)
            data.write(b.size) // all test strings are shorter than 128 characters, so one ULEB128 byte
            data.write(b)
            data.write(0)
            off
        }
        val out = ByteArray(header + idsSize + data.size())
        fun put(at: Int, v: Int) { for (i in 0..3) out[at + i] = (v shr (8 * i)).toByte() }
        "dex\n035\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(out)
        put(56, strings.size)
        put(60, header)
        offsets.forEachIndexed { i, o -> put(header + 4 * i, o) }
        data.toByteArray().copyInto(out, header + idsSize)
        return out
    }

    private fun apk(vararg entries: Pair<String, ByteArray>): File {
        val f = tmp.newFile()
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return f
    }

    private val rules = ApkRules.parse(
        """
        # comment
        deny java/net/
        allow java/net/URI
        deny android/webkit/
        allow android/webkit/MimeTypeMap
        deny com/google/firebase/
        """.trimIndent().lines()
    )

    private val libs = setOf("libjni_latinime.so")

    // ---- core behaviour ----

    @Test fun readsStringTable() {
        assertEquals(listOf("Lfoo/Bar;", "x"), ApkRules.dexStrings(dex("Lfoo/Bar;", "x")))
    }

    @Test fun deniedDescriptorIsReported() {
        val problems = ApkRules.scanDex("classes.dex", dex("Ljava/net/Socket;"), rules)
        assertEquals(1, problems.size)
        assertTrue(problems[0], problems[0].contains("Ljava/net/Socket;"))
    }

    // ---- full functionality ----

    @Test fun allowedPrefixOverridesShorterDeny() {
        assertEquals(emptyList<String>(), ApkRules.scanDex("classes.dex", dex("Ljava/net/URI;", "Ljava/net/URISyntaxException;"), rules))
        assertEquals(1, ApkRules.scanDex("classes.dex", dex("Ljava/net/URL;"), rules).size) // URL is not covered by the URI allow
    }

    @Test fun arrayDescriptorsAreChecked() {
        assertEquals(1, ApkRules.scanDex("classes.dex", dex("[Ljava/net/Socket;"), rules).size)
        assertEquals(1, ApkRules.scanDex("classes.dex", dex("[[Landroid/webkit/WebView;"), rules).size)
    }

    @Test fun dottedClassNameStringsAreChecked() {
        assertEquals(1, ApkRules.scanDex("classes.dex", dex("java.net.Socket"), rules).size)
        assertEquals(0, ApkRules.scanDex("classes.dex", dex("java.net.URI"), rules).size)
    }

    @Test fun unrelatedStringsPass() {
        val strings = arrayOf("Landroid/net/Uri;", "Ljava/lang/String;", "https://example.org", "Ljava/netx;", "Landroid/webkit/MimeTypeMap;")
        // android/net/ has no rule in this rule set; java/netx; is not under java/net/
        assertEquals(emptyList<String>(), ApkRules.scanDex("classes.dex", dex(*strings), rules))
    }

    @Test fun trackerPrefixIsDenied() {
        assertEquals(1, ApkRules.scanDex("classes.dex", dex("Lcom/google/firebase/FirebaseApp;"), rules).size)
    }

    @Test fun badDexIsAnError() {
        try { ApkRules.dexStrings(ByteArray(10)); fail("expected error") } catch (e: IllegalArgumentException) { /* fail closed */ }
        val bad = dex("Lfoo;")
        bad[60] = 0x7f // string table offset beyond the file
        bad[61] = 0x7f
        try { ApkRules.dexStrings(bad); fail("expected error") } catch (e: IllegalArgumentException) { /* fail closed */ }
    }

    @Test fun ruleLinesMustBeValid() {
        try { ApkRules.parse(listOf("permit java/net/")); fail("expected error") } catch (e: IllegalArgumentException) { }
    }

    @Test fun apkScanFindsDexHitsInEveryDexFile() {
        val a = apk("classes.dex" to dex("Lok/A;"), "classes2.dex" to dex("Ljava/net/Socket;"))
        val problems = ApkRules.scanApk(a, rules, libs)
        assertEquals(1, problems.size)
        assertTrue(problems[0], problems[0].contains("classes2.dex"))
    }

    @Test fun apkScanChecksNativeLibNames() {
        val ok = apk("classes.dex" to dex("Lok/A;"), "lib/arm64-v8a/libjni_latinime.so" to byteArrayOf(1), "lib/x86/libjni_latinime.so" to byteArrayOf(1))
        assertEquals(emptyList<String>(), ApkRules.scanApk(ok, rules, libs))
        val bad = apk("classes.dex" to dex("Lok/A;"), "lib/arm64-v8a/libjni_latinime.so" to byteArrayOf(1), "lib/arm64-v8a/libevil.so" to byteArrayOf(1))
        val problems = ApkRules.scanApk(bad, rules, libs)
        assertEquals(1, problems.size)
        assertTrue(problems[0], problems[0].contains("libevil.so"))
    }

    // the release dex must not call android.util.Log (R8 strips the calls; this keeps it so)
    @Test fun theCommittedRulesDenyAndroidUtilLog() {
        val real = ApkRules.parse(File("../privacy/dex-rules.txt").readLines())
        assertEquals(1, ApkRules.scanDex("classes.dex", dex("Landroid/util/Log;"), real).size)
        assertEquals(1, ApkRules.scanDex("classes.dex", dex("android.util.Log"), real).size)
        // the wrapper of the app has its own name and is not the platform logger
        assertEquals(emptyList<String>(), ApkRules.scanDex("classes.dex", dex("Lhelium314/keyboard/latin/utils/Log;"), real))
    }

    // nothing shipped starts a process: ProcessBuilder and Runtime.exec (its signature names java.lang.Process) are in no dex string table
    @Test fun theCommittedRulesDenyStartingAProcess() {
        val real = ApkRules.parse(File("../privacy/dex-rules.txt").readLines())
        assertEquals(1, ApkRules.scanDex("classes.dex", dex("Ljava/lang/ProcessBuilder;"), real).size)
        assertEquals(1, ApkRules.scanDex("classes.dex", dex("Ljava/lang/Process;"), real).size) // what Runtime.exec returns
        assertEquals(1, ApkRules.scanDex("classes.dex", dex("java.lang.ProcessBuilder"), real).size)
        // positive controls: the rest of java.lang.Runtime, Thread and the like stay allowed
        assertEquals(emptyList<String>(), ApkRules.scanDex("classes.dex", dex("Ljava/lang/Runtime;", "Ljava/lang/Thread;", "availableProcessors"), real))
    }

    @Test fun apkWithoutDexIsAnError() {
        val problems = ApkRules.scanApk(apk("res/x.xml" to byteArrayOf(1)), rules, libs)
        assertTrue(problems.any { it.contains("no classes") })
    }

    // ---- native libraries must not contain a logger (P11) ------------------------------------------------------------------

    private fun so(vararg texts: String) = ByteArrayOutputStream().also { o -> texts.forEach { o.write(byteArrayOf(0, 1, 2)); o.write(it.toByteArray()); o.write(0) } }.toByteArray()

    @Test fun nativeLibraryThatReachesTheJavaLoggerOrTheAndroidLogIsRejected() {
        val dex = dex("Ljava/lang/String;")
        for ((needle, denied) in listOf("android/util/Log" to "android/util/Log", "__android_log_print" to "__android_log",
            "__android_log_write" to "__android_log", "liblog.so" to "liblog.so")) {
            val problems = ApkRules.scanApk(apk("classes.dex" to dex, "lib/arm64-v8a/libjni_latinime.so" to so("_ZN8latinime", needle)), rules, libs)
            assertEquals(needle, listOf("lib/arm64-v8a/libjni_latinime.so: native library contains \"$denied\""), problems)
        }
    }

    @Test fun everyAbiAndEveryLibraryIsScannedAndACleanOneIsAccepted() {
        val dex = dex("Ljava/lang/String;")
        val clean = so("_ZN8latinime", "snprintf")
        assertEquals(emptyList<String>(), ApkRules.scanApk(apk("classes.dex" to dex, "lib/arm64-v8a/libjni_latinime.so" to clean, "lib/x86/libjni_latinime.so" to clean), rules, libs))
        val problems = ApkRules.scanApk(apk("classes.dex" to dex, "lib/arm64-v8a/libjni_latinime.so" to clean, "lib/x86/libjni_latinime.so" to so("x", "__android_log_print")), rules, libs)
        assertEquals(1, problems.size)
        assertTrue(problems[0], problems[0].startsWith("lib/x86/libjni_latinime.so"))
        // a library of another name that is on the allowlist is scanned too
        val other = ApkRules.scanApk(apk("classes.dex" to dex, "lib/arm64-v8a/libother.so" to so("android/util/Log")), rules, libs + "libother.so")
        assertEquals(1, other.size)
    }

    // ---- ONNX Runtime (prebuilt, MIT) links liblog; that one library is named on the allowlist, nothing else may ----------

    @Test fun aLibraryNamedAsLogLinkingMayLinkTheAndroidLogButNothingElseMay() {
        val dex = dex("Ljava/lang/String;")
        val ort = so("OrtApis", "__android_log_print", "liblog.so")
        val allowed = libs + "libonnxruntime.so"
        // without the flag the same library is rejected (positive control: the scan sees both strings)
        assertEquals(2, ApkRules.scanApk(apk("classes.dex" to dex, "lib/arm64-v8a/libonnxruntime.so" to ort), rules, allowed).size)
        // with the flag the Android log link is accepted for that library
        assertEquals(emptyList<String>(), ApkRules.scanApk(apk("classes.dex" to dex, "lib/arm64-v8a/libonnxruntime.so" to ort), rules, allowed, setOf("libonnxruntime.so")))
        // the Java logger is never accepted, flag or not
        val java = ApkRules.scanApk(apk("classes.dex" to dex, "lib/arm64-v8a/libonnxruntime.so" to so("android/util/Log", "liblog.so")), rules, allowed, setOf("libonnxruntime.so"))
        assertEquals(listOf("lib/arm64-v8a/libonnxruntime.so: native library contains \"android/util/Log\""), java)
        // the flag is for that library only: our own library still may not link liblog
        val own = ApkRules.scanApk(apk("classes.dex" to dex, "lib/arm64-v8a/libjni_latinime.so" to so("liblog.so"), "lib/arm64-v8a/libonnxruntime.so" to ort), rules, allowed, setOf("libonnxruntime.so"))
        assertEquals(listOf("lib/arm64-v8a/libjni_latinime.so: native library contains \"liblog.so\""), own)
    }

    @Test fun theAllowlistFileHasOneLibraryPerLineWithAnOptionalLogLinkingFlag() {
        val parsed = ApkRules.parseNativeLibs(listOf("# comment", "libjni_latinime.so # reason", "", "libonnxruntime.so  links-liblog  # reason", "libonnxruntime4j_jni.so"))
        assertEquals(setOf("libjni_latinime.so", "libonnxruntime.so", "libonnxruntime4j_jni.so"), parsed.first)
        assertEquals(setOf("libonnxruntime.so"), parsed.second)
        try { ApkRules.parseNativeLibs(listOf("libx.so allow-everything")); fail("expected error") } catch (e: IllegalArgumentException) { }
        try { ApkRules.parseNativeLibs(listOf("libx.so links-liblog extra")); fail("expected error") } catch (e: IllegalArgumentException) { }
    }

    @Test fun theCommittedAllowlistFlagsOnnxRuntimeOnly() {
        val (names, logLinking) = ApkRules.parseNativeLibs(File("../privacy/native-libs.allow").readLines())
        assertEquals(setOf("libjni_latinime.so", "libandroidx.graphics.path.so", "libonnxruntime.so", "libonnxruntime4j_jni.so"), names)
        assertEquals(setOf("libonnxruntime.so"), logLinking)
    }
}

