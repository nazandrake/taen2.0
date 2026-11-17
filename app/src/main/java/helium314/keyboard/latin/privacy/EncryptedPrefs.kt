// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.SharedPreferences
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executor

/**
 * [SharedPreferences] whose only copy on disk is an encrypted [SecureStore]. It replaces the
 * platform implementation at the single accessor (`Context.prefs()` and `Context.protectedPrefs()`), so nothing else in
 * the app changes. The whole map is kept in memory; every change writes a new encrypted snapshot.
 *
 * [store] returns null while the store cannot be opened (device locked). Then the preferences are empty and read-only:
 * reads return the defaults and edits are dropped (commit returns false), never written anywhere else. The store is
 * opened again on the next access. [executor] runs the writes of apply(); [notify] delivers change callbacks.
 */
class EncryptedPrefs(
    private val store: () -> SecureStore?,
    private val executor: Executor,
    private val notify: (Runnable) -> Unit,
) : SharedPreferences {
    private var map: HashMap<String, Any>? = null
    private var openedStore: SecureStore? = null
    private val listeners = Collections.synchronizedMap(WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Any>())

    // Every change to the in-memory map gets a number. A snapshot is written only if no newer one was written before, so a late
    // run of an older apply() can never replace what [flushAndEvict] already wrote.
    private var changeCount = 0L // guarded by this
    private var writtenCount = 0L // guarded by writeLock
    private val writeLock = Any()

    /** Forgets the contents (screen lock): the next access opens the store again, or sees it unavailable and reads defaults. */
    @Synchronized
    fun evict() {
        map = null
        openedStore = null
    }

    /**
     * Screen lock: writes the memory state first when an apply() has not reached the disk yet, then forgets it (like [evict]).
     * Returns false when that write failed, so the lock can report that an edit was lost; the memory is wiped either way.
     */
    @Synchronized
    fun flushAndEvict(): Boolean {
        val s = openedStore
        val m = map
        val ok = if (m != null && s != null && changeCount > synchronized(writeLock) { writtenCount }) write(snapshot(), s, changeCount) else true
        evict()
        return ok
    }

    /** True when the store has been opened and edits are possible. */
    @Synchronized
    fun isAvailable() = ensureLoaded()

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (map != null) return true
        val s = store() ?: return false
        val loaded = when (val content = s.read()) {
            is StoreContent.Missing, is StoreContent.Reset -> HashMap<String, Any>()
            is StoreContent.Unavailable -> return false
            is StoreContent.Data -> try {
                PrefsCodec.decode(content.bytes)
            } catch (_: CorruptStoreException) {
                s.delete() // authenticated but malformed: start empty, never keep partial data
                HashMap()
            }
        }
        map = HashMap(loaded)
        openedStore = s
        return true
    }

    private fun snapshot(): Map<String, Any> = map!!.mapValues { (_, v) -> if (v is Set<*>) HashSet(v) else v }

    @Synchronized
    private fun <T> read(key: String, default: T, cast: (Any) -> T): T {
        if (!ensureLoaded()) return default
        val value = map!![key] ?: return default
        return cast(value)
    }

    override fun getAll(): MutableMap<String, *> = synchronized(this) {
        if (!ensureLoaded()) HashMap<String, Any>() else HashMap(snapshot())
    }

    override fun getString(key: String, defValue: String?): String? = read(key, defValue) { it as String }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        read(key, defValues) { HashSet(it as Set<String>) }

    override fun getInt(key: String, defValue: Int): Int = read(key, defValue) { it as Int }
    override fun getLong(key: String, defValue: Long): Long = read(key, defValue) { it as Long }
    override fun getFloat(key: String, defValue: Float): Float = read(key, defValue) { it as Float }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = read(key, defValue) { it as Boolean }

    @Synchronized
    override fun contains(key: String): Boolean = ensureLoaded() && map!!.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners[listener] = this
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    private fun write(snapshot: Map<String, Any>, s: SecureStore, count: Long): Boolean = synchronized(writeLock) {
        if (count < writtenCount) return true // a newer snapshot is already on disk
        try {
            s.write(PrefsCodec.encode(snapshot))
            writtenCount = count
            true
        } catch (_: StoreWriteException) {
            false // the memory state stays, the next edit writes a complete snapshot again
        } catch (_: VaultLockedException) {
            false
        }
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>() // null value = remove
        private var clear = false

        override fun putString(key: String, value: String?) = also { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = also { changes[key] = values?.let { HashSet(it) } }
        override fun putInt(key: String, value: Int) = also { changes[key] = value }
        override fun putLong(key: String, value: Long) = also { changes[key] = value }
        override fun putFloat(key: String, value: Float) = also { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = also { changes[key] = value }
        override fun remove(key: String) = also { changes[key] = null }
        override fun clear() = also { clear = true }

        override fun apply() {
            val (changed, cleared, snap, s, count) = commitToMemory() ?: return
            notifyListeners(changed, cleared)
            executor.execute { write(snap, s, count) }
        }

        override fun commit(): Boolean {
            val (changed, cleared, snap, s, count) = commitToMemory() ?: return false
            val ok = write(snap, s, count)
            notifyListeners(changed, cleared)
            return ok
        }

        private fun commitToMemory(): Committed? = synchronized(this@EncryptedPrefs) {
            if (!ensureLoaded()) return null
            val current = map!!
            var cleared = false
            val changed = ArrayList<String>()
            if (clear) {
                if (current.isNotEmpty()) current.clear()
                cleared = true
            }
            for ((key, value) in changes) {
                if (value == null) {
                    if (current.remove(key) != null) changed.add(key)
                } else {
                    if (current[key] == value) continue
                    current[key] = value
                    changed.add(key)
                }
            }
            changes.clear()
            clear = false
            changeCount++
            Committed(changed, cleared, snapshot(), openedStore!!, changeCount)
        }

        private fun notifyListeners(changed: List<String>, cleared: Boolean) {
            if (!cleared && changed.isEmpty()) return
            val targets = synchronized(listeners) { listeners.keys.toList() }
            if (targets.isEmpty()) return
            notify(Runnable {
                if (cleared) targets.forEach { it.onSharedPreferenceChanged(this@EncryptedPrefs, null) }
                for (key in changed) targets.forEach { it.onSharedPreferenceChanged(this@EncryptedPrefs, key) }
            })
        }
    }
}

private data class Committed(val changed: List<String>, val cleared: Boolean, val snapshot: Map<String, Any>, val store: SecureStore, val count: Long)
