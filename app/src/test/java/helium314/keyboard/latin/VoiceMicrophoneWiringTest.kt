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
