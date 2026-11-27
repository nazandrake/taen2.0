// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.voice.engine.SpeechEngine
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

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
 * One recording at a time, typed phrase by phrase: the microphone is read until the stop (at most [maxSeconds]); [PauseDetector] cuts the
 * recording at pauses (and at [PauseDetector.MAX_PHRASE_SECONDS]), and each phrase is transcribed while the user goes on speaking and handed
 * over as text at once. The stop ends the recording and the last phrase is transcribed then.
 *
 * P20 (voice privacy): the audio lives in one array of [maxSeconds] seconds that only this object's tasks see. The part of a phrase is
 * overwritten with zeros as soon as its float copy for the engine is made, the float copy as soon as the engine returns, and the whole array
 * when the recording ends (also after a cancel or an error). It is never written anywhere, logged or passed to anything but the engine; this
 * class has no file, log or network type (privacyCheck). The microphone stays open while a phrase is transcribed and is released at the stop,
 * at the length limit, at a cancel (field change, window closed, screen lock, incognito switch, service end), when the engine fails and on
 * every way out of the reading loop, including an exception.
 *
 * Threads: [start], [stop], [cancel] are called on the main thread; [reader] runs the reading loop (one task per recording); [transcriber]
 * runs the transcriptions, one phrase at a time in order (it must run its tasks one after another: one phrase in flight); the listener is
 * called through [main]. The two may be one inline executor (tests): then each phrase is transcribed before the next read.
 */
class VoiceSession(
    private val capture: () -> AudioCapture,
    private val engine: () -> SpeechEngine,
    private val reader: Executor,
    private val transcriber: Executor,
    private val main: Executor,
    private val listener: Listener,
    val maxSeconds: Int = MAX_SECONDS,
    private val newPauses: () -> PauseDetector = { PauseDetector() },
) {
    enum class State {
        IDLE,
        /** The microphone is open (the listening state); finished phrases are transcribed and typed meanwhile. */
        RECORDING,
        /** The microphone is closed; the last phrases are being transcribed. */
        TRANSCRIBING,
    }
    enum class Failure { MICROPHONE, ENGINE }

    interface Listener {
        fun onState(state: State)
        fun onText(text: String)
        fun onFailure(failure: Failure)
    }

    @Volatile private var state = State.IDLE
    @Volatile private var stopRequested = false
    @Volatile private var cancelled = false
    @Volatile private var failed = false // the engine failed in this recording: no more phrases are transcribed
    @Volatile private var recording: AudioCapture? = null // the open device while the reader may be blocked in its read

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
        failed = false
        recording = device
        state = State.RECORDING
        listener.onState(State.RECORDING)
        reader.execute { run(device) }
        return true
    }

    /** Ends the recording; the last phrase is transcribed. No effect unless recording. */
    fun stop() {
        if (state == State.RECORDING) stopRequested = true
    }

    /**
     * Ends the recording or the wait for the transcriptions and discards the audio; no more text is delivered. The microphone is stopped
     * here, on the calling thread, because the reader may be blocked in a read that only returns when samples arrive or the device is stopped.
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
        val pauses = newPauses()
        var length = 0
        var phraseStart = 0
        try {
            try {
                while (!stopRequested && length < pcm.size) {
                    val n = device.read(pcm, length, minOf(CHUNK, pcm.size - length))
                    if (n < 1) break
                    val cut = pauses.next(pcm, length, n)
                    length += n
                    if (cut) {
                        transcribe(pcm, phraseStart, length)
                        phraseStart = length
                    }
                }
            } catch (_: Throwable) {
                if (!cancelled) main.execute { listener.onFailure(Failure.MICROPHONE) }
                cancelled = true // nothing more is typed from this recording
                return
            } finally {
                recording = null
                device.release() // the microphone is closed before anything else happens
            }
            if (cancelled) return
            post(State.TRANSCRIBING)
            if (length > phraseStart) transcribe(pcm, phraseStart, length) // the last phrase, whatever its levels
        } finally {
            val end = Runnable {
                pcm.fill(0)
                main.execute {
                    state = State.IDLE
                    listener.onState(State.IDLE)
                }
            }
            try {
                transcriber.execute(end) // after every phrase: the transcriber runs its tasks in order
            } catch (_: RejectedExecutionException) {
                end.run() // the service ended and shut the transcriber down: the audio is zeroed here, and nothing is thrown
            }
        }
    }

    /** Queues the phrase [from, to) of [pcm]: transcribed after the earlier ones, its audio cleared from [pcm] once copied. */
    private fun transcribe(pcm: ShortArray, from: Int, to: Int) = try {
        transcribePhrase(pcm, from, to)
    } catch (_: RejectedExecutionException) { // the service ended: the transcriber is shut down, the phrase is dropped
        pcm.fill(0, from, to)
    }

    private fun transcribePhrase(pcm: ShortArray, from: Int, to: Int) = transcriber.execute {
        if (cancelled || failed) {
            pcm.fill(0, from, to)
            return@execute
        }
        val count = to - from
        val samples = FloatArray(count) { pcm[from + it] / 32768f }
        pcm.fill(0, from, to)
        val text = try {
            engine().transcribe(samples, count)
        } catch (_: Throwable) {
            failed = true
            if (!cancelled) {
                recording?.abort() // the engine cannot type anything: the microphone is closed too
                stopRequested = true
                main.execute { listener.onFailure(Failure.ENGINE) }
            }
            return@execute
        } finally {
            samples.fill(0f)
        }
        if (!cancelled && text.isNotEmpty()) main.execute { if (!cancelled) listener.onText(text) }
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
