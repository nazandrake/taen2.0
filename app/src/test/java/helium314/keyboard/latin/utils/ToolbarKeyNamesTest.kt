// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.privacy.PrivacyPolicy
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.EnumMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bubble over a pressed toolbar key: the key's name, and what a long press does. */
@RunWith(RobolectricTestRunner::class)
class ToolbarKeyNamesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dot = "·"

    // ---- pass 1: the core ---------------------------------------------------------------------------------------------------

    @Test fun undoNamesItselfAndItsLongPress() {
        assertEquals("Undo $dot hold: Redo", toolbarKeyBubbleText(context, ToolbarKey.UNDO, ToolbarHold.ACTION))
    }

    // ---- pass 2: every key, every kind of long press, both languages ----------------------------------------------------------

    /** The English names (the strings of the keys) and the long press each key has by default, written out by hand. */
    private val expected = mapOf(
        ToolbarKey.VOICE to ("Voice input" to null),
        ToolbarKey.CLIPBOARD to ("Clipboard" to "Paste"),
        ToolbarKey.NUMPAD to ("Numpad" to null),
        ToolbarKey.DPAD to ("D-pad" to null),
        ToolbarKey.UNDO to ("Undo" to "Redo"),
        ToolbarKey.REDO to ("Redo" to "Undo"),
        ToolbarKey.SETTINGS to ("Settings" to null),
        ToolbarKey.SELECT_ALL to ("Select all" to "Select word"),
        ToolbarKey.SELECT_WORD to ("Select word" to "Select all"),
        ToolbarKey.COPY to ("Copy" to "Cut"),
        ToolbarKey.CUT to ("Cut" to null),
        ToolbarKey.PASTE to ("Paste" to "Clipboard"),
        ToolbarKey.ONE_HANDED to ("One-handed mode" to null),
        ToolbarKey.FLOATING to ("Floating Keyboard" to null),
        ToolbarKey.SPLIT to ("Split keyboard" to null),
        ToolbarKey.INCOGNITO to ("Force incognito mode" to null),
        ToolbarKey.AUTOCORRECT to ("Auto-correction" to null),
        ToolbarKey.CLEAR_CLIPBOARD to ("Clear clipboard" to null),
        ToolbarKey.CLOSE_HISTORY to ("Close clipboard history" to null),
        ToolbarKey.EMOJI to ("Emoji" to null),
        ToolbarKey.LEFT to ("Left" to "Repeat"),
        ToolbarKey.RIGHT to ("Right" to "Repeat"),
        ToolbarKey.UP to ("Up" to "Repeat"),
        ToolbarKey.DOWN to ("Down" to "Repeat"),
        ToolbarKey.WORD_LEFT to ("Word left" to "Repeat"),
        ToolbarKey.WORD_RIGHT to ("Word right" to "Repeat"),
        ToolbarKey.PAGE_UP to ("Page up" to "Page start"),
        ToolbarKey.PAGE_DOWN to ("Page down" to "Page end"),
        ToolbarKey.FULL_LEFT to ("Full left" to null),
        ToolbarKey.FULL_RIGHT to ("Full right" to null),
        ToolbarKey.PAGE_START to ("Page start" to null),
        ToolbarKey.PAGE_END to ("Page end" to null),
        ToolbarKey.QUICK_SETTINGS to ("Quick settings" to null),
        ToolbarKey.MEDIA to ("GIFs and stickers" to null),
        ToolbarKey.SELECT_MODE to ("Selection mode" to null),
        ToolbarKey.NEXT_OCCURRENCE to ("Select next occurrence" to null),
        ToolbarKey.CARET_BELOW to ("Cursor on the next line" to null),
        ToolbarKey.CLEAR_ALL to ("Clear all text" to null),
        ToolbarKey.VIM to ("Vim Esc (Normal mode)" to null),
    )

    @Test fun theTableCoversEveryToolbarKey() {
        assertEquals(ToolbarKey.entries.toSet(), expected.keys, "a new key needs a row here, a name and its long press")
    }

    @Test fun everyKeyShowsItsNameAndItsDefaultLongPress() {
        for ((key, pair) in expected) {
            val (name, hold) = pair
            assertEquals(if (hold == null) name else "$name $dot hold: $hold", toolbarKeyBubbleText(context, key, ToolbarHold.ACTION), "$key")
        }
    }

    @Test fun theNameIsTheStringOfTheKey() {
        for (key in ToolbarKey.entries) assertEquals(expected.getValue(key).first, toolbarKeyName(context, key), "$key")
    }

    @Test fun theStripCanSayWhatAHoldDoesWhereTheKeyIsNotTheActionOne() {
        assertEquals("Undo", toolbarKeyBubbleText(context, ToolbarKey.UNDO, ToolbarHold.NONE))
        assertEquals("Voice input $dot hold: Speak", toolbarKeyBubbleText(context, ToolbarKey.VOICE, ToolbarHold.VOICE))
        assertEquals("Undo $dot hold: Pin to the strip", toolbarKeyBubbleText(context, ToolbarKey.UNDO, ToolbarHold.PIN))
        assertEquals("Undo $dot hold: Unpin from the strip", toolbarKeyBubbleText(context, ToolbarKey.UNDO, ToolbarHold.UNPIN))
        // positive control for the three: the same key with its own action says the action
        assertEquals("Undo $dot hold: Redo", toolbarKeyBubbleText(context, ToolbarKey.UNDO, ToolbarHold.ACTION))
    }

    /** The rule that lets the label show: the setting, the field's decision and the live lock all have to say yes. */
    @Test fun theLabelNeedsTheSettingAndNoLockInTheFieldAndNoLiveLock() {
        val open = PrivacyPolicy.Decision(passwordField = false, noPersonalizedLearning = false, incognito = false)
        val lockedField = PrivacyPolicy.Decision(passwordField = false, noPersonalizedLearning = false, incognito = true, locked = true)
        assertTrue(ToolbarKeyBubble.allowed(true, open, false), "positive control: setting on, nothing locked")
        assertFalse(ToolbarKeyBubble.allowed(false, open, false), "setting off")
        assertFalse(ToolbarKeyBubble.allowed(true, lockedField, false), "the field was decided as locked")
        assertFalse(ToolbarKeyBubble.allowed(true, open, true), "the screen is locked now")
        assertFalse(ToolbarKeyBubble.allowed(false, lockedField, true), "all three")
    }

    private fun withCustomCodes(codes: Map<ToolbarKey, Pair<Int?, Int?>>, test: () -> Unit) {
        Settings.init(context)
        val map = EnumMap<ToolbarKey, Pair<Int?, Int?>>(ToolbarKey::class.java)
        map.putAll(codes)
        writeCustomKeyCodes(context.prefs(), map)
        clearCustomToolbarKeyCodes()
        try {
            test()
        } finally {
            context.prefs().edit { clear() }
            clearCustomToolbarKeyCodes()
        }
    }

    @Test fun aCustomLongPressNamesTheKeyThatSendsThatCodeOrIsCalledCustom() {
        withCustomCodes(mapOf(ToolbarKey.UNDO to (null to KeyCode.CLIPBOARD_PASTE))) {
            assertEquals("Undo $dot hold: Paste", toolbarKeyBubbleText(context, ToolbarKey.UNDO, ToolbarHold.ACTION))
        }
        withCustomCodes(mapOf(ToolbarKey.UNDO to (null to 98765))) {
            assertEquals("Undo $dot hold: Custom action", toolbarKeyBubbleText(context, ToolbarKey.UNDO, ToolbarHold.ACTION))
        }
        withCustomCodes(mapOf(ToolbarKey.UNDO to (null to KeyCode.KEY_REPEAT))) {
            assertEquals("Undo $dot hold: Repeat", toolbarKeyBubbleText(context, ToolbarKey.UNDO, ToolbarHold.ACTION))
        }
        withCustomCodes(mapOf(ToolbarKey.UNDO to (null to KeyCode.UNSPECIFIED))) {
            assertEquals("Undo", toolbarKeyBubbleText(context, ToolbarKey.UNDO, ToolbarHold.ACTION))
        }
        withCustomCodes(emptyMap()) {
            assertEquals("Undo $dot hold: Redo", toolbarKeyBubbleText(context, ToolbarKey.UNDO, ToolbarHold.ACTION), "positive control: no custom code, the default")
        }
    }

    // ---- both languages -----------------------------------------------------------------------------------------------------

    private fun stringsOf(dir: String): Map<String, String> {
        val text = File("src/main/res/$dir/strings.xml").readText()
        return Regex("""<string\s+name="([^"]+)"[^>]*>([^<]*)</string>""").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** A key's name is its own string in the base file and a string of the Bulgarian file, or (like the platform's Copy, Paste, Cut and Select all) a reference to a platform or layout string. */
    @Test fun everyKeyHasANameInEnglishAndBulgarian() {
        val en = stringsOf("values")
        val bg = stringsOf("values-bg")
        val bgNames = bg + en.filter { it.value.startsWith("@android:string/") } // the platform translates its own strings
        for (key in ToolbarKey.entries) {
            val id = key.name.lowercase()
            assertTrue(en[id]?.isNotBlank() == true, "$key has no English name")
            val value = en.getValue(id)
            if (value.startsWith("@string/")) {
                val target = value.removePrefix("@string/")
                assertTrue(bg[target]?.isNotBlank() == true, "$key refers to $target, which has no Bulgarian string")
            } else {
                assertTrue(bgNames[id]?.isNotBlank() == true, "$key has no Bulgarian name")
            }
        }
        for (id in listOf("toolbar_hold_format", "toolbar_hold_repeat", "toolbar_hold_custom", "toolbar_hold_talk", "toolbar_hold_pin", "toolbar_hold_unpin",
            "show_toolbar_key_names", "show_toolbar_key_names_summary")) {
            assertTrue(en[id]?.isNotBlank() == true, "$id has no English text")
            assertTrue(bg[id]?.isNotBlank() == true, "$id has no Bulgarian text")
        }
        assertTrue(en.getValue("toolbar_hold_format").let { "%1\$s" in it && "%2\$s" in it }, "English format")
        assertTrue(bg.getValue("toolbar_hold_format").let { "%1\$s" in it && "%2\$s" in it }, "Bulgarian format")
    }

    @Config(qualifiers = "bg")
    @Test fun inBulgarianTheBubbleSpeaksBulgarian() {
        assertEquals("Отмяна $dot задръжте: Повтори", toolbarKeyBubbleText(context, ToolbarKey.UNDO, ToolbarHold.ACTION))
        assertEquals("Гласово въвеждане $dot задръжте: Говорете", toolbarKeyBubbleText(context, ToolbarKey.VOICE, ToolbarHold.VOICE))
        assertEquals("Цифрова клавиатура", toolbarKeyBubbleText(context, ToolbarKey.NUMPAD, ToolbarHold.NONE))
        assertEquals("Отмяна $dot задръжте: Закачане към лентата", toolbarKeyBubbleText(context, ToolbarKey.UNDO, ToolbarHold.PIN))
        // the platform's own strings (Copy, Cut, Paste, Select all) come in the language of the system, not in English
        val cyrillic = Regex("[А-Яа-я]")
        for (key in listOf(ToolbarKey.COPY, ToolbarKey.CUT, ToolbarKey.PASTE, ToolbarKey.SELECT_ALL))
            assertTrue(cyrillic.containsMatchIn(toolbarKeyName(context, key)), "$key: ${toolbarKeyName(context, key)}")
        assertTrue(cyrillic.containsMatchIn(toolbarKeyBubbleText(context, ToolbarKey.CLIPBOARD, ToolbarHold.ACTION).substringAfter("задръжте: ")),
            "the hold part of the clipboard key is the platform's Paste")
    }
}
