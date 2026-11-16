// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TokensTest {
    private val vocabulary = listOf(
        "<unk> 0", "<|nospeech|> 1", "<pad> 2", "▁ 3", "▁the 4", "cat 5", "▁Hello 6", "s 7",
        "▁Бъл 8", "гария 9", "<0xD0> 10", "<0x96> 11", "<blk> 12",
    )

    @Test fun wordPiecesBecomeWordsWithSpaces() {
        val tokens = Tokens(vocabulary)
        assertEquals(13, tokens.size)
        assertEquals("the cat", tokens.text(intArrayOf(4, 3, 5))) // "▁the" + "▁" + "cat"
        assertEquals("Hello s", tokens.text(intArrayOf(6, 3, 7)))
        assertEquals("България", tokens.text(intArrayOf(8, 9)))
    }

    @Test fun leadingAndTrailingSpacesAreTrimmedAndSpecialsDropped() {
        val tokens = Tokens(vocabulary)
        assertEquals("the", tokens.text(intArrayOf(3, 0, 1, 2, 4, 3, 12)))
        assertEquals("", tokens.text(intArrayOf()))
        assertEquals("", tokens.text(intArrayOf(0, 2)))
    }

    @Test fun bytePiecesAreJoinedToUtf8() {
        val tokens = Tokens(vocabulary)
        assertEquals("Ж", tokens.text(intArrayOf(10, 11))) // D0 96 = U+0416
        assertEquals("theЖ cat", tokens.text(intArrayOf(4, 10, 11, 3, 5))) // a byte sequence ends where a normal piece starts
    }

    @Test fun pieceMayContainASpaceBeforeItsId() {
        assertEquals("a b", Tokens(listOf("a b 0")).text(intArrayOf(0)))
    }

    @Test fun badVocabularyIsRejected() {
        assertFailsWith<IllegalArgumentException> { Tokens(listOf("a 0", "b 2")) } // gap
        assertFailsWith<IllegalArgumentException> { Tokens(listOf("a 0", "b 0")) } // repeated id
        assertFailsWith<IllegalArgumentException> { Tokens(listOf("a x")) } // id not a number
        assertFailsWith<IllegalArgumentException> { Tokens(listOf("7")) } // no piece
        assertFailsWith<IllegalArgumentException> { Tokens(listOf("a -1")) }
    }

    @Test fun idOutsideTheVocabularyIsRejected() {
        assertFailsWith<IllegalArgumentException> { Tokens(vocabulary).text(intArrayOf(13)) }
        assertFailsWith<IllegalArgumentException> { Tokens(vocabulary).text(intArrayOf(-1)) }
    }
}
