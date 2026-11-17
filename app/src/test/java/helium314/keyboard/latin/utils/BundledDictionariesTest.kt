// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** The dictionaries inside the APK are English (en-GB) and Bulgarian only; every other language is added by the user from a file or a listed download. */
@RunWith(RobolectricTestRunner::class)
class BundledDictionariesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun theBundledDictionaryListIsEnglishAndBulgarian() {
        assertEquals(listOf("main_bg.dict", "main_en-GB.dict"), DictionaryInfoUtils.getAssetsDictionaryList(ctx)!!.sorted())
    }
}
