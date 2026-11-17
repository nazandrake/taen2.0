// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.icu.text.DecimalFormatSymbols
import android.os.LocaleList
import android.text.InputType

/**
 * What a number, phone or date field accepts of a pasted text. One owner for the clipboard paths (the paste key, a history entry,
 * the clipboard suggestion); typed keys are not filtered here. Other fields get the text unchanged.
 *
 * Number fields take the FIRST number of the text, read with its common separators (1,234.50 / 1.234,50 / 1 234,50 / 1'234.50). A signed
 * field keeps a minus directly before that number; an integer field takes its integer part; a decimal field keeps the decimal part with the
 * field's own separator. A number that can be read two ways (1,234: a thousand, or one point two) pastes nothing rather than a wrong number.
 * A text of digits and spaces only (a card or phone-style number, a leading + allowed) is one number: the groups are joined, the plus dropped. A
 * number is never pasted in part: when it goes on in a group that no reading of it takes in, nothing is pasted.
 * Phone fields keep digits, +, space, -, ( and ).
 */
object FieldText {
    private const val PHONE_CHARS = "+-() "
    private const val DATE_CHARS = "/-."
    private const val TIME_CHARS = ":. APMapm"
    /** Decimal point or group separator, as the text uses it. */
    private const val POINTS = ".,"
    /** Group separators only: spaces (also no-break and narrow no-break) and apostrophes. */
    private const val GROUP_ONLY = "   '’"

    /** The field is a number, phone or date/time field: the keyboard shows its number layout and offers the field keys. */
    @JvmStatic fun isNumericField(inputType: Int): Boolean = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
        else -> false
    }

    /**
     * [clip] reduced to what the field accepts; text fields get it unchanged. An empty result means: paste nothing. [decimalSeparator] is the
     * field's own decimal separator ([decimalSeparator] of the field's hint locales).
     */
    @JvmStatic @JvmOverloads fun forField(clip: CharSequence, inputType: Int, decimalSeparator: Char = '.'): String {
        val text = clip.toString()
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> number(text, inputType, decimalSeparator)
            InputType.TYPE_CLASS_PHONE -> text.filter { it.isAsciiDigit() || it in PHONE_CHARS }.trim().takeIf { r -> r.any { it.isAsciiDigit() } }.orEmpty()
            InputType.TYPE_CLASS_DATETIME -> when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_DATETIME_VARIATION_DATE -> text.filter { it.isAsciiDigit() || it in DATE_CHARS }
                InputType.TYPE_DATETIME_VARIATION_TIME -> text.filter { it.isAsciiDigit() || it in TIME_CHARS }
                else -> text.filter { it.isAsciiDigit() || it in DATE_CHARS || it in TIME_CHARS }
            }
            else -> text
        }
    }

    /**
     * The decimal separator a number field accepts: the platform's decimal field takes "." unless the app names a locale for it
     * (EditorInfo.hintLocales); then the first locale's separator, as the platform's key listener for that field does.
     */
    @JvmStatic fun decimalSeparator(locales: LocaleList?): Char {
        if (locales == null || locales.isEmpty) return '.'
        val separator = DecimalFormatSymbols.getInstance(locales[0]).decimalSeparatorString
        return if (separator.length == 1) separator[0] else '.'
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    /** A text of digits and spaces only, optionally with a leading plus: a card or phone-style number. */
    private val DIGITS_AND_SPACES = Regex("""\+?[0-9]+(?:[   ]+[0-9]+)+""")

    private fun number(text: String, inputType: Int, decimalSeparator: Char): String {
        // digit groups separated by spaces are one number whatever their sizes: 4111 1111 1111 1111, +359 88 123 4567 (the plus is not a digit)
        text.trim().takeIf { DIGITS_AND_SPACES.matches(it) }?.let { return it.filter { c -> c.isAsciiDigit() } }
        val parsed = firstNumber(text) ?: return ""
        val signed = inputType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0
        val fraction = if (inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0) parsed.fraction else null
        val digits = parsed.integer + fraction.orEmpty()
        val minus = if (signed && parsed.negative && digits.any { it != '0' }) "-" else "" // the integer part of -0.5 is 0, not -0
        return minus + parsed.integer + (fraction?.let { decimalSeparator + it } ?: "")
    }

    /** A number of a text: its integer digits, its fraction digits (null without one) and whether a minus is directly before it. */
    private class Parsed(val integer: String, val fraction: String?, val negative: Boolean)

    /** The first number of [text], or null when there is none or when it can be read two ways. */
    private fun firstNumber(text: String): Parsed? {
        val start = text.indexOfFirst { it.isAsciiDigit() }
        if (start < 0) return null
        // digit runs joined by one separator each; a space or an apostrophe joins only a group of exactly three digits
        val runs = ArrayList<String>()
        val separators = ArrayList<Char>()
        var i = start
        while (true) {
            var end = i
            while (end < text.length && text[end].isAsciiDigit()) end++
            runs.add(text.substring(i, end))
            if (end + 1 >= text.length || !text[end + 1].isAsciiDigit()) break
            val separator = text[end]
            if (separator !in POINTS && separator !in GROUP_ONLY) break
            var next = end + 1
            while (next < text.length && text[next].isAsciiDigit()) next++
            if (separator in GROUP_ONLY && next - (end + 1) != 3) return null // the number goes on in a group that is no thousands group: never paste a part of it
            separators.add(separator)
            i = end + 1
        }
        // ".5": a point right before the first digit starts a fraction
        val leadingPoint = start > 0 && text[start - 1] in POINTS
        val signAt = (if (leadingPoint) start - 1 else start) - 1
        val negative = signAt >= 0 && (text[signAt] == '-' || text[signAt] == '−')
        if (leadingPoint) return if (separators.isEmpty()) Parsed("0", runs[0], negative) else null

        var integerRuns: List<String> = runs
        var fraction: String? = null
        if (separators.isNotEmpty() && separators.last() in POINTS) {
            val last = separators.last()
            val otherPoints = separators.dropLast(1).filter { it in POINTS }
            when {
                otherPoints.any { it != otherPoints.first() } -> return null // 1,234.567,8: no reading
                otherPoints.isNotEmpty() && otherPoints.first() == last -> Unit // 1,234,567: all of them group thousands
                otherPoints.isNotEmpty() -> { // 1,234.50 and 1.234,50: the last one is the decimal point
                    integerRuns = runs.dropLast(1)
                    fraction = runs.last()
                }
                else -> { // one point: 12.50 is a fraction; 1,234 or 1 234.567 could also group thousands
                    if (isGrouped(runs)) return null
                    integerRuns = runs.dropLast(1)
                    fraction = runs.last()
                }
            }
        }
        if (!isGrouped(integerRuns)) return null // 12.5.3, 1.234.56: no reading
        return Parsed(integerRuns.joinToString(""), fraction, negative)
    }

    /** The digit runs form one number grouped by thousands: a single run, or 1 to 3 digits (not starting with 0) followed by groups of three. */
    private fun isGrouped(runs: List<String>): Boolean {
        if (runs.size == 1) return true
        val first = runs.first()
        return first.length in 1..3 && first[0] != '0' && runs.drop(1).all { it.length == 3 }
    }
}
