// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

/**
 * One entry of the personal dictionary. With a [shortcut] it is a snippet: typing the shortcut offers [word].
 * [locale] is a `Locale.toString()` string such as "en_US"; null means all languages. [frequency] is 0..255.
 */
data class PersonalWord(val word: String, val shortcut: String?, val frequency: Int, val locale: String?)

object PersonalDictionaryCodec {
    private const val VERSION = 1

    fun encode(words: List<PersonalWord>): ByteArray = encodeRecords {
        writeInt(VERSION)
        writeInt(words.size)
        for (w in words) {
            writeText(w.word)
            writeBoolean(w.shortcut != null)
            if (w.shortcut != null) writeText(w.shortcut)
            writeInt(w.frequency)
            writeBoolean(w.locale != null)
            if (w.locale != null) writeText(w.locale)
        }
    }

    fun decode(data: ByteArray): List<PersonalWord> = decodeRecords(data) {
        if (readInt() != VERSION) throw CorruptStoreException("unknown personal dictionary version")
        List(readCount()) {
            val word = readText()
            val shortcut = if (readBoolean()) readText() else null
            val frequency = readInt()
            val locale = if (readBoolean()) readText() else null
            PersonalWord(word, shortcut, frequency, locale)
        }
    }
}

/**
 * The personal dictionary and snippets, kept in `snippets.enc` instead of the system user dictionary (no other app can
 * read it). An entry is identified by word and locale. Pure logic: persistence is injected through [persist].
 */
class PersonalDictionary(initial: List<PersonalWord>, private val persist: (List<PersonalWord>) -> Unit) {
    fun interface ChangeListener { fun onChanged() }

    private val words = ArrayList(initial)
    private val listeners = ArrayList<ChangeListener>()
    private var evicted = false

    /** True while the entries are wiped from memory (screen lock): nothing can be added, deleted or replaced. */
    @Synchronized fun isEvicted() = evicted

    /** Wipes the entries from memory without touching the store; listeners are told so dictionaries built from them rebuild. */
    fun evict() {
        synchronized(this) {
            if (evicted) return
            evicted = true
            words.clear()
        }
        fireChanged()
    }

    /** Puts the entries read from the store back after [evict]; listeners are told again. */
    fun restore(entries: List<PersonalWord>) {
        synchronized(this) {
            if (!evicted) return
            words.addAll(entries)
            evicted = false
        }
        fireChanged()
    }

    @Synchronized fun all(): List<PersonalWord> = words.toList()

    /** Entries of exactly this locale string, or the all-languages entries for null. Sorted ignoring case, like the old screen. */
    @Synchronized fun forLocale(locale: String?): List<PersonalWord> =
        words.filter { it.locale == locale }.sortedBy { it.word.uppercase() }

    /**
     * Entries a dictionary for [dictLocale] uses: all-languages entries and entries of the locale itself or of a
     * less specific one ("en_US_POSIX" uses "en", "en_US" and "en_US_POSIX"; null means no language: all-languages only).
     */
    @Synchronized fun matching(dictLocale: String?): List<PersonalWord> {
        val chain = HashSet<String>()
        var soFar = ""
        val parts = if (dictLocale.isNullOrEmpty()) emptyList() else dictLocale.split("_", limit = 3)
        for (part in parts) {
            soFar += part
            chain.add(soFar)
            soFar += "_"
        }
        return words.filter { it.locale == null || it.locale in chain }
    }

    @Synchronized fun exists(word: String, locale: String?) = words.any { it.word == word && it.locale == locale }

    /**
     * Writes [updated] and makes it the current list. When the write fails (locked tier, Keystore or disk error) nothing
     * changes and false is returned, so the caller never believes in an entry that exists only in memory (and would be wiped
     * by the next screen lock). Called with the lock of this object held.
     */
    private fun commit(updated: List<PersonalWord>): Boolean {
        try {
            persist(updated)
        } catch (_: Exception) {
            return false
        }
        words.clear()
        words.addAll(updated)
        return true
    }

    /** Adds or replaces the entry with the same word and locale. Returns false (nothing changed) for invalid input or a failed write. */
    fun add(entry: PersonalWord): Boolean {
        if (entry.word.isBlank() || entry.word.length > MAX_WORD_LENGTH) return false
        if (entry.shortcut != null && (entry.shortcut.isBlank() || entry.shortcut.length > MAX_SHORTCUT_LENGTH)) return false
        if (entry.frequency !in 0..255) return false
        synchronized(this) {
            if (evicted) return false
            val index = words.indexOfFirst { it.word == entry.word && it.locale == entry.locale }
            if (index < 0 && words.size >= MAX_ENTRIES) return false
            val updated = words.toMutableList()
            if (index >= 0) updated[index] = entry else updated.add(entry)
            if (!commit(updated)) return false
        }
        fireChanged()
        return true
    }

    /** Deletes exactly this entry. Returns whether it existed and was removed (false too when the write failed). */
    fun delete(entry: PersonalWord): Boolean {
        synchronized(this) {
            if (evicted || entry !in words) return false
            if (!commit(words - entry)) return false
        }
        fireChanged()
        return true
    }

    /**
     * Replaces all entries (an import). The new list must be valid as a whole (same rules as [add], no two entries with
     * the same word and locale); otherwise nothing changes and false is returned.
     */
    fun replaceAll(entries: List<PersonalWord>): Boolean {
        val valid = entries.size <= MAX_ENTRIES &&
            entries.all { it.word.isNotBlank() && it.word.length <= MAX_WORD_LENGTH && it.frequency in 0..255 &&
                (it.shortcut == null || (it.shortcut.isNotBlank() && it.shortcut.length <= MAX_SHORTCUT_LENGTH)) } &&
            entries.map { it.word to it.locale }.toSet().size == entries.size
        if (!valid) return false
        synchronized(this) {
            if (evicted) return false
            if (!commit(entries.toList())) return false
        }
        fireChanged()
        return true
    }

    fun clear() {
        synchronized(this) {
            if (evicted || words.isEmpty()) return
            if (!commit(emptyList())) return
        }
        fireChanged()
    }

    @Synchronized fun addListener(l: ChangeListener) { listeners.add(l) }
    @Synchronized fun removeListener(l: ChangeListener) { listeners.remove(l) }

    private fun fireChanged() {
        val targets = synchronized(this) { listeners.toList() }
        targets.forEach { it.onChanged() }
    }

    companion object {
        const val MAX_WORD_LENGTH = 1000
        const val MAX_SHORTCUT_LENGTH = 100
        const val MAX_ENTRIES = 5000
        const val DEFAULT_FREQUENCY = 250
    }
}
