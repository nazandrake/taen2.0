package helium314.keyboard

import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import helium314.keyboard.latin.spellcheck.AndroidSpellCheckerService
import helium314.keyboard.latin.spellcheck.AndroidSpellCheckerSession
import helium314.keyboard.latin.R
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    @Test fun `spell checker checks in the language the keyboard tells the system`() {
        val bulgarian = SubtypeSettings.getResourceSubtypesForLocale(Locale.forLanguageTag("bg")).first()
        val english = SubtypeSettings.getResourceSubtypesForLocale(Locale.US).first()
        try {
            ShadowInputMethodService.systemCurrent = RichInputMethodManager.systemSubtypeFor(bulgarian)!!
            assertEquals("bg", checker.locale)
            ShadowInputMethodService.systemCurrent = RichInputMethodManager.systemSubtypeFor(english)!!
            assertEquals("en_US", checker.locale)
        } finally {
            ShadowInputMethodService.reset()
        }
    }

    @Test fun `spell checker offers Bulgarian and English to the system`() {
        val locales = ArrayList<String>()
        val xml = checkService.resources.getXml(R.xml.spellchecker)
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == XmlPullParser.START_TAG && xml.name == "subtype")
                locales.add(xml.getAttributeValue("http://schemas.android.com/apk/res/android", "subtypeLocale"))
        }
        assertTrue("bg" in locales, locales.toString())
        assertTrue("en_US" in locales)
        assertEquals(locales.size, locales.toSet().size, "no locale twice")
    }

    @Test fun `spell checker settings never allow learned or personal words`() {
        assertFalse(personalized())
        checkService.onSharedPreferenceChanged(checkService.prefs(), helium314.keyboard.latin.settings.Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE)
        assertFalse(personalized()) // also after a setting changed
    }
}
