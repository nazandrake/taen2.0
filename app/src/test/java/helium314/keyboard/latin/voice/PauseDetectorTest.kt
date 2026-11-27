// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The pause detector on blocks of constant level (the RMS of a constant block is its value); limits from the class's stated numbers. */
class PauseDetectorTest {
    private val block = VoiceSession.CHUNK // 100 ms
    private val room = 50
    private val loud = 3000

    private fun run(vararg runs: Pair<Int, Int>) = runs.flatMap { (count, level) -> List(count) { level } }

    /** The 1-based block numbers that end a phrase, one block per level. */
    private fun cuts(levels: List<Int>, detector: PauseDetector = PauseDetector()): List<Int> {
        val pcm = ShortArray(block)
        return levels.withIndex().filter { (_, level) -> pcm.fill(level.toShort()); detector.next(pcm, 0, block) }.map { it.index + 1 }
    }

    @Test fun aPauseOfEightBlocksAfterSpeechEndsThePhrase() {
        assertEquals(listOf(21), cuts(run(3 to room, 10 to loud, 8 to room, 10 to loud)))
        assertEquals(emptyList(), cuts(run(3 to room, 10 to loud, 7 to room, 10 to loud))) // 0.7 s is not a pause
    }

    @Test fun aPhraseIsAtLeastTwentyBlocks() {
        val d = PauseDetector()
        assertEquals(listOf(21), cuts(run(1 to room, 12 to loud, 8 to room), d))
        // the next phrase: 1 block of speech and the pause after 9 blocks, but the phrase is complete only at the 20th
        assertEquals(listOf(20), cuts(run(1 to loud, 19 to room), d))
        assertEquals(listOf(20), cuts(run(12 to loud, 8 to room), d)) // pause and length both reached at the 20th
    }

    @Test fun speechIsFourTimesTheQuietestBlock() {
        assertEquals(listOf(23), cuts(run(5 to room, 10 to 200, 8 to room)))
        assertEquals(emptyList(), cuts(run(5 to room, 10 to 199, 8 to room)))
    }

    @Test fun speechMustReachMinus50Dbfs() {
        assertEquals(listOf(23), cuts(run(5 to 5, 10 to 100, 8 to 5)))
        // a recording quieter than that (four times its room, but very quiet) has no pauses: it is cut only at the maximum length
        assertEquals(emptyList(), cuts(run(5 to 5, 10 to 99, 8 to 5)))
    }

    @Test fun digitalSilenceSetsNoFloor() {
        // the zeros a microphone gives at its start are not the room: the floor is the 30 of the room, speech needs 120
        assertEquals(listOf(26), cuts(run(5 to 0, 3 to 30, 10 to 120, 8 to 30)))
        assertEquals(emptyList(), cuts(run(5 to 0, 3 to 30, 10 to 110, 8 to 30))) // 110 would be speech over a floor of zeros
        // only digital silence before the first block of 100: no floor before it, so it is not speech, nor are the next ones of the same level
        assertEquals(emptyList(), cuts(run(5 to 0, 10 to 100, 8 to 0)))
    }

    @Test fun speechRightAtTheStartIsFoundOnlyAfterAQuieterBlock() {
        // the first block sets the floor: speech from the first block on is not speech until the room was heard; the first pause is then not a cut
        assertEquals(listOf(36), cuts(run(10 to loud, 8 to room, 10 to loud, 8 to room)))
    }

    @Test fun nothingIsDroppedTheRoomBetweenPhrasesStartsTheNextOne() {
        // after the first phrase, 2 s of the room and 1 s of speech make the second phrase: 20 + 10 + 8 blocks, nothing cut away
        assertEquals(listOf(21, 59), cuts(run(3 to room, 10 to loud, 8 to room, 20 to room, 10 to loud, 8 to room)))
        // a recording without anything told apart as speech is cut only at the maximum length (and transcribed by the session)
        assertEquals(listOf(20), cuts(run(25 to 200), PauseDetector(maxPhraseSamples = 20 * block)))
    }

    @Test fun thePhraseIsCutAtTheMaximumLength() {
        assertEquals(listOf(20, 40), cuts(run(1 to room, 45 to loud), PauseDetector(maxPhraseSamples = 20 * block)))
        assertEquals(listOf(150, 300), cuts(run(1 to room, 300 to loud))) // 15 s by default
        assertEquals(listOf(149), cuts(run(1 to room, 140 to loud, 8 to room))) // a pause before the limit ends it there
        assertEquals(listOf(150), cuts(run(1 to room, 148 to loud, 8 to room))) // the limit comes first
    }

    @Test fun anEmptyReadChangesNothing() {
        val d = PauseDetector()
        assertFalse(d.next(ShortArray(0), 0, 0))
        assertEquals(listOf(21), cuts(run(3 to room, 10 to loud, 8 to room), d))
    }
}
