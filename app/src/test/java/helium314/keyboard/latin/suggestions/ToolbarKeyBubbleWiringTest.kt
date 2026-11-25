// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.ToolbarKeyBubble
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The label over a pressed toolbar key, through the running keyboard: the key's name (and what a long press does) while the finger is down, gone
 * when it lifts, and the key's tap and long press as before. Remove the wiring (the touch listener of a strip key) and a test here fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class, ShadowFacilitator2::class])
class ToolbarKeyBubbleWiringTest {
    private val text = InputType.TYPE_CLASS_TEXT

    private fun withKeyboard(test: (LatinIME) -> Unit) {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        KeyboardSwitcher.getInstance().onCreateInputView(ime, true)
        try {
            test(ime)
        } finally {
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ClipboardDao.forgetInstance()
            ShadowInputMethodService.reset()
        }
    }

    private fun startField(ime: LatinIME, content: String = "", cursor: Int = content.length) {
        ShadowInputMethodService.currentInputType = text
        ShadowInputMethodService.text = content
        ShadowInputMethodService.selectionStart = cursor
        ShadowInputMethodService.selectionEnd = cursor
        ShadowInputMethodService.composingStart = -1
        ShadowInputMethodService.composingEnd = -1
        val ei = EditorInfo().apply {
            inputType = text
            initialSelStart = cursor
            initialSelEnd = cursor
        }
        ime.mHandler.onStartInput(ei, false)
        ime.mHandler.onStartInputView(ei, false)
    }

    private fun strip(ime: LatinIME) = assertNotNull(
        LatinIME::class.java.getDeclaredField("mSuggestionStripView").apply { isAccessible = true }.get(ime) as SuggestionStripView?
    )

    private fun toolbarKey(ime: LatinIME, key: ToolbarKey) =
        assertNotNull(strip(ime).findViewById<ViewGroup>(R.id.toolbar).findViewWithTag<View>(key), "$key is in the toolbar")

    private fun pinnedKey(ime: LatinIME, key: ToolbarKey) =
        assertNotNull(strip(ime).findViewById<ViewGroup>(R.id.pinned_keys).findViewWithTag<View>(key), "$key is on the pinned row")

    private fun touch(view: View, action: Int) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, 1f, 1f, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /** The labels that are over the keyboard now: every visible view of the window tree that carries the bubble's tag. */
    private fun bubbles(ime: LatinIME): List<String> {
        fun walk(v: View): List<String> =
            (if (v.tag == ToolbarKeyBubble.TAG && v.visibility == View.VISIBLE && v is TextView) listOf(v.text.toString()) else emptyList()) +
                (if (v is ViewGroup) (0 until v.childCount).flatMap { walk(v.getChildAt(it)) } else emptyList())
        return walk(strip(ime).rootView)
    }

    // ---- pass 1: the core ---------------------------------------------------------------------------------------------------

    @Test fun pressingAToolbarKeyShowsItsNameAndLiftingTakesItAway() = withKeyboard { ime ->
        startField(ime)
        val undo = toolbarKey(ime, ToolbarKey.UNDO)
        assertEquals(emptyList(), bubbles(ime), "positive control: nothing before the press")
        touch(undo, MotionEvent.ACTION_DOWN)
        assertEquals(listOf("Undo · hold: Redo"), bubbles(ime))
        touch(undo, MotionEvent.ACTION_CANCEL)
        assertEquals(emptyList(), bubbles(ime))
    }

    // ---- pass 2: the whole behaviour ------------------------------------------------------------------------------------------

