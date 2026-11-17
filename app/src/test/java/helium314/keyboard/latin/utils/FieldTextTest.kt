// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.os.LocaleList
import android.text.InputType.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a number, phone or date field takes of a pasted text. The rule for number fields: the FIRST number of the text, read with its common
 * separators (1,234.50 / 1.234,50 / 1 234,50); a signed field keeps a "-" directly before it; an integer field takes the integer part only; a
 * decimal field keeps the decimal part with the field's own separator; a number that can be read two ways pastes nothing. Phone fields keep
 * digits, +, space, -, ( and ). Expected values are written by hand from that rule.
 */
@RunWith(RobolectricTestRunner::class)
class FieldTextTest {
    private val integer = TYPE_CLASS_NUMBER
    private val signed = TYPE_CLASS_NUMBER or TYPE_NUMBER_FLAG_SIGNED
    private val decimal = TYPE_CLASS_NUMBER or TYPE_NUMBER_FLAG_DECIMAL
    private val signedDecimal = TYPE_CLASS_NUMBER or TYPE_NUMBER_FLAG_SIGNED or TYPE_NUMBER_FLAG_DECIMAL

    private fun paste(text: String, inputType: Int, separator: Char = '.') = FieldText.forField(text, inputType, separator)

    @Test fun theFirstNumberOfTheTextIsPasted() {
        assertEquals("12", paste("12abc3", integer))
        assertEquals("3", paste("from 3 to 7", integer))
        assertEquals("1", paste("1e5", integer))
        assertEquals("1", paste("+1 (2) 3x", integer))
        assertEquals("2026", paste("Invoice 2026-11-17", integer))
        assertEquals("42", paste("42", integer)) // positive control: a plain number as it is
        assertEquals("007", paste("007", integer)) // leading zeros are the field's business
    }

    @Test fun nothingIsPastedWithoutANumber() {
        for (text in listOf("", "NaN", "Infinity", "-", ".", ",", "no digits", "٣")) // an Arabic-Indic digit is not one the layout types
            for (type in listOf(integer, signed, decimal, signedDecimal))
                assertEquals("", paste(text, type), "'$text' in type $type")
    }

    @Test fun theCommonSeparatorsAreRead() {
        for (text in listOf("1,234.50", "1.234,50", "1 234,50", "1 234,50", "1 234,50", "1'234.50", "1,234.50 EUR", "EUR 1.234,50"))
            assertEquals("1234.50", paste(text, decimal), text)
        assertEquals("1234567.89", paste("1,234,567.89", decimal))
        assertEquals("1234567.89", paste("1.234.567,89", decimal))
        assertEquals("1234567", paste("1,234,567", decimal)) // two group separators of one kind: grouping, no fraction
        assertEquals("1234567", paste("1 234 567", integer))
        assertEquals("3.14", paste("3.14", decimal))
        assertEquals("3.14", paste("3,14", decimal))
        assertEquals("0.123", paste("0.123", decimal)) // a 0 does not start a group of thousands
        assertEquals("1234.567", paste("1234.567", decimal)) // four digits before: not a group
        assertEquals("1.5", paste("1,5", decimal))
    }

    @Test fun aNumberThatCanBeReadTwoWaysPastesNothing() {
        for (text in listOf("1,234", "1.234", "12.345", "1 234.567", "12.5.3", "1.234.56", "1,234.567,8"))
            for (type in listOf(integer, decimal))
                assertEquals("", paste(text, type), "'$text' in type $type")
        assertEquals("1", paste("1 2 3", integer)) // a space joins a group of three digits only
    }

    @Test fun anIntegerFieldTakesTheIntegerPart() {
        assertEquals("12", paste("12.50", integer))
        assertEquals("1234", paste("-1,234.50 EUR", integer)) // no sign accepted
        assertEquals("-1234", paste("-1,234.50 EUR", signed))
        assertEquals("1", paste("1.5", integer))
        assertEquals("0", paste(".5", integer))
        assertEquals("0", paste("-0.5", signed)) // the integer part of -0.5 is 0, not -0
        assertEquals("12", paste("12.", integer))
    }

    @Test fun aSignedFieldKeepsAMinusDirectlyBeforeTheNumber() {
        assertEquals("-5", paste("-5", signed))
        assertEquals("-5", paste("Total: -5", signed))
        assertEquals("-5", paste("  −5", signed)) // the typographic minus is a minus
        assertEquals("5", paste("- 5", signed)) // not directly before
        assertEquals("5", paste("+5", signed)) // a plus is no sign to keep: the number is the same
        assertEquals("5", paste("5-3", signed))
        assertEquals("5", paste("a-b 5", signed))
        assertEquals("5", paste("-5", integer)) // the field takes no sign
        assertEquals("5", paste("5", signed)) // positive control: no sign is added
    }

