// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/**
 * The voice key logic without Android types: a tap starts or ends a recording, a hold records while the key is down. Which key press may
 * start anything is decided by [VoiceGate]; the text goes to [Host.commit] only when the field is still the one the recording started in
 * and still allows voice. Wired to the keyboard by helium314.keyboard.latin.VoiceTyping (tests: VoiceControllerTest, VoiceWiringTest).
 */
class VoiceController(private val host: Host, newSession: (VoiceSession.Listener) -> VoiceSession) : VoiceSession.Listener {
    interface Host {
        fun voiceEnabled(): Boolean
        fun passwordField(): Boolean
        fun locked(): Boolean
        fun modelReady(): Boolean
        fun permissionGranted(): Boolean
        fun requestPermission()
        fun explainModelMissing()
        /** Changes whenever the text field or the keyboard window changes (a recording must not end up in another field). */
        fun fieldGeneration(): Int
        /** Starts loading the speech engine in the background while the user speaks. */
        fun prepareEngine()
        fun commit(text: String)
        fun showState(state: VoiceSession.State)
        fun showFailure(failure: VoiceSession.Failure)
    }

    private val session = newSession(this)
    private var startedInGeneration = 0
    private var held = false

    val state: VoiceSession.State get() = session.current

    private fun action() = VoiceGate.decide(host.voiceEnabled(), host.passwordField(), host.locked(), host.modelReady(), host.permissionGranted())

    /** A tap on the voice key. */
    fun onKey() {
        when (session.current) {
            VoiceSession.State.RECORDING -> session.stop()
            VoiceSession.State.TRANSCRIBING -> Unit
            VoiceSession.State.IDLE -> begin()
        }
    }

    /** The voice key is held down long enough to count as a hold: records until [onHoldEnd]. */
    fun onHoldStart() {
        if (session.current != VoiceSession.State.IDLE) return
        held = true // before the recording starts, so a release that arrives at once is seen
        if (!begin()) held = false
    }

    fun onHoldEnd() {
        if (held) {
            held = false
            session.stop()
        }
    }

    /** The field changed, the window closed, the screen locked or the app is going away: nothing is kept, the microphone is released. */
    fun cancel() {
        held = false
        session.cancel()
    }

    private fun begin(): Boolean {
        when (action()) {
            VoiceGate.Action.IGNORE -> return false
            VoiceGate.Action.NEED_MODEL -> { host.explainModelMissing(); return false }
            VoiceGate.Action.NEED_PERMISSION -> { host.requestPermission(); return false }
            VoiceGate.Action.START -> Unit
        }
        startedInGeneration = host.fieldGeneration()
        host.prepareEngine()
        return session.start()
    }

    override fun onState(state: VoiceSession.State) = host.showState(state)

    override fun onFailure(failure: VoiceSession.Failure) = host.showFailure(failure)

    override fun onText(text: String) {
        // the same field, still allowed: the policy is checked again at the moment of the commit
        if (host.fieldGeneration() != startedInGeneration || host.passwordField() || host.locked() || !host.voiceEnabled()) return
        host.commit(text)
    }
}
