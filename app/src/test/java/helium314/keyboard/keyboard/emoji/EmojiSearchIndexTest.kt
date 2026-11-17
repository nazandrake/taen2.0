// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline emoji search: the index over the CLDR annotations in assets/emoji_search/<lang>.txt (Unicode CLDR 49,
 * Unicode License v3, built by tools/gen-emoji-search.py). The logic is tested on a small fixture whose expected order
 * is worked out by hand from the documented rule: a better match first (a keyword equal to the word, then a word of a
 * keyword, then the start of a word), then the shorter CLDR name, then fewer keywords, then the palette order.
 * The data is checked against the keyboard's emoji palette and against the CLDR names of a few known emoji.
 */
@RunWith(RobolectricTestRunner::class)
class EmojiSearchIndexTest {
    private val fixture = listOf(
        "😀\tgrinning face\tgrin|grinning|smile|smiling|face|grinning face",
        "😃\tgrinning face with big eyes\tsmile|happy|face|big eyes|grinning face with big eyes",
        "❤️\tred heart\theart|love|red heart",
        "❤️‍🔥\theart on fire\theart|fire|love|heart on fire",
        "🔥\tfire\tfire|flame|hot",
        "🐶\tdog face\tdog|puppy|face|pet|dog face",
        "👍\tthumbs up\tthumbs up|yes|hand",
    ).joinToString("\n")
    private val index = EmojiSearchIndex.parse(fixture, Locale.ENGLISH)

    @Test fun anExactKeywordGivesItsEmojiShortestNameFirst() {
        assertEquals(listOf("😀", "😃"), index.exact("smile")) // "grinning face" (13 letters) before "grinning face with big eyes" (27)
        assertEquals(listOf("❤️", "❤️‍🔥"), index.exact("heart")) // "red heart" (9) before "heart on fire" (13)
        assertEquals(listOf("🔥", "❤️‍🔥"), index.exact("fire")) // "fire" (4) first
        assertEquals(listOf("❤️"), index.exact("red heart")) // a whole keyword may have several words
        assertEquals(listOf("🐶", "😀", "😃"), index.exact("face")) // "dog face" (8), "grinning face" (13), the long one (27)
        // an emoji whose own name has the word comes before one that only has it as a keyword, even with a longer name
        val named = EmojiSearchIndex.parse("K\tkiss\tkiss|heart|love\nH\tred heart\theart|love\nS\tsuit of hearts\theart|card", Locale.ENGLISH)
        assertEquals(listOf("H", "S", "K"), named.exact("heart")) // "red heart", "suit of hearts" (the word starts a name word), then "kiss"
        assertEquals(listOf("H", "S", "K"), named.search("heart"))
        // equal names: fewer keywords first, then the palette order
        val tie = EmojiSearchIndex.parse("A\tsame\tx|y|z\nB\tsame\tx|y\nC\tsame\tx|y\nD\tsa\tx", Locale.ENGLISH)
        assertEquals(listOf("D", "B", "C", "A"), tie.exact("x"))
    }

    @Test fun exactNeedsTheWholeKeywordAndIgnoresCase() {
        assertEquals(emptyList(), index.exact("hear")) // a start of a word is a search, not a suggestion
        assertEquals(emptyList(), index.exact("hearts"))
        assertEquals(emptyList(), index.exact(""))
        assertEquals(emptyList(), index.exact("red")) // a word inside a keyword is not the keyword
        assertEquals(listOf("😀", "😃"), index.exact("SMILE"))
        val bg = EmojiSearchIndex.parse("🐶\tМуцуна на куче\tдомашно животно|куче|муцуна на куче", Locale("bg"))
        assertEquals(listOf("🐶"), bg.exact("КУЧЕ"))
        assertEquals(listOf("🐶"), bg.exact("куче"))
    }

