// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.quick

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Switch keyboard tile: the shade tap opens the system keyboard picker, after the unlock when the phone is locked. */
class SwitchKeyboardTileTest {
    @Test fun anUnlockedTapOpensThePickerAtOnce() {
        val calls = mutableListOf<String>()
        SwitchKeyboardTile.click(locked = false, unlockAndRun = { calls.add("unlock") }, open = { calls.add("open") })
        assertEquals(listOf("open"), calls)
    }

    @Test fun aLockedTapOpensNothingUntilTheSystemRunsItAfterTheUnlock() {
        val calls = mutableListOf<String>()
        var afterUnlock: Runnable? = null
        SwitchKeyboardTile.click(locked = true, unlockAndRun = { calls.add("unlock"); afterUnlock = it }, open = { calls.add("open") })
        assertEquals(listOf("unlock"), calls, "the unlock is asked for and the picker is not open yet")
        afterUnlock!!.run() // the user unlocked
        assertEquals(listOf("unlock", "open"), calls)
    }

    @Test fun aLockedTapThatIsNeverUnlockedOpensNothing() {
        var opened = 0
        var asked = 0
        SwitchKeyboardTile.click(locked = true, unlockAndRun = { asked++ }, open = { opened++ })
        assertEquals(1, asked, "positive control: the unlock was asked for")
        assertEquals(0, opened)
    }

    @Test fun theIntentFormOfCollapseIsUsedBelowAndroid14AndThePendingIntentFormFrom14() {
        for (sdk in listOf(30, 31, 33)) assertFalse(SwitchKeyboardTile.usesPendingIntent(sdk), "sdk $sdk")
        for (sdk in listOf(34, 35, 36, 37)) assertTrue(SwitchKeyboardTile.usesPendingIntent(sdk), "sdk $sdk")
    }
}
