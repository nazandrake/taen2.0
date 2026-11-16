// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.voice.engine.SpeechEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceEngineHolderTest {
    private class Engine(val id: String = "x") : SpeechEngine {
        val closed = AtomicInteger()
        override fun transcribe(pcm: FloatArray, length: Int) = id
        override fun close() { closed.incrementAndGet() }
    }

    private val audio = FloatArray(0)

    @Test fun loadsOnceAndUsesTheSameEngineUntilItIsUnloaded() {
        val loads = AtomicInteger()
        val engines = ArrayList<Engine>()
        val holder = VoiceEngineHolder { loads.incrementAndGet(); Engine("engine ${engines.size}").also { engines.add(it) } }
        assertFalse(holder.isLoaded)
        holder.prepare(); holder.prepare()
        assertEquals("engine 0", holder.transcribe(audio, 0))
        assertEquals("engine 0", holder.transcribe(audio, 0))
        assertEquals(1, loads.get())
        assertTrue(holder.isLoaded)
        holder.unload()
        assertEquals(1, engines[0].closed.get())
        assertFalse(holder.isLoaded)
        assertEquals("engine 1", holder.transcribe(audio, 0))
        assertEquals(2, loads.get())
        holder.close() // the holder is the engine the session sees: closing it unloads
        assertEquals(1, engines[1].closed.get())
        assertEquals(1, engines[0].closed.get())
    }

    @Test fun aFailedLoadIsReportedWithItsCauseAndTheNextPrepareTriesAgain() {
        val loads = AtomicInteger()
        val holder = VoiceEngineHolder { if (loads.incrementAndGet() == 1) throw IllegalStateException("files do not verify") else Engine() }
        val error = assertFailsWith<IllegalStateException> { holder.transcribe(audio, 0) }
        assertEquals("files do not verify", error.message)
        assertFalse(holder.isLoaded)
        holder.transcribe(audio, 0) // a retry loads (the user imported the model again)
        assertEquals(2, loads.get())
        assertTrue(holder.isLoaded)
    }

    @Test fun transcribeWaitsForALoadThatIsStillRunning() {
        val release = CountDownLatch(1)
        val holder = VoiceEngineHolder { release.await(5, TimeUnit.SECONDS); Engine() }
        holder.prepare()
        assertFalse(holder.isLoaded) // still loading
        Thread { Thread.sleep(100); release.countDown() }.start()
        holder.transcribe(audio, 0)
        assertTrue(holder.isLoaded)
    }

    @Test fun unloadWhileLoadingClosesTheEngineWhenItArrives() {
        val release = CountDownLatch(1)
        val engine = Engine()
        val holder = VoiceEngineHolder { release.await(5, TimeUnit.SECONDS); engine }
        holder.prepare()
        holder.unload()
        assertEquals(0, engine.closed.get())
        release.countDown()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (engine.closed.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(1, engine.closed.get())
        assertFalse(holder.isLoaded)
    }

    // ---- unload must not close the native session under a running transcription ----

    private class SlowEngine : SpeechEngine {
        val entered = CountDownLatch(1)
        val go = CountDownLatch(1)
        val log: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        override fun transcribe(pcm: FloatArray, length: Int): String {
            log.add("transcribe start")
            entered.countDown()
            go.await(5, TimeUnit.SECONDS)
            log.add("transcribe end")
            return "text"
        }
        override fun close() { log.add("close") }
    }

    private fun runInThread(holder: VoiceEngineHolder, out: MutableList<String>): Thread =
        Thread { out.add(runCatching { holder.transcribe(audio, 0) }.getOrElse { "error: ${it.message}" }) }.apply { isDaemon = true; start() }

    @Test fun unloadDuringATranscriptionWaitsForItAndTheWorkerClosesTheEngineAfterwards() {
        val engine = SlowEngine()
        val holder = VoiceEngineHolder { engine }
        val results = java.util.Collections.synchronizedList(ArrayList<String>())
        val worker = runInThread(holder, results)
        assertTrue(engine.entered.await(5, TimeUnit.SECONDS), "positive control: the transcription is running")
        holder.unload() // returns at once and must not close
        assertEquals(listOf("transcribe start"), engine.log)
        assertFalse(holder.isLoaded)
        holder.unload() // a second unload changes nothing
        assertEquals(listOf("transcribe start"), engine.log)
        engine.go.countDown()
        worker.join(5000)
        assertEquals(listOf("text"), results) // the running transcription completed
        assertEquals(listOf("transcribe start", "transcribe end", "close"), engine.log) // closed exactly once, after the last native call
    }

    @Test fun anEngineIdleAtUnloadIsClosedAtOnceAndATranscriptionAfterwardsLoadsAFreshOne() {
        val loads = AtomicInteger()
        val engines = ArrayList<SlowEngine>()
        val holder = VoiceEngineHolder { loads.incrementAndGet(); SlowEngine().also { it.go.countDown(); engines.add(it) } }
        holder.transcribe(audio, 0)
        holder.unload()
        assertEquals(listOf("transcribe start", "transcribe end", "close"), engines[0].log)
        holder.transcribe(audio, 0)
        assertEquals(2, loads.get())
        assertEquals(listOf("transcribe start", "transcribe end"), engines[1].log)
    }

    @Test fun aTranscriptionThatArrivesAfterTheUnloadWhileLoadingIsRefusedAndTheEngineIsClosedOnce() {
        val release = CountDownLatch(1)
        val engine = SlowEngine().also { it.go.countDown() }
        val holder = VoiceEngineHolder { release.await(5, TimeUnit.SECONDS); engine }
        val results = java.util.Collections.synchronizedList(ArrayList<String>())
        val worker = runInThread(holder, results) // waits for the load
        while (worker.state != Thread.State.WAITING) Thread.sleep(1) // parked in the load
        holder.unload()
        release.countDown()
        worker.join(5000)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!engine.log.contains("close") && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(listOf("error: the voice engine was unloaded"), results)
        assertEquals(listOf("close"), engine.log) // never used, closed exactly once
    }
}
