// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.makedict

import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Audit follow-up (1.0.1): a dictionary file with no newline must fail with a message, not with an OutOfMemoryError. */
class BoundedLineReaderTest {
    private fun reader(text: String) = BufferedReader(StringReader(text))

    // ---- the core behaviour -------------------------------------------------------------------------------------

    @Test fun linesAreReadLikeReadLine() {
        val r = reader("first\nsecond\n")
        assertEquals("first", BoundedLineReader.readLine(r, 100))
        assertEquals("second", BoundedLineReader.readLine(r, 100))
        assertNull(BoundedLineReader.readLine(r, 100))
    }

    @Test fun aLineLongerThanTheLimitIsAnIoError() {
        assertFailsWith<IOException> { BoundedLineReader.readLine(reader("x".repeat(101)), 100) }
    }

    // ---- the whole contract -------------------------------------------------------------------------------------

    @Test fun theLimitIsInclusive() {
        assertEquals("x".repeat(100), BoundedLineReader.readLine(reader("x".repeat(100)), 100))
        assertEquals("x".repeat(100), BoundedLineReader.readLine(reader("x".repeat(100) + "\n"), 100))
        assertFailsWith<IOException> { BoundedLineReader.readLine(reader("x".repeat(101) + "\n"), 100) } // a terminator does not rescue a long line
        assertFailsWith<IOException> { BoundedLineReader.readLine(reader("x".repeat(101)), 100) }
    }

    @Test fun everyLineTerminatorOfReadLineIsHonoured() {
        val r = reader("a\r\nb\rc\n\nd")
        assertEquals(listOf("a", "b", "c", "", "d"), generateSequence { BoundedLineReader.readLine(r, 100) }.toList())
        // differential: BufferedReader.readLine gives the same lines for every one of these inputs
        for (text in listOf("", "\n", "\r", "\r\n", "a", "a\n", "a\r\nb", "\n\n\r\r\n", "x\ry\nz\r\n", "no end", "tab\there\n", "български\nтекст\r\n")) {
            val bounded = reader(text).let { r -> generateSequence { BoundedLineReader.readLine(r, 100) }.toList() }
            val reference = reader(text).let { r -> generateSequence { r.readLine() }.toList() }
            assertEquals(reference, bounded, "input: ${text.replace("\r", "\\r").replace("\n", "\\n")}")
        }
        assertNull(BoundedLineReader.readLine(reader(""), 100))
        assertEquals("", BoundedLineReader.readLine(reader("\n"), 100))
    }

    @Test fun aLineThatEndsWithACarriageReturnAtTheEndOfTheInputIsOneLine() {
        val r = reader("last\r")
        assertEquals("last", BoundedLineReader.readLine(r, 100))
        assertNull(BoundedLineReader.readLine(r, 100))
    }

    @Test fun anEndlessInputWithoutANewlineStopsAtTheLimitWithoutKeepingMoreThanTheLimit() { // the audit case: 60 MiB with no newline
        var chars = 0L
        val endless = object : java.io.Reader() {
            override fun read(cbuf: CharArray, off: Int, len: Int): Int { chars += len; cbuf.fill('a', off, off + len); return len }
            override fun close() {}
        }
        val limit = BoundedLineReader.MAX_LINE_CHARS
        assertFailsWith<IOException> { BoundedLineReader.readLine(BufferedReader(endless), limit) }
        assertTrue(chars <= limit + 8192 + 1, "read $chars characters of an endless input")
        assertEquals(1 shl 20, BoundedLineReader.MAX_LINE_CHARS)
    }

    @Test fun theCombinedFormatReaderRefusesALongHeaderOrLongLineWithAnIoError() {
        val long = "x".repeat(BoundedLineReader.MAX_LINE_CHARS + 1)
        assertFailsWith<IOException> { CombinedInputOutput.readDictionaryCombined(reader(long)) } // the header line
        assertFailsWith<IOException> { CombinedInputOutput.readDictionaryCombined(reader("dictionary=main:en,locale=en,description=x,date=1,version=1\n" + long)) } // a word line
        assertFailsWith<IOException> { CombinedInputOutput.readDictionaryCombined(reader("# comment\n" + "# ".padEnd(long.length, 'c'))) } // a comment line
    }

    @Test fun theFormatTestsReturnFalseForAFileWithoutANewline() {
        val file = java.io.File.createTempFile("taen-longline", ".txt").also { it.deleteOnExit() }
        file.outputStream().use { out -> val chunk = ByteArray(1 shl 16) { 'a'.code.toByte() }; repeat(24) { out.write(chunk) } } // 1.5 MiB, one line
        assertEquals(false, CombinedInputOutput.isCombinedDictionary(file.absolutePath))
        assertEquals(false, XmlDictInputOutput.isXmlUnigramDictionary(file.absolutePath))
        // positive control: the same checks accept a real first line
        file.writeText("dictionary=main:en,locale=en,description=x,date=1,version=1\nword=a,f=1\n")
        assertEquals(true, CombinedInputOutput.isCombinedDictionary(file.absolutePath))
        file.writeText("<wordlist format=\"1\">\n</wordlist>\n")
        assertEquals(true, XmlDictInputOutput.isXmlUnigramDictionary(file.absolutePath))
    }

    @Test fun noCodeOfTheDictionaryImportReadsALineWithoutTheBound() { // the wiring: reverting one call to reader.readLine() brings the OutOfMemoryError back
        val dir = java.io.File("src/main/java/helium314/keyboard/latin/makedict")
        val files = dir.listFiles { f -> f.extension == "java" || f.extension == "kt" }!!.filter { it.name != "BoundedLineReader.kt" }
        assertTrue(files.any { it.name == "CombinedInputOutput.java" } && files.any { it.name == "XmlDictInputOutput.java" }) // positive control: the files are found
        for (f in files) assertFalse(Regex("""\.readLine\(\)""").containsMatchIn(f.readText()), "${f.name} reads an unbounded line")
        assertTrue(java.io.File(dir, "CombinedInputOutput.java").readText().contains("BoundedLineReader.readLine(reader)"))
        assertTrue(java.io.File(dir, "XmlDictInputOutput.java").readText().contains("BoundedLineReader.readLine(reader)"))
    }
}
