// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P9: PrivacyPolicy is the single place where "incognito" and "secure field" are decided. */
class PrivacyPolicyTest {
    private val text = InputType.TYPE_CLASS_TEXT

    private fun decide(
        inputType: Int = text, imeOptions: Int = 0, packageName: String? = "org.example.app", force: Boolean = false,
        apps: Set<String> = emptySet(), locked: Boolean = false, secureClipboard: Boolean = false,
    ) = PrivacyPolicy.decide(PrivacyPolicy.Input(inputType, imeOptions, packageName, force, apps, locked, secureClipboard))

    // ---- the core behaviour -----------------------------------------------------------------------------------

    @Test fun aPasswordFieldIsIncognitoWithoutKeyPreviewsAndAPlainFieldIsNot() {
        val password = decide(inputType = text or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertTrue(password.passwordField)
        assertTrue(password.incognito)
        assertFalse(password.keyPreviews)
        val plain = decide()
        assertFalse(plain.passwordField)
        assertFalse(plain.incognito) // positive control: the policy does not make everything incognito
        assertTrue(plain.keyPreviews)
    }

    /** it gives a password field the strip keys and marks its copies, and nothing is remembered more. */
    @Test fun theClipboardSettingGivesAPasswordFieldItsStripKeys() {
        val pw = text or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertFalse(decide(inputType = pw).suggestionStrip, "off: no strip keys")
        val on = decide(inputType = pw, secureClipboard = true)
        assertTrue(on.suggestionStrip)
        assertTrue(on.sensitiveCopy)
        assertTrue(on.incognito)
        assertFalse(on.storeClipboard)
    }

    /** every password variation, on and off; the setting changes the strip keys and nothing that is remembered. */
    @Test fun theClipboardSettingChangesOnlyTheStripKeysOfEveryPasswordVariation() {
        for ((name, type) in passwordTypes) {
            val off = decide(inputType = type)
            val on = decide(inputType = type, secureClipboard = true)
            assertFalse(off.suggestionStrip, "$name off: no strip keys")
            assertTrue(on.suggestionStrip, "$name on: strip keys")
            for (d in listOf(off, on)) {
                assertTrue(d.passwordField, name)
                assertTrue(d.incognito, "$name stays incognito")
                assertFalse(d.learn, name)
                assertFalse(d.storeClipboard, "$name: its copies are never stored")
                assertFalse(d.recordRecents, name)
                assertFalse(d.personalizedSuggestions, name)
                assertFalse(d.clipboardSuggestion, "$name: the clip is never suggested in it")
                assertFalse(d.keyPreviews, name)
                assertFalse(d.fieldEditing, "$name: no space bar selection, no reading of the field")
                assertTrue(d.sensitiveCopy, "$name: a copy from it is sensitive")
            }
        }
    }

    @Test fun theClipboardSettingDoesNotMakeAPlainFieldDifferent() {
        val plainOff = decide()
        val plainOn = decide(secureClipboard = true)
        for (d in listOf(plainOff, plainOn)) {
            assertTrue(d.suggestionStrip)
            assertFalse(d.sensitiveCopy, "a copy from a plain field is not flagged")
            assertFalse(d.incognito)
            assertTrue(d.storeClipboard)
            assertTrue(d.clipboardSuggestion)
        }
        // a field that is only no-learning is not a password field: the setting is not what gives it its strip
        val noLearning = decide(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, secureClipboard = false)
        assertTrue(noLearning.suggestionStrip)
        assertFalse(noLearning.sensitiveCopy)
    }

    @Test fun theSettingIsOffInTheDecisionUnlessSet() {
        assertFalse(PrivacyPolicy.decide(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0, null, false, emptySet(), false).suggestionStrip)
        assertTrue(PrivacyPolicy.decide(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0, null, false, emptySet(), false, true).suggestionStrip)
    }

    @Test fun forceIncognitoMakesAPlainFieldIncognito() {
        assertTrue(decide(force = true).incognito)
        assertFalse(decide(force = false).incognito)
    }

    // ---- the whole contract -----------------------------------------------------------------------------------

    // the four password variations of Android (TextView.isPasswordInputType and isVisiblePasswordInputType)
    private val passwordTypes = mapOf(
        "text password" to (text or InputType.TYPE_TEXT_VARIATION_PASSWORD),
        "visible password" to (text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
        "web password" to (text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
        "number password" to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
    )

    @Test fun everyPasswordVariationGetsTheSecureMode() {
        for ((name, type) in passwordTypes) {
            for (extra in listOf(0, InputType.TYPE_TEXT_FLAG_MULTI_LINE, InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)) {
                // the flags of a number field are not the text flags, so only text types take the extra bits
                val inputType = if (type and InputType.TYPE_MASK_CLASS == text) type or extra else type
                val d = decide(inputType = inputType)
                assertTrue(d.passwordField, "$name $extra")
                assertTrue(d.incognito, name)
                assertFalse(d.keyPreviews, name)
                assertFalse(d.clipboardSuggestion, name)
                assertFalse(d.suggestionStrip, name)
                assertFalse(d.vimMode, name) // no Vim layer in a password field
                assertFalse(d.storeClipboard, name)
                assertFalse(d.learn, name)
                assertFalse(d.recordRecents, name)
                assertFalse(d.personalizedSuggestions, name)
            }
        }
    }

    @Test fun fieldsThatLookLikePasswordsButAreNotKeepTheirNormalBehaviour() {
        val others = mapOf(
            "normal text" to text,
            "email" to (text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            "uri" to (text or InputType.TYPE_TEXT_VARIATION_URI),
            "person name" to (text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME),
            "web edit" to (text or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT),
            "number" to InputType.TYPE_CLASS_NUMBER,
            "decimal number" to (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL),
            "phone" to InputType.TYPE_CLASS_PHONE,
            "datetime" to InputType.TYPE_CLASS_DATETIME,
            "no input type" to 0,
        )
        for ((name, type) in others) {
            val d = decide(inputType = type)
            assertFalse(d.passwordField, name)
            assertFalse(d.incognito, name)
            assertTrue(d.keyPreviews, name)
            assertTrue(d.clipboardSuggestion, name)
            assertTrue(d.vimMode, name)
            assertTrue(d.storeClipboard, name)
            assertTrue(d.learn, name)
            assertTrue(d.recordRecents, name)
            assertTrue(d.personalizedSuggestions, name)
        }
        // the variation bit pattern of a password in another class is not a password
        assertFalse(decide(inputType = InputType.TYPE_CLASS_PHONE or InputType.TYPE_TEXT_VARIATION_PASSWORD).passwordField)
    }

    @Test fun aNoPersonalizedLearningFlagMakesAnyFieldIncognitoWithoutTheSecureMode() {
        // the flag is honoured in every input class (the old code ignored it outside text fields)
        for (type in listOf(text, InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME, 0)) {
            val d = decide(inputType = type, imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE)
            assertTrue(d.noPersonalizedLearning, "$type")
            assertTrue(d.incognito, "$type")
            assertFalse(d.passwordField, "$type") // suggestions and key previews stay: only the memory is switched off
            assertTrue(d.keyPreviews, "$type")
            assertFalse(d.storeClipboard, "$type")
        }
        // other IME options do not
        for (options in listOf(0, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_FLAG_NO_EXTRACT_UI, EditorInfo.IME_FLAG_NO_FULLSCREEN, EditorInfo.IME_FLAG_FORCE_ASCII)) {
            assertFalse(decide(imeOptions = options).incognito, "$options")
        }
    }

    @Test fun theListOfIncognitoAppsMatchesTheExactPackageName() {
        val apps = setOf("org.bank.app", "chat.secret")
        assertTrue(decide(packageName = "org.bank.app", apps = apps).incognito)
        assertTrue(decide(packageName = "chat.secret", apps = apps).incognito)
        assertFalse(decide(packageName = "org.bank.app.pro", apps = apps).incognito) // no prefix match
        assertFalse(decide(packageName = "bank.app", apps = apps).incognito) // no suffix match
        assertFalse(decide(packageName = "ORG.BANK.APP", apps = apps).incognito) // package names are case sensitive
        assertFalse(decide(packageName = null, apps = apps).incognito)
        assertFalse(decide(packageName = "org.bank.app", apps = emptySet()).incognito)
        val listed = decide(packageName = "org.bank.app", apps = apps)
        assertFalse(listed.passwordField) // the secure mode is for password fields; a listed app keeps its suggestions
        assertFalse(listed.storeClipboard)
        assertFalse(listed.recordRecents)
        assertFalse(listed.learn)
        assertFalse(listed.personalizedSuggestions)
    }

    @Test fun aLockedDeviceIsIncognito() { // P19: before the first unlock and while the screen is locked nothing is learned or kept
        val d = decide(locked = true)
        assertTrue(d.incognito)
        assertFalse(d.storeClipboard)
        assertFalse(d.learn)
        assertFalse(d.recordRecents)
        assertFalse(d.passwordField)
        assertFalse(decide(locked = false).incognito)
    }

    @Test fun incognitoIsTheOrOfEveryReason() {
        val password = text or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val noLearning = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        val apps = setOf("a.b")
        for (mask in 0 until 32) {
            val d = decide(
                inputType = if (mask and 1 != 0) password else text, imeOptions = if (mask and 2 != 0) noLearning else 0,
                packageName = "a.b", force = mask and 4 != 0, apps = if (mask and 8 != 0) apps else emptySet(), locked = mask and 16 != 0,
            )
            assertEquals(mask != 0, d.incognito, "mask $mask")
            assertEquals(mask != 0, !d.storeClipboard, "mask $mask")
            assertEquals(mask and 1 == 0, d.vimMode, "mask $mask") // only a password field takes the Vim layer away; the register follows storeClipboard
        }
    }

    @Test fun parseAppsKeepsValidPackageNamesOnly() {
        assertEquals(setOf("org.bank.app", "chat.secret"), PrivacyPolicy.parseApps("org.bank.app, chat.secret"))
        assertEquals(setOf("a.b", "c.d", "e.f"), PrivacyPolicy.parseApps("  a.b\nc.d;\t e.f ,, "))
        assertEquals(setOf("a.b"), PrivacyPolicy.parseApps("a.b a.b a.b")) // duplicates
        assertEquals(setOf("a.b"), PrivacyPolicy.parseApps("nodots 9.start a..b a.b -x.y a.b.")) // not package names
        assertEquals(setOf("A.b_1.c2"), PrivacyPolicy.parseApps("A.b_1.c2"))
        assertEquals(emptySet(), PrivacyPolicy.parseApps(""))
        assertEquals(emptySet(), PrivacyPolicy.parseApps(null))
        val many = (1..PrivacyPolicy.MAX_APPS + 50).joinToString(" ") { "app.n$it" }
        assertEquals(PrivacyPolicy.MAX_APPS, PrivacyPolicy.parseApps(many).size) // bounded
    }

    @Test fun parseAppsOutputRoundTripsThroughFormat() {
        val set = setOf("z.last", "a.first", "m.middle")
        val text = PrivacyPolicy.formatApps(set)
        assertEquals("a.first\nm.middle\nz.last", text) // stable order
        assertEquals(set, PrivacyPolicy.parseApps(text))
    }
}
