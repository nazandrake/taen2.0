// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bulgarian user interface: every translatable string of the app has a Bulgarian text, with the same format
 * placeholders (%1$d, %1$s, %s, %d) as the English one, and the new screens are really translated. A new
 * English string without a translation fails here, so the Bulgarian typist never sees an English screen by accident.
 */
class BulgarianStringsTest {
    private val res = File("src/main/res")

    // name -> text of the plain <string> entries that are translatable; the Android XML parser rules for quotes are not needed
    // for the comparison: both files are read the same way and only placeholders and presence are compared
    private fun strings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        val map = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val attrs = node.attributes
            if (attrs.getNamedItem("translatable")?.nodeValue == "false") continue
            map[attrs.getNamedItem("name").nodeValue] = node.textContent.trim().removeSurrounding("\"") // Android: quotes around a text
        }
        return map
    }

    private val placeholder = Regex("%(\\d+\\$)?[sd]")
    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()

    private val english = strings("values")
    private val bulgarian = strings("values-bg")

    @Test fun everyTranslatableStringHasABulgarianText() {
        assertTrue(english.size > 500, "positive control: the English file was read (${english.size} strings)")
        val missing = english.keys.filter { it !in bulgarian }
        assertEquals(emptyList(), missing, "strings without a Bulgarian translation")
    }

    @Test fun thePlaceholdersOfEveryBulgarianTextMatchTheEnglishOne() {
        val wrong = english.keys.filter { it in bulgarian && placeholders(english.getValue(it)) != placeholders(bulgarian.getValue(it)) }
        assertEquals(emptyList(), wrong, "placeholders differ")
        // positive control: the check sees placeholders at all
        assertEquals(listOf("%1\$d"), placeholders(bulgarian.getValue("app_lock_wrong")))
        assertEquals(listOf("%1\$s"), placeholders(bulgarian.getValue("privacy_a11y_warning")))
    }

    @Test fun theNewScreensAreReallyTranslatedAndNotCopiedFromEnglish() {
        val screens = english.keys.filter { it.startsWith("self_destruct_") || it.startsWith("app_lock_") || it.startsWith("privacy_") || it.startsWith("incognito_") }
        assertTrue(screens.size > 80, "positive control: ${screens.size} strings of the privacy screens")
        for (name in screens) {
            val bg = bulgarian.getValue(name)
            assertTrue(bg.any { it in 'а'..'я' || it in 'А'..'Я' }, "$name has no Cyrillic letter: $bg")
        }
        assertEquals("Самоунищожение", bulgarian.getValue("self_destruct_title"))
    }

    @Test fun smartQuotesHaveTheirOwnBulgarianText() {
        assertTrue("„" in bulgarian.getValue("smart_quotes_summary") && "“" in bulgarian.getValue("smart_quotes_summary"))
    }
}
