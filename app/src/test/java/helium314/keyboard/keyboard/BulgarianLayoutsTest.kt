// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bulgarian layouts: phonetic ("bulgarian"), BDS ("bulgarian_bds") and BEKL ("bulgarian_bekl") must give access to all
 * 30 letters of the Bulgarian alphabet, each as a key of its own, and "ѝ" (with the grave accent, the one that tells
 * "й" apart in a few words) by long press on "и". The files are the simple layout format: one key per line, the first
 * word is the label, the following words are the long-press keys, an empty line starts the next row.
 * The quotation marks „ “ are on the quote key by long press (locale_key_texts/bg.txt).
 */
class BulgarianLayoutsTest {
    private val alphabet = "абвгдежзийклмнопрстуфхцчшщъьюя".toList() // the 30 letters
    private fun file(path: String) = File("src/main/assets/$path").readText()

    private class Layout(val rows: List<List<List<String>>>) {
        val keys get() = rows.flatten()
        val labels get() = keys.map { it.first() }
    }

    private fun layout(name: String): Layout {
        val rows = ArrayList<List<List<String>>>()
        var row = ArrayList<List<String>>()
        for (line in file("layouts/main/$name.txt").lines()) {
            if (line.isBlank()) { if (row.isNotEmpty()) rows.add(row); row = ArrayList(); continue }
            row.add(line.trim().split(Regex("\\s+")))
        }
        if (row.isNotEmpty()) rows.add(row)
        return Layout(rows)
    }

    private val names = listOf("bulgarian", "bulgarian_bds", "bulgarian_bekl")

    @Test fun everyLayoutHasAllThirtyLettersOnKeysOfTheirOwn() {
        for (name in names) {
            val labels = layout(name).labels
            val missing = alphabet.filter { it.toString() !in labels }
            assertEquals(emptyList(), missing, "$name: letters without a key")
            for (letter in alphabet) assertEquals(1, labels.count { it == letter.toString() }, "$name: $letter must be on exactly one key")
        }
    }

    @Test fun everyKeyIsALetterOfTheBulgarianAlphabetOrAKnownExtra() {
        // "э" is on the BDS keyboard; "ѭ", "ѫ", "ѣ" are the letters of the old orthography on the BEKL keyboard
        val extras = mapOf("bulgarian" to emptySet(), "bulgarian_bds" to setOf("э"), "bulgarian_bekl" to setOf("ѭ", "ѫ", "ѣ"))
        for (name in names) {
            val unknown = layout(name).labels.filter { it.length != 1 || it[0] !in alphabet && it !in extras.getValue(name) }
            assertEquals(emptyList(), unknown, "$name: keys that are no letter of the alphabet")
        }
        // positive control: the extras are really there
        assertTrue("э" in layout("bulgarian_bds").labels && "ѣ" in layout("bulgarian_bekl").labels)
    }

    @Test fun theRowsFitTheScreenAndTheShapeIsThreeRows() {
        for (name in names) {
            val layout = layout(name)
            assertEquals(3, layout.rows.size, "$name rows")
            layout.rows.forEach { assertTrue(it.size in 8..12, "$name: a row of ${it.size} keys") }
        }
    }

    @Test fun theGraveILetterIsOnALongPressOfIInEveryLayout() {
        for (name in names) {
            val i = layout(name).keys.single { it.first() == "и" }
            assertTrue("ѝ" in i.drop(1), "$name: long press on и: $i")
        }
    }

    @Test fun theBulgarianQuotationMarksAreOnTheQuoteKey() {
        val popup = file("locale_key_texts/bg.txt").lines().first { it.startsWith("\"") }.split(" ")
        assertEquals(listOf("\"", "„", "“", "”", "«", "»"), popup)
        assertEquals(listOf("[popup_keys]", "и ѝ"), file("locale_key_texts/bg.txt").lines().take(2)) // the existing line is kept
    }

    @Test fun theSubtypesNameTheThreeLayoutsForBulgarian() {
        val method = file("../res/xml/method.xml")
        val bg = Regex("<subtype[^>]*?imeSubtypeLocale=\"bg\"[^>]*?/>", RegexOption.DOT_MATCHES_ALL).findAll(method).map { it.value }.toList()
        assertEquals(3, bg.size, "Bulgarian subtypes")
        assertEquals(names.sorted(), bg.map { Regex("MAIN:([a-z_]+)").find(it)!!.groupValues[1] }.sorted())
        bg.forEach { assertTrue("isAsciiCapable=\"false\"" in it, "a Bulgarian subtype that claims to be ASCII capable") }
    }
}