    @Test fun searchRanksBetterMatchesFirst() {
        assertEquals(listOf("❤️", "❤️‍🔥"), index.search("heart"))
        assertEquals(listOf("❤️", "❤️‍🔥"), index.search("hea")) // the start of a word
        assertEquals(listOf("🔥", "❤️‍🔥"), index.search("fire"))
        // a word inside a keyword ranks after a keyword that is the word, then by the start of a word
        val mixed = EmojiSearchIndex.parse("A\ta\tred heart\nB\tb\tred\nC\tc\treddish|red wine\nD\td\trelaxed\nE\te\treddish", Locale.ENGLISH)
        // B: the keyword is "red" (rank 0); A: "red" is a word of the keyword (rank 1, 1 keyword); C: also a word of
        // "red wine" (rank 1, 2 keywords: after A); E: only the start of a word (rank 2); D does not match
        assertEquals(listOf("B", "A", "C", "E"), mixed.search("red"))
    }

    @Test fun everyWordOfAQueryMustMatch() {
        assertEquals(listOf("😃"), index.search("big eyes"))
        assertEquals(listOf("🐶"), index.search("face dog"))
        assertEquals(emptyList(), index.search("face fire")) // no emoji has both
        assertEquals(listOf("🐶", "😀", "😃"), index.search("fa"))
    }

    @Test fun aQueryInTheMiddleOfAWordIsNoMatch() {
        assertEquals(emptyList(), index.search("ace")) // inside "face"
        assertEquals(emptyList(), index.search("ea")) // inside "heart"
        assertEquals(emptyList(), index.search("ire")) // the end of "fire"
        assertEquals(listOf("🔥", "❤️‍🔥"), index.search("fir")) // control: the start of the word finds them
    }

    @Test fun emptyAndUnknownQueriesGiveNothing() {
        assertEquals(emptyList(), index.search(""))
        assertEquals(emptyList(), index.search("   "))
        assertEquals(emptyList(), index.search("zzz"))
    }

    @Test fun theLimitCutsTheListAfterTheRanking() {
        assertEquals(listOf("🐶", "😀"), index.search("face", limit = 2))
        assertEquals(listOf("🐶", "😀", "😃"), index.search("face", limit = 3))
        assertEquals(emptyList(), index.search("face", limit = 0))
    }

    @Test fun theNameComesFromTheEmojiWithOrWithoutVariationSelectorOrSkinTone() {
        assertEquals("red heart", index.name("❤️"))
        assertEquals("red heart", index.name("❤")) // without the variation selector
        assertEquals("fire", index.name("🔥"))
        assertEquals("thumbs up", index.name("👍🏽")) // the keyboard's emoji with a skin tone: the neutral one is described
        assertNull(index.name("🚀"))
    }

    @Test fun damagedLinesAreSkippedAndTheRestIsKept() {
        // empty line, no tabs, no keywords, a good line, a good line for 🔥, the same emoji again, no emoji, a fourth field
        val damaged = EmojiSearchIndex.parse(
            "\nno tabs here\n🔥\tfire\t\n😀\tgrin\tsmile\n🔥\tfire first\tflame\n🔥\tfire second\tblaze\n\t\t\n🍎\tapple\tfruit\textra\n", Locale.ENGLISH)
        assertEquals(2, damaged.size)
        assertEquals(emptyList(), damaged.exact("fruit")) // a line with more than three fields is damaged
        assertEquals(listOf("😀"), damaged.exact("smile"))
        assertEquals(listOf("🔥"), damaged.exact("flame"))
        assertEquals(emptyList(), damaged.exact("blaze")) // the first line of an emoji wins
        assertEquals("fire first", damaged.name("🔥"))
        assertEquals(0, EmojiSearchIndex.parse("", Locale.ENGLISH).size)
    }

    // ---- the bundled data ----------------------------------------------------------------------------------------

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun asset(path: String) = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    private val en get() = assertNotNull(EmojiSearchIndex.load(context, "en"))
    private val bg get() = assertNotNull(EmojiSearchIndex.load(context, "bg"))

