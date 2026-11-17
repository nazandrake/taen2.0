// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.profiles

import android.content.SharedPreferences

/** One preference that applying changes: [new] null means the key is removed (back to its default). [old] null means it was not set. */
class SettingChange(val key: String, val old: Any?, val new: Any?)

/**
 * Applies settings values to the device-protected preferences, for profiles and templates (P21). The one place that writes them: INCLUDED keys get
 * their value, PRIVACY keys only ever move towards their strict value (a profile or template can make privacy stricter, never looser), EXCLUDED and
 * unknown keys and invalid values are never written. Everything goes into one commit; a failed commit is put back.
 */
object SettingsApply {
    /** The changes applying [values] (and resetting [resets] to their defaults) would make to [de]. Nothing is written. */
    fun plan(de: SharedPreferences, values: Map<String, Any>, resets: Set<String> = emptySet()): List<SettingChange> {
        val current = de.all
        val changes = ArrayList<SettingChange>()
        for ((key, raw) in values) {
            val value = ProfileKeys.validate(key, raw) ?: continue
            when (ProfileKeys.classOf(key)) {
                KeyClass.INCLUDED -> if (current[key] != value) changes.add(SettingChange(key, current[key], value))
                KeyClass.PRIVACY -> {
                    val effective = current[key] ?: ProfileKeys.privacyDefault(key)
                    val result = ProfileKeys.stricter(key, effective, value)
                    if (result != effective) changes.add(SettingChange(key, current[key], result))
                }
                KeyClass.EXCLUDED -> Unit
            }
        }
        for (key in resets) if (ProfileKeys.classOf(key) == KeyClass.INCLUDED && key !in values && current.containsKey(key)) changes.add(SettingChange(key, current[key], null))
        return changes
    }

    /** Writes [changes] in ONE commit. False when the commit failed: then the old values are written back (best effort) and an error should be shown. */
    fun commit(de: SharedPreferences, changes: List<SettingChange>): Boolean {
        if (changes.isEmpty()) return true
        val editor = de.edit()
        changes.forEach { put(editor, it.key, it.new) }
        if (editor.commit()) return true
        val undo = de.edit()
        changes.forEach { put(undo, it.key, it.old) }
        undo.commit()
        return false
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            else -> throw IllegalArgumentException("unsupported preference type")
        }
    }
}
