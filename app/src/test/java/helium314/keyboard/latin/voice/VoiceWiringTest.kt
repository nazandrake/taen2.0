// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.Manifest
import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.ScreenLockPolicy
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.ShadowKeyboardSwitcher
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.SettingsActivity2
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Production wiring (P20): a tap on the voice key travels from the keyboard service through VoiceTyping and the VoiceController to the
 * gate, in the real LatinIME with its real settings. Removing the call in LatinIME.onEvent, the password check, the setting, the lock hook or
 * the permission check fails a test here. The microphone and the model themselves are covered by VoiceSessionTest, VoiceModelStoreTest and the smoke test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class, ShadowFacilitator2::class])
class VoiceWiringTest {
    private val text = InputType.TYPE_CLASS_TEXT

    private fun withKeyboard(test: (LatinIME, Context) -> Unit) {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        ShadowInputMethodService.reset()
        ShadowToast.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        val dir = Privacy.voiceModelDir(ime)
        dir.deleteRecursively()
        try {
            test(ime, ime.applicationContext)
        } finally {
            dir.deleteRecursively()
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ClipboardDao.forgetInstance()
            ShadowInputMethodService.reset()
        }
    }

    private fun tap(ime: LatinIME) {
        ime.onEvent(Event.createSoftwareKeypressEvent(KeyCode.VOICE_INPUT, 0, 0, 0, false))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun startField(ime: LatinIME, inputType: Int) { // the same way AntiSnoopingWiringTest loads the settings for a field
        val editor = EditorInfo().apply { this.inputType = inputType }
        Settings.getInstance().loadSettings(ime, Locale.ENGLISH, InputAttributes(editor, false, ime.packageName))
    }

    /** Sparse files of the exact sizes: the cheap completeness check passes without 670 MB on disk. */
    private fun putModel(ime: LatinIME) {
        val dir = Privacy.voiceModelDir(ime).also { it.mkdirs() }
        for (e in VoiceModelSpec.entries) RandomAccessFile(File(dir, e.name), "rw").use { it.setLength(e.size) }
    }

    private fun deny(ime: LatinIME) = shadowOf(ime.application).denyPermissions(Manifest.permission.RECORD_AUDIO)
    private fun grant(ime: LatinIME) = shadowOf(ime.application).grantPermissions(Manifest.permission.RECORD_AUDIO)

    private fun nextActivity(ime: LatinIME): String? = shadowOf(ime.application).nextStartedActivity?.component?.className
    private fun nextIntent(ime: LatinIME) = shadowOf(ime.application).nextStartedActivity

    @Test fun withoutAModelTheKeyExplainsAndOpensTheSettingsAndAsksForNothing() = withKeyboard { ime, _ ->
        startField(ime, text)
        deny(ime)
        tap(ime)
        assertEquals(ime.getString(R.string.voice_model_missing), ShadowToast.getTextOfLatestToast())
        val intent = nextIntent(ime)!!
        assertEquals(SettingsActivity2::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(SettingsActivity.EXTRA_VOICE_SETTINGS, false), "the Voice typing page opens directly, not the main settings page")
        assertNull(nextActivity(ime)) // and nothing else: no permission dialog
    }

    @Test fun withAModelButWithoutThePermissionTheKeyAsksForItThroughTheDialogActivity() = withKeyboard { ime, _ ->
        startField(ime, text)
        putModel(ime)
        deny(ime)
        tap(ime)
        assertEquals(VoicePermissionActivity::class.java.name, nextActivity(ime))
        assertNull(ShadowToast.getLatestToast())
        // positive control: granting the permission removes the request (the tap then goes on to the microphone)
        grant(ime)
        tap(ime)
        assertNull(nextActivity(ime))
    }

    @Test fun aPasswordFieldIgnoresTheKeyAndHidesIt() = withKeyboard { ime, _ ->
        putModel(ime)
        deny(ime)
        startField(ime, text or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertFalse(Settings.getValues().mShowsVoiceInputKey, "the voice key is hidden in a password field")
        tap(ime)
        assertNull(nextActivity(ime)); assertNull(ShadowToast.getLatestToast())
        startField(ime, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        tap(ime)
        assertNull(nextActivity(ime))
        // positive control: an ordinary text field shows the key and the same tap asks for the permission
        startField(ime, text)
        assertTrue(Settings.getValues().mShowsVoiceInputKey)
        tap(ime)
        assertEquals(VoicePermissionActivity::class.java.name, nextActivity(ime))
    }

    @Test fun theSettingTurnsTheKeyAndTheMicrophoneOff() = withKeyboard { ime, ctx ->
        putModel(ime)
        deny(ime)
        ctx.prefs().edit().putBoolean(Settings.PREF_VOICE_TYPING, false).commit()
        startField(ime, text)
        assertFalse(Settings.getValues().mShowsVoiceInputKey)
        tap(ime)
        assertNull(nextActivity(ime)); assertNull(ShadowToast.getLatestToast())
        ctx.prefs().edit().putBoolean(Settings.PREF_VOICE_TYPING, true).commit()
        startField(ime, text)
        assertTrue(Settings.getValues().mShowsVoiceInputKey)
        tap(ime)
        assertEquals(VoicePermissionActivity::class.java.name, nextActivity(ime))
    }

    @Test fun aLockedScreenIgnoresTheKey() = withKeyboard { ime, _ ->
        putModel(ime)
        deny(ime)
        startField(ime, text)
        ScreenLockPolicy.onScreenOff(ime)
        assertTrue(Privacy.isLocked)
        tap(ime)
        assertNull(nextActivity(ime)); assertNull(ShadowToast.getLatestToast())
        ScreenLockPolicy.onUserPresent(ime)
        tap(ime)
        assertEquals(VoicePermissionActivity::class.java.name, nextActivity(ime))
    }

    @Test fun theModelDirectoryIsInTheNoBackupAreaOfTheDeviceProtectedStorage() = withKeyboard { ime, _ ->
        val dir = Privacy.voiceModelDir(ime)
        assertEquals("taen-voice", dir.name)
        assertEquals(ime.createDeviceProtectedStorageContext().noBackupFilesDir.path, dir.parentFile!!.path)
        assertNotNull(dir)
    }
}
