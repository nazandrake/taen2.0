package helium314.keyboard

import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import helium314.keyboard.latin.spellcheck.AndroidSpellCheckerService
import helium314.keyboard.latin.spellcheck.AndroidSpellCheckerSession
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodManager2::class,
    ShadowDictionaryFacilitatorImpl::class,
])
class SpellCheckerTest {
    private val checkService = Robolectric.setupService(AndroidSpellCheckerService::class.java)
    private val checker = AndroidSpellCheckerSession(checkService)

    @Test fun `number is not a typo`() {
        val result = checker.onGetSuggestions(TextInfo("123"), 2)
        assertNotEquals(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO, result.suggestionsAttributes)
    }

    @Test fun `$ is not a typo`() {
        val result = checker.onGetSuggestions(TextInfo("$"), 2)
        assertNotEquals(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO, result.suggestionsAttributes)
    }

    // the system spell checker has no field and never uses learned or personal words
    private fun personalized(): Boolean {
        val values = AndroidSpellCheckerService::class.java.getDeclaredField("mSettingsValuesForSuggestion").apply { isAccessible = true }
            .get(checkService) as helium314.keyboard.latin.settings.SettingsValuesForSuggestion
        return values.mPersonalized
    }

    @Test fun `spell checker settings never allow learned or personal words`() {
        assertFalse(personalized())
        checkService.onSharedPreferenceChanged(checkService.prefs(), helium314.keyboard.latin.settings.Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE)
        assertFalse(personalized()) // also after a setting changed
    }
}
