// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.voice.engine.SpeechEngine
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The voice key: what a tap and a hold do in every combination of setting, field, lock, model and permission (P20). */
class VoiceControllerTest {
    private val inline = Executor { it.run() }

    /** A phone for the controller: the booleans are the current situation, the lists record what the controller did to it. */
    private class Phone : VoiceController.Host {
        var enabled = true
        var password = false
        var locked = false
        var model = true
        var permission = true
        var generation = 0
        val calls = ArrayList<String>()
        val committed = ArrayList<String>()
        val states = ArrayList<VoiceSession.State>()
        var engineText = "hello"
        var micOpen = false
        var blockInRead: (() -> Unit)? = null

        override fun voiceEnabled() = enabled
        override fun passwordField() = password
        override fun locked() = locked
        override fun modelReady() = model
        override fun permissionGranted() = permission
        override fun requestPermission() { calls.add("permission") }
        override fun explainModelMissing() { calls.add("model") }
        override fun fieldGeneration() = generation
        override fun prepareEngine() { calls.add("prepare") }
        override fun commit(text: String) { committed.add(text) }
        override fun showState(state: VoiceSession.State) { states.add(state) }
        override fun showFailure(failure: VoiceSession.Failure) { calls.add("failure $failure") }

        // a microphone that supplies chunks until the test lets it end
        val capture = object : AudioCapture {
            override fun start() { micOpen = true; calls.add("mic start") }
            override fun read(buffer: ShortArray, offset: Int, length: Int): Int { blockInRead?.invoke(); return if (micOpen) { buffer[offset] = 5; 1600 } else -1 }
            override fun abort() {}
            override fun release() { micOpen = false; calls.add("mic release") }
        }
    }

    private fun controller(phone: Phone, readHook: (VoiceController) -> Unit = {}): VoiceController {
        lateinit var c: VoiceController
        phone.blockInRead = { readHook(c) }
        c = VoiceController(phone) { listener ->
            VoiceSession({ phone.capture }, { object : SpeechEngine { override fun transcribe(pcm: FloatArray, length: Int) = phone.engineText; override fun close() {} } }, inline, inline, listener)
        }
        return c
    }

    @Test fun aTapStartsAndTheNextTapStopsAndTheTextIsCommitted() {
        val phone = Phone()
        var taps = 0
        val c = controller(phone) { c -> if (++taps == 3) c.onKey() } // the second tap arrives during the third read
        c.onKey()
        assertEquals(listOf("prepare", "mic start", "mic release"), phone.calls)
        assertEquals(listOf("hello"), phone.committed)
        assertEquals(listOf(VoiceSession.State.RECORDING, VoiceSession.State.TRANSCRIBING, VoiceSession.State.IDLE), phone.states)
        assertEquals(VoiceSession.State.IDLE, c.state)
    }

    @Test fun passwordFieldDisabledSettingAndLockedDeviceNeverOpenTheMicrophoneOrAsk() {
        for (case in listOf<(Phone) -> Unit>({ it.password = true }, { it.enabled = false }, { it.locked = true })) {
            val phone = Phone().also(case)
            // everything else is missing too: even then there is no prompt and no message
            phone.model = false; phone.permission = false
            controller(phone).onKey()
            assertEquals(emptyList(), phone.calls)
        }
        // positive control: the same phone without the blocker asks for the model
        val phone = Phone().also { it.model = false }
        controller(phone).onKey()
        assertEquals(listOf("model"), phone.calls)
    }

    @Test fun aMissingModelIsExplainedBeforeAnyPermissionIsAsked() {
        val phone = Phone().also { it.model = false; it.permission = false }
        controller(phone).onKey()
        assertEquals(listOf("model"), phone.calls)
    }

    @Test fun aMissingPermissionIsAskedForOnlyWhenEverythingElseAllowsRecording() {
        val phone = Phone().also { it.permission = false }
        val c = controller(phone)
        c.onKey()
        assertEquals(listOf("permission"), phone.calls) // no microphone, no engine load, no recording
        assertEquals(VoiceSession.State.IDLE, c.state)
        phone.permission = true
        c.onKey() // the user taps again after granting: only then it records (this phone's microphone runs to the 60 s limit)
        assertEquals(listOf("permission", "prepare", "mic start", "mic release"), phone.calls)
        assertEquals(listOf("hello"), phone.committed)
    }

    @Test fun holdRecordsWhileHeldAndStopsOnRelease() {
        val phone = Phone()
        lateinit var c: VoiceController
        var reads = 0
        c = controller(phone) { c -> if (++reads == 2) c.onHoldEnd() }
        c.onHoldStart()
        assertEquals(listOf("prepare", "mic start", "mic release"), phone.calls)
        assertEquals(listOf("hello"), phone.committed)
        // a release without a hold does nothing
        c.onHoldEnd()
        assertEquals(1, phone.committed.size)
    }

    @Test fun aHoldDuringATapRecordingDoesNotTakeOverAndItsReleaseDoesNotStopIt() {
        val phone = Phone()
        var reads = 0
        val c = controller(phone) { c ->
            reads++
            if (reads == 1) { c.onHoldStart(); c.onHoldEnd() } // ignored: a recording runs already, and the release is not a hold release
            if (reads == 3) c.onKey() // the real stop
        }
        c.onKey()
        assertEquals(3, reads) // the hold release did not end it early
        assertEquals(1, phone.calls.count { it == "mic start" })
    }

    @Test fun theTextIsDroppedWhenTheFieldChangedMeanwhile() {
        val phone = Phone()
        var reads = 0
        val c = controller(phone) { c -> if (++reads == 2) { phone.generation++; c.onKey() } }
        c.onKey()
        assertEquals(emptyList(), phone.committed)
        // positive control: the same flow without a field change commits
        val ok = Phone()
        var reads2 = 0
        controller(ok) { c -> if (++reads2 == 2) c.onKey() }.onKey()
        assertEquals(listOf("hello"), ok.committed)
    }

    @Test fun theTextIsDroppedWhenTheFieldBecameAPasswordFieldOrTheDeviceLocked() {
        for (change in listOf<(Phone) -> Unit>({ it.password = true }, { it.locked = true }, { it.enabled = false })) {
            val phone = Phone()
            var reads = 0
            controller(phone) { c -> if (++reads == 2) { change(phone); c.onKey() } }.onKey()
            assertEquals(emptyList(), phone.committed)
        }
    }

    @Test fun cancelReleasesTheMicrophoneAndCommitsNothing() {
        val phone = Phone()
        var reads = 0
        val c = controller(phone) { c -> if (++reads == 2) c.cancel() }
        c.onKey()
        assertEquals(listOf("prepare", "mic start", "mic release"), phone.calls)
        assertEquals(emptyList(), phone.committed)
        assertEquals(VoiceSession.State.IDLE, c.state)
        assertFalse(phone.micOpen)
    }

    @Test fun aTapWhileTheTextIsBeingWorkedOutDoesNothing() {
        val phone = Phone()
        lateinit var c: VoiceController
        val engine = object : SpeechEngine {
            override fun transcribe(pcm: FloatArray, length: Int): String { c.onKey(); return "once" } // a tap during the transcription
            override fun close() {}
        }
        var reads = 0
        phone.blockInRead = { if (++reads == 2) c.onKey() }
        c = VoiceController(phone) { VoiceSession({ phone.capture }, { engine }, inline, inline, it) }
        c.onKey()
        assertEquals(listOf("once"), phone.committed)
        assertEquals(1, phone.calls.count { it == "mic start" })
    }
}
