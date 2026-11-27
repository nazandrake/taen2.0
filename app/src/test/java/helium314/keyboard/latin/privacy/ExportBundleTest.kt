// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The payload inside the encrypted export: what is packed comes back exactly, and a hostile or damaged payload is rejected whole. */
class ExportBundleTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }
    private val version = "taen-export" to "1".toByteArray()

    private fun sample() = ExportParts(
        prefsDe = mapOf("theme" to "dark", "n" to 3, "flag" to true, "set" to setOf("a", "б")),
        prefsCe = mapOf("secret-ish" to "x"),
        personal = listOf(PersonalWord("Taen", "tn", 250, "en_US"), PersonalWord("Жщ", null, 100, null)),
        learned = mapOf("en-US" to byteArrayOf(1, 2, 3), "bg-BG" to ByteArray(300) { it.toByte() }),
        files = mapOf("layouts/custom.qwerty.json" to "{\"a\":1}".toByteArray(), "custom_font" to byteArrayOf(9, 9)),
        protectedFiles = mapOf("custom_emoji_font" to byteArrayOf(7)),
        removedWords = mapOf("en-US" to setOf("zork", "Quibble"), "bg" to setOf("Жщ")),
    )

    private fun assertSame(a: ExportParts, b: ExportParts) {
        assertEquals(a.prefsDe, b.prefsDe)
        assertEquals(a.prefsCe, b.prefsCe)
        assertEquals(a.personal, b.personal)
        assertEquals(a.learned.keys, b.learned.keys)
        a.learned.forEach { (k, v) -> assertContentEquals(v, b.learned[k], k) }
        assertEquals(a.files.keys, b.files.keys)
        a.files.forEach { (k, v) -> assertContentEquals(v, b.files[k], k) }
        assertEquals(a.protectedFiles.keys, b.protectedFiles.keys)
        a.protectedFiles.forEach { (k, v) -> assertContentEquals(v, b.protectedFiles[k], k) }
        assertEquals(a.removedWords, b.removedWords)
    }

    // ---- core behaviour -------------------------------------------------------------------------------

    @Test fun everythingPackedComesBack() {
        assertSame(sample(), ExportBundle.unpack(ExportBundle.pack(sample())))
    }

    @Test fun emptyExportRoundTrips() {
        val empty = ExportParts(null, null, null, emptyMap(), emptyMap(), emptyMap())
        val back = ExportBundle.unpack(ExportBundle.pack(empty))
        assertNull(back.prefsDe)
        assertNull(back.personal)
        assertEquals(emptyMap(), back.learned)
    }

    // ---- hostile and damaged payloads ----------------------------------------------------------------

    @Test fun unsafePathsAreRejected() {
        for (name in listOf("files/../evil", "files/a/../../b", "files//x", "files/a\\b", "files/a\u0000b", "protected/..", "learned/../x.bin", "learned/a b.bin", "learned/.bin", "files/" + "x".repeat(300))) {
            assertFailsWith<ExportException>(name) { ExportBundle.unpack(zip(version, name to byteArrayOf(1))) }
        }
        // positive control: a normal name in the same position is accepted
        assertNotNull(ExportBundle.unpack(zip(version, "files/ok/x.txt" to byteArrayOf(1))).files["ok/x.txt"])
        assertNotNull(ExportBundle.unpack(zip(version, "learned/en-US.bin" to byteArrayOf(1))).learned["en-US"])
    }

    @Test fun missingOrUnknownVersionIsRejected() {
        assertFailsWith<ExportException> { ExportBundle.unpack(zip("files/x" to byteArrayOf(1))) }
        assertFailsWith<ExportException> { ExportBundle.unpack(zip("taen-export" to "2".toByteArray())) }
        assertFailsWith<ExportException> { ExportBundle.unpack(byteArrayOf()) }
        assertFailsWith<ExportException> { ExportBundle.unpack("not a zip at all".toByteArray()) }
    }

    @Test fun duplicateEntriesAreRejected() {
        // java.util.zip refuses duplicate names when writing: write "files/b" and rename it to "files/a" in the bytes
        val bytes = zip(version, "files/a" to byteArrayOf(1), "files/b" to byteArrayOf(2))
        val needle = "files/b".toByteArray()
        var i = bytes.indexOfSub(needle)
        assertEquals(true, i >= 0)
        while (i >= 0) { bytes[i + needle.size - 1] = 'a'.code.toByte(); i = bytes.indexOfSub(needle) }
        assertFailsWith<ExportException> { ExportBundle.unpack(bytes) }
    }

    @Test fun oversizedContentIsRejectedWhileReading() {
        val big = zip(version, "files/big" to ByteArray(5000)) // compresses to a few bytes
        assertFailsWith<ExportException> { ExportBundle.unpack(big, maxTotalBytes = 4000, maxEntries = 100) }
        assertNotNull(ExportBundle.unpack(big, maxTotalBytes = 6000, maxEntries = 100)) // control: the same payload within the limit
    }

    @Test fun tooManyEntriesAreRejected() {
        val many = zip(version, *Array(11) { "files/f$it" to byteArrayOf(1) })
        assertFailsWith<ExportException> { ExportBundle.unpack(many, maxTotalBytes = 1_000_000, maxEntries = 10) }
        assertNotNull(ExportBundle.unpack(many, maxTotalBytes = 1_000_000, maxEntries = 12))
    }

    @Test fun damagedRecordsAreRejected() {
        assertFailsWith<ExportException> { ExportBundle.unpack(zip(version, "prefs_de.bin" to byteArrayOf(0, 0, 0, 1, 0xFF.toByte()))) }
        assertFailsWith<ExportException> { ExportBundle.unpack(zip(version, "personal.bin" to byteArrayOf(1, 2, 3))) }
    }

    @Test fun unknownEntriesAreIgnored() {
        // names outside the known prefixes (also an absolute path or a bare directory entry) are never restored
        val back = ExportBundle.unpack(zip(version, "something/else" to byteArrayOf(1), "/files/evil" to byteArrayOf(3), "files/x" to byteArrayOf(2)))
        assertEquals(setOf("x"), back.files.keys)
        assertEquals(emptyMap(), back.protectedFiles)
    }

    // ---- the removed words ----

    @Test fun thePlainRemovedWordFilesOfAnEarlierExportBecomeRemovedWords() {
        val parts = ExportBundle.unpack(zip(version, "files/blacklists/en-US.txt" to "zork\nQuibble\n\n${"x".repeat(49)}\n".toByteArray(),
            "removed/en-US.bin" to RemovedWordsCodec.encode(listOf("stored")), "files/layouts/custom.a.json" to byteArrayOf(1)))
        assertEquals(mapOf("en-US" to setOf("stored", "zork", "Quibble")), parts.removedWords) // empty and over-long lines are no words
        assertEquals(setOf("layouts/custom.a.json"), parts.files.keys) // the plain file is not handed on as a file
    }

    @Test fun badOrDamagedRemovedWordEntriesAreRejected() {
        for (name in listOf("removed/../x.bin", "removed/en US.bin", "removed/en.txt", "files/blacklists/a b.txt"))
            assertFailsWith<ExportException>(name) { ExportBundle.unpack(zip(version, name to RemovedWordsCodec.encode(listOf("w")))) }
        assertFailsWith<ExportException> { ExportBundle.unpack(zip(version, "removed/en.bin" to byteArrayOf(0, 0, 0, 7))) }
        assertEquals(mapOf("en" to setOf("w")), ExportBundle.unpack(zip(version, "removed/en.bin" to RemovedWordsCodec.encode(listOf("w")))).removedWords) // control
    }
}
