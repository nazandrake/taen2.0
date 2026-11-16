// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.test.Test
import kotlin.test.assertEquals

class ImeSwitcherTest {
    @Test fun aTapCyclesLanguagesThenGoesToAnotherKeyboardThenDoesNothing() {
        assertEquals(ImeSwitcher.Tap.NEXT_LANGUAGE, ImeSwitcher.tap(otherLanguages = true, somethingToSwitchTo = true))
        assertEquals(ImeSwitcher.Tap.NEXT_LANGUAGE, ImeSwitcher.tap(otherLanguages = true, somethingToSwitchTo = false))
        assertEquals(ImeSwitcher.Tap.NEXT_KEYBOARD, ImeSwitcher.tap(otherLanguages = false, somethingToSwitchTo = true))
        assertEquals(ImeSwitcher.Tap.NOTHING, ImeSwitcher.tap(otherLanguages = false, somethingToSwitchTo = false))
    }

    @Test fun theSystemBarIsRequestedOnlyWithSomethingToSwitchTo() {
        assertEquals(true, ImeSwitcher.shouldRequest(true))
        assertEquals(false, ImeSwitcher.shouldRequest(false))
    }
}
