// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.voice.engine.SpeechEngine
import java.util.concurrent.Executor

/** A source of 16 kHz mono 16-bit samples (the microphone in the app, a fake in the tests). */
interface AudioCapture {
    /** Opens the device and starts it; throws when it cannot (permission revoked, microphone in use). */
    fun start()

    /** Blocks until samples are available; returns the count read, or a value below 1 when the capture has ended or failed. */
    fun read(buffer: ShortArray, offset: Int, length: Int): Int

    /**
     * Stops the recording from ANOTHER thread so that a [read] blocked in the device returns at once (a cancel must not wait for samples).
     * Safe to call at any time, also after [release]; the device is released by [release] on the reading thread.
     */
    fun abort()

    /** Stops and releases the device. Safe to call twice. */
    fun release()
}

/**
 * One utterance at a time: record, close the microphone, transcribe, hand over the text.
 *
 * P20 (voice privacy): the audio lives in one array of [maxSeconds] seconds that is only in this object's worker task, is overwritten with
 * zeros as soon as the text is known (or the recording is cancelled) and is never written anywhere, logged or passed to anything but
 * the engine; this class has no file, log or network type (privacyCheck). The microphone is released before the engine runs and on
 * every way out of the recording loop, including an exception.
 *
 * Threads: [start], [stop], [cancel] are called on the main thread; the worker does the reading and the transcription (one task per
 * utterance, [worker] must run tasks one at a time); the listener is called through [main].
 */
class VoiceSession(
    private val capture: () -> AudioCapture,
    private val engine: () -> SpeechEngine,
    private val worker: Executor,
    private val main: Executor,
    private val listener: Listener,
    val maxSeconds: Int = MAX_SECONDS,
) {
    enum class State { IDLE, RECORDING, TRANSCRIBING }
    enum class Failure { MICROPHONE, ENGINE }

    interface Listener {
        fun onState(state: State)
        fun onText(text: String)
        fun onFailure(failure: Failure)
    }

    @Volatile private var state = State.IDLE
    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false
    @Volatile private var recording: AudioCapture? = null // the open device while the worker may be blocked in its read

    val current: State get() = state

    /** Starts recording. False when a recording or a transcription is already running, or the microphone could not be opened. */
    fun start(): Boolean {
        if (state != State.IDLE) return false
        val device = capture()
        try {
            device.start()
        } catch (_: Exception) {
            device.release()
            listener.onFailure(Failure.MICROPHONE)
            return false
        }
        stopRequested = false
        cancelled = false
        recording = device
        state = State.RECORDING
        listener.onState(State.RECORDING)
        worker.execute { run(device) }
        return true
    }

    /** Ends the recording and transcribes it. No effect unless recording. */
    fun stop() {
        if (state == State.RECORDING) stopRequested = true
    }

    /**
     * Ends the recording or the wait for the transcription and discards the audio; no text is delivered. The microphone is stopped here, on the
     * calling thread, because the worker may be blocked in a read that only returns when samples arrive or the device is stopped.
     */
    fun cancel() {
        if (state != State.IDLE) {
            cancelled = true
            stopRequested = true
            recording?.abort()
        }
    }

    private fun run(device: AudioCapture) {
        val pcm = ShortArray(maxSeconds * RATE)
        var length = 0
        try {
            try {
                while (!stopRequested && length < pcm.size) {
                    val n = device.read(pcm, length, minOf(CHUNK, pcm.size - length))
                    if (n < 1) break
                    length += n
                }
            } catch (_: Throwable) {
                if (!cancelled) main.execute { listener.onFailure(Failure.MICROPHONE) }
                return
            } finally {
                recording = null
                device.release() // the microphone is closed before anything else happens
            }
            if (cancelled) return
            post(State.TRANSCRIBING)
            val samples = FloatArray(length) { pcm[it] / 32768f }
            pcm.fill(0)
            val text = try {
                engine().transcribe(samples, length)
            } catch (_: Throwable) {
                if (!cancelled) main.execute { listener.onFailure(Failure.ENGINE) }
                return
            } finally {
                samples.fill(0f)
            }
            if (!cancelled && text.isNotEmpty()) main.execute { listener.onText(text) }
        } finally {
            pcm.fill(0)
            main.execute {
                state = State.IDLE
                listener.onState(State.IDLE)
            }
        }
    }

    private fun post(next: State) = main.execute {
        if (state != State.IDLE) { // not after a cancel that already finished
            state = next
            listener.onState(next)
        }
    }

    companion object {
        const val RATE = 16000
        const val CHUNK = 1600 // 100 ms
        const val MAX_SECONDS = 60
    }
}
