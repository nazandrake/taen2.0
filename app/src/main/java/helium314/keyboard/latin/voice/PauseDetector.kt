// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where the recording is cut into phrases: no model, only the level (RMS) of each block of samples the microphone returns (about 100 ms),
 * measured against the quietest block of this recording.
 *
 * A block is speech when its level is at least [SPEECH_FACTOR] times the quietest block of the recording (blocks below [MIN_FLOOR_RMS], the
 * zeros a microphone gives at its start, do not count as the room) and at least [MIN_SPEECH_RMS] (-50 dBFS). A phrase ends:
 * - at a pause: after speech, [PAUSE_SAMPLES] (0.8 s) of blocks that are not speech, when the phrase is at least [MIN_PHRASE_SAMPLES] (2 s) long;
 * - when it reaches [maxPhraseSamples] (15 s by default), wherever that is.
 * Nothing is dropped: every sample belongs to a phrase, so a soft word that the levels miss is still transcribed. Nothing is kept here but a
 * few numbers.
 */
class PauseDetector(
    private val maxPhraseSamples: Int = MAX_PHRASE_SECONDS * VoiceSession.RATE,
    private val pauseSamples: Int = PAUSE_SAMPLES,
    private val minPhraseSamples: Int = MIN_PHRASE_SAMPLES,
) {
    private var floor = Double.MAX_VALUE // the quietest block of the recording at or above MIN_FLOOR_RMS
    private var phrase = 0 // samples since the last cut
    private var pause = 0 // samples since the last speech block of this phrase
    private var speech = false // the phrase has a speech block

    /** Takes the next [length] samples of [pcm] from [offset]; true when the phrase ends with them. */
    fun next(pcm: ShortArray, offset: Int, length: Int): Boolean {
        if (length < 1) return false
        var sum = 0.0
        for (i in offset until offset + length) sum += pcm[i].toDouble() * pcm[i]
        val rms = sqrt(sum / length)
        if (rms >= MIN_FLOOR_RMS && rms < floor) floor = rms
        phrase += length
        if (rms >= MIN_SPEECH_RMS && floor != Double.MAX_VALUE && rms >= SPEECH_FACTOR * max(floor, MIN_FLOOR_RMS)) {
            speech = true
            pause = 0
        } else {
            pause += length
        }
        val cut = phrase >= maxPhraseSamples || (speech && pause >= pauseSamples && phrase >= minPhraseSamples)
        if (cut) {
            phrase = 0
            pause = 0
            speech = false
        }
        return cut
    }

    companion object {
        const val MAX_PHRASE_SECONDS = 15
        // 0.8 s and 2 s: with 0.6 s and 0.8 s the engine got short pieces of sentences and recognised Bulgarian worse (device bench, 8 FLEURS
        // utterances: 20.4 % word errors against 15.7 %) and, from the phone's speaker, took some for English; sentences are cut at their ends
        const val PAUSE_SAMPLES = VoiceSession.RATE * 8 / 10
        const val MIN_PHRASE_SAMPLES = VoiceSession.RATE * 2
        const val SPEECH_FACTOR = 4.0 // 12 dB over the quietest block
        const val MIN_FLOOR_RMS = 4.0 // about -78 dBFS: below it a block is digital silence, not the room
        // about -50 dBFS: quieter speech is not cut at pauses (only at the maximum length), because the engine lost words of very quiet
        // recordings (peaks near -43 dBFS) that were cut into phrases, and recognised them whole (device bench, public FLEURS samples)
        const val MIN_SPEECH_RMS = 100.0
    }
}
