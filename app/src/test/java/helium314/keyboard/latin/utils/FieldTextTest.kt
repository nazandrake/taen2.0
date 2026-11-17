// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.text.InputType.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a number, phone or date field takes of a pasted text. Expected values are written by hand from the field's accepted characters. */
class FieldTextTest {
    private val integer = TYPE_CLASS_NUMBER
    private val signed = TYPE_CLASS_NUMBER or TYPE_NUMBER_FLAG_SIGNED
    private val decimal = TYPE_CLASS_NUMBER or TYPE_NUMBER_FLAG_DECIMAL
    private val signedDecimal = TYPE_CLASS_NUMBER or TYPE_NUMBER_FLAG_SIGNED or TYPE_NUMBER_FLAG_DECIMAL

    private fun paste(text: String, inputType: Int) = FieldText.forField(text, inputType)

    @Test fun anIntegerFieldTakesDigitsOnly() {
        assertEquals("123", paste("12abc3", integer))
        assertEquals("1234", paste("1 234", integer))
        assertEquals("5", paste("-5", integer)) // no sign accepted
        assertEquals("125", paste("12.5", integer)) // no separator accepted
        assertEquals("15", paste("1e5", integer))
        assertEquals("", paste("NaN", integer))
        assertEquals("", paste("Infinity", integer))
        assertEquals("", paste("", integer))
        assertEquals("", paste("٣", integer)) // an Arabic-Indic digit is not one the layout types
    }

    @Test fun aSignedFieldTakesOneLeadingSign() {
        assertEquals("-5", paste("-5", signed))
        assertEquals("+5", paste("+5", signed))
        assertEquals("-5", paste("  -5", signed))
        assertEquals("-5", paste("−5", signed)) // the typographic minus is a minus
        assertEquals("53", paste("5-3", signed)) // a sign inside is not a leading sign
        assertEquals("-53", paste("-5-3", signed))
        assertEquals("", paste("-", signed))
        assertEquals("5", paste("5", signed)) // positive control: no sign is added
    }

    @Test fun aDecimalFieldTakesTheLastSeparatorAsTheDecimalPoint() {
        assertEquals("3.14", paste("3.14", decimal))
        assertEquals("3,14", paste("3,14", decimal))
        assertEquals("1234.56", paste("1,234.56", decimal))
        assertEquals("1234,56", paste("1.234,56", decimal))
        assertEquals("125.3", paste("12.5.3", decimal))
        assertEquals("12.", paste("12.", decimal))
        assertEquals(".5", paste(".5", decimal))
        assertEquals("", paste(".", decimal))
        assertEquals("", paste(",", decimal))
        assertEquals("-0.5", paste("-0.5", decimal.or(TYPE_NUMBER_FLAG_SIGNED)))
        assertEquals("0.5", paste("-0.5", decimal)) // the field takes no sign
    }

    @Test fun aSignedDecimalFieldTakesBoth() {
        assertEquals("-1234.50", paste("-1,234.50 EUR", signedDecimal))
        assertEquals("+7", paste("+7 kg", signedDecimal))
    }

    @Test fun aNumberPasswordFieldTakesDigitsOnly() {
        assertEquals("123", paste("12-3", TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test fun aPhoneFieldTakesTheDialerCharacters() {
        assertEquals("+359 88 123-456", paste("+359 88 123-456", TYPE_CLASS_PHONE))
        assertEquals("+1*2#3", paste("ab+1*2#3xyz", TYPE_CLASS_PHONE))
        assertEquals("(02) 9/8,7;N.", paste("(02) 9/8,7;N.", TYPE_CLASS_PHONE))
        assertEquals("", paste("callme", TYPE_CLASS_PHONE)) // lower case letters are not dialer characters
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