    @Test fun englishAndBulgarianAreBundledAndOtherLanguagesAreNot() {
        assertEquals(1914, en.size)
        assertEquals(1914, bg.size)
        assertNull(EmojiSearchIndex.load(context, "fr"))
        assertNull(EmojiSearchIndex.load(context, ""))
        assertTrue(EmojiSearchIndex.load(context, "en") === en) // read once
    }

    @Test fun knownEmojiAreFoundByTheirCldrKeywords() {
        // CLDR 49 en: red heart has the keyword "heart"; dog face the keyword "dog"; the flag of Bulgaria is "flag: bulgaria"
        assertTrue("❤️" in en.exact("heart"))
        assertTrue("🐶" in en.exact("dog"))
        assertEquals("red heart", en.name("❤️"))
        assertEquals("flag: Bulgaria", en.name("🇧🇬"))
        assertTrue("🇧🇬" in en.search("bulgaria"))
        // CLDR 49 bg: Червено сърце has the keyword "сърце"; куче (🐕) and муцуна на куче (🐶) have "куче"
        assertTrue("❤️" in bg.exact("сърце"))
        assertTrue("🐕" in bg.exact("куче") && "🐶" in bg.exact("куче"))
        assertEquals("Червено сърце", bg.name("❤️"))
        assertEquals("Флаг: България", bg.name("🇧🇬"))
        assertTrue("🇧🇬" in bg.search("българия"))
        // an English word does not find the Bulgarian emoji through the Bulgarian index
        assertEquals(emptyList(), bg.exact("heart"))
    }

    @Test fun everyIndexedEmojiIsInTheKeyboardsPaletteAndInPaletteOrder() {
        val palette = listOf("SMILEYS_AND_EMOTION", "PEOPLE_AND_BODY", "ANIMALS_AND_NATURE", "FOOD_AND_DRINK", "TRAVEL_AND_PLACES",
            "ACTIVITIES", "OBJECTS", "SYMBOLS", "FLAGS").flatMap { asset("emoji/$it.txt").split(Regex("\\s+")) }
        val minApi = asset("emoji/minApi.txt").lines().flatMap { it.split(" ").drop(1) }
        val position = HashMap<String, Int>()
        (palette + minApi).filter { it.isNotEmpty() }.forEach { position.putIfAbsent(it, position.size) }
        for (lang in listOf("en", "bg")) {
            val lines = asset("emoji_search/$lang.txt").lines().filter { it.isNotEmpty() }
            val emojis = lines.map { it.substringBefore('\t') }
            assertEquals(emojis.size, emojis.toSet().size, "$lang: an emoji twice")
            val missing = emojis.filter { it !in position }
            assertEquals(emptyList(), missing, "$lang: not in the palette")
            assertEquals(emojis.sortedBy { position.getValue(it) }, emojis, "$lang: palette order")
            assertTrue(emojis.none { Regex("[\\x{1F3FB}-\\x{1F3FF}]").containsMatchIn(it) }, "$lang: a skin-tone variant")
            assertTrue(lines.all { it.split('\t').size == 3 && it.split('\t')[2].isNotEmpty() }, "$lang: a line without name or keywords")
        }
        assertEquals(asset("emoji_search/en.txt").lines().map { it.substringBefore('\t') }, asset("emoji_search/bg.txt").lines().map { it.substringBefore('\t') })
    }

    @Test fun theBulgarianKeywordsAreBulgarian() {
        val lines = asset("emoji_search/bg.txt").lines().filter { it.isNotEmpty() }
        val cyrillic = lines.count { l -> l.split('\t')[2].split('|').any { w -> w.any { it in 'а'..'я' } } }
        assertTrue(cyrillic >= lines.size * 99 / 100, "$cyrillic of ${lines.size} entries have a Bulgarian keyword")
    }
}
