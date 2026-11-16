// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.SharedPreferences

/**
 * "Remember language per app": the language (subtype preference string) the keyboard last used in each app, kept in ONE value of the credential-encrypted
 * preferences (empty and read-only while the device or the screen is locked, wiped with them by the self-destruct and by a forget, part of the encrypted
 * export like the per-app incognito list). The newest [MAX_APPS] apps are kept, the oldest is dropped. This class only holds the map; whether the keyboard
 * may record in the current field (not locked, no password field, no incognito app) is decided by PrivacyPolicy through Settings.saveSubtypeForApp.
 *
 * Format: one line per app, `package TAB subtype`, oldest first; a malformed line is ignored. The earlier format (one plain-named key per app in the
 * device-protected preferences, written even in password fields and without a bound) is settled at the start of the app and at every unlock by [settleLegacy]: deleted always, moved here only for apps that may be remembered.
 * The list is cleared by [clear] (the setting turned off, the "Clear app language list" action) and loses an app when it is put on the incognito list ([removeApps]).
 */
object AppLanguageMemory {
    const val KEY = "app_languages"
    const val MAX_APPS = 200
    private const val MAX_NAME = 255
    private const val MAX_VALUE = 512

    private fun valid(packageName: String, subtype: String) =
        packageName.isNotEmpty() && packageName.length <= MAX_NAME && subtype.isNotEmpty() && subtype.length <= MAX_VALUE &&
            packageName.none { it == '\t' || it == '\n' || it == '\r' } && subtype.none { it == '\t' || it == '\n' || it == '\r' }

    /** The entries of [raw], oldest first, one per package (the last line of a package wins), at most [MAX_APPS]. */
    fun parse(raw: String?): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (raw.isNullOrEmpty()) return map
        for (line in raw.split('\n')) {
            val parts = line.split('\t')
            if (parts.size != 2 || !valid(parts[0], parts[1])) continue
            map.remove(parts[0]) // moves a repeated package to the newest place
            map[parts[0]] = parts[1]
        }
        while (map.size > MAX_APPS) map.remove(map.keys.first())
        return map
    }

    fun format(map: Map<String, String>): String = map.entries.joinToString("\n") { it.key + "\t" + it.value }

    /** The language remembered for [packageName], or null. */
    fun recall(prefs: SharedPreferences, packageName: String): String? = parse(prefs.getString(KEY, null))[packageName]

    /** Remembers [subtype] for [packageName] as the newest entry; false when the values are not storable (the map is left as it was). */
    fun remember(prefs: SharedPreferences, packageName: String, subtype: String): Boolean {
        if (!valid(packageName, subtype)) return false
        val map = parse(prefs.getString(KEY, null))
        if (map[packageName] == subtype && map.keys.last() == packageName) return true // nothing to write
        map.remove(packageName)
        map[packageName] = subtype
        while (map.size > MAX_APPS) map.remove(map.keys.first())
        prefs.edit().putString(KEY, format(map)).apply()
        return true
    }

    /** A subtype was renamed (an additional subtype was edited): every app that had [from] gets [to]. */
    fun replace(prefs: SharedPreferences, from: String, to: String) {
        val map = parse(prefs.getString(KEY, null))
        if (map.none { it.value == from } || !valid("x", to)) return
        for (e in map.entries) if (e.value == from) e.setValue(to)
        prefs.edit().putString(KEY, format(map)).apply()
    }

    /** The key prefix of the earlier format. */
    const val LEGACY_PREFIX = "saved_app_subtype_"

    /**
     * Settles the entries of the earlier format in [legacy] (device-protected preferences, readable before the first unlock) once the credential-encrypted tier
     * is open: they are ALWAYS deleted from [legacy], and moved into [memory] only when the per-app setting is [enabled] and the app is not on the [incognito]
     * list (the earlier version recorded in password fields and for apps that are incognito now). Nothing is deleted unless what is to be kept was stored:
     * false, and the entries stay, when [memory] cannot be written. Call only while the credential-encrypted tier is open (not locked); true when [legacy]
     * holds no entry any more.
     */
    fun settleLegacy(legacy: SharedPreferences, memory: SharedPreferences, enabled: Boolean, incognito: Set<String>): Boolean {
        val old = legacy.all.filter { it.key.startsWith(LEGACY_PREFIX) && it.value is String }
        if (enabled && old.isNotEmpty()) {
            val map = parse(memory.getString(KEY, null))
            var changed = false
            for ((key, value) in old) {
                val pkg = key.removePrefix(LEGACY_PREFIX)
                if (pkg !in incognito && pkg !in map && valid(pkg, value as String)) { map[pkg] = value; changed = true } // a newer entry of the new format wins
            }
            while (map.size > MAX_APPS) map.remove(map.keys.first())
            if (changed && !memory.edit().putString(KEY, format(map)).commit()) return false
        }
        clearLegacy(legacy)
        return true
    }

    /** Forgets the whole list (the setting is turned off, or the user asks for it). */
    fun clear(memory: SharedPreferences) {
        memory.edit().remove(KEY).apply()
    }

    /** Removes every entry of the earlier format (including values that are not strings) from [legacy]; other keys stay. */
    fun clearLegacy(legacy: SharedPreferences) {
        val keys = legacy.all.keys.filter { it.startsWith(LEGACY_PREFIX) }
        if (keys.isEmpty()) return
        val editor = legacy.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }

    /** Forgets the languages of [apps] (they were put on the incognito list). */
    fun removeApps(memory: SharedPreferences, apps: Set<String>) {
        if (apps.isEmpty()) return
        val map = parse(memory.getString(KEY, null))
        if (apps.none { it in map }) return
        apps.forEach { map.remove(it) }
        memory.edit().putString(KEY, format(map)).apply()
    }
}
