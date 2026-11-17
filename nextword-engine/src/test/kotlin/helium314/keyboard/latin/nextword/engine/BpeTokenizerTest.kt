// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LM3: the Kotlin tokenizer gives the ids of the models' own tokenizers (Hugging Face tokenizers 0.22.1, tools/nextword/make_token_fixtures.py) for 100
 * strings (50 English, 50 Bulgarian; spaces, digits, punctuation, contractions, emoji, decomposed letters, line breaks), and decodes tokens back to text.
 */
class BpeTokenizerTest {
    private fun tokenizer(dir: String, split: PreSplit): BpeTokenizer {
        val root = File(System.getProperty("taen.assets"), "nextword/$dir")
        return BpeTokenizer.read(File(root, "vocab.json").readText(), File(root, "merges.txt").readText(), split)
    }

    private val smol by lazy { tokenizer("smollm2-135m", PreSplit.SMOLLM2) }
    private val qwen by lazy { tokenizer("qwen3-0.6b", PreSplit.QWEN2) }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'u' -> { out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue }
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    '\\' -> out.append('\\')
                    else -> throw IllegalArgumentException("escape $n")
                }
                i += 2
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun fixture(name: String): List<Pair<String, IntArray>> =
        javaClass.getResource("/$name")!!.readText().lines().filter { it.isNotEmpty() }.map { line ->
            val (text, ids) = line.split('\t')
            unescape(text) to ids.split(' ').map(String::toInt).toIntArray()
        }

    @Test
    fun englishWordsGiveTheReferenceIds() {
        // ids from the model's own tokenizer (tokens-smollm2.tsv, lines 1 and 2)
        assertArrayEquals(intArrayOf(19556), smol.encode("Hello"))
        assertArrayEquals(intArrayOf(1195, 2644, 2643, 335, 260, 1171), smol.encode("the cat sat on the mat"))
    }

    @Test
    fun everyReferenceStringGivesTheReferenceIdsWithBothTokenizers() {
        for ((name, tok) in listOf("tokens-smollm2.tsv" to smol, "tokens-qwen3.tsv" to qwen)) {
            val cases = fixture(name)
            assertEquals("$name has 100 strings", 100, cases.size)
            val wrong = cases.filter { (text, ids) -> !tok.encode(text).contentEquals(ids) }.map { it.first }
            assertEquals("$name: strings with other ids", emptyList<String>(), wrong)
        }
    }

    @Test
    fun tokensDecodeToTheirTextAndPartsOfACharacterDecodeToNothing() {
        assertEquals(" cat", smol.piece(smol.encode(" cat").single()))
        val bg = qwen.encode(" добро")
        assertEquals(" добро", bg.joinToString("") { qwen.piece(it)!! })
        // the emoji is split into byte tokens: the first one alone is not a whole UTF-8 character
        val emoji = smol.encode("\uD83D\uDE00")
        assertTrue("the emoji takes more than one token", emoji.size > 1)
        assertNull(smol.piece(emoji.first()))
        assertNull(smol.piece(-1))
        assertNull(smol.piece(smol.vocabularySize))
    }

    @Test
    fun specialTokenTextIsPlainText() {
        assertTrue("<|endoftext|> typed by a user is not the control token 0", 0 !in smol.encode("<|endoftext|>"))
        assertTrue(151643 !in qwen.encode("<|endoftext|>"))
    }

    @Test
    fun aDamagedVocabularyIsRefused() {
        for (bad in listOf("", "[]", "{\"a\":}", "{\"a\":1", "{\"a\" 1}", "{a:1}", "{\"a\":1,}", "{\"\\x\":1}"))
            assertThrows(bad, IllegalArgumentException::class.java) { BpeTokenizer.read(bad, "#version: 0.2\n", PreSplit.SMOLLM2) }
        val ok = BpeTokenizer.read("{\"a\":0, \"b\" : 1, \"\\u0120\":2}", "#version: 0.2\n", PreSplit.SMOLLM2)
        assertEquals("positive control: a small vocabulary reads", 3, ok.vocabularySize)
        assertArrayEquals(intArrayOf(0, 1), ok.encode("ab"))
    }
}
