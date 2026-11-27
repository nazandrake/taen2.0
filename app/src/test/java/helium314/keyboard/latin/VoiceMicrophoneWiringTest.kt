// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.Manifest
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.AudioCapture
import helium314.keyboard.latin.voice.VoiceEngineHolder
import helium314.keyboard.latin.voice.VoiceModelSpec
import helium314.keyboard.latin.voice.VoiceSession
import helium314.keyboard.latin.voice.engine.SpeechEngine
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P20, the microphone's lifetime in the real keyboard service: it opens only after a tap on the voice key and closes at the stop, a field
 * change, the window closing, the screen lock, an incognito switch and the end of the service. The microphone and the engine are fakes
 * (VoiceTyping's seams); everything else is the production wiring, so removing one of the calls in LatinIME, VoiceTyping or the toolbar
 * key's handler fails a test here. The length limits are in VoiceSessionTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class, ShadowFacilitator2::class])
class VoiceMicrophoneWiringTest {
    /**
     * A microphone that delivers 100 ms of silence every 100 ms until it is stopped (abort) or released, like AudioRecord.read. Real time matters: a
     * faster fake reaches the 15 s phrase limit within the wait of a test, and a phrase handed to a transcriber that was shut down ends the
     * recording too, which would hide a missing cancel.
     */
    private class Mic : AudioCapture {
        val starts = AtomicInteger()
        val released = CountDownLatch(1)
        private val stopped = CountDownLatch(1)
        override fun start() { starts.incrementAndGet() }
        override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
            if (stopped.await(100, TimeUnit.MILLISECONDS)) return -1
            buffer.fill(0, offset, offset + minOf(length, VoiceSession.CHUNK))
            return minOf(length, VoiceSession.CHUNK)
        }
        override fun abort() { stopped.countDown() }
        override fun release() { stopped.countDown(); released.countDown() }
        val open get() = starts.get() == 1 && released.count == 1L
        fun closes() = released.await(3, TimeUnit.SECONDS)
    }

    private val idle = { shadowOf(Looper.getMainLooper()).idle() }

    private fun withKeyboard(test: (LatinIME, Mic, AtomicInteger) -> Unit) {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        ShadowInputMethodService.reset()
        val ime = Robolectric.setupService(LatinIME::class.java)
        Settings.init(ime)
        val dir = Privacy.voiceModelDir(ime)
        dir.deleteRecursively()
        val mic = Mic()
        val captures = AtomicInteger()
        ime.mVoiceTyping.newCapture = { captures.incrementAndGet(); mic }
        ime.mVoiceTyping.newEngine = {
            VoiceEngineHolder { object : SpeechEngine { override fun transcribe(pcm: FloatArray, length: Int) = ""; override fun close() {} } }
        }
        try {
            dir.mkdirs() // sparse files of the exact sizes: the cheap completeness check passes
            for (e in VoiceModelSpec.entries) RandomAccessFile(File(dir, e.name), "rw").use { it.setLength(e.size) }
            shadowOf(ime.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
            startField(ime)
            test(ime, mic, captures)
        } finally {
            Privacy.unlock(ime)
            ime.mVoiceTyping.onDestroy() // the test shadow cannot run the framework's onDestroy (NextWordTypingLifecycleTest); a second call is harmless
            idle()
            dir.deleteRecursively()
            Privacy.forgetCachedState()
            ClipboardDao.forgetInstance()
            ShadowInputMethodService.reset()
        }
    }

    private fun startField(ime: LatinIME) {
        val editor = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        ime.mHandler.onStartInput(editor, false)
        ime.mHandler.onStartInputView(editor, false)
        idle()
    }

    private fun tap(ime: LatinIME) {
        ime.onEvent(Event.createSoftwareKeypressEvent(KeyCode.VOICE_INPUT, 0, 0, 0, false))
        idle()
    }

    /** A tap opens the microphone (the positive control of every test), then [close] must close it. */
    private fun closedBy(close: (LatinIME) -> Unit) = withKeyboard { ime, mic, _ ->
        tap(ime)
        assertTrue(mic.open, "the tap opened the microphone")
        Thread.sleep(250) // the reader is in its loop
        assertTrue(mic.open, "and it stays open while nothing ends the recording")
        close(ime)
        idle()
        assertTrue(mic.closes(), "the microphone was released")
        idle()
        assertEquals(1, mic.starts.get())
    }

    @Test fun theMicrophoneOpensOnlyAtATapOnTheVoiceKey() = withKeyboard { ime, mic, captures ->
        // the service started, a field started and the settings were loaded: nothing opened a microphone
        ime.onStartInputView(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, true)
        idle()
        assertEquals(0, captures.get())
        assertEquals(0, mic.starts.get())
        tap(ime)
        assertEquals(1, captures.get())
        assertEquals(1, mic.starts.get())
    }

    @Test fun aSecondTapClosesTheMicrophone() = closedBy { tap(it) }

    @Test fun anotherFieldClosesTheMicrophone() = closedBy { ime ->
        ime.mHandler.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
    }

    @Test fun theKeyboardWindowClosingClosesTheMicrophone() = closedBy { it.onFinishInputView(true) }

    @Test fun theScreenLockClosesTheMicrophone() = closedBy { ScreenLockPolicy.onScreenOff(it) }

    @Test fun switchingIncognitoModeClosesTheMicrophone() = closedBy { ime ->
        ime.mKeyboardActionListener.onCodeInput(KeyCode.TOGGLE_INCOGNITO_MODE, 0, 0, false)
        assertTrue(ime.prefs().getBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, false), "positive control: the key switched incognito mode on")
    }

    private fun restart(ime: LatinIME, editor: EditorInfo, restarting: Boolean = true) {
        ShadowInputMethodService.currentInputType = editor.inputType // the settings are loaded from the service's current editor
        ShadowInputMethodService.currentImeOptions = editor.imeOptions
        ime.mHandler.onStartInput(editor, restarting)
        ime.mHandler.onStartInputView(editor, restarting)
        idle()
    }

    /** A restarted field (the same field, a new input type) that turns into a password or no-learning field closes the microphone. */
    @Test fun aRestartedFieldThatBecomesAPasswordFieldClosesTheMicrophone() = closedBy { ime ->
        restart(ime, EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD })
        assertTrue(Settings.getValues().mPrivacy.passwordField, "positive control: the restarted field is a password field")
    }

    @Test fun aRestartedFieldThatBecomesNoLearningClosesTheMicrophone() = closedBy { ime ->
        restart(ime, EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        })
        assertTrue(Settings.getValues().mPrivacy.incognito, "positive control: the restarted field is incognito")
        assertFalse(Settings.getValues().mPrivacy.passwordField)
    }

    /** The negative control of the two above: a restart to another ordinary input type keeps the recording. */
    @Test fun aRestartedFieldThatStaysOrdinaryKeepsTheMicrophoneOpen() = withKeyboard { ime, mic, _ ->
        tap(ime)
        assertTrue(mic.open)
        restart(ime, EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS })
        assertFalse(Settings.getValues().mPrivacy.incognito, "positive control: the restart was handled and the field is still ordinary")
        Thread.sleep(300)
        assertTrue(mic.open, "an ordinary restart does not end the recording")
        tap(ime)
        assertTrue(mic.closes())
    }

    /** Only a change into incognito closes it: a restart of a field that was already incognito (here no-learning, then a number type) keeps the recording. */
    @Test fun aRestartedFieldThatWasAlreadyIncognitoKeepsTheMicrophoneOpen() = withKeyboard { ime, mic, _ ->
        val noLearning = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        restart(ime, EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; imeOptions = noLearning }, restarting = false)
        assertTrue(Settings.getValues().mPrivacy.incognito, "positive control: the field is incognito before the restart")
        tap(ime)
        assertTrue(mic.open)
        restart(ime, EditorInfo().apply { inputType = InputType.TYPE_CLASS_NUMBER; imeOptions = noLearning })
        assertEquals(InputType.TYPE_CLASS_NUMBER, Settings.getValues().mInputAttributes.mInputType, "positive control: the restart was handled")
        Thread.sleep(300)
        assertTrue(mic.open, "the restart did not change the privacy decision")
        tap(ime)
        assertTrue(mic.closes())
    }

    /** A password field closes it whatever the field was before: here a no-learning field (already incognito) restarted as a password field. */
    @Test fun anIncognitoFieldRestartedAsAPasswordFieldClosesTheMicrophone() = withKeyboard { ime, mic, _ ->
        val noLearning = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        restart(ime, EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; imeOptions = noLearning }, restarting = false)
        assertTrue(Settings.getValues().mPrivacy.incognito, "positive control: the field is incognito before the restart")
        tap(ime)
        assertTrue(mic.open, "the tap opened the microphone")
        restart(ime, EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; imeOptions = noLearning })
        assertTrue(Settings.getValues().mPrivacy.passwordField, "positive control: the restarted field is a password field")
        assertTrue(mic.closes(), "the microphone was released")
    }

    /** The same from force-incognito into a number password (another of the four password variations). The control is the test above it that keeps the recording. */
    @Test fun aForceIncognitoFieldRestartedAsANumberPasswordClosesTheMicrophone() = withKeyboard { ime, mic, _ ->
        assertTrue(ime.prefs().edit().putBoolean(Settings.PREF_ALWAYS_INCOGNITO_MODE, true).commit())
        restart(ime, EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, restarting = false)
        assertTrue(Settings.getValues().mPrivacy.incognito, "positive control: the field is incognito before the restart")
        assertFalse(Settings.getValues().mPrivacy.passwordField)
        tap(ime)
        assertTrue(mic.open, "the tap opened the microphone")
        restart(ime, EditorInfo().apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD })
        assertTrue(Settings.getValues().mPrivacy.passwordField, "positive control: the restarted field is a password field")
        assertTrue(mic.closes(), "the microphone was released")
    }

    /** What LatinIME.onDestroy calls (the shadow cannot run the framework's own onDestroy, see NextWordTypingLifecycleTest). */
    @Test fun theEndOfTheServiceClosesTheMicrophone() = closedBy { it.mVoiceTyping.onDestroy() }

    @Test fun aSessionThatEndsLeavesNoMicrophoneOpenForTheNextTap() = withKeyboard { ime, mic, _ ->
        tap(ime)
        tap(ime)
        assertTrue(mic.closes())
        idle()
        assertFalse(mic.open)
        assertEquals(1, mic.starts.get()) // the stop did not open anything again
    }
}
