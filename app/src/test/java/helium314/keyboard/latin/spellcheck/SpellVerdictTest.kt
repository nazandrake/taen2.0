// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.spellcheck

import helium314.keyboard.latin.common.StringUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which unknown words the spell checker underlines. */
class SpellVerdictTest {
    // an unknown lowercase word is a typo; an unknown capitalized or all-caps word (a name, an abbreviation) is one only when the dictionary has a close word
    @Test fun anUnknownLowercaseWordIsATypo() {
        assertTrue(SpellVerdict.isTypo(StringUtils.CAPITALIZE_NONE) { false })
    }

    @Test fun anUnknownNameOrAbbreviationIsNotATypoWithoutACloseWord() {
        assertFalse(SpellVerdict.isTypo(StringUtils.CAPITALIZE_FIRST) { false })
        assertFalse(SpellVerdict.isTypo(StringUtils.CAPITALIZE_ALL) { false })
    }

    // the whole table, and the close word is only looked for when it can matter
    @Test fun aCapitalizedWordWithACloseWordIsATypoAndLowercaseNeedsNone() {
        val table = mapOf(
            Triple(StringUtils.CAPITALIZE_NONE, false, "lowercase or mixed, no close word") to true,
            Triple(StringUtils.CAPITALIZE_NONE, true, "lowercase, close word") to true,
            Triple(StringUtils.CAPITALIZE_FIRST, false, "capitalized, no close word") to false,
            Triple(StringUtils.CAPITALIZE_FIRST, true, "capitalized, close word") to true,
            Triple(StringUtils.CAPITALIZE_ALL, false, "all capitals, no close word") to false,
            Triple(StringUtils.CAPITALIZE_ALL, true, "all capitals, close word") to true,
        )
        for ((case, expected) in table) assertEquals(expected, SpellVerdict.isTypo(case.first) { case.second }, case.third)
    }

    @Test fun theCloseWordIsNotLookedUpForALowercaseWord() {
        var asked = 0
        assertTrue(SpellVerdict.isTypo(StringUtils.CAPITALIZE_NONE) { asked++; true })
        assertEquals(0, asked)
        SpellVerdict.isTypo(StringUtils.CAPITALIZE_FIRST) { asked++; false }
        assertEquals(1, asked, "asked once for a capitalized word")
    }
}
