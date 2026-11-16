// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/** Hand-calculated cases for the WER helper of the smoke test (the same rules as tools/voice/wer.py). */
class WerTest {
    private fun e(ref: String, hyp: String) = Wer.edits(Wer.words(ref), Wer.words(hyp))

    @Test fun identicalTextIsZero() = assertEquals(0, e("the cat sat", "the cat sat"))
    @Test fun oneDeletion() = assertEquals(1, e("a b c", "a c"))
    @Test fun oneInsertion() = assertEquals(1, e("a c", "a b c"))
    @Test fun oneSubstitution() = assertEquals(1, e("a b c", "a x c"))
    @Test fun emptyHypothesisCostsEveryWord() = assertEquals(3, e("a b c", ""))
    @Test fun caseAndPunctuationAreIgnored() = assertEquals(0, e("Hello, World!", "hello world"))
    @Test fun cyrillicIsLowerCasedAndPunctuationSplit() = assertEquals(0, e("Това обаче, не е.", "това обаче не е"))
    @Test fun hyphenSplitsAWord() = assertEquals(0, e("по-късно", "по късно")) // the python tool does the same
    @Test fun swapCostsTwo() = assertEquals(2, e("a b", "b a"))
}
