// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic

import helium314.keyboard.latin.common.Constants

/**
 * Smart quotes. In Bulgarian the quote key types „ (U+201E) to open a quotation and “ (U+201C) to close it, the quotation
 * marks of the Bulgarian orthography. [convert] decides from the character before the cursor:
 * - at the start of the text, after white space or after an opening bracket the quote opens;
 * - after a digit it stays the plain quote (inches, seconds);
 * - with a selection it opens (wrapping the selection adds the closing one);
 * - after anything else (a letter, punctuation, a closing quote, an opening quote: an empty pair) it closes.
 * Only the plain quote U+0022 in Bulgarian is converted; every other character and language is returned as typed.
 */
object SmartQuotes {
    const val BULGARIAN = "bg"
    private const val PLAIN = '"'.code
    private const val OPEN = '„'.code
    private const val CLOSE = '“'.code
    private const val OPENING_BRACKETS = "([{<"

    /** The code point to type for [typed], given the code point before the cursor ([Constants.NOT_A_CODE] at the start). */
    @JvmStatic
    fun convert(typed: Int, codePointBefore: Int, hasSelection: Boolean, language: String): Int {
        if (typed != PLAIN || language != BULGARIAN) return typed
        if (hasSelection) return OPEN
        if (codePointBefore == Constants.NOT_A_CODE) return OPEN
        if (Character.isWhitespace(codePointBefore) || Character.isSpaceChar(codePointBefore)) return OPEN
        if (codePointBefore < 0x10000 && OPENING_BRACKETS.indexOf(codePointBefore.toChar()) >= 0) return OPEN
        if (Character.isDigit(codePointBefore)) return PLAIN
        return CLOSE
    }
}
