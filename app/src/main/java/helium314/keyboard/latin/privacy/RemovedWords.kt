// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** The plain removed-word file of an earlier version (`files/blacklists/<tag>.txt`). */
interface LegacyRemovedWords {
    fun exists(): Boolean
    /** The words of the file, or null when it cannot be read now or is larger than [RemovedWordsStorage.MAX_LEGACY_BYTES]. */
    fun read(): List<String>?
    /** Deletes the file; false when it is still there. */
    fun delete(): Boolean
}

/** Binary form of the removed words of one language. */
object RemovedWordsCodec {
    private const val VERSION = 1

    fun encode(words: Collection<String>): ByteArray = encodeRecords {
        writeInt(VERSION)
        writeInt(words.size)
        words.forEach { writeText(it) }
    }

    fun decode(data: ByteArray): Set<String> = decodeRecords(data) {
        if (readInt() != VERSION) throw CorruptStoreException("unknown removed-words version")
        val count = readCount()
        if (count > RemovedWords.MAX_WORDS) throw CorruptStoreException("too many removed words")
        LinkedHashSet<String>(count).apply { repeat(count) { add(readText()) } }
    }
}

/**
 * Where the words the user removed from the suggestions of one language are kept: one AES-GCM store `blacklist_<tag>` of the
 * credential-encrypted tier (P7). The owner of the rules:
 * - while the store cannot be read (device or screen locked, a transient error) [load] reports it unavailable and nothing is written
 *   or deleted, the plain file of an earlier version included;
 * - a damaged store is deleted by [SecureStore] (or here, when it decrypts but does not decode) and the words in it are lost;
 * - the plain file of an earlier version is moved in: its words are written to the store, read back and compared, and only then is
 *   the plain file deleted. A plain file that cannot be read, is too large or holds more words than fit is left as it is.
 */
class RemovedWordsStorage(private val store: () -> SecureStore?, private val legacy: LegacyRemovedWords?) {

    /** The stored words, with those of the plain file of an earlier version moved in; null while the store is unavailable. */
    fun load(): Set<String>? {
        val s = store() ?: return null
        val stored: Set<String> = when (val content = s.read()) {
            is StoreContent.Unavailable -> return null
            is StoreContent.Data -> try {
                RemovedWordsCodec.decode(content.bytes)
            } catch (_: CorruptStoreException) {
                s.delete() // authenticated but malformed: the words are lost, never read in part
                emptySet()
            }
            is StoreContent.Missing, is StoreContent.Reset -> emptySet()
        }
        val old = legacy?.takeIf { it.exists() } ?: return stored
        val oldWords = try { old.read() } catch (_: Exception) { null } ?: return stored // unreadable or too large: kept, tried at the next load
        // a line that is no acceptable word (over 48 characters, longer than any dictionary word) is dropped with the plain file: it could not
        // be suggested or removed anyway, and the plain file must not stay for it. Nothing about it is logged, not even a count (P11).
        val acceptable = oldWords.filter(RemovedWords::acceptable)
        val merged = LinkedHashSet(stored).apply { addAll(acceptable) }
        if (merged.size > RemovedWords.MAX_WORDS) return stored // the plain file would not fit: it stays, nothing is lost
        if (writeVerified(s, merged)) old.delete()
        return merged
    }

    /** Stores [words]; false (nothing changed) when the store is unavailable or the write failed. */
    fun save(words: Set<String>): Boolean {
        val s = store() ?: return false
        return write(s, words)
    }

    /**
     * Deletes the stored words and the plain file of an earlier version. False (nothing deleted) while the store is unavailable, and false when
     * the store or the plain file is still there afterwards (both are tried): "Forget learned data" then says it failed. A plain file that is
     * still there is moved in and deleted again by the next [load].
     */
    fun delete(): Boolean {
        val s = store() ?: return false
        val storeGone = s.delete()
        val plainGone = legacy?.takeIf { it.exists() }?.delete() ?: true
        return storeGone && plainGone
    }

    private fun write(s: SecureStore, words: Set<String>): Boolean = try {
        s.write(RemovedWordsCodec.encode(words))
        true
    } catch (_: StoreWriteException) {
        false
    } catch (_: VaultLockedException) {
        false
    }

    // the plain file goes only when the encrypted copy is on disk and reads back as the same words
    private fun writeVerified(s: SecureStore, words: Set<String>): Boolean {
        if (!write(s, words)) return false
        val back = s.read() as? StoreContent.Data ?: return false
        return try { RemovedWordsCodec.decode(back.bytes) == words } catch (_: CorruptStoreException) { false }
    }

