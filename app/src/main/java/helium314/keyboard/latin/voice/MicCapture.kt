// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord

/**
 * The microphone (P20): the only class that touches AudioRecord (privacyCheck). 16 kHz mono PCM 16-bit, read into memory by the caller.
 * The device is opened in [start] and released in [release]; the caller (VoiceSession) releases it before it transcribes, on a cancel and
 * after an error. The recording permission is checked by the caller (VoicePermission) before a capture is made.
 */
class MicCapture : AudioCapture {
    @Volatile private var record: AudioRecord? = null
    private val lock = Any() // abort (cancelling thread) and release (reading thread) never run together

    @SuppressLint("MissingPermission") // VoiceGate only lets a start through when the permission is granted
    override fun start() {
        val minBytes = AudioRecord.getMinBufferSize(VoiceSession.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { "no usable audio buffer size" }
        val device = AudioRecord(SOURCE_VOICE_RECOGNITION, VoiceSession.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBytes * 4)
        record = device
        check(device.state == AudioRecord.STATE_INITIALIZED) { "microphone not initialised" }
        device.startRecording()
        check(device.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "microphone not recording" }
    }

    override fun read(buffer: ShortArray, offset: Int, length: Int): Int = record?.read(buffer, offset, length) ?: -1

    /** AudioRecord.stop from the cancelling thread ends the recording (the microphone indicator goes off) and makes a blocked read return. */
    override fun abort() {
        synchronized(lock) { record?.let { runCatching { it.stop() } } }
    }

    override fun release() {
        synchronized(lock) {
            val device = record ?: return
            record = null
            runCatching { device.stop() }
            device.release()
        }
    }

    private companion object {
        /** The VOICE_RECOGNITION audio source (tuned for speech recognition). The platform's file recorder class is forbidden by privacyCheck. */
        const val SOURCE_VOICE_RECOGNITION = 6
    }
}
