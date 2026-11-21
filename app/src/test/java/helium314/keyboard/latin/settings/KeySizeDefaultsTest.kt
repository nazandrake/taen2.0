// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.profiles.ProfileKeys
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** The keys are taller by default in portrait, so that fewer letters are mistyped; a height the user chose stays, and landscape is as it was. */
@RunWith(RobolectricTestRunner::class)
class KeySizeDefaultsTest {
    private fun prefs() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("key_size_defaults", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    // the portrait default is 110 % (166 px keys at 100 %, 183 px at 110 % on a 1080x1920 screen)
    @Test fun thePortraitKeyboardIsTallerByDefault() {
        assertEquals(1.1f, Settings.readHeightScale(prefs(), false, false))
    }

    // every screen state (landscape, folded), a stored value of the user wins, 100 % stays possible, the gap default is unchanged
    @Test fun portraitAndFoldedPortraitAreTallerAndLandscapeIsUnchanged() {
        val p = prefs()
        assertEquals(1.1f, Settings.readHeightScale(p, false, false), "portrait")
        assertEquals(1.1f, Settings.readHeightScale(p, false, true), "folded portrait")
        assertEquals(1.0f, Settings.readHeightScale(p, true, false), "landscape")
        assertEquals(1.0f, Settings.readHeightScale(p, true, true), "folded landscape")
    }

    @Test fun aHeightTheUserChoseKeepsItsValueIncludingOneHundredPercent() {
        val p = prefs()
        p.edit().putFloat(ProfileKeys.heightKey(landscape = false, folded = false), 1.0f).commit()
        assertEquals(1.0f, Settings.readHeightScale(p, false, false), "the user put the height back to 100 %")
        p.edit().putFloat(ProfileKeys.heightKey(landscape = false, folded = false), 0.8f).commit()
        assertEquals(0.8f, Settings.readHeightScale(p, false, false))
        assertEquals(1.1f, Settings.readHeightScale(p, false, true), "another state is not touched by it")
    }

    @Test fun theKeyGapDefaultIsUnchanged() {
        val p = prefs()
        for (landscape in listOf(false, true)) for (folded in listOf(false, true))
            assertEquals(1.0f, Settings.readKeyGapScale(p, landscape, folded))
    }
}
