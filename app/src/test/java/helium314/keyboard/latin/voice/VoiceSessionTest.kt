// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.voice.engine.SpeechEngine
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The recording session with a fake microphone and a fake engine. Most tests run every executor inline, so every order of calls is
 * deterministic: a phrase is then transcribed before the next read.
 */
class VoiceSessionTest {
    private val inline = Executor { it.run() }

    private companion object {
        const val LOUD = 3000 // RMS of a speech block (about -21 dBFS)
        const val ROOM = 50 // the room between phrases: speech is 4 times the quietest block
        const val BLOCK = VoiceSession.CHUNK // 100 ms
    }

    /**
     * A microphone: chunk c (0-based) holds the value [value] (c), [chunks] gives the sizes. [buffer] is the session's own array (the fake keeps
     * the reference the session reads into, so a test can check that it is cleared).
     */
    private class FakeCapture(
        private val onRead: (call: Int) -> Unit = {},
        private val chunks: List<Int> = List(10) { BLOCK },
        val failStart: Boolean = false,
        private val value: (Int) -> Int = { (it % 10 + 1) * 1000 }, // 1000 to 10000: audible, never 0.6 s without speech
    ) : AudioCapture {
        val events: MutableList<String> = Collections.synchronizedList(ArrayList())
        var calls = 0
        var aborts = 0
        @Volatile var released = false
        var buffer: ShortArray? = null
        override fun start() { events.add("start"); if (failStart) throw IllegalStateException("busy") }
        override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
            this.buffer = buffer
            onRead(calls)
            if (calls >= chunks.size) return -1
            val n = minOf(chunks[calls], length)
            val v = value(calls).toShort()
            for (i in 0 until n) buffer[offset + i] = v
            calls++
            events.add("read $n")
            return n
        }
        override fun abort() { aborts++ }
        override fun release() { released = true; events.add("release") }
    }

    /** Levels per 100 ms block: speech, pause, speech. */
    private fun levels(vararg runs: Pair<Int, Int>): (Int) -> Int {
        val list = runs.flatMap { (count, level) -> List(count) { level } }
        return { list.getOrElse(it) { 0 } }
    }

    private class FakeEngine(val log: MutableList<String>, val texts: List<String> = listOf("hello"), val fail: Boolean = false) : SpeechEngine {
        var pcm: FloatArray? = null
        var snapshot: FloatArray? = null // the audio as it was when the engine was called (the session clears its copy afterwards)
        var length = -1
        val lengths = ArrayList<Int>()
        override fun transcribe(pcm: FloatArray, length: Int): String {
            log.add("transcribe $length")
            this.pcm = pcm
            snapshot = pcm.copyOf(length)
            this.length = length
            lengths.add(length)
            if (fail) throw IllegalStateException("model broken")
            return texts[minOf(lengths.size - 1, texts.size - 1)]
        }
        override fun close() {}
    }

    private class Recorder : VoiceSession.Listener {
        val states: MutableList<VoiceSession.State> = Collections.synchronizedList(ArrayList())
        val texts: MutableList<String> = Collections.synchronizedList(ArrayList())
        val failures: MutableList<VoiceSession.Failure> = Collections.synchronizedList(ArrayList())
        override fun onState(state: VoiceSession.State) { states.add(state) }
        override fun onText(text: String) { texts.add(text) }
        override fun onFailure(failure: VoiceSession.Failure) { failures.add(failure) }
    }

    private fun session(capture: AudioCapture, engine: SpeechEngine, rec: Recorder, maxSeconds: Int = VoiceSession.MAX_SECONDS, maxPhrase: Int = PauseDetector.MAX_PHRASE_SECONDS) =
        VoiceSession({ capture }, { engine }, inline, inline, inline, rec, maxSeconds) { PauseDetector(maxPhrase * VoiceSession.RATE) }

    // ---- phrase by phrase ----

    @Test fun aPauseEndsAPhraseThatIsTypedWhileTheMicrophoneStaysOpen() {
        val log = Collections.synchronizedList(ArrayList<String>())
        lateinit var s: VoiceSession
        // 0.2 s of the room, 1 s of speech, 0.9 s pause, 1 s of speech; the stop comes during the 32nd read
        val capture = object : AudioCapture {
            val level = levels(2 to ROOM, 10 to LOUD, 9 to ROOM, 10 to LOUD)
            var n = 0
            override fun start() { log.add("start") }
            override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
                if (n == 31) s.stop()
                buffer.fill(level(n++).toShort(), offset, offset + BLOCK)
                log.add("read")
                return BLOCK
            }
            override fun abort() {}
            override fun release() { log.add("release") }
        }
        val rec = Recorder()
        val engine = FakeEngine(log, listOf("first phrase", "second phrase"))
        s = session(capture, engine, rec)
        assertTrue(s.start())
        // the first phrase ends after 8 blocks without speech (0.8 s): 20 blocks; it is transcribed and typed before the next read, the microphone open
        val expected = listOf("start") + List(20) { "read" } + "transcribe 32000" + List(12) { "read" } + "release" + "transcribe 19200"
        assertEquals(expected, log)
        assertEquals(listOf("first phrase", "second phrase"), rec.texts)
        assertEquals(listOf(VoiceSession.State.RECORDING, VoiceSession.State.TRANSCRIBING, VoiceSession.State.IDLE), rec.states)
    }

    @Test fun recordsUntilStopAndHandsTheAudioToTheEngine() {
        lateinit var session: VoiceSession
        val capture = FakeCapture(onRead = { call -> if (call == 2) session.stop() }) // stop is asked for during the third read, which still returns
        val engine = FakeEngine(ArrayList())
        val rec = Recorder()
        session = session(capture, engine, rec)
        assertTrue(session.start())
        assertEquals(listOf("start", "read 1600", "read 1600", "read 1600", "release"), capture.events)
        assertEquals(4800, engine.length)
        // chunk values 1000, 2000, 3000 in 1600-sample blocks, scaled by 1/32768
        val heard = engine.snapshot!!
        assertEquals(4800, heard.size)
        assertEquals(1000 / 32768f, heard[0]); assertEquals(1000 / 32768f, heard[1599]); assertEquals(2000 / 32768f, heard[1600]); assertEquals(3000 / 32768f, heard[4799])
        assertEquals(listOf("hello"), rec.texts)
        assertEquals(listOf(VoiceSession.State.RECORDING, VoiceSession.State.TRANSCRIBING, VoiceSession.State.IDLE), rec.states)
        assertEquals(VoiceSession.State.IDLE, session.current)
    }

    @Test fun atTheStopTheMicrophoneIsReleasedBeforeTheLastPhraseIsTranscribed() {
        val log = ArrayList<String>()
        lateinit var session: VoiceSession
        val capture = object : AudioCapture {
            var n = 0
            override fun start() { log.add("start") }
            override fun read(buffer: ShortArray, offset: Int, length: Int): Int { if (n++ == 1) session.stop(); log.add("read"); buffer.fill(LOUD.toShort(), offset, offset + 800); return 800 }
            override fun abort() {}
            override fun release() { log.add("release") }
        }
        session = session(capture, FakeEngine(log), Recorder())
        session.start()
        assertEquals(listOf("start", "read", "read", "release", "transcribe 1600"), log)
    }

    @Test fun aPhraseIsCutAtTheMaximumPhraseLengthAndTheSessionAtItsMaximum() {
        val capture = FakeCapture(chunks = List(100) { BLOCK }) // 10 s of speech without a pause
        val engine = FakeEngine(ArrayList(), listOf("a", "b", "c"))
        val rec = Recorder()
        session(capture, engine, rec, maxSeconds = 5, maxPhrase = 2).start()
        assertEquals(listOf(32000, 32000, 16000), engine.lengths) // 2 s, 2 s, and the last second at the 5 s limit of the session
        assertEquals(50, capture.calls) // the session ended at 5 s by itself
        assertEquals("release", capture.events.last())
        assertEquals(listOf("a", "b", "c"), rec.texts)
    }

    @Test fun theDefaultLimitsAre15SecondsPerPhraseAnd60PerSession() {
        assertEquals(15, PauseDetector.MAX_PHRASE_SECONDS)
        assertEquals(60, VoiceSession.MAX_SECONDS)
        val capture = FakeCapture(chunks = List(700) { BLOCK }) // 70 s offered
        val engine = FakeEngine(ArrayList())
        session(capture, engine, Recorder()).start()
        assertEquals(listOf(240000, 240000, 240000, 240000), engine.lengths) // four phrases of 15 s
        assertEquals(600, capture.calls)
    }

    @Test fun theLastChunkIsCutToTheBuffer() {
        val capture = FakeCapture(chunks = List(20) { 1500 }) // 10 chunks make 15000 samples; the 11th is asked for 1000 only
        val engine = FakeEngine(ArrayList())
        session(capture, engine, Recorder(), maxSeconds = 1).also { it.start() }
        assertEquals(16000, engine.length) // exactly one second, never more than the buffer holds
        assertEquals(11, capture.calls)
    }

    @Test fun nothingIsDroppedTheRoomBeforeAPhraseIsTranscribedWithIt() {
        lateinit var session: VoiceSession
        // 1 s of the room, 1 s of speech, a pause, 2 s of the room, 1 s of speech; stop after it
        val capture = FakeCapture(onRead = { if (it == 56) session.stop() }, chunks = List(60) { BLOCK },
            value = levels(10 to ROOM, 10 to LOUD, 8 to ROOM, 18 to ROOM, 10 to LOUD))
        val engine = FakeEngine(ArrayList())
        val rec = Recorder()
        session = session(capture, engine, rec)
        session.start()
        // every block reaches the engine: 28 blocks up to the pause, then blocks 28 to 56 (the stop comes before the 57th read, which still returns)
        assertEquals(listOf(28 * BLOCK, 29 * BLOCK), engine.lengths)
        assertEquals(ROOM / 32768f, engine.snapshot!![0])
        assertEquals(LOUD / 32768f, engine.snapshot!![18 * BLOCK])
        assertEquals(listOf("hello", "hello"), rec.texts)
        // nothing told apart as speech in the whole recording: everything is transcribed at the stop (a voice the levels miss is not lost)
        val unproven = FakeEngine(ArrayList())
        lateinit var s2: VoiceSession
        s2 = session(FakeCapture(onRead = { if (it == 15) s2.stop() }, chunks = List(20) { BLOCK }, value = { ROOM }), unproven, Recorder())
        s2.start()
        assertEquals(listOf(16 * BLOCK), unproven.lengths)
    }

    @Test fun theAudioOfAPhraseIsClearedOnceTheEngineHasItsCopyAndTheWholeArrayAtTheEnd() {
        lateinit var session: VoiceSession
        var clearedBeforeTheEngine = false
        lateinit var capture: FakeCapture
        val engine = object : SpeechEngine {
            var first = true
            override fun transcribe(pcm: FloatArray, length: Int): String {
                val b = capture.buffer!!
                if (first) clearedBeforeTheEngine = (0 until length).all { b[it] == 0.toShort() } && pcm[0] != 0f
                first = false
                return "x"
            }
            override fun close() {}
        }
        capture = FakeCapture(onRead = { if (it == 32) session.stop() }, chunks = List(40) { BLOCK }, value = levels(2 to ROOM, 10 to LOUD, 9 to ROOM, 10 to LOUD))
        val rec = Recorder()
        session = VoiceSession({ capture }, { engine }, inline, inline, inline, rec)
        session.start()
        assertTrue(clearedBeforeTheEngine, "the phrase was cleared from the session's array before the engine ran, and the engine got real audio")
        assertTrue(capture.buffer!!.all { it == 0.toShort() }, "the whole array is cleared at the end")
        assertEquals(listOf("x", "x"), rec.texts) // positive control: both phrases reached the engine
    }

    @Test fun theFloatCopyGivenToTheEngineIsClearedAfterUse() {
        lateinit var session: VoiceSession
        val engine = FakeEngine(ArrayList())
        val capture = FakeCapture(onRead = { if (it == 2) session.stop() })
        session = session(capture, engine, Recorder())
        session.start()
        assertEquals(4800, engine.length)
        assertTrue(engine.snapshot!!.any { it != 0f }, "positive control: the engine was given real audio")
        assertTrue(engine.pcm!!.all { it == 0f }, "the float copy given to the engine is cleared after use")
    }

    @Test fun cancelDiscardsTheAudioAndDeliversNoText() {
        lateinit var session: VoiceSession
        val log = ArrayList<String>()
        val capture = FakeCapture(onRead = { if (it == 1) session.cancel() })
        val rec = Recorder()
        session = session(capture, FakeEngine(log), rec)
        session.start()
        assertEquals(emptyList(), log) // the engine never ran
        assertEquals(emptyList(), rec.texts)
        assertEquals("release", capture.events.last())
        assertTrue(capture.buffer!!.all { it == 0.toShort() })
        assertEquals(listOf(VoiceSession.State.RECORDING, VoiceSession.State.IDLE), rec.states)
        assertEquals(VoiceSession.State.IDLE, session.current)
    }

    @Test fun aCancelDropsThePhrasesThatAreNotTranscribedYet() {
        // the transcriber holds its tasks until the test runs them: two phrases are cut, then the field changes
        val queued = ArrayList<Runnable>()
        val later = Executor { queued.add(it) }
        fun run(cancel: Boolean): Pair<List<String>, FakeEngine> {
            queued.clear()
            lateinit var s: VoiceSession
            val capture = FakeCapture(onRead = { if (it == 46) { if (cancel) s.cancel() else s.stop() } }, chunks = List(50) { BLOCK },
                value = levels(2 to ROOM, 10 to LOUD, 9 to ROOM, 10 to LOUD, 9 to ROOM, 10 to LOUD))
            val engine = FakeEngine(ArrayList(), listOf("one", "two", "three"))
            val rec = Recorder()
            s = VoiceSession({ capture }, { engine }, inline, later, inline, rec)
            s.start()
            assertTrue(capture.released)
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            assertTrue(capture.buffer!!.all { it == 0.toShort() }, "cleared at the end, cancel = $cancel")
            assertEquals(VoiceSession.State.IDLE, s.current)
            return rec.texts to engine
        }
        val (kept, keptEngine) = run(cancel = false) // positive control: the same phrases are typed without the cancel
        assertEquals(listOf("one", "two", "three"), kept)
        assertEquals(3, keptEngine.lengths.size)
        val (dropped, engine) = run(cancel = true)
        assertEquals(emptyList(), dropped)
        assertEquals(emptyList(), engine.lengths) // queued phrases are not even transcribed
    }

    @Test fun aCancelDuringTheTranscriptionDropsTheText() {
        lateinit var session: VoiceSession
        val capture = FakeCapture(onRead = { if (it == 1) session.stop() })
        val rec = Recorder()
        val engine = object : SpeechEngine {
            override fun transcribe(pcm: FloatArray, length: Int): String { session.cancel(); return "late text" }
            override fun close() {}
        }
        session = session(capture, engine, rec)
        session.start()
        assertEquals(emptyList(), rec.texts)
        assertEquals(VoiceSession.State.IDLE, session.current)
        assertEquals(0, capture.aborts) // the device was already released: nothing to stop
        // positive control: without the cancel the same engine returns text
        val rec2 = Recorder()
        session(FakeCapture(chunks = List(2) { BLOCK }), object : SpeechEngine { override fun transcribe(pcm: FloatArray, length: Int) = "late text"; override fun close() {} }, rec2).also {
            it.start(); it.stop()
        }
        assertEquals(listOf("late text"), rec2.texts)
    }

    @Test fun microphoneThatCannotBeOpenedIsReportedAndReleased() {
        val capture = FakeCapture(failStart = true)
        val rec = Recorder()
        val session = session(capture, FakeEngine(ArrayList()), rec)
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
        val session = session(capture, FakeEngine(ArrayList()), rec)
        session.start()
        assertEquals(listOf("release"), capture.events)
        assertEquals(listOf(VoiceSession.Failure.MICROPHONE), rec.failures)
        assertEquals(VoiceSession.State.IDLE, session.current)
        assertEquals(VoiceSession.State.IDLE, rec.states.last())
    }

    @Test fun anEngineThatFailsClosesTheMicrophoneIsReportedOnceAndTheSessionCanStartAgain() {
        var calls = 0
        val rec = Recorder()
        val captures = ArrayList<FakeCapture>()
        val session = VoiceSession({ FakeCapture(chunks = List(47) { BLOCK }, value = levels(2 to ROOM, 10 to LOUD, 9 to ROOM, 10 to LOUD, 9 to ROOM, 6 to LOUD)).also { captures.add(it) } },
            { FakeEngine(ArrayList(), fail = calls++ == 0) }, inline, inline, inline, rec)
        session.start()
        assertEquals(listOf(VoiceSession.Failure.ENGINE), rec.failures)
        assertEquals(20, captures[0].calls) // the microphone was closed after the first phrase (20 blocks) failed, not read to the end
        assertTrue(captures[0].released)
        assertEquals(1, calls) // and no later phrase was given to the engine
        assertEquals(emptyList(), rec.texts)
        assertEquals(VoiceSession.State.IDLE, session.current)
        assertTrue(session.start()) // a second recording works (the engine is fine now)
        assertEquals(listOf("hello", "hello", "hello"), rec.texts)
    }

    @Test fun emptyTextIsNotDelivered() {
        val rec = Recorder()
        session(FakeCapture(chunks = List(2) { BLOCK }), FakeEngine(ArrayList(), listOf("")), rec).also { it.start() }
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
        session = session(capture, engine, Recorder())
        assertTrue(session.start())
        assertTrue(refusedWhileRecording)
        assertTrue(refusedWhileTranscribing)
        assertEquals(1, capture.events.count { it == "start" })
    }

    @Test fun stopAndCancelWithoutARecordingDoNothing() {
        val rec = Recorder()
        val session = session(FakeCapture(), FakeEngine(ArrayList()), rec)
        session.stop(); session.cancel()
        assertEquals(emptyList(), rec.states)
        assertTrue(session.start()) // and a later start is not affected by the earlier cancel (positive control: it records)
        assertContentEquals(listOf(VoiceSession.State.RECORDING), rec.states.take(1))
    }

    @Test fun theEngineIsLoadedOncePerSession() {
        val loads = AtomicInteger()
        val holder = VoiceEngineHolder { loads.incrementAndGet(); FakeEngine(ArrayList(), listOf("one", "two", "three")) }
        lateinit var s: VoiceSession
        val capture = FakeCapture(onRead = { if (it == 46) s.stop() }, chunks = List(50) { BLOCK }, value = levels(2 to ROOM, 10 to LOUD, 9 to ROOM, 10 to LOUD, 9 to ROOM, 10 to LOUD))
        val rec = Recorder()
        s = VoiceSession({ capture }, { holder }, inline, inline, inline, rec)
        holder.prepare() // at the tap (VoiceController.begin -> prepareEngine)
        s.start()
        assertEquals(listOf("one", "two", "three"), rec.texts)
        assertEquals(1, loads.get())
    }

    // ---- real threads: the reader goes on while a phrase is transcribed, one phrase at a time ----

    @Test fun phrasesAreTranscribedOneAtATimeInOrderWhileTheMicrophoneIsRead() {
        val firstStarted = CountDownLatch(1)
        val letFirstEnd = CountDownLatch(1)
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val calls = AtomicInteger()
        var micOpenDuringTheFirst = false
        var readsDuringTheFirst = 0
        val level = levels(2 to ROOM, 10 to LOUD, 9 to ROOM, 10 to LOUD, 9 to ROOM, 10 to LOUD)
        val capture = object : AudioCapture {
            @Volatile var released = false
            val reads = AtomicInteger()
            override fun start() {}
            override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
                val n = reads.getAndIncrement()
                if (n == 21) firstStarted.await(5, TimeUnit.SECONDS) // the second phrase is read while the first is transcribed
                if (n == 32) letFirstEnd.countDown()
                if (n >= 47) return -1
                buffer.fill(level(n).toShort(), offset, offset + BLOCK)
                return BLOCK
            }
            override fun abort() {}
            override fun release() { released = true }
        }
        val engine = object : SpeechEngine {
            override fun transcribe(pcm: FloatArray, length: Int): String {
                val now = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                val i = calls.incrementAndGet()
                if (i == 1) {
                    micOpenDuringTheFirst = !capture.released
                    val before = capture.reads.get()
                    firstStarted.countDown()
                    letFirstEnd.await(5, TimeUnit.SECONDS)
                    readsDuringTheFirst = capture.reads.get() - before
                }
                inFlight.decrementAndGet()
                return "phrase $i"
            }
            override fun close() {}
        }
        val reader = Executors.newSingleThreadExecutor()
        val transcriber = Executors.newSingleThreadExecutor()
        val rec = Recorder()
        val done = CountDownLatch(1)
        val main = Executor { it.run(); if (rec.states.lastOrNull() == VoiceSession.State.IDLE) done.countDown() }
        try {
            val s = VoiceSession({ capture }, { engine }, reader, transcriber, main, rec)
            assertTrue(s.start())
            assertTrue(done.await(10, TimeUnit.SECONDS), "the session ended")
            assertTrue(micOpenDuringTheFirst, "the microphone was open while the first phrase was transcribed")
            assertTrue(readsDuringTheFirst >= 10, "the reader went on while the first phrase was transcribed: $readsDuringTheFirst reads")
            assertEquals(1, maxInFlight.get(), "one phrase in flight")
            assertEquals(listOf("phrase 1", "phrase 2", "phrase 3"), rec.texts)
        } finally {
            reader.shutdownNow()
            transcriber.shutdownNow()
        }
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
        return VoiceSession({ capture }, { engine }, worker, inline, inline, rec)
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
        session = session(capture, FakeEngine(ArrayList()), rec)
        session.start()
        assertEquals(0, capture.aborts)
        assertEquals(listOf("hello"), rec.texts)
    }
}
