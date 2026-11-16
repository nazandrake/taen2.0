// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy


class ClipboardEntry(val id: Long, var timestamp: Long, var pinned: Boolean, val text: String)

/** Retention and size limits of the history, clamped to the design range (retention 5 min to 7 days, 1 to 500 items). */
class ClipboardLimits private constructor(val retentionMillis: Long, val maxItems: Int) {
    companion object {
        const val MIN_RETENTION_MINUTES = 5
        const val MAX_RETENTION_MINUTES = 7 * 24 * 60
        const val MAX_ITEMS = 500
        const val DEFAULT_RETENTION_MINUTES = 60
        const val DEFAULT_MAX_ITEMS = 50

        fun of(retentionMinutes: Int, maxItems: Int) = ClipboardLimits(
            retentionMinutes.coerceIn(MIN_RETENTION_MINUTES, MAX_RETENTION_MINUTES) * 60_000L,
            maxItems.coerceIn(1, MAX_ITEMS),
        )
    }
}

/** Decides whether a copied item may enter the history. The only place where this is decided. */
object ClipboardCapture {
    /** Longest text that is stored. A longer clip is not stored at all (bounded store size). */
    const val MAX_TEXT_LENGTH = 100_000

    /**
     * @param sensitiveFlag the EXTRA_IS_SENSITIVE flag of the clip, null when the clip does not say
     * @param incognito PrivacyPolicy says nothing is kept in this field (password field, no-learning flag, force-incognito, listed app, locked device)
     * @param storeAvailable false while the encrypted store cannot be opened (device locked)
     */
    fun shouldStore(historyEnabled: Boolean, storeAvailable: Boolean, sensitiveFlag: Boolean?, incognito: Boolean, text: CharSequence?): Boolean {
        if (!historyEnabled || !storeAvailable) return false
        if (sensitiveFlag == true) return false
        if (incognito) return false
        return !text.isNullOrEmpty() && text.length <= MAX_TEXT_LENGTH
    }
}

object ClipboardCodec {
    private const val VERSION = 1

    fun encode(entries: List<ClipboardEntry>): ByteArray = encodeRecords {
        writeInt(VERSION)
        writeInt(entries.size)
        for (e in entries) {
            writeLong(e.id)
            writeLong(e.timestamp)
            writeBoolean(e.pinned)
            writeText(e.text)
        }
    }

    fun decode(data: ByteArray): List<ClipboardEntry> = decodeRecords(data) {
        if (readInt() != VERSION) throw CorruptStoreException("unknown clipboard version")
        List(readCount()) { ClipboardEntry(readLong(), readLong(), readBoolean(), readText()) }
    }
}

/**
 * Clipboard history rules (P10): text only, one entry per distinct text, unpinned entries expire when their age reaches
 * the retention time, at most `maxItems` unpinned entries, pinned entries stay until removed. Entries are kept sorted
 * (pinned first or last as [pinnedFirst] says, newest first). Pure logic: time, limits and storage are injected.
 * Every change calls [persist]. Positions in listener calls refer to the sorted list.
 */
class ClipboardHistory(
    initial: List<ClipboardEntry>,
    private val clock: () -> Long,
    private val limits: () -> ClipboardLimits,
    private val pinnedFirst: () -> Boolean = { true },
    private val persist: (List<ClipboardEntry>) -> Unit,
) {
    interface Listener {
        fun onInserted(position: Int)
        fun onRemoved(position: Int, count: Int)
        fun onMoved(oldPosition: Int, newPosition: Int)
    }

    var listener: Listener? = null

    private val list = ArrayList(initial)
    private var evicted = false
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    init { sort() }

    val entries: List<ClipboardEntry> get() = list
    val size get() = list.size
    operator fun get(index: Int) = list[index]
    fun byId(id: Long) = list.first { it.id == id }

    fun sort() = list.sortWith(comparator())

    /**
     * Screen lock: forgets every entry in memory (the listener is told so a visible list empties) without touching the stored
     * copy. From then on this object takes no text and changes nothing; the unlock opens a new history from the store.
     */
    fun evict() {
        if (evicted) return
        evicted = true
        val count = list.size
        list.clear()
        if (count > 0) listener?.onRemoved(0, count)
    }

    private fun comparator() = Comparator<ClipboardEntry> { a, b ->
        val byPin = b.pinned.compareTo(a.pinned).let { if (pinnedFirst()) it else -it }
        if (byPin != 0) byPin else b.timestamp.compareTo(a.timestamp)
    }

    /** True when [entry] has reached the retention time. An age equal to the retention time counts as expired. */
    private fun isExpired(entry: ClipboardEntry, now: Long) = !entry.pinned && now - entry.timestamp >= limits().retentionMillis

    /** Time at which the next unpinned entry expires, or null when none can expire. */
    fun nextExpiry(): Long? = list.filter { !it.pinned }.minOfOrNull { it.timestamp + limits().retentionMillis }

    /** Adds a copied text. The copy time is capped at now, so a wrong timestamp in the future cannot extend retention. */
    fun add(text: String, timestamp: Long) {
        if (evicted || text.isEmpty() || text.length > ClipboardCapture.MAX_TEXT_LENGTH) return
        val now = clock()
        val time = timestamp.coerceAtMost(now)
        expire()
        val existing = list.indexOfFirst { it.text == text }
        if (existing >= 0) {
            if (list[existing].timestamp == time) return
            list[existing].timestamp = time
            reposition(existing)
        } else {
            val entry = ClipboardEntry(nextId++, time, false, text)
            if (isExpired(entry, now)) return // copied long ago: already past retention
            list.add(entry)
            list.sortWith(comparator())
            listener?.onInserted(list.indexOf(entry))
            enforceMaxItems()
        }
        persist(list)
    }

    /** Removes unpinned entries that have reached the retention time. Returns how many were removed. */
    fun expire(): Int {
        val now = clock()
        return removeWhere { isExpired(it, now) }
    }

    fun togglePinned(id: Long) {
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list[index].pinned = !list[index].pinned
        list[index].timestamp = clock() // retention counts from the moment it was unpinned
        reposition(index)
        enforceMaxItems()
        persist(list)
    }

    fun removeAt(index: Int) {
        if (evicted) return
        list.removeAt(index)
        persist(list)
    }

    fun clearNonPinned() {
        removeWhere { !it.pinned }
    }

    fun clearAll() {
        if (list.isEmpty()) return
        val count = list.size
        list.clear()
        listener?.onRemoved(0, count)
        persist(list)
    }

    private fun reposition(index: Int) {
        val entry = list[index]
        list.sortWith(comparator())
        listener?.onMoved(index, list.indexOf(entry))
    }

    private fun enforceMaxItems() {
        val max = limits().maxItems
        val unpinned = list.filter { !it.pinned } // newest first within the sorted list
        val drop = unpinned.drop(max).toSet()
        if (drop.isNotEmpty()) removeWhere { it in drop }
    }

    private fun removeWhere(predicate: (ClipboardEntry) -> Boolean): Int {
        val doomed = list.indices.filter { predicate(list[it]) }
        if (doomed.isEmpty()) return 0
        for (i in doomed.reversed()) { // from the end, so earlier positions stay valid
            list.removeAt(i)
            listener?.onRemoved(i, 1)
        }
        persist(list)
        return doomed.size
    }
}
