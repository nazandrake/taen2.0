// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.voice.engine.SpeechEngine
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The recording session with a fake microphone and a fake engine; both executors run tasks inline, so every order of calls is deterministic. */
class VoiceSessionTest {
    private val inline = Executor { it.run() }

    private class FakeCapture(private val onRead: (call: Int) -> Unit = {}, private val chunks: List<Int> = List(10) { 1600 }, val failStart: Boolean = false) : AudioCapture {
        val events = ArrayList<String>()
        var calls = 0
        var aborts = 0
        override fun start() { events.add("start"); if (failStart) throw IllegalStateException("busy") }
        override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
            onRead(calls)
            if (calls >= chunks.size) return -1
            val n = minOf(chunks[calls], length)
            for (i in 0 until n) buffer[offset + i] = (calls + 1).toShort() // chunk c holds the value c + 1
            calls++
            events.add("read $n")
            return n
        }
        override fun abort() { aborts++ }
        override fun release() { events.add("release") }
    }

    private class FakeEngine(val log: MutableList<String>, val text: String = "hello", val fail: Boolean = false) : SpeechEngine {
        var pcm: FloatArray? = null
        var snapshot: FloatArray? = null // the audio as it was when the engine was called (the session clears its copy afterwards)
        var length = -1
        override fun transcribe(pcm: FloatArray, length: Int): String {
            log.add("transcribe")
            this.pcm = pcm
            snapshot = pcm.copyOf(length)
            this.length = length
            if (fail) throw IllegalStateException("model broken")
            return text
        }
        override fun close() {}
    }

    private class Recorder : VoiceSession.Listener {
        val states = ArrayList<VoiceSession.State>()
        val texts = ArrayList<String>()
        val failures = ArrayList<VoiceSession.Failure>()
        override fun onState(state: VoiceSession.State) { states.add(state) }
        override fun onText(text: String) { texts.add(text) }
        override fun onFailure(failure: VoiceSession.Failure) { failures.add(failure) }
    }

    @Test fun recordsUntilStopAndHandsTheAudioToTheEngine() {
        lateinit var session: VoiceSession
        val capture = FakeCapture(onRead = { call -> if (call == 2) session.stop() }) // stop is asked for during the third read, which still returns
        val engine = FakeEngine(ArrayList())
        val rec = Recorder()
        session = VoiceSession({ capture }, { engine }, inline, inline, rec)
        assertTrue(session.start())
        assertEquals(listOf("start", "read 1600", "read 1600", "read 1600", "release"), capture.events)
        assertEquals(4800, engine.length)
        // chunk values 1, 2, 3 in 1600-sample blocks, scaled by 1/32768
        val heard = engine.snapshot!!
        assertEquals(4800, heard.size)
        assertEquals(1 / 32768f, heard[0]); assertEquals(1 / 32768f, heard[1599]); assertEquals(2 / 32768f, heard[1600]); assertEquals(3 / 32768f, heard[4799])
        assertEquals(listOf("hello"), rec.texts)
        assertEquals(listOf(VoiceSession.State.RECORDING, VoiceSession.State.TRANSCRIBING, VoiceSession.State.IDLE), rec.states)
        assertEquals(VoiceSession.State.IDLE, session.current)
    }

    @Test fun theMicrophoneIsReleasedBeforeTheEngineIsCalled() {
        val log = ArrayList<String>()
        lateinit var session: VoiceSession
        val capture = object : AudioCapture {
            var n = 0
            override fun start() { log.add("start") }
            override fun read(buffer: ShortArray, offset: Int, length: Int): Int { if (n++ == 1) session.stop(); log.add("read"); buffer[offset] = 1; return 800 }
            override fun abort() {}
            override fun release() { log.add("release") }
        }
        session = VoiceSession({ capture }, { FakeEngine(log) }, inline, inline, Recorder())
        session.start()
        assertEquals(listOf("start", "read", "read", "release", "transcribe"), log)
    }

    @Test fun theAudioIsZeroedAfterTheTextIsKnown() {
        lateinit var session: VoiceSession
        val engine = FakeEngine(ArrayList())
        val capture = FakeCapture(onRead = { if (it == 2) session.stop() })
        session = VoiceSession({ capture }, { engine }, inline, inline, Recorder())
        session.start()
        assertEquals(4800, engine.length)
        assertTrue(engine.snapshot!!.any { it != 0f }, "positive control: the engine was given real audio")
        assertTrue(engine.pcm!!.all { it == 0f }, "the float copy given to the engine is cleared after use")
    }

    @Test fun theRecordingStopsByItselfAtTheMaximumLength() {
        val capture = FakeCapture(chunks = List(100) { 1600 }) // 10 s offered
        val engine = FakeEngine(ArrayList())
        val rec = Recorder()
        val session = VoiceSession({ capture }, { engine }, inline, inline, rec, maxSeconds = 2) // 32000 samples
        session.start()
        assertEquals(32000, engine.length)
        assertEquals(20, capture.calls)
        assertEquals(listOf("hello"), rec.texts)
    }

    @Test fun theLastChunkIsCutToTheBuffer() {
        val capture = FakeCapture(chunks = List(20) { 1500 }) // 10 chunks make 15000 samples; the 11th is asked for 1000 only
        val engine = FakeEngine(ArrayList())
        VoiceSession({ capture }, { engine }, inline, inline, Recorder(), maxSeconds = 1).also { it.start() }
        assertEquals(16000, engine.length) // exactly one second, never more than the buffer holds
        assertEquals(11, capture.calls)
    }

    @Test fun cancelDiscardsTheAudioAndDeliversNoText() {
        lateinit var session: VoiceSession
        val log = ArrayList<String>()
        val capture = FakeCapture(onRead = { if (it == 1) session.cancel() })
        val rec = Recorder()
        session = VoiceSession({ capture }, { FakeEngine(log) }, inline, inline, rec)
        session.start()
        assertEquals(emptyList(), log) // the engine never ran
        assertEquals(emptyList(), rec.texts)
        assertTrue(capture.events.last() == "release")
        assertEquals(listOf(VoiceSession.State.RECORDING, VoiceSession.State.IDLE), rec.states)
        assertEquals(VoiceSession.State.IDLE, session.current)
    }

    @Test fun aCancelDuringTheTranscriptionDropsTheText() {
        lateinit var session: VoiceSession
        val capture = FakeCapture(onRead = { if (it == 1) session.stop() })
        val rec = Recorder()
        val engine = object : SpeechEngine {
            override fun transcribe(pcm: FloatArray, length: Int): String { session.cancel(); return "late text" }
            override fun close() {}
        }
        session = VoiceSession({ capture }, { engine }, inline, inline, rec)
        session.start()
        assertEquals(emptyList(), rec.texts)
        assertEquals(VoiceSession.State.IDLE, session.current)
        assertEquals(0, capture.aborts) // the device was already released: nothing to stop
        // positive control: without the cancel the same engine returns text
        val rec2 = Recorder()
        VoiceSession({ FakeCapture(chunks = List(2) { 1600 }) }, { object : SpeechEngine { override fun transcribe(pcm: FloatArray, length: Int) = "late text"; override fun close() {} } }, inline, inline, rec2).also {
            it.start(); it.stop()
        }
        assertEquals(listOf("late text"), rec2.texts)
    }

    @Test fun microphoneThatCannotBeOpenedIsReportedAndReleased() {
        val capture = FakeCapture(failStart = true)
        val rec = Recorder()
        val session = VoiceSession({ capture }, { FakeEngine(ArrayList()) }, inline, inline, rec)
        assertFalse(session.start())
        assertEquals(listOf("start", "release"), capture.events)
        assertEquals(listOf(VoiceSession.Failure.MICROPHONE), rec.failures)
        assertEquals(emptyList(), rec.states)
        assertEquals(VoiceSession.State.IDLE, session.current)
    }

    @Test fun aReadThatThrowsReleasesTheMicrophoneReportsAndEndsIdle() {
        val capture = object : AudioCapture {
            val events = ArrayList<String>()
            override fun start() {}
            override fun read(buffer: ShortArray, offset: Int, length: Int): Int = throw IllegalStateException("device lost")
            override fun abort() {}
            override fun release() { events.add("release") }
        }
        val rec = Recorder()
        val session = VoiceSession({ capture }, { FakeEngine(ArrayList()) }, inline, inline, rec)
        session.start()
        assertEquals(listOf("release"), capture.events)
        assertEquals(listOf(VoiceSession.Failure.MICROPHONE), rec.failures)
        assertEquals(VoiceSession.State.IDLE, session.current)
        assertEquals(VoiceSession.State.IDLE, rec.states.last())
    }

    @Test fun anEngineThatFailsIsReportedAndTheSessionEndsIdleAndCanStartAgain() {
        var calls = 0
        val rec = Recorder()
        val session = VoiceSession({ FakeCapture(chunks = List(2) { 1600 }) }, { FakeEngine(ArrayList(), fail = calls++ == 0) }, inline, inline, rec)
        session.start(); session.stop()
        assertEquals(listOf(VoiceSession.Failure.ENGINE), rec.failures)
        assertEquals(emptyList(), rec.texts)
        assertEquals(VoiceSession.State.IDLE, session.current)
        assertTrue(session.start()) // a second utterance works (the engine is fine now)
        assertEquals(listOf("hello"), rec.texts)
    }

    @Test fun emptyTextIsNotDelivered() {
        val rec = Recorder()
        VoiceSession({ FakeCapture(chunks = List(2) { 1600 }) }, { FakeEngine(ArrayList(), text = "") }, inline, inline, rec).also { it.start() }
        assertEquals(emptyList(), rec.texts)
        assertEquals(VoiceSession.State.IDLE, rec.states.last())
    }

    @Test fun aSecondStartWhileRecordingOrTranscribingIsRefused() {
        lateinit var session: VoiceSession
        var refusedWhileRecording = true
        var refusedWhileTranscribing = true
        val engine = object : SpeechEngine {
            override fun transcribe(pcm: FloatArray, length: Int): String { refusedWhileTranscribing = !session.start(); return "x" }
            override fun close() {}
        }
        val capture = FakeCapture(onRead = { if (it == 0) refusedWhileRecording = !session.start(); if (it == 1) session.stop() })
        session = VoiceSession({ capture }, { engine }, inline, inline, Recorder())
        assertTrue(session.start())
        assertTrue(refusedWhileRecording)
        assertTrue(refusedWhileTranscribing)
        assertEquals(1, capture.events.count { it == "start" })
    }

    @Test fun stopAndCancelWithoutARecordingDoNothing() {
        val rec = Recorder()
        val session = VoiceSession({ FakeCapture() }, { FakeEngine(ArrayList()) }, inline, inline, rec)
        session.stop(); session.cancel()
        assertEquals(emptyList(), rec.states)
        assertTrue(session.start()) // and a later start is not affected by the earlier cancel (positive control: it records)
        assertContentEquals(listOf(VoiceSession.State.RECORDING), rec.states.take(1))
    }

    // ---- a cancel must stop the microphone at once, even when read() is blocked ----

    /** A capture whose read() blocks like AudioRecord.read until the device is stopped from another thread (abort) or, wrongly, a timeout. */
    private class BlockingCapture : AudioCapture {
        val readEntered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val order: MutableList<String> = Collections.synchronizedList(ArrayList())
        private val stopped = CountDownLatch(1)
        override fun start() {}
        override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
            readEntered.countDown()
            stopped.await(3, TimeUnit.SECONDS) // a real device never returns while it records and nobody speaks
            return -1
        }
        override fun abort() { order.add("abort"); stopped.countDown() }
        override fun release() { order.add("release"); released.countDown() }
    }

    private fun threadSession(capture: AudioCapture, rec: Recorder, engineCalls: AtomicInteger = AtomicInteger()): VoiceSession {
        val worker = Executor { Thread(it, "test-voice").apply { isDaemon = true }.start() }
        val engine = object : SpeechEngine {
            override fun transcribe(pcm: FloatArray, length: Int): String { engineCalls.incrementAndGet(); return "never" }
            override fun close() {}
        }
        return VoiceSession({ capture }, { engine }, worker, inline, rec)
    }

    @Test fun cancelStopsTheDeviceWhileTheReadIsBlockedAndTheMicrophoneIsReleasedAtOnce() {
        val capture = BlockingCapture()
        val rec = Recorder()
        val engineCalls = AtomicInteger()
        val session = threadSession(capture, rec, engineCalls)
        assertTrue(session.start())
        assertTrue(capture.readEntered.await(5, TimeUnit.SECONDS), "positive control: the worker is inside the blocked read")
        session.cancel()
        assertTrue(capture.released.await(1, TimeUnit.SECONDS), "cancel must end the blocked read and release the microphone without waiting for samples")
        assertEquals(listOf("abort", "release"), capture.order)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (rec.states.lastOrNull() != VoiceSession.State.IDLE && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(VoiceSession.State.IDLE, rec.states.last())
        assertEquals(0, engineCalls.get())
        assertEquals(emptyList(), rec.texts)
    }

    @Test fun stopWithoutCancelDoesNotAbortTheDevice() {
        lateinit var session: VoiceSession
        val capture = FakeCapture(onRead = { if (it == 1) session.stop() })
        val rec = Recorder()
        session = VoiceSession({ capture }, { FakeEngine(ArrayList()) }, inline, inline, rec)
        session.start()
        assertEquals(0, capture.aborts)
        assertEquals(listOf("hello"), rec.texts)
    }
}
