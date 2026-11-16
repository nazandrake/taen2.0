// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The digits of the PIN pad live in one array that is zeroed, never in Strings (a prefix String per digit cannot be wiped). */
class PinDigitsTest {
    private val zero = '\u0000'

    @Test fun digitsAreCollectedUpToTheLimitAndTheLastCanBeRemoved() {
        val d = PinDigits(4)
        for (c in "1234") assertTrue(d.add(c))
        assertFalse(d.add('5')) // at the limit
        assertEquals(4, d.length)
        d.removeLast()
        assertEquals(3, d.length)
        assertTrue(d.add('9'))
        assertContentEquals("1239".toCharArray(), d.take())
    }

    @Test fun removingFromAnEmptyPadDoesNothing() {
        val d = PinDigits(4)
        d.removeLast()
        assertEquals(0, d.length)
        assertContentEquals(CharArray(0), d.take())
    }

    @Test fun aRemovedDigitIsZeroedAtOnce() {
        val d = PinDigits(8)
        for (c in "4829") d.add(c)
        d.removeLast()
        assertContentEquals(charArrayOf('4', '8', '2', zero, zero, zero, zero, zero), d.backing())
    }

    @Test fun takingTheDigitsGivesACopyAndLeavesNothingInThePad() {
        val d = PinDigits(8)
        for (c in "482915") d.add(c)
        val taken = d.take()
        assertContentEquals("482915".toCharArray(), taken)
        assertTrue(d.backing().all { it == zero })
        assertEquals(0, d.length)
        taken.fill(zero) // the caller clears its copy: the pad does not share it
        d.add('7')
        assertContentEquals("7".toCharArray(), d.take())
    }

    @Test fun clearZeroesEverything() {
        val d = PinDigits(16)
        for (c in "1234567890123456") d.add(c)
        d.clear()
        assertTrue(d.backing().all { it == zero })
        assertEquals(0, d.length)
    }
}
