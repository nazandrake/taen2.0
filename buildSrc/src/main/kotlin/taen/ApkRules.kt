package taen

import java.io.File
import java.util.zip.ZipFile

/**
 * Static scan of a built APK (invariants P1, P5, P6): classes that can reach the network, trackers, dynamic code
 * loading, and native libraries that are not on the allowlist or that contain a logger. Pure functions, so they can be unit tested.
 */
object ApkRules {
    /** A rule is a class-name prefix in slash form (java/net/Socket); the longest matching prefix decides deny or allow. */
    class Rules(val entries: List<Pair<Boolean, String>>) { // (deny, prefix)
        /** The deny prefix that applies to the slash-form class name, or null when it is allowed or unmatched. */
        fun deniedBy(slashName: String): String? {
            val best = entries.filter { slashName.startsWith(it.second) }.maxByOrNull { it.second.length } ?: return null
            return if (best.first) best.second else null
        }
    }

    fun parse(lines: List<String>): Rules = Rules(
        lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.map {
            val parts = it.split(Regex("\\s+"))
            require(parts.size == 2 && (parts[0] == "deny" || parts[0] == "allow")) { "bad rule line: '$it'" }
            Pair(parts[0] == "deny", parts[1])
        }
    )

    /** All strings of the DEX string table. Fails closed (IllegalArgumentException) on a file that is not a valid DEX. */
    fun dexStrings(dex: ByteArray): List<String> {
        require(dex.size >= 112 && dex[0] == 'd'.code.toByte() && dex[1] == 'e'.code.toByte() && dex[2] == 'x'.code.toByte()) { "not a DEX file" }
        fun u32(at: Int): Int {
            require(at >= 0 && at + 4 <= dex.size) { "DEX offset $at out of range" }
            return (0..3).sumOf { (dex[at + it].toInt() and 0xff) shl (8 * it) }
        }
        val count = u32(56)
        val ids = u32(60)
        require(count >= 0 && ids >= 0 && ids.toLong() + 4L * count <= dex.size) { "DEX string table out of range" }
        return (0 until count).map { i ->
            var p = u32(ids + 4 * i)
            require(p in 0 until dex.size) { "DEX string offset out of range" }
            while (dex[p].toInt() and 0x80 != 0) { p++; require(p < dex.size) { "DEX string out of range" } } // ULEB128 length
            p++
            val start = p
            while (true) { require(p < dex.size) { "unterminated DEX string" }; if (dex[p].toInt() == 0) break; p++ }
            String(dex, start, p - start, Charsets.UTF_8)
        }
    }

    fun scanDex(name: String, dex: ByteArray, rules: Rules): List<String> {
        val problems = linkedSetOf<String>()
        for (s in dexStrings(dex)) {
            val descriptor = s.trimStart('[')
            if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
                // a type descriptor: Ljava/net/Socket;
                if (rules.deniedBy(descriptor.substring(1, descriptor.length - 1)) != null) problems.add("$name: references $s")
            } else if (s.contains('.') && !s.contains(' ')) {
                // a class name in a string constant (reflection): java.net.Socket
                if (rules.deniedBy(s.replace('.', '/')) != null) problems.add("$name: string constant names $s")
            }
        }
        return problems.toList()
    }

    /** Byte strings no native library may contain: the Java logger (JNI reflection), the Android log functions and a link to liblog (P11). */
    private val nativeDenied = listOf("android/util/Log", "__android_log", "liblog.so")

    /** What a library named `links-liblog` on the allowlist may still contain: the Android log link, never the Java logger. */
    private val nativeLogLinks = setOf("__android_log", "liblog.so")

    fun scanNativeLib(name: String, lib: ByteArray, mayLinkLog: Boolean = false): List<String> =
        nativeDenied.filter { !(mayLinkLog && it in nativeLogLinks) && indexOf(lib, it.toByteArray(Charsets.ISO_8859_1)) >= 0 }
            .map { "$name: native library contains \"$it\"" }

    /**
     * privacy/native-libs.allow: one library file name per line, `#` starts a comment; the optional word `links-liblog` after the name lets that
     * one library link liblog (a prebuilt library that logs through Android: ONNX Runtime). Returns the names and the names with the flag.
     */
    fun parseNativeLibs(lines: List<String>): Pair<Set<String>, Set<String>> {
        val names = linkedSetOf<String>()
        val logLinking = linkedSetOf<String>()
        for (line in lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }) {
            val parts = line.split(Regex("\\s+"))
            require(parts.size <= 2 && (parts.size == 1 || parts[1] == "links-liblog")) { "bad native library line: '$line'" }
            names.add(parts[0])
            if (parts.size == 2) logLinking.add(parts[0])
        }
        return names to logLinking
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** Problems found in the APK: denied references in every DEX file, and native library names not in [allowedLibs]. */
    fun scanApk(apk: File, rules: Rules, allowedLibs: Set<String>, logLinkingLibs: Set<String> = emptySet()): List<String> {
        val problems = mutableListOf<String>()
        ZipFile(apk).use { zip ->
            val entries = zip.entries().toList()
            val dexes = entries.filter { Regex("classes\\d*\\.dex").matches(it.name) }
            if (dexes.isEmpty()) problems.add("${apk.name}: no classes.dex found, nothing was scanned")
            for (e in dexes) problems += scanDex(e.name, zip.getInputStream(e).use { it.readBytes() }, rules)
            for (e in entries) {
                val m = Regex("lib/[^/]+/([^/]+)").matchEntire(e.name) ?: continue
                if (m.groupValues[1] !in allowedLibs) problems.add("${e.name}: native library ${m.groupValues[1]} is not in the allowlist")
                problems += scanNativeLib(e.name, zip.getInputStream(e).use { it.readBytes() }, m.groupValues[1] in logLinkingLibs)
            }
        }
        return problems
    }
}