    /** The name of the key and what its long press does, by hand, for the keys of the default toolbar. */
    private val dot = "·"
    private val defaultToolbar = mapOf(
        ToolbarKey.SETTINGS to "Settings",
        ToolbarKey.QUICK_SETTINGS to "Quick settings",
        ToolbarKey.VOICE to "Voice input $dot hold: Speak",
        ToolbarKey.CLIPBOARD to "Clipboard $dot hold: Paste",
        ToolbarKey.INCOGNITO to "Force incognito mode",
        ToolbarKey.UNDO to "Undo $dot hold: Redo",
        ToolbarKey.REDO to "Redo $dot hold: Undo",
        ToolbarKey.SELECT_WORD to "Select word $dot hold: Select all",
        ToolbarKey.SELECT_ALL to "Select all $dot hold: Select word",
        ToolbarKey.CLEAR_ALL to "Clear all text",
        ToolbarKey.SELECT_MODE to "Selection mode",
        ToolbarKey.NEXT_OCCURRENCE to "Select next occurrence",
        ToolbarKey.CARET_BELOW to "Cursor on the next line",
        ToolbarKey.COPY to "Copy $dot hold: Cut",
        ToolbarKey.PASTE to "Paste $dot hold: Clipboard",
        ToolbarKey.LEFT to "Left $dot hold: Repeat",
        ToolbarKey.RIGHT to "Right $dot hold: Repeat",
    )

