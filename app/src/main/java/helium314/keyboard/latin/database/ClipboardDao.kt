// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.Context
import helium314.keyboard.latin.privacy.ClipboardCodec
import helium314.keyboard.latin.privacy.ClipboardEntry
import helium314.keyboard.latin.privacy.ClipboardHistory
import helium314.keyboard.latin.privacy.ClipboardLimits
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.privacy.SecureStore
import helium314.keyboard.latin.privacy.isReadable
import helium314.keyboard.latin.profiles.ProfileKeys
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/**
 * Access to the clipboard history for the keyboard UI. The rules live in [ClipboardHistory]; the entries are stored
 * encrypted (clipboard.enc, credential-encrypted tier), never in a database or plain file.
 */
class ClipboardDao private constructor(private val history: ClipboardHistory) {
    interface Listener {
        fun onClipInserted(position: Int)
        fun onClipsRemoved(position: Int, count: Int)
        fun onClipMoved(oldPosition: Int, newPosition: Int)
    }

    var listener: Listener? = null

    init {
        history.listener = object : ClipboardHistory.Listener {
            override fun onInserted(position: Int) { listener?.onClipInserted(position) }
            override fun onRemoved(position: Int, count: Int) { listener?.onClipsRemoved(position, count) }
            override fun onMoved(oldPosition: Int, newPosition: Int) { listener?.onClipMoved(oldPosition, newPosition) }
        }
    }

    fun addClip(timestamp: Long, text: String) = synchronized(this) { history.add(text, timestamp) }

    fun isPinned(index: Int) = history[index].pinned

    fun getAt(index: Int) = history[index]

    fun get(id: Long) = history.byId(id)

    fun getAll(): List<ClipboardEntry> = history.entries

    fun count() = history.size

    fun sort() = history.sort()

    fun togglePinned(id: Long) = synchronized(this) { history.togglePinned(id) }

    // RecyclerView initiates this, so we don't call the listener (or we'll get an IndexOutOfRangeException from RecyclerView)
    fun deleteClipAt(index: Int) = synchronized(this) {
        val saved = listener
        listener = null
        try { history.removeAt(index) } finally { listener = saved }
    }

    /** Removes entries that reached the retention time. The listener is told, so a visible list stays consistent. */
    fun clearOldClips(): Int = synchronized(this) { history.expire() }

    fun clearNonPinned() = synchronized(this) { history.clearNonPinned() }

    fun clear() = synchronized(this) { history.clearAll() }

    /** Time (ms since epoch) at which the next entry expires, or null. */
    fun nextExpiry(): Long? = synchronized(this) { history.nextExpiry() }

    /** Screen lock: the entries leave memory, also for a caller that still holds this object. */
    private fun evict() = synchronized(this) { history.evict() }

    companion object {
        private const val TAG = "ClipboardDao"
        private var instance: ClipboardDao? = null

        init { Privacy.onLock("clipboard") { evictInstance() } }

        @Synchronized
        private fun evictInstance() {
            instance?.evict()
            instance = null
        }

        /** For tests: the next [getInstance] opens the store again. */
        @Synchronized
        internal fun forgetInstance() { instance = null }

        /** Returns the instance or creates it. Null while the encrypted store cannot be opened (device locked); callers retry later. */
        @Synchronized
        fun getInstance(context: Context): ClipboardDao? {
            if (Privacy.isLocked) { // screen lock: the history is wiped from memory and nothing new is opened
                instance = null
                return null
            }
            instance?.let { return it }
            try {
                val (store, entries) = Privacy.loadClipboard(context) ?: return null
                val app = context.applicationContext ?: context
                instance = ClipboardDao(ClipboardHistory(
                    initial = entries,
                    clock = System::currentTimeMillis,
                    limits = { limitsFrom(app) },
                    pinnedFirst = { Settings.getValues()?.mClipboardHistoryPinnedFirst ?: Defaults.PREF_CLIPBOARD_HISTORY_PINNED_FIRST },
                    persist = { list -> save(store, list) },
                )).also { it.clearOldClips() }
            } catch (e: Throwable) {
                Log.e(TAG, "can't create ClipboardDao")
            }
            return instance
        }

        internal fun limitsFrom(context: Context): ClipboardLimits {
            val prefs = context.prefs()
            // unreadable settings give the defaults, which are looser than a stored choice (5 minutes would become 60): take the strictest values (fail closed)
            if (!prefs.isReadable()) return ClipboardLimits.of(
                ProfileKeys.strictValue(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME) as Int,
                ProfileKeys.strictValue(Settings.PREF_CLIPBOARD_HISTORY_MAX_ITEMS) as Int,
            )
            return ClipboardLimits.of(
                prefs.getInt(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, Defaults.PREF_CLIPBOARD_HISTORY_RETENTION_TIME),
                prefs.getInt(Settings.PREF_CLIPBOARD_HISTORY_MAX_ITEMS, Defaults.PREF_CLIPBOARD_HISTORY_MAX_ITEMS),
            )
        }

        // a failed write keeps the entries in memory only; the next change writes the complete list again
        private fun save(store: SecureStore, list: List<ClipboardEntry>) {
            try {
                store.write(ClipboardCodec.encode(list))
            } catch (e: Exception) {
                Log.w(TAG, "could not save the clipboard history")
            }
        }
    }
}
