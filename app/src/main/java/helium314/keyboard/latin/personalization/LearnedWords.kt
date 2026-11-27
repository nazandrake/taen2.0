// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import helium314.keyboard.latin.dictionary.ExpandableBinaryDictionary
import helium314.keyboard.latin.dictionary.ListedWord
import helium314.keyboard.latin.privacy.Privacy
import java.util.Locale

/**
 * Settings > Privacy > Learned words and Forget learned data. The learned words of a language are those of its learned-word dictionary (the one
 * the keyboard uses, PersonalizationHelper); the removed words are its list of words removed from the suggestions (Privacy.removedWords). Nothing
 * is listed, removed or restored while the device or the screen is locked (null or false). Every call may wait for I/O: not on the main thread.
 * The personal dictionary is not touched here (it has its own "Clear personal dictionary").
 */
object LearnedWords {
    private const val WAIT_MILLIS = 5_000L

    /** Test seam: the learned-word dictionary of a language (the native engine is not in the unit tests). */
    @Volatile internal var historyForTest: ((Locale) -> ExpandableBinaryDictionary)? = null

    private fun history(context: Context, tag: String): ExpandableBinaryDictionary {
        val locale = Locale.forLanguageTag(tag)
        return historyForTest?.invoke(locale) ?: PersonalizationHelper.getUserHistoryDictionary(context.applicationContext ?: context, locale)
    }

    /** The languages with learned or removed words, sorted; null while locked. */
    fun languages(context: Context): List<String>? {
        if (Privacy.isLocked) return null
        val learned = Privacy.learnedTags(context) ?: return null
        val removed = Privacy.removedWordTags(context) ?: return null
        return (learned + removed).toSortedSet().toList()
    }

    /** The learned words of [tag] with their use counts, sorted; null while locked or when the dictionary cannot be read now. */
    fun learned(context: Context, tag: String): List<ListedWord>? {
        if (Privacy.isLocked) return null
        val words = history(context, tag).listWords(WAIT_MILLIS) ?: return null
        if (Privacy.isLocked) return null // a lock while the list was read: nothing of it is shown
        return words.sortedWith(compareBy({ it.word.lowercase(Locale.ROOT) }, { it.word }))
    }

    /** The words removed from the suggestions of [tag], sorted; null while locked. */
    fun removed(context: Context, tag: String): List<String>? {
        if (Privacy.isLocked) return null
        return Privacy.removedWords(context, tag).all()?.sortedWith(compareBy({ it.lowercase(Locale.ROOT) }, { it }))
    }

    /**
     * Removes the learned word [word] of [tag]: it goes to the removed words (not suggested any more, can be restored there) and out of the learned
     * words, which are saved at once. False when it could not be done: locked, the removed words not stored, or the learned words not saved.
     */
    fun remove(context: Context, tag: String, word: String): Boolean {
        if (Privacy.isLocked) return false
        if (!Privacy.removedWords(context, tag).add(word)) return false
        return history(context, tag).removeWordAndSave(word, WAIT_MILLIS)
    }

    /** Takes [word] off the removed words of [tag]: it may be suggested and learned again. False while locked or when it could not be stored. */
    fun restore(context: Context, tag: String, word: String): Boolean {
        if (Privacy.isLocked) return false
        return Privacy.removedWords(context, tag).remove(word)
    }

    /**
     * Forget learned data: the learned words and the removed words of every language are deleted now, from memory and from the stores; the
     * personal dictionary stays. False while locked or when something could not be deleted.
     */
    fun forgetAll(context: Context): Boolean {
        if (Privacy.isLocked) return false
        val app = context.applicationContext ?: context
        val cleared = PersonalizationHelper.clearAllAndWait(WAIT_MILLIS) // the running dictionaries: memory and store, writes in flight done first
        Privacy.deleteAllLearned(app) // every language, also those that are not loaded
        PersonalizationHelper.deleteLegacyFiles(app)
        val removed = Privacy.deleteAllRemovedWords(app)
        return cleared && removed && Privacy.learnedTags(app)?.isEmpty() == true
    }
}
