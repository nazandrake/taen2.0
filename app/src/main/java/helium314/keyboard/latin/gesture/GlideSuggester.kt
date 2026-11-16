// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import android.os.Process
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.dictionary.Dictionary
import java.util.WeakHashMap
import kotlin.concurrent.thread

/**
 * Glide typing for the keyboard: the words of a main dictionary, the letter keys of the keyboard shown and
 * the touch points of one gesture go to [GlideEngine]; the best words come back as suggestions.
 *
 * Gesture data (P7, P11): the word list is the read-only main dictionary held in memory only; the touch points are
 * read, scored and dropped. Nothing here stores, logs or sends a gesture (privacyCheck rule for this package).
 *
 * Learned words and the personal dictionary: the word lists of the user dictionaries are copied into small
 * lexicons that live in memory only, are read again only when the dictionary says it changed
 * ([Dictionary.contentVersion]) and are emptied by [forgetUserWords] (the screen-lock hook, set in
 * DictionaryFacilitatorImpl). The dictionaries stay the owner of that data and of its encrypted storage; nothing new
 * is stored.
 */
class GlideSuggester(
    private val engine: GlideEngine = GlideEngine(),
    /** Runs the reading of a word list; a background thread in the app, the calling thread in tests. */
    private val background: (() -> Unit) -> Unit = { task ->
        thread(name = "glide-lexicon", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) // bulk native read of up to 400 000 words
            task()
        }
    },
) {
    private class Entry { @Volatile var lexicon: GlideLexicon? = null; var building = false }

    private val lexicons = WeakHashMap<Dictionary, Entry>()

    // the key geometry of the last keyboard: one gesture asks for suggestions many times while it is drawn
    private var lastKeyboard: Keyboard? = null
    private var lastKeys: GlideKeyboard? = null

    /** Starts reading the word list of [dictionary] in the background, if it is not read or being read. */
    fun warm(dictionary: Dictionary) {
        val entry = synchronized(lexicons) { lexicons.getOrPut(dictionary) { Entry() } }
        synchronized(entry) {
            if (entry.lexicon != null || entry.building) return
            entry.building = true
        }
        background {
            try {
                entry.lexicon = lexiconOf(dictionary)
            } finally {
                synchronized(entry) { entry.building = false }
            }
        }
    }

    /**
     * The glide suggestions for the touch points of [pointers] on [keyboard], best first, with the score scaled by
     * [weightForLocale]. Empty while the word list is still being read (the first gesture after the dictionary
     * loaded), for a path that is no glide and for a keyboard without letter keys.
     */
    fun suggest(
        dictionary: Dictionary, keyboard: Keyboard, pointers: InputPointers, weightForLocale: Float, blockOffensive: Boolean,
        userDictionaries: List<Dictionary> = emptyList(),
        userWordsStillAllowed: () -> Boolean = { true },
    ): List<SuggestedWordInfo> {
        val lexicon = lexiconIfReady(dictionary) ?: return emptyList()
        val keys = keysOf(keyboard) ?: return emptyList()
        // The state of the user words at the start (P7, P9): the generation of the memory copies and the lock state. The caller
        // authorized the user words earlier; a lock after that must not let a copy that is in hand be scored and returned.
        val startGeneration = synchronized(userLists) { forgotten }
        val users = if (userDictionaries.isNotEmpty() && userWordsStillAllowed()) userDictionaries else emptyList()
        val count = pointers.pointerSize
        val xs = FloatArray(count) { pointers.xCoordinates[it].toFloat() }
        val ys = FloatArray(count) { pointers.yCoordinates[it].toFloat() }
        // some spare candidates for the ones the offensive-word setting takes out
        val mainCandidates = engine.suggest(lexicon, keys, xs, ys, count, MAX_SUGGESTIONS + 4)
        val userCandidates = userLexicons(users).flatMap { engine.suggest(it, keys, xs, ys, count, MAX_SUGGESTIONS + 4) }
        // the best of the main list and of the learned and personal words, a word of both lists once with its better cost
        fun build(candidates: List<GlideCandidate>): List<SuggestedWordInfo> {
            val result = ArrayList<SuggestedWordInfo>()
            for (c in candidates.sortedBy { it.cost }.distinctBy { it.word }) {
                if (blockOffensive && dictionary.isPossiblyOffensive(c.word)) continue
                val score = (BASE_SCORE - c.cost * COST_SCALE).toInt().coerceAtLeast(1)
                result.add(SuggestedWordInfo(
                    c.word, "", (score * weightForLocale).toInt(), SuggestedWordInfo.KIND_CORRECTION, dictionary,
                    SuggestedWordInfo.NOT_AN_INDEX, SuggestedWordInfo.NOT_A_CONFIDENCE
                ))
                if (result.size == MAX_SUGGESTIONS) break
            }
            return result
        }
        val result = build(mainCandidates + userCandidates)
        // checked again right before the result is returned: a lock in between (the generation or the lock state changed)
        // drops every user word candidate
        if (userCandidates.isNotEmpty() && (synchronized(userLists) { forgotten } != startGeneration || !userWordsStillAllowed())) {
            return build(mainCandidates)
        }
        return result
    }

    /** The memory copies of the user dictionaries, each read again only when its [Dictionary.contentVersion] changed. */
    private fun userLexicons(dictionaries: List<Dictionary>): List<GlideLexicon> = dictionaries.mapNotNull { dictionary ->
        val version = dictionary.contentVersion() // before reading: an edit during the read gives a newer version, so a re-read
        val generation: Long // before reading: a forgetUserWords during the read (screen lock) must not be undone by the copy
        val cached = synchronized(userLists) {
            generation = forgotten
            userLists[dictionary]?.takeIf { it.version == version }?.lexicon
        }
        cached ?: runCatching { lexiconOf(dictionary) }.getOrNull()?.takeIf { // a dictionary that cannot be read is left out
            synchronized(userLists) {
                (generation == forgotten).also { current -> if (current) userLists[dictionary] = UserList(version, it) }
            }
        }
    }.filter { it.size > 0 }

    private fun lexiconIfReady(dictionary: Dictionary): GlideLexicon? {
        val entry = synchronized(lexicons) { lexicons[dictionary] }
        val lexicon = entry?.lexicon
        if (lexicon == null) warm(dictionary)
        return lexicon
    }

    @Synchronized
    private fun keysOf(keyboard: Keyboard): GlideKeyboard? {
        if (keyboard === lastKeyboard) return lastKeys
        val keys = keyboard.sortedKeys.filter { it.code > 0 && Character.isLetter(it.code) }.map {
            val box = it.hitBox
            GlideKey(it.code, box.exactCenterX(), box.exactCenterY(), box.width().toFloat(), box.height().toFloat())
        }
        val result = if (keys.size >= 2) GlideKeyboard(keys) else null
        lastKeyboard = keyboard
        lastKeys = result
        return result
    }

    private class UserList(val version: Long, val lexicon: GlideLexicon)

    companion object {
        // the learned and personal word lists, one copy per user dictionary of the process
        private val userLists = WeakHashMap<Dictionary, UserList>()
        // counts the calls of forgetUserWords; guarded by userLists
        private var forgotten = 0L

        /** Empties the memory copies of the learned and personal words (screen lock, wipe). The dictionaries are the owner. */
        fun forgetUserWords() = synchronized(userLists) { forgotten++; userLists.clear() }

        /** The number of words in the memory copies of the user dictionaries. */
        fun userWordCount(): Int = synchronized(userLists) { userLists.values.sumOf { it.lexicon.size } }

        const val MAX_SUGGESTIONS = 8
        // the engine's cost (lower is better, about 0 to 30) to the integer score of the suggestion pipeline (higher is better)
        private const val BASE_SCORE = 1_000_000f
        private const val COST_SCALE = 10_000f

        /** Reads all words of [dictionary] into a list for the engine; null when the dictionary did not hand over all of them (never a short list). */
        fun lexiconOf(dictionary: Dictionary): GlideLexicon? {
            val builder = GlideLexicon.Builder()
            val complete = dictionary.forEachWord { word, probability -> builder.add(word, probability) }
            return if (complete) builder.build() else null
        }
    }
}