    companion object {
        /** The largest plain file of an earlier version that is read (about 20 000 words of 48 characters). */
        const val MAX_LEGACY_BYTES = 1 shl 20
    }
}

/**
 * The words removed from the suggestions of one language, in memory, for the life of the process (one object per language, from
 * [Privacy.removedWords]). [contains] is on the suggestion path: it reads a snapshot and never waits for I/O; until the words are loaded
 * it answers no and starts a load in the background (never while the tier is locked). Every change is stored before it shows: a change
 * that cannot be stored is refused. A screen lock keeps the snapshot: it only hides words, it is never listed or changed while locked.
 */
class RemovedWords(
    private val storage: RemovedWordsStorage,
    private val background: Executor = Executor { it.run() },
    private val locked: () -> Boolean = { false },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Volatile private var words: Set<String>? = null
    // hidden at once by the strip's bin icon until the store has the word (or for this process, when it cannot be stored): only ever hides more
    @Volatile private var hiddenNow: Set<String> = emptySet()
    private val loading = AtomicBoolean(false)
    @Volatile private var lastTry = Long.MIN_VALUE / 2

    /** Whether [word] is removed. No I/O: before the first load it answers from what was hidden in this process. */
    fun contains(word: String): Boolean {
        val w = words
        if (w == null) requestLoad()
        return w?.contains(word) == true || word in hiddenNow
    }

    private fun requestLoad() {
        if (locked() || now() - lastTry < RETRY_MILLIS || !loading.compareAndSet(false, true)) return
        lastTry = now()
        try {
            background.execute {
                try {
                    synchronized(this) { ensureLoadedLocked() }
                } finally {
                    loading.set(false)
                }
            }
        } catch (_: Exception) {
            loading.set(false)
        }
    }

    // guarded by this
    private fun ensureLoadedLocked(): Set<String>? {
        words?.let { return it }
        val loaded = storage.load() ?: return null
        words = loaded
        return loaded
    }

    /** The removed words, loaded now if needed (I/O: not on the main thread); null while the storage is unavailable. */
    @Synchronized
    fun all(): Set<String>? = ensureLoadedLocked()

    /** Hides [word] at once, before [add] has stored it (the strip's bin icon). */
    fun hideNow(word: String) {
        if (acceptable(word)) synchronized(this) { hiddenNow = hiddenNow + word }
    }

    /**
     * Adds [word]: true when it is stored. Refused (false, nothing stored) while the storage is unavailable, for a word that is empty, longer than
     * [MAX_WORD_LENGTH] or holds a line break, and when [MAX_WORDS] are stored.
     */
    @Synchronized
    fun add(word: String): Boolean {
        if (!acceptable(word)) return false
        val current = ensureLoadedLocked() ?: return false
        if (word in current) return true
        if (current.size >= MAX_WORDS) return false
        val next = LinkedHashSet(current).apply { add(word) }
        if (!storage.save(next)) return false
        words = next
        return true
    }

    /** Takes [word] off the list (restore, or the user typed it again): true when it is not stored any more. Refused while the storage is unavailable. */
    @Synchronized
    fun remove(word: String): Boolean {
        val current = ensureLoadedLocked() ?: return false
        if (word in current) {
            val next = LinkedHashSet(current).apply { remove(word) }
            if (!storage.save(next)) return false
            words = next
        }
        hiddenNow = hiddenNow - word
        return true
    }

    /** Deletes every removed word of this language (Forget learned data, an import); false while the storage is unavailable. */
    @Synchronized
    fun forget(): Boolean {
        if (!storage.delete()) return false
        words = emptySet()
        hiddenNow = emptySet()
        return true
    }

    /** Replaces the removed words by [replacement] (an import); false (nothing changed) when they could not be stored. */
    @Synchronized
    fun replace(replacement: Set<String>): Boolean {
        val next = LinkedHashSet(replacement.filter(::acceptable).take(MAX_WORDS))
        if (!storage.save(next)) return false
        words = next
        hiddenNow = emptySet()
        return true
    }

    /** Drops the memory copy (a wipe): nothing is hidden or listed any more until it is read again. */
    @Synchronized
    fun evict() {
        words = null
        hiddenNow = emptySet()
    }

    companion object {
        /** At most this many removed words per language. */
        const val MAX_WORDS = 10_000
        /** The longest word a dictionary holds (DecoderSpecificConstants.DICTIONARY_MAX_WORD_LENGTH). */
        const val MAX_WORD_LENGTH = 48
        private const val RETRY_MILLIS = 2_000L

        fun acceptable(word: String): Boolean = word.isNotEmpty() && word.length <= MAX_WORD_LENGTH && word.none { it == '\n' || it == '\r' }
    }
}