    @Test fun everyKeyOfTheToolbarShowsItsOwnLabel() = withKeyboard { ime ->
        startField(ime)
        val toolbar = strip(ime).findViewById<ViewGroup>(R.id.toolbar)
        val shown = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }.filter { it.tag is ToolbarKey }
        assertEquals(defaultToolbar.keys, shown.map { it.tag as ToolbarKey }.toSet(), "positive control: the toolbar has the default keys")
        for (view in shown) {
            touch(view, MotionEvent.ACTION_DOWN)
            assertEquals(listOf(defaultToolbar.getValue(view.tag as ToolbarKey)), bubbles(ime), "${view.tag}")
            touch(view, MotionEvent.ACTION_CANCEL)
            assertEquals(emptyList(), bubbles(ime), "${view.tag} released")
        }
    }

    /** One owner of the names: the label and the content description of a key are the same string. */
    @Test fun theKeysCarryTheirNameAsContentDescription() = withKeyboard { ime ->
        startField(ime)
        val toolbar = strip(ime).findViewById<ViewGroup>(R.id.toolbar)
        val keys = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }.filter { it.tag is ToolbarKey }
        assertTrue(keys.size > 10, "positive control: the walk found the keys")
        for (view in keys)
            assertEquals(helium314.keyboard.latin.utils.toolbarKeyName(ime, view.tag as ToolbarKey), view.contentDescription.toString(), "${view.tag}")
    }

    @Test fun theLiftOfTheFingerTakesTheLabelAwayToo() = withKeyboard { ime ->
        startField(ime)
        val left = toolbarKey(ime, ToolbarKey.LEFT)
        touch(left, MotionEvent.ACTION_DOWN)
        assertEquals(1, bubbles(ime).size, "positive control: shown")
        touch(left, MotionEvent.ACTION_UP)
        assertEquals(emptyList(), bubbles(ime))
    }

    @Test fun thePinnedKeysHaveALabelToo() = withKeyboard { ime ->
        startField(ime)
        val voice = pinnedKey(ime, ToolbarKey.VOICE)
        touch(voice, MotionEvent.ACTION_DOWN)
        assertEquals(listOf("Voice input $dot hold: Speak"), bubbles(ime))
        touch(voice, MotionEvent.ACTION_CANCEL)
        assertEquals(emptyList(), bubbles(ime))
    }

    @Test fun withQuickPinTheLabelSaysWhatTheLongPressDoesWhereTheKeyIs() = withKeyboard { ime ->
        ime.prefs().edit().putBoolean(Settings.PREF_QUICK_PIN_TOOLBAR_KEYS, true).commit()
        startField(ime)
        assertTrue(Settings.getValues().mQuickPinToolbarKeys, "positive control: quick pin is on")
        fun label(view: View): List<String> { touch(view, MotionEvent.ACTION_DOWN); return bubbles(ime).also { touch(view, MotionEvent.ACTION_CANCEL) } }
        assertEquals(listOf("Undo $dot hold: Pin to the strip"), label(toolbarKey(ime, ToolbarKey.UNDO)), "a key that is not pinned: its long press pins it")
        assertEquals(listOf("Voice input $dot hold: Speak"), label(pinnedKey(ime, ToolbarKey.VOICE)), "the voice key holds to talk, pinned or not")
        toolbarKey(ime, ToolbarKey.UNDO).performLongClick() // pins it
        assertEquals(listOf("Undo $dot hold: Redo"), label(pinnedKey(ime, ToolbarKey.UNDO)), "on the pinned row the long press is the key's own")
        assertEquals(listOf("Undo $dot hold: Unpin from the strip"), label(toolbarKey(ime, ToolbarKey.UNDO)), "in the toolbar a pinned key is unpinned by it")
        assertEquals(listOf("Voice input $dot hold: Speak"), label(toolbarKey(ime, ToolbarKey.VOICE)), "the voice key in the toolbar: still hold to talk")
    }

    @Test fun theSettingIsOnByDefaultAndOffMeansNoLabel() = withKeyboard { ime ->
        assertFalse(ime.prefs().contains(Settings.PREF_SHOW_TOOLBAR_KEY_NAMES), "nothing stored: the default")
        startField(ime)
        assertTrue(Settings.getValues().mShowToolbarKeyNames, "on by default")
        val undo = toolbarKey(ime, ToolbarKey.UNDO)
        touch(undo, MotionEvent.ACTION_DOWN)
        assertEquals(1, bubbles(ime).size, "positive control: on, the press shows the label")
        touch(undo, MotionEvent.ACTION_CANCEL)
        ime.prefs().edit().putBoolean(Settings.PREF_SHOW_TOOLBAR_KEY_NAMES, false).commit()
        startField(ime)
        assertFalse(Settings.getValues().mShowToolbarKeyNames)
        for (view in listOf(undo, pinnedKey(ime, ToolbarKey.VOICE))) {
            touch(view, MotionEvent.ACTION_DOWN)
            assertEquals(emptyList(), bubbles(ime), "off: nothing is shown for ${view.tag}")
            touch(view, MotionEvent.ACTION_CANCEL)
        }
        ime.prefs().edit().putBoolean(Settings.PREF_SHOW_TOOLBAR_KEY_NAMES, true).commit()
        startField(ime)
        touch(undo, MotionEvent.ACTION_DOWN)
        assertEquals(1, bubbles(ime).size, "positive control: on again, the same press shows it")
        touch(undo, MotionEvent.ACTION_CANCEL)
    }

    @Test fun aLockedDeviceShowsNoLabel() = withKeyboard { ime ->
        startField(ime)
        val undo = toolbarKey(ime, ToolbarKey.UNDO)
        Privacy.lock(ime) // the live lock; the strip is not laid out again
        assertTrue(Privacy.isLocked, "positive control: locked")
        touch(undo, MotionEvent.ACTION_DOWN)
        assertEquals(emptyList(), bubbles(ime), "no label while locked")
        touch(undo, MotionEvent.ACTION_CANCEL)
        Privacy.unlock(ime)
        assertFalse(Privacy.isLocked, "positive control: unlocked")
        touch(undo, MotionEvent.ACTION_DOWN)
        assertEquals(1, bubbles(ime).size, "unlocked: the label is back")
        touch(undo, MotionEvent.ACTION_CANCEL)
    }

    @Test fun aKeyThatGoesAwayDuringThePressTakesItsLabelWithIt() = withKeyboard { ime ->
        startField(ime)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = strip(ime).rootView as ViewGroup
        (root.parent as? ViewGroup)?.removeView(root)
        activity.windowManager.addView(root, WindowManager.LayoutParams()) // the keyboard is in a window, as on the phone
        shadowOf(Looper.getMainLooper()).idle() // the first traversal attaches it
        val left = toolbarKey(ime, ToolbarKey.LEFT)
        assertTrue(left.isAttachedToWindow, "positive control: the key is in a window")
        touch(left, MotionEvent.ACTION_DOWN)
        assertEquals(1, bubbles(ime).size, "positive control")
        (left.parent as ViewGroup).removeView(left)
        assertEquals(emptyList(), bubbles(ime), "the strip was rebuilt while the finger was down: no label stays")
    }

    @Test fun aSecondPressReplacesTheLabelNeverAddsOne() = withKeyboard { ime ->
        startField(ime)
        touch(toolbarKey(ime, ToolbarKey.UNDO), MotionEvent.ACTION_DOWN)
        touch(toolbarKey(ime, ToolbarKey.COPY), MotionEvent.ACTION_DOWN) // a second finger, or a lost cancel
        assertEquals(listOf("Copy $dot hold: Cut"), bubbles(ime))
    }

    // ---- the key's own actions are as before, with the label on and off -------------------------------------------------------

    private fun press(view: View, action: () -> Unit) {
        touch(view, MotionEvent.ACTION_DOWN)
        action()
        touch(view, MotionEvent.ACTION_CANCEL)
    }

    @Test fun tapAndLongPressDoTheSameWithTheLabelOnAndOff() = withKeyboard { ime ->
        for (names in listOf(true, false)) {
            ime.prefs().edit().putBoolean(Settings.PREF_SHOW_TOOLBAR_KEY_NAMES, names).commit()
            startField(ime, "ab cd ef", 4)
            val selectAll = toolbarKey(ime, ToolbarKey.SELECT_ALL)
            press(selectAll) { assertTrue(selectAll.performClick()) }
            assertEquals("ab cd ef", ShadowInputMethodService.selectedText, "tap selects all (label $names)")
            startField(ime, "ab cd ef", 4)
            press(selectAll) { assertTrue(selectAll.performLongClick()) }
            assertEquals("cd", ShadowInputMethodService.selectedText, "long press selects the word (label $names)")
            startField(ime, "abcdef", 0)
            ShadowInputMethodService.keyDownCodes.clear()
            val right = toolbarKey(ime, ToolbarKey.RIGHT)
            press(right) { right.performClick() }
            assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT), ShadowInputMethodService.keyDownCodes, "tap sends the cursor key once (label $names)")
        }
    }

    /** The toolbar of the clipboard panel has the same keys and the same labels. */
    @Test fun theClipboardPanelKeysShowTheirLabelsToo() = withKeyboard { ime ->
        startField(ime)
        ime.mKeyboardActionListener.onCodeInput(KeyCode.CLIPBOARD, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        val clipboardStrip = KeyboardSwitcher.getInstance().clipboardStrip
        fun key(k: ToolbarKey) = assertNotNull(clipboardStrip.findViewWithTag<View>(k), "$k is in the clipboard toolbar")
        val copy = key(ToolbarKey.COPY)
        touch(copy, MotionEvent.ACTION_DOWN)
        assertEquals(listOf("Copy $dot hold: Cut"), bubbles(ime))
        touch(copy, MotionEvent.ACTION_CANCEL)
        assertEquals(emptyList(), bubbles(ime))
        touch(key(ToolbarKey.CLEAR_CLIPBOARD), MotionEvent.ACTION_DOWN)
        assertEquals(listOf("Clear clipboard"), bubbles(ime), "a key with no long press: its name only")
        touch(key(ToolbarKey.CLEAR_CLIPBOARD), MotionEvent.ACTION_CANCEL)
        ime.prefs().edit().putBoolean(Settings.PREF_SHOW_TOOLBAR_KEY_NAMES, false).commit()
        startField(ime)
        touch(copy, MotionEvent.ACTION_DOWN)
        assertEquals(emptyList(), bubbles(ime), "off: no label in the clipboard panel either")
        touch(copy, MotionEvent.ACTION_CANCEL)
    }

    @Test fun theLabelDoesNotTakeThePressFromTheKey() = withKeyboard { ime ->
        startField(ime)
        val voice = pinnedKey(ime, ToolbarKey.VOICE) // the toolbar is in a scrolling container: its keys show the pressed state a moment later
        touch(voice, MotionEvent.ACTION_DOWN)
        assertEquals(1, bubbles(ime).size, "positive control: the label is up")
        assertTrue(voice.isPressed, "the key is pressed as without the label")
        touch(voice, MotionEvent.ACTION_CANCEL)
        assertFalse(voice.isPressed)
    }
}
