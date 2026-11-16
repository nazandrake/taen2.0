// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.voice.engine.OrtParakeet
import helium314.keyboard.latin.voice.engine.SpeechEngine
import helium314.keyboard.latin.voice.engine.TransducerSpeechEngine
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * Loads the speech engine on its own thread (the model files are hashed against [VoiceModelSpec] first, then mapped by ONNX Runtime: a few
 * seconds on a slow phone), so loading overlaps with the user speaking. [unload] frees the roughly 700 MB again.
 * [load] is a parameter so tests use a fake engine.
 *
 * The session talks to the engine only through [transcribe]: an [unload] that arrives while a transcription runs (service destroyed,
 * idle timer) never closes the native session under it (use after free); the worker that is running closes it when it is done.
 * [close] is [unload], so this object is the engine the session sees.
 */
class VoiceEngineHolder(private val load: () -> SpeechEngine) : SpeechEngine {
    private class Slot(val task: FutureTask<SpeechEngine>) {
        var busy = 0 // transcriptions running on this engine (at most one: one worker)
        var retired = false // unload was called; nobody may start a transcription on it any more
    }

    private var slot: Slot? = null

    /** Starts loading if no engine is loaded or loading. */
    @Synchronized
    fun prepare() {
        val current = slot
        if (current != null && !(current.task.isDone && failed(current.task))) return
        val next = Slot(FutureTask(load))
        slot = next
        Thread(next.task, "taen-voice-load").apply { isDaemon = true }.start()
    }

    /** Waits for the load, then runs the transcription; throws when the engine cannot be loaded (the next [prepare] tries again) or was unloaded meanwhile. */
    override fun transcribe(pcm: FloatArray, length: Int): String {
        prepare()
        val mine = synchronized(this) { slot!! }
        val engine = try {
            mine.task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
        synchronized(this) {
            check(!mine.retired) { "the voice engine was unloaded" } // unload already closed it (or a thread will, once the load is done)
            mine.busy++
        }
        try {
            return engine.transcribe(pcm, length)
        } finally {
            synchronized(this) {
                mine.busy--
                if (mine.retired && mine.busy == 0) closeEngine(mine.task) // unload came during the run: closed here, after the last native call
            }
        }
    }

    /** Frees the engine: at once when idle, when the load is done when still loading, after the running transcription when one is in progress. */
    @Synchronized
    override fun close() = unload()

    @Synchronized
    fun unload() {
        val current = slot ?: return
        slot = null
        current.retired = true
        when {
            current.busy > 0 -> Unit // transcribe() closes it
            current.task.isDone -> closeEngine(current.task)
            else -> Thread { closeEngine(current.task) }.apply { isDaemon = true }.start() // still loading: close it when it is there
        }
    }

    val isLoaded: Boolean @Synchronized get() = slot?.let { it.task.isDone && !failed(it.task) } ?: false

    private fun closeEngine(t: FutureTask<SpeechEngine>) { runCatching { t.get().close() } }

    private fun failed(t: FutureTask<SpeechEngine>) = runCatching { t.get() }.isFailure

    companion object {
        /** The real engine: verified model files, ONNX Runtime with [threads] threads. */
        fun parakeet(store: VoiceModelStore, threads: Int = 4) = VoiceEngineHolder {
            check(store.verify()) { "voice model files do not match the pinned hashes" }
            val model = OrtParakeet(store.file(VoiceModelSpec.ENCODER).path, store.file(VoiceModelSpec.DECODER).path,
                store.file(VoiceModelSpec.JOINER).path, threads)
            try {
                TransducerSpeechEngine.parakeetV3(model, store.tokenLines())
            } catch (e: Throwable) {
                model.close()
                throw e
            }
        }
    }
}
