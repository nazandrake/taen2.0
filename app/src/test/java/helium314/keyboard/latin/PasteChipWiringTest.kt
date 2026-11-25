// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.compat.ClipboardManagerCompat
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestionStripView
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Paste chip through the running keyboard: a copy puts it first on the strip's pinned row, next to the word suggestions, with a label and no
 * text of the clip; a tap pastes through the paste key's own code; it follows to any field (also of another app) that starts within 60 s of the copy
 * and goes after use, 60 s, a screen lock, and is not there when the setting is off or the device is locked; password and incognito fields have it.
 * Remove the wiring (the clip listener, the strip's row, the screen-off hook) and a test here fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class, ShadowFacilitator2::class])
class PasteChipWiringTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val password = text or InputType.TYPE_TEXT_VARIATION_PASSWORD
    private val numberPassword = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    private val clip = "private words 4711"

    private fun withKeyboard(test: (LatinIME) -> Unit) {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        KeyboardSwitcher.getInstance().onCreateInputView(ime, true)
        val keyguard = shadowOf(ime.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(false)
        keyguard.setIsDeviceLocked(false)
        try {
            test(ime)
        } finally {
            keyguard.setKeyguardLocked(false)
            keyguard.setIsDeviceLocked(false)
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ClipboardDao.forgetInstance()
            ShadowInputMethodService.reset()
        }
    }

    private var fieldCount = 0
    private var lastField: EditorInfo? = null

    /** Input starts in a new field (its own EditorInfo.fieldId), unless [sameField] says the field of the last call starts again. */
    private fun startField(ime: LatinIME, inputType: Int = text, content: String = "", sameField: Boolean = false, packageName: String = "org.example.app") {
        val id = if (sameField) lastField!!.fieldId else ++fieldCount
        ShadowInputMethodService.currentFieldId = id
        ShadowInputMethodService.currentInputType = inputType
        ShadowInputMethodService.text = content
        ShadowInputMethodService.selectionStart = content.length
        ShadowInputMethodService.selectionEnd = content.length
        ShadowInputMethodService.composingStart = -1
        ShadowInputMethodService.composingEnd = -1
        val ei = EditorInfo().apply {
            this.inputType = inputType
            fieldId = id
            this.packageName = packageName
            initialSelStart = content.length
            initialSelEnd = content.length
        }
        lastField = ei
        ime.mHandler.onStartInput(ei, false)
        ime.mHandler.onStartInputView(ei, false)
    }

    private fun strip(ime: LatinIME) = assertNotNull(
        LatinIME::class.java.getDeclaredField("mSuggestionStripView").apply { isAccessible = true }.get(ime) as SuggestionStripView?
    )

    private fun pinned(ime: LatinIME) = strip(ime).findViewById<ViewGroup>(R.id.pinned_keys)
    private fun pinnedViews(ime: LatinIME) = pinned(ime).let { row -> (0 until row.childCount).map { row.getChildAt(it) } }
    /** The chips on the strip; a locked device has no strip at all (the keyboard builds none), which counts as no chip. */
    private fun chips(ime: LatinIME) =
        if (LatinIME::class.java.getDeclaredField("mSuggestionStripView").apply { isAccessible = true }.get(ime) == null) emptyList()
        else pinnedViews(ime).filterIsInstance<TextView>()
    private fun chip(ime: LatinIME) = chips(ime).single()

    /** A copy. The clipboard gives the clip its time stamp (the shadow takes the uptime), so a copy comes 1 ms after the one before, as two copies do. */
    private fun copyText(ime: LatinIME, content: String = clip, sensitive: Boolean = false) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        val data = ClipData.newPlainText("t", content)
        if (sensitive) data.description.extras = PersistableBundle().apply { putBoolean(ClipboardManagerCompat.EXTRA_IS_SENSITIVE, true) }
        (ime.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(data)
    }

    /** The user copies in an app that shows no keyboard (a page, a message), then opens a field in another app. */
    private fun copyWithoutKeyboardThenOpen(ime: LatinIME, openType: Int = text, content: String = clip, sensitive: Boolean = false) {
        startField(ime) // the app the copy is made in started an input before
        ShadowInputMethodService.inputViewShown = false
        copyText(ime, content, sensitive)
        ShadowInputMethodService.inputViewShown = true
        startField(ime, openType)
    }

    // ---- pass 1: the core ---------------------------------------------------------------------------------------------------

    @Test fun aCopyPutsThePasteChipFirstOnTheStripWithNoTextOfTheClip() = withKeyboard { ime ->
        startField(ime)
        assertEquals(emptyList(), chips(ime), "positive control: no copy, no chip")
        copyWithoutKeyboardThenOpen(ime)
        val views = pinnedViews(ime)
        assertTrue(views.first() is TextView, "the chip is the first view of the row: $views")
        assertEquals("Paste", chip(ime).text.toString())
        assertTrue(ToolbarKey.VOICE in views.map { it.tag }, "positive control: the row still has the pinned keys: ${views.map { it.tag }}")
    }

    // ---- pass 2: the whole behaviour ------------------------------------------------------------------------------------------

    @Test fun nothingOfTheClipIsOnTheStripAndTheClipboardIsReadOnlyByTheTap() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime)
        fun allText(v: View): List<String> = (if (v is TextView) listOf(v.text.toString(), v.contentDescription?.toString() ?: "") else emptyList()) +
            (if (v is ViewGroup) (0 until v.childCount).flatMap { allText(v.getChildAt(it)) } else emptyList())
        val shown = allText(strip(ime))
        assertTrue(shown.any { it == "Paste" }, "positive control: the walk found the chip: $shown")
        assertTrue(shown.none { "4711" in it || "private" in it }, "no text of the clip: $shown")
        assertEquals("", ShadowInputMethodService.text, "nothing was written")
    }

    @Test fun theWordSuggestionsStayNextToTheChip() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime)
        val infos = ArrayList(listOf("hello", "help", "held").map { SuggestedWordInfo(it, "", 100, 0, null, 0, 0) })
        strip(ime).setSuggestions(SuggestedWords(infos, null, null, false, false, false, 0, 0), false)
        assertEquals(1, chips(ime).size, "the chip is still there after suggestions arrive")
        val words = strip(ime).findViewById<ViewGroup>(R.id.suggestions_strip).let { g -> (0 until g.childCount).map { g.getChildAt(it) } }
            .filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(listOf("hello", "help", "held").all { it in words }, "the words are on the strip: $words")
        assertEquals(1, chips(ime).size, "and only one chip")
    }

    @Test fun tappingTheChipPastesLikeThePasteKeyAndTheChipGoes() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime)
        ShadowInputMethodService.text = "ab"
        ShadowInputMethodService.selectionStart = 2
        ShadowInputMethodService.selectionEnd = 2
        chip(ime).performClick()
        assertEquals("ab$clip", ShadowInputMethodService.text)
        assertEquals(emptyList(), chips(ime), "used: gone")
        startField(ime, text, "x")
        assertEquals(emptyList(), chips(ime), "and not back in the next field")
    }

    @Test fun theChipPastesWhatANumberFieldAccepts() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime, InputType.TYPE_CLASS_NUMBER, "pin 4711 x")
        chip(ime).performClick()
        assertEquals("4711", ShadowInputMethodService.text, "the same filter as the paste key")
    }

    @Test fun theChipGoesAfterSixtySeconds() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(59_000))
        assertEquals(1, chips(ime).size, "after 59 s")
        looper.idleFor(Duration.ofMillis(1_000))
        assertEquals(emptyList(), chips(ime), "after 60 s the view is removed")
        startField(ime, text)
        assertEquals(emptyList(), chips(ime), "and it does not come back in the next field")
    }

    /** The main use: copy in one app, then open a field of another app (or another field of the same one) within the minute. */
    @Test fun aChipThatWasShownFollowsToAnotherFieldAndAnotherApp() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime)
        assertEquals(1, chips(ime).size, "positive control: shown in the first field")
        startField(ime, text, "b", packageName = "org.other.app")
        assertEquals(1, chips(ime).size, "another field of another app, within the minute")
        startField(ime, password, "c", packageName = "org.third.app")
        assertEquals(1, chips(ime).size, "and a password field of a third app")
        startField(ime, text, "d", packageName = "org.third.app")
        assertEquals(1, chips(ime).size, "and another field of the same app")
    }

    @Test fun aChipThatFollowedToAnotherFieldStillGoesAfterSixtySecondsFromTheCopy() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(40_000))
        startField(ime, text, "b", packageName = "org.other.app")
        assertEquals(1, chips(ime).size, "40 s after the copy, in another field")
        looper.idleFor(Duration.ofMillis(19_000))
        assertEquals(1, chips(ime).size, "59 s after the copy")
        looper.idleFor(Duration.ofMillis(1_000))
        assertEquals(emptyList(), chips(ime), "60 s after the copy: the view in that field is removed")
        startField(ime, text, "c", packageName = "org.third.app")
        assertEquals(emptyList(), chips(ime), "and a field that opens after the minute has none")
    }

    @Test fun aChipUsedInOneFieldIsNotInTheNextAppEither() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime)
        startField(ime, text, "b", packageName = "org.other.app")
        chip(ime).performClick()
        assertEquals("b$clip", ShadowInputMethodService.text, "positive control: the chip in the second app pasted after its \"b\"")
        startField(ime, text, "c", packageName = "org.third.app")
        assertEquals(emptyList(), chips(ime))
    }

    @Test fun aRestartOfTheSameFieldKeepsTheChip() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime)
        val ei = lastField!!
        ime.mHandler.onStartInput(ei, true)
        ime.mHandler.onStartInputView(ei, true)
        assertEquals(1, chips(ime).size)
    }

    /** A window that opens starts input in its field twice within a moment, both times as a new start (restarting = false), and an empty editor may come first. */
    @Test fun repeatedStartsAndEmptyEditorsKeepTheChip() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime)
        assertEquals(1, chips(ime).size, "positive control: shown at the first start")
        startField(ime, text, sameField = true)
        assertEquals(1, chips(ime).size, "the second start of the same field")
        startField(ime, 0) // the empty editor of a window
        startField(ime, text, "b", packageName = "org.other.app")
        assertEquals(1, chips(ime).size, "the field that follows an empty editor")
    }

    @Test fun aCopyMadeWhileAFieldIsOpenShowsTheChipThereAtOnceAndInTheNextField() = withKeyboard { ime ->
        startField(ime)
        copyText(ime)
        assertEquals(1, chips(ime).size)
        startField(ime, text) // a field change within the minute
        assertEquals(1, chips(ime).size)
    }

    @Test fun aNewCopyReplacesTheClipAndRestartsTheMinute() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime)
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(50_000))
        copyText(ime, "second")
        looper.idleFor(Duration.ofMillis(50_000))
        assertEquals(1, chips(ime).size, "100 s after the first copy: the second one's minute is running")
        startField(ime, text, "b", packageName = "org.other.app")
        assertEquals(1, chips(ime).size, "also in the next app")
        chip(ime).performClick()
        assertEquals("bsecond", ShadowInputMethodService.text, "the second clip, after the \"b\" of the field")
    }

    @Test fun aNewCopyAfterTheMinuteBringsTheChipBack() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(61_000))
        assertEquals(emptyList(), chips(ime), "positive control: the first minute is over")
        copyText(ime, "second")
        startField(ime, text, "b", packageName = "org.other.app")
        assertEquals(1, chips(ime).size)
    }

    /** Measured on the reference phone: the system announces the clip of a copy a second time exactly 60 s later, with the same time stamp. */
    @Test fun theSystemAnnouncingTheSameClipAgainDoesNotKeepOrBringBackTheChip() = withKeyboard { ime ->
        val looper = shadowOf(Looper.getMainLooper())
        startField(ime)
        copyText(ime)
        assertEquals(1, chips(ime).size, "positive control: the copy shows the chip")
        looper.idleFor(Duration.ofMillis(59_990))
        ime.clipboardHistoryManager.onPrimaryClipChanged() // the system's second notice of the same clip, just before the minute ends
        looper.idleFor(Duration.ofMillis(10))
        assertEquals(emptyList(), chips(ime), "60 s after the copy the chip is gone, the second notice did not start another minute")
        ime.clipboardHistoryManager.onPrimaryClipChanged() // and once more after the minute
        startField(ime, text, "b", packageName = "org.other.app")
        assertEquals(emptyList(), chips(ime), "not a fresh copy: no chip in the next field either")
        copyText(ime)
        assertEquals(1, chips(ime).size, "positive control: a new clip is a copy")
    }

    @Test fun aSecondNoticeOfTheSameClipKeepsTheTimeOfTheCopy() = withKeyboard { ime ->
        val looper = shadowOf(Looper.getMainLooper())
        startField(ime)
        copyText(ime)
        looper.idleFor(Duration.ofMillis(40_000))
        ime.clipboardHistoryManager.onPrimaryClipChanged()
        looper.idleFor(Duration.ofMillis(19_000))
        assertEquals(1, chips(ime).size, "59 s after the copy")
        looper.idleFor(Duration.ofMillis(1_000))
        assertEquals(emptyList(), chips(ime), "60 s after the copy, not 100 s")
    }

    @Test fun aClipboardChangedToAnImageTakesTheChipFromTheNextField() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime)
        assertEquals(1, chips(ime).size, "positive control")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1)) // a later copy has a later time stamp
        (ime.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData("img", arrayOf("image/png"), ClipData.Item(android.net.Uri.parse("content://example/1"))))
        startField(ime, text, "b", packageName = "org.other.app")
        assertEquals(emptyList(), chips(ime))
    }

    @Test fun thePasswordAndIncognitoFieldsHaveTheChipAndItPastes() = withKeyboard { ime ->
        for (type in listOf(password, numberPassword, text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            copyWithoutKeyboardThenOpen(ime, type, if (type == numberPassword) "pin 4711 x" else clip)
            assertTrue(Settings.getValues().mPrivacy.passwordField, "type $type is a password field")
            assertEquals(1, chips(ime).size, "type $type has the chip")
            assertTrue(chips(ime).single().isShown || chips(ime).single().visibility == View.VISIBLE, "type $type: the chip is visible")
            chip(ime).performClick()
            assertEquals(if (type == numberPassword) "4711" else clip, ShadowInputMethodService.text, "type $type pastes")
            assertEquals(emptyList(), chips(ime))
        }
        ime.prefs().edit().putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, true).commit()
        copyWithoutKeyboardThenOpen(ime)
        assertTrue(Settings.getValues().mIncognitoModeEnabled, "positive control: the field is incognito")
        assertEquals(1, chips(ime).size, "an incognito field has the chip")
    }

    @Test fun theChipDoesNotShowTheClipOfAPasswordFieldEither() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime, password)
        assertEquals("Paste", chip(ime).text.toString())
        assertEquals("Paste", chip(ime).contentDescription.toString())
    }

    @Test fun aClipMarkedSensitiveGetsTheChipWithNoPreview() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime, text, clip, sensitive = true)
        assertEquals("Paste", chip(ime).text.toString(), "the chip shows no preview, so the flag does not matter to it")
        chip(ime).performClick()
        assertEquals(clip, ShadowInputMethodService.text)
    }

    @Test fun aSensitiveCopyStaysOutOfTheHistoryWhateverTheChipDoes() = withKeyboard { ime ->
        ime.prefs().edit().putBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, true).commit()
        startField(ime)
        val before = ime.clipboardHistoryManager.getHistorySize()
        copyText(ime, "hunter2", sensitive = true)
        assertEquals(1, chips(ime).size, "positive control: the chip path ran")
        assertEquals(before, ime.clipboardHistoryManager.getHistorySize(), "the history did not take the sensitive copy")
    }

    @Test fun theSettingOffMeansNoChip() = withKeyboard { ime ->
        ime.prefs().edit().putBoolean(Settings.PREF_SUGGEST_PASTE, false).commit()
        startField(ime)
        copyWithoutKeyboardThenOpen(ime)
        assertFalse(Settings.getValues().mSuggestPaste)
        assertEquals(emptyList(), chips(ime))
        ime.prefs().edit().putBoolean(Settings.PREF_SUGGEST_PASTE, true).commit()
        copyWithoutKeyboardThenOpen(ime)
        assertEquals(1, chips(ime).size, "positive control: on, the same steps give the chip")
    }

    @Test fun theSettingIsOnByDefault() = withKeyboard { ime ->
        assertFalse(ime.prefs().contains(Settings.PREF_SUGGEST_PASTE))
        startField(ime)
        assertTrue(Settings.getValues().mSuggestPaste)
    }

    @Test fun aLockedDeviceGetsNoChip() = withKeyboard { ime ->
        val keyguard = shadowOf(ime.getSystemService(KeyguardManager::class.java))
        startField(ime)
        keyguard.setKeyguardLocked(true)
        keyguard.setIsDeviceLocked(true)
        startField(ime) // the field that was started while locked: the copy is made in it
        ShadowInputMethodService.inputViewShown = false
        copyText(ime)
        ShadowInputMethodService.inputViewShown = true
        assertTrue(Settings.getValues().mPrivacy.locked, "positive control: the field is decided as locked")
        startField(ime)
        assertEquals(emptyList(), chips(ime), "copied while locked")
        keyguard.setKeyguardLocked(false)
        keyguard.setIsDeviceLocked(false)
        startField(ime)
        assertEquals(emptyList(), chips(ime), "a copy made while locked is not offered after the unlock")
        copyWithoutKeyboardThenOpen(ime)
        assertEquals(1, chips(ime).size, "positive control: unlocked, the chip is offered")
    }

    @Test fun aFieldThatStartsLockedHidesAChipThatWasArmedBefore() = withKeyboard { ime ->
        val keyguard = shadowOf(ime.getSystemService(KeyguardManager::class.java))
        startField(ime)
        ShadowInputMethodService.inputViewShown = false
        copyText(ime)
        ShadowInputMethodService.inputViewShown = true
        keyguard.setKeyguardLocked(true)
        keyguard.setIsDeviceLocked(true)
        startField(ime)
        assertEquals(emptyList(), chips(ime), "a locked field has no chip")
        keyguard.setKeyguardLocked(false)
        keyguard.setIsDeviceLocked(false)
        startField(ime)
        assertEquals(1, chips(ime).size, "the lock was only in the field's decision: the unlocked field still gets it within the minute")
    }

    @Test fun theScreenOffEndsTheChip() = withKeyboard { ime ->
        copyWithoutKeyboardThenOpen(ime)
        assertEquals(1, chips(ime).size, "positive control")
        ime.applicationContext.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        ime.applicationContext.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        val ei = lastField!!
        ime.mHandler.onStartInput(ei, true)
        ime.mHandler.onStartInputView(ei, true)
        assertEquals(emptyList(), chips(ime), "the lock ended the chip; the unlock does not bring it back")
        startField(ime, text, "b", packageName = "org.other.app")
        assertEquals(emptyList(), chips(ime), "and not in a field of another app within the minute either")
    }

    @Test fun anImageClipGetsNoChip() = withKeyboard { ime ->
        startField(ime)
        ShadowInputMethodService.inputViewShown = false
        (ime.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData("img", arrayOf("image/png"), ClipData.Item(android.net.Uri.parse("content://example/1"))))
        ShadowInputMethodService.inputViewShown = true
        startField(ime)
        assertEquals(emptyList(), chips(ime), "only text is pasted by the chip")
    }
}
