// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import android.content.Context
import java.util.Locale

/**
 * Offline emoji search: the keywords of every emoji of the keyboard's palette in one language, from the Unicode
 * CLDR annotations (Unicode License v3, see NOTICE) bundled as assets/emoji_search/<lang>.txt and built by
 * tools/gen-emoji-search.py. One line per emoji in palette order: "<emoji><TAB><name><TAB><keyword>|<keyword>|...".
 * The data is public and read-only; nothing of the user's is in it, and nothing is read from or written to anywhere
 * else. No network: the data is part of the APK.
 *
 * Ranking (the same for [exact] and [search]): a better match first (a keyword equal to the word, then a word of a
 * keyword, then the start of a word), then an emoji whose own name has the word ("red heart" before "kiss", which has the
 * keyword "heart"), then the shorter CLDR name ("dog" before "dog face": the plainer emoji is the likelier choice), then the
 * one with fewer keywords, then the palette order.
 */
class EmojiSearchIndex private constructor(
    private val locale: Locale,
    private val emojis: List<String>,
    private val names: List<String>,
    private val keywords: List<List<String>>,
) {
    val size get() = emojis.size

    // keyword -> emoji ids, each list already in the final order
    private val nameWords = names.map { name -> name.lowercase(locale).split(NOT_A_LETTER).filter { it.isNotEmpty() } }

    // true if every word of [tokens] is, or starts, a word of the name of the emoji
    private fun nameHas(id: Int, tokens: List<String>) = tokens.all { token -> nameWords[id].any { it.startsWith(token) } }

    private val byKeyword = HashMap<String, ArrayList<Int>>().also { map ->
        val ids = emojis.indices.sortedWith(compareBy({ names[it].length }, { keywords[it].size }, { it }))
        for (keyword in ids.flatMap { keywords[it] }.toSet()) {
            val tokens = keyword.split(' ').filter { it.isNotEmpty() }
            // ids is in the tie-break order; the stable partition puts the emoji whose name has the keyword first
            val (named, other) = ids.filter { keyword in keywords[it] }.partition { nameHas(it, tokens) }
            map[keyword] = ArrayList(named + other)
        }
    }
    private val nameOf = HashMap<String, String>().also { map -> emojis.indices.forEach { map[plain(emojis[it])] = names[it] } }

    /** The emoji that have [word] as a whole keyword, best first. Empty for an empty word. */
    fun exact(word: String): List<String> {
        val w = word.trim().lowercase(locale)
        if (w.isEmpty()) return emptyList()
        return byKeyword[w].orEmpty().map { emojis[it] }
    }

    /**
     * The emoji for which every word of [query] matches a keyword (equal, a word of it, or the start of a word of it),
     * best first, at most [limit].
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<String> {
        val tokens = query.trim().lowercase(locale).split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || limit <= 0) return emptyList()
        class Hit(val id: Int, val rank: Int, val named: Int)
        val hits = ArrayList<Hit>()
        for (id in emojis.indices) {
            var total = 0
            for (token in tokens) {
                val rank = rankOf(keywords[id], token)
                if (rank < 0) { total = -1; break }
                total += rank
            }
            if (total >= 0) hits.add(Hit(id, total, if (nameHas(id, tokens)) 0 else 1))
        }
        hits.sortWith(compareBy({ it.rank }, { it.named }, { names[it.id].length }, { keywords[it.id].size }, { it.id }))
        return hits.take(limit).map { emojis[it.id] }
    }

    /** The CLDR name of [emoji] (also of its skin-tone form and without variation selector), or null. */
    fun name(emoji: String): String? = nameOf[plain(emoji)]

    // 0: a keyword equals the token, 1: a word of a keyword, 2: the start of a word, -1: no match
    private fun rankOf(words: List<String>, token: String): Int {
        var best = -1
        for (keyword in words) {
            if (keyword == token) return 0
            for (w in keyword.split(' ')) {
                val rank = if (w == token) 1 else if (w.startsWith(token)) 2 else continue
                if (best < 0 || rank < best) best = rank
            }
        }
        return best
    }

    companion object {
        const val DEFAULT_LIMIT = 100
        private val WHITESPACE = Regex("\\s+")
        private val NOT_A_LETTER = Regex("[^\\p{L}\\p{N}]+")
        private val SKIN_TONE_OR_VS16 = Regex("[\\x{FE0F}\\x{1F3FB}-\\x{1F3FF}]")

        /** The emoji without variation selectors and skin tones: the key under which names are found. */
        private fun plain(emoji: String) = emoji.replace(SKIN_TONE_OR_VS16, "")

        /** Reads the lines of a data file. Lines that are not "emoji, name, at least one keyword" are skipped; the first line of an emoji wins. */
        fun parse(text: String, locale: Locale): EmojiSearchIndex {
            val emojis = ArrayList<String>()
            val names = ArrayList<String>()
            val keywords = ArrayList<List<String>>()
            val seen = HashSet<String>()
            for (line in text.lineSequence()) {
                val fields = line.split('\t')
                if (fields.size != 3 || fields[0].isEmpty()) continue
                val words = fields[2].split("|").filter { it.isNotEmpty() }.distinct()
                if (words.isEmpty() || !seen.add(plain(fields[0]))) continue
                emojis.add(fields[0])
                names.add(fields[1])
                keywords.add(words)
            }
            return EmojiSearchIndex(locale, emojis, names, keywords)
        }

        private val loaded = HashMap<String, EmojiSearchIndex?>()

        /** The index of a language ("en", "bg"), read once per process; null if the app has no data for it. */
        fun load(context: Context, language: String): EmojiSearchIndex? = synchronized(loaded) {
            if (language.isEmpty() || !language.all { it in 'a'..'z' }) return null
            loaded.getOrPut(language) {
                runCatching {
                    context.assets.open("emoji_search/$language.txt").bufferedReader(Charsets.UTF_8).use {
                        parse(it.readText(), Locale.forLanguageTag(language))
                    }
                }.getOrNull()
            }
        }
    }
}