    @Test fun aDecimalFieldKeepsTheDecimalPartWithItsOwnSeparator() {
        assertEquals("1234.50", paste("1.234,50", decimal, '.'))
        assertEquals("1234,50", paste("1,234.50", decimal, ','))
        assertEquals("3,14", paste("3.14", decimal, ','))
        assertEquals("12", paste("12", decimal, ',')) // no fraction, no separator
        assertEquals("12", paste("12.", decimal)) // a separator with no digit after it is not part of the number
        assertEquals("0.5", paste(".5", decimal))
        assertEquals("-0.5", paste("-0.5", signedDecimal))
        assertEquals("0.5", paste("-0.5", decimal)) // the field takes no sign
        assertEquals("-1234.50", paste("-1,234.50 EUR", signedDecimal))
        assertEquals("7", paste("+7 kg", signedDecimal))
    }

    @Test fun theFieldSeparatorIsADotUnlessTheFieldNamesALocale() {
        assertEquals('.', FieldText.decimalSeparator(null))
        assertEquals('.', FieldText.decimalSeparator(LocaleList.getEmptyLocaleList()))
        assertEquals(',', FieldText.decimalSeparator(LocaleList(Locale.GERMANY)))
        assertEquals(',', FieldText.decimalSeparator(LocaleList(Locale.forLanguageTag("bg-BG"))))
        assertEquals('.', FieldText.decimalSeparator(LocaleList(Locale.US)))
        assertEquals('.', FieldText.decimalSeparator(LocaleList(Locale.US, Locale.GERMANY))) // the first locale counts
    }

    @Test fun aNumberPasswordFieldTakesTheFirstNumber() {
        assertEquals("12", paste("12-3", TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD))
        assertEquals("1234", paste("PIN: 1234", TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test fun aPhoneFieldKeepsDigitsPlusSpaceMinusAndBrackets() {
        assertEquals("+359 88 123-456", paste("+359 88 123-456", TYPE_CLASS_PHONE))
        assertEquals("+1 (555) 123-4567", paste("call +1 (555) 123-4567#", TYPE_CLASS_PHONE))
        assertEquals("+123", paste("ab+1*2#3xyz", TYPE_CLASS_PHONE))
        assertEquals("(02) 987", paste("(02) 9/8,7;N.", TYPE_CLASS_PHONE))
        assertEquals("", paste("callme", TYPE_CLASS_PHONE))
        assertEquals("", paste("+ - ( )", TYPE_CLASS_PHONE)) // no digit, nothing to dial
        assertEquals("12", paste("1\n2", TYPE_CLASS_PHONE))
    }

    @Test fun aDateAndATimeFieldTakeTheirOwnCharacters() {
        assertEquals("2026-11-17", paste("2026-11-17 ok", TYPE_CLASS_DATETIME or TYPE_DATETIME_VARIATION_DATE))
        assertEquals("17.11.2026", paste("17.11.2026", TYPE_CLASS_DATETIME or TYPE_DATETIME_VARIATION_DATE))
        assertEquals("10:30 PM", paste("10:30 PM!", TYPE_CLASS_DATETIME or TYPE_DATETIME_VARIATION_TIME))
        assertEquals("1730", paste("17/30", TYPE_CLASS_DATETIME or TYPE_DATETIME_VARIATION_TIME)) // no date separator in a time
        assertEquals("17/11 10:30", paste("17/11 10:30", TYPE_CLASS_DATETIME or TYPE_DATETIME_VARIATION_NORMAL))
    }

    @Test fun otherFieldsGetTheTextUnchanged() {
        val text = "12abc3\n+ ٣ NaN"
        assertEquals(text, paste(text, TYPE_CLASS_TEXT))
        assertEquals(text, paste(text, TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD))
        assertEquals(text, paste(text, TYPE_NULL))
        assertEquals(text, paste(text, TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_CAP_WORDS)) // same bit as the decimal flag, another class
    }

    @Test fun theNumericFieldsAreNumberPhoneAndDate() {
        for (type in listOf(integer, decimal, signed, TYPE_CLASS_PHONE, TYPE_CLASS_DATETIME, TYPE_CLASS_DATETIME or TYPE_DATETIME_VARIATION_TIME))
            assertTrue(FieldText.isNumericField(type), "$type")
        for (type in listOf(TYPE_CLASS_TEXT, TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD, TYPE_NULL, TYPE_CLASS_TEXT or TYPE_TEXT_FLAG_CAP_WORDS))
            assertFalse(FieldText.isNumericField(type), "$type")
    }
}
