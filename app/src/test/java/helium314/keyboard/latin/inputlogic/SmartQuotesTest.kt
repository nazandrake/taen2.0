// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import helium314.keyboard.latin.common.Constants
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smart quotes: in Bulgarian the quote key types „ (U+201E) to open and “ (U+201C) to close, the quotation marks of
 * the Bulgarian orthography. The rule decides from the character before the cursor; everything else stays as typed.
 */
class SmartQuotesTest {
    private val plain = '"'.code
    private val open = '„'.code
    private val close = '“'.code
    private fun bg(before: Int, selection: Boolean = false, typed: Int = plain, language: String = "bg") =
        SmartQuotes.convert(typed, before, selection, language)

    @Test fun aQuoteAtTheStartOrAfterSpaceOrAnOpeningBracketOpens() {
        assertEquals(open, bg(Constants.NOT_A_CODE))
        for (c in listOf(' ', '\n', '\t', ' ', '(', '[', '{', '<')) assertEquals(open, bg(c.code), "before U+%04X".format(c.code))
    }

    @Test fun aQuoteAfterALetterOrPunctuationCloses() {
        for (c in listOf('й', 'Я', 'a', '.', ',', '!', '?', ':', ';', ')', '…', '“', '”', '»')) assertEquals(close, bg(c.code), "before $c")
        assertEquals(close, bg('„'.code)) // an empty pair: „“
    }

    @Test fun afterADigitTheQuoteStaysPlainForInchesAndSeconds() {
        for (c in '0'..'9') assertEquals(plain, bg(c.code))
        assertEquals(plain, bg('٣'.code)) // any decimal digit
    }

    @Test fun aSelectionIsWrappedByAnOpeningQuote() {
        assertEquals(open, bg('й'.code, selection = true)) // the wrapping adds the closing one itself
        assertEquals(open, bg('5'.code, selection = true))
    }

    @Test fun onlyThePlainQuoteInBulgarianIsConverted() {
        assertEquals('\''.code, bg(' '.code, typed = '\''.code)) // apostrophe
        assertEquals(open, bg(' '.code, typed = open))
        assertEquals(close, bg(' '.code, typed = close))
        assertEquals('«'.code, bg(' '.code, typed = '«'.code))
        assertEquals('a'.code, bg(' '.code, typed = 'a'.code))
        assertEquals(Constants.CODE_SPACE, bg('a'.code, typed = Constants.CODE_SPACE))
        for (language in listOf("en", "de", "ru", "", "BG", "bgx")) assertEquals(plain, bg(' '.code, language = language), "language '$language'")
    }
}
