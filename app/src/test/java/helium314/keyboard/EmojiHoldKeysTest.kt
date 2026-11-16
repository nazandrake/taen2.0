// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.internal.KeyboardParams
import helium314.keyboard.keyboard.internal.keyboard_parser.LayoutParser
import helium314.keyboard.keyboard.internal.keyboard_parser.LocaleKeyboardInfos
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.EmojiHold
import helium314.keyboard.latin.utils.POPUP_KEYS_LAYOUT
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Setting "Open emoji by holding": the chosen key gets ONE popup key, the emoji key, behind the no-panel marker that the shift key already uses for its caps-lock hold,
 * so the hold carries out emoji at once; every other key keeps what it had; "none" changes nothing; and it does not depend on whether the emoji key is shown.
 * The keys are parsed from the layout labels (comma, period, space, language_switch), the way a layout names them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class, ShadowProximityInfo::class])
class EmojiHoldKeysTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val params = KeyboardParams()
    private val baseId = KeyboardLayoutSet.getFakeKeyboardId(KeyboardElement.ALPHABET)

    init {
        params.mPopupKeyOrder.add(POPUP_KEYS_LAYOUT)
        params.mPopupKeyHintOrder.add(POPUP_KEYS_LAYOUT)
        params.mId = baseId
        LocaleKeyboardInfos.addLocaleKeyTextsToParams(latinIME, params, LocaleKeyboardInfos.POPUP_KEYS_NORMAL)
    }

    private val labels = listOf("comma", "period", "space", "language_switch", "a")

    /** The keys of one row, by label, parsed with the id [hold] / [emojiKey] / [element]. */
    private fun keys(hold: EmojiHold, emojiKey: Boolean = true, element: KeyboardElement = KeyboardElement.ALPHABET): Map<String, Key> {
        params.mId = KeyboardLayoutSet.getFakeKeyboardId(element).copy(emojiHold = hold, emojiKeyEnabled = emojiKey, languageSwitchKeyEnabled = true)
        val json = labels.joinToString(prefix = "[[", postfix = "]]") { "{ \"label\": \"$it\" }" }
        val result = LayoutParser.parseJsonString(json).flatMap { row -> row.mapNotNull { it.compute(params)?.toKeyParams(params) } }
        return labels.zip(result).associate { (label, keyParams) ->
            keyParams.mAbsoluteWidth = 1f
            keyParams.mAbsoluteHeight = 1f
            label to keyParams.createKey()
        }
    }

    private fun Key.holdsEmoji() = hasNoPanelAutoPopupKey() && popupKeys?.size == 1 && popupKeys!![0].mCode == KeyCode.EMOJI

    private fun popupCodes(key: Key) = key.popupKeys?.map { it.mCode }.orEmpty()

    // ---- the default holds emoji on the comma key ------------------------------------------------------

    @Test fun theCommaKeyOpensEmojiOnAHoldAndTheOthersKeepTheirMenus() {
        val k = keys(EmojiHold.COMMA)
        assertTrue(k.getValue("comma").holdsEmoji())
        assertFalse(k.getValue("period").holdsEmoji())
        assertFalse(k.getValue("space").holdsEmoji())
        assertFalse(k.getValue("language_switch").holdsEmoji())
        assertTrue(k.getValue("period").popupKeys != null, "the period key keeps its popup keys")
        assertTrue(k.getValue("comma").isLongPressEnabled)
    }

    // ---- every choice, the emoji key on and off, number layouts, none, the default ---------------------------

    @Test fun eachChoiceMakesExactlyItsKeyHoldEmoji() {
        val byChoice = mapOf(EmojiHold.COMMA to "comma", EmojiHold.PERIOD to "period", EmojiHold.SPACE to "space", EmojiHold.LANGUAGE to "language_switch")
        for ((hold, label) in byChoice) {
            val k = keys(hold)
            for (l in labels) assertEquals(l == label, k.getValue(l).holdsEmoji(), "$hold: $l")
        }
    }

    @Test fun theHoldWorksWithTheEmojiKeyOffAndOn() {
        for (emojiKey in listOf(false, true)) {
            val k = keys(EmojiHold.COMMA, emojiKey)
            assertTrue(k.getValue("comma").holdsEmoji(), "emoji key shown = $emojiKey")
            assertEquals(listOf(KeyCode.EMOJI), popupCodes(k.getValue("comma")))
        }
    }

    @Test fun noneLeavesEveryKeyAsItWasAndTheCommaMenuStays() {
        val none = keys(EmojiHold.NONE, emojiKey = false)
        for (l in labels) assertFalse(none.getValue(l).holdsEmoji(), l)
        val comma = none.getValue("comma")
        assertTrue((comma.popupKeys?.size ?: 0) > 1, "the comma menu (clipboard, settings...) is back")
        assertTrue(popupCodes(comma).contains(KeyCode.EMOJI), "with the emoji key off, the menu still offers emoji")
        // positive control: with the hold on comma the menu is replaced by the one hold
        assertNotEquals(popupCodes(comma), popupCodes(keys(EmojiHold.COMMA, emojiKey = false).getValue("comma")))
    }

    @Test fun theKeysThatAreNotChosenAreIdenticalToTheirNoneVersion() {
        val none = keys(EmojiHold.NONE)
        for ((hold, chosen) in mapOf(EmojiHold.COMMA to "comma", EmojiHold.PERIOD to "period", EmojiHold.SPACE to "space", EmojiHold.LANGUAGE to "language_switch")) {
            val k = keys(hold)
            for (l in labels.filter { it != chosen })
                assertEquals(popupCodes(none.getValue(l)), popupCodes(k.getValue(l)), "$hold: $l")
        }
    }

    @Test fun numberLayoutsKeepTheirKeys() {
        for (element in listOf(KeyboardElement.NUMPAD, KeyboardElement.PHONE)) {
            val k = keys(EmojiHold.COMMA, element = element)
            for (l in labels) assertFalse(k.getValue(l).holdsEmoji(), "$element: $l")
        }
    }

    @Test fun theSettingValuesAndTheDefault() {
        assertEquals(EmojiHold.COMMA, EmojiHold.DEFAULT)
        assertEquals(EmojiHold.DEFAULT.pref, Defaults.PREF_EMOJI_HOLD)
        assertEquals(listOf("comma", "period", "space", "language", "none"), EmojiHold.entries.map { it.pref })
        for (h in EmojiHold.entries) assertEquals(h, EmojiHold.fromPref(h.pref))
        assertEquals(EmojiHold.COMMA, EmojiHold.fromPref(null))
        assertEquals(EmojiHold.COMMA, EmojiHold.fromPref("exotic"))
        assertEquals(listOf(EmojiHold.PERIOD, EmojiHold.SPACE, EmojiHold.LANGUAGE, EmojiHold.NONE, EmojiHold.COMMA), EmojiHold.entries.map { it.next() })
    }
}
