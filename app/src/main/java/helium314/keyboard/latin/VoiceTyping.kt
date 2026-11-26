// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.privacy.isReadable
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.voice.MicCapture
import helium314.keyboard.latin.voice.VoiceController
import helium314.keyboard.latin.voice.VoiceEngineHolder
import helium314.keyboard.latin.voice.VoiceModelStore
import helium314.keyboard.latin.voice.VoicePermissionActivity
import helium314.keyboard.latin.voice.VoiceSession
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Voice typing in the keyboard service (P20): connects the voice key, the settings and the field to [VoiceController]. Everything is
 * on this device: the microphone (MicCapture), ONNX Runtime, no network. The service owns one instance; the recording is cancelled when the field changes,
 * the window closes, the screen locks (Privacy.onLock, also a self-destruct) and when the service ends.
 */
class VoiceTyping(private val ime: LatinIME) : VoiceController.Host {
    private val handler = Handler(Looper.getMainLooper())
    private val main = Executor { handler.post(it) }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "taen-voice").apply { isDaemon = true } }
    private val store by lazy { VoiceModelStore(Privacy.voiceModelDir(ime)) }
    private val engines by lazy { VoiceEngineHolder.parakeet(store, THREADS) }
    private var generation = 0

    // the controller is created at the first use: the service context is not ready when the field initialisers of LatinIME run
    private val controller by lazy {
        VoiceController(this) { listener -> VoiceSession({ MicCapture() }, { engines }, worker, main, listener) }
    }
    private val unloadEngine = Runnable { if (controller.state == VoiceSession.State.IDLE) engines.unload() }

    private val lockHook: () -> Unit = { handler.post { cancel() } }

    fun onCreate() {
        Privacy.onLock(LOCK_HOOK, lockHook) // screen off, and the start of a self-destruct wipe
    }

    fun onDestroy() {
        Privacy.removeOnLock(LOCK_HOOK, lockHook) // the hook holds this service: it ends with it
        handler.removeCallbacks(unloadEngine)
        cancel()
        engines.unload()
        worker.shutdown()
    }

    /** A tap on the voice key. */
    fun onKey() = controller.onKey()

    /** Long press of the voice key in the toolbar: record while it is held. */
    fun onHoldStart() = controller.onHoldStart()

    fun onHoldEnd() = controller.onHoldEnd()

    /** The text field changes or the window closes. */
    fun onFieldChange() {
        generation++
        cancel()
    }

    private fun cancel() = controller.cancel()

    // ---- VoiceController.Host ----

    override fun voiceEnabled() = ime.prefs().let { it.isReadable() && it.getBoolean(Settings.PREF_VOICE_TYPING, Defaults.PREF_VOICE_TYPING) } // unreadable settings: off
    override fun passwordField() = ime.mSettings.current.mPrivacy.passwordField // PrivacyPolicy is the only owner of that test (P9)
    override fun locked() = Privacy.isLocked
    override fun modelReady() = store.isComplete()
    override fun permissionGranted() = VoicePermissionActivity.granted(ime)
    override fun requestPermission() = VoicePermissionActivity.start(ime)

    override fun explainModelMissing() {
        Toast.makeText(ime, R.string.voice_model_missing, Toast.LENGTH_LONG).show()
        ime.launchVoiceSettings() // the Voice typing page with Download and Install, not the main settings page
    }

    override fun fieldGeneration() = generation
    override fun prepareEngine() {
        handler.removeCallbacks(unloadEngine)
        engines.prepare()
    }

    override fun commit(text: String) = ime.commitVoiceText(text)

    override fun showState(state: VoiceSession.State) {
        ime.showVoiceState(state)
        if (state == VoiceSession.State.IDLE) handler.postDelayed(unloadEngine, ENGINE_IDLE_MILLIS)
    }

    override fun showFailure(failure: VoiceSession.Failure) {
        Toast.makeText(ime, if (failure == VoiceSession.Failure.MICROPHONE) R.string.voice_failed_mic else R.string.voice_failed_engine, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val LOCK_HOOK = "voice"
        const val THREADS = 4 // Pixel 8 Pro: 4 of 9 cores (the X3 and A715 group); reference phone: 4 of 8 A53 cores
        const val ENGINE_IDLE_MILLIS = 120_000L // about 700 MB are freed two minutes after the last recording
    }
}
