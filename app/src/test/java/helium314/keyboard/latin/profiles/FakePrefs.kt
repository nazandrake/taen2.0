// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.profiles

import android.content.SharedPreferences

/**
 * In-memory SharedPreferences that records every commit (the keys it changed) and can be told to fail the next commits: a failing commit
 * leaves the map as it was, like a store that refused the write. apply() is not supported (the profile code must use one commit()).
 */
class FakePrefs(initial: Map<String, Any> = emptyMap()) : SharedPreferences {
    val map = LinkedHashMap<String, Any>(initial)
    val commits = ArrayList<Map<String, Any?>>() // one entry per successful commit: key -> new value (null = removed)
    var failCommits = 0 // the next N commits fail and change nothing
    var applyCalls = 0

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?): String? = map[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as Boolean? ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = also { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = also { changes[key] = values }
        override fun putInt(key: String, value: Int) = also { changes[key] = value }
        override fun putLong(key: String, value: Long) = also { changes[key] = value }
        override fun putFloat(key: String, value: Float) = also { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = also { changes[key] = value }
        override fun remove(key: String) = also { changes[key] = null }
        override fun clear() = also { clear = true }
        override fun apply() { applyCalls++; commit() }
        override fun commit(): Boolean {
            if (failCommits > 0) { failCommits--; return false }
            if (clear) map.clear()
            for ((k, v) in changes) if (v == null) map.remove(k) else map[k] = v
            commits.add(LinkedHashMap(changes))
            return true
        }
    }
}
