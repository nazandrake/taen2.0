// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.text.InputType

/**
 * What a number, phone or date field accepts of a pasted text. One owner for the clipboard paths (the paste key, a history entry,
 * the clipboard suggestion); typed keys are not filtered here. Other fields get the text unchanged.
 */
object FieldText {
    private const val DIALER_CHARS = "0123456789#*+-(),;/N. "
    private const val DATE_CHARS = "/-."
    private const val TIME_CHARS = ":. APMapm"

    /** The field is a number, phone or date/time field: the keyboard shows its number layout and offers the field keys. */
    @JvmStatic fun isNumericField(inputType: Int): Boolean = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
        else -> false
    }

    /** [text] reduced to what the field accepts, in order; text fields get it unchanged. An empty result means: paste nothing. */
    @JvmStatic fun forField(clip: CharSequence, inputType: Int): String {
        val text = clip.toString()
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> number(text, inputType)
            InputType.TYPE_CLASS_PHONE -> text.filter { it in DIALER_CHARS }
            InputType.TYPE_CLASS_DATETIME -> when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_DATETIME_VARIATION_DATE -> text.filter { it.isAsciiDigit() || it in DATE_CHARS }
                InputType.TYPE_DATETIME_VARIATION_TIME -> text.filter { it.isAsciiDigit() || it in TIME_CHARS }
                else -> text.filter { it.isAsciiDigit() || it in DATE_CHARS || it in TIME_CHARS }
            }
            else -> text
        }
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun number(text: String, inputType: Int): String {
        val signed = inputType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0
        val decimal = inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0
        val sign = if (signed) text.firstOrNull { !it.isWhitespace() }?.let { if (it == '-' || it == '\u2212') "-" else if (it == '+') "+" else "" }.orEmpty() else ""
        val digitsAndSeparators = text.filter { it.isAsciiDigit() || (decimal && (it == '.' || it == ',')) }
        if (digitsAndSeparators.none { it.isAsciiDigit() }) return "" // a sign or a separator alone is nothing to paste
        if (!decimal) return sign + digitsAndSeparators
        // the last separator is the decimal one (1,234.56 and 1.234,56 both give a decimal fraction); earlier ones group thousands
        val lastSeparator = digitsAndSeparators.indexOfLast { it == '.' || it == ',' }
        val cleaned = buildString {
            digitsAndSeparators.forEachIndexed { i, c -> if (c.isAsciiDigit() || i == lastSeparator) append(c) }
        }
        return sign + cleaned
    }
}
