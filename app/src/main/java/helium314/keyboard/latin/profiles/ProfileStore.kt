// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.profiles

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/** A saved settings profile. A [damaged] one has no values and cannot be applied. Never logged: the name is user text. */
class Profile(val name: String, val values: Map<String, Any>, val defaults: Set<String>, val damaged: Boolean)

sealed interface ProfileList {
    class Ok(val profiles: List<Profile>) : ProfileList
    /** The stored value cannot be decoded, has an unknown format or is too large: save and apply are refused until "Delete all profiles". */
    object Damaged : ProfileList
    /** The credential-encrypted tier is locked. */
    object Locked : ProfileList
}

enum class ProfileOutcome { DONE, LOCKED, DAMAGED, PROFILE_DAMAGED, INVALID_NAME, DUPLICATE_NAME, FULL, TOO_LARGE, NOT_FOUND, WRITE_FAILED }

/**
 * The saved profiles (P21): ONE value, [KEY], in the credential-encrypted preferences [ce] (so it is part of the encrypted export, wiped by a wipe with the
 * settings scope, empty and read-only while the device or screen is locked). The settings they hold are read from and applied to the device-protected
 * preferences [de], only through [SettingsApply]. [locked] says the credential-encrypted tier is not available; then nothing is read or written.
 * [extraKeys] lists per-screen keys to record too (the floating switch of this screen width).
 *
 * Format 1 (JSON): `{"v":1,"p":[{"n":name,"s":{key:value},"d":[key]}]}`. `s` holds the INCLUDED and PRIVACY values at the time of saving, `d` the INCLUDED
 * keys that were at their default (applying removes them). At most [MAX_PROFILES] profiles, names 1 to [MAX_NAME] characters (trimmed, unique ignoring case),
 * at most [MAX_BYTES] bytes. No timestamps, no app names.
 */
class ProfileStore(
    private val ce: SharedPreferences,
    private val de: SharedPreferences,
    private val locked: () -> Boolean,
    private val extraKeys: () -> Collection<String> = { emptyList() },
) {
    companion object {
        const val KEY = "taen_profiles"
        const val FORMAT = 1
        const val MAX_PROFILES = 10
        const val MAX_NAME = 40
        const val MAX_BYTES = 64 * 1024

        fun nameOf(input: String): String? = input.trim().takeIf { it.length in 1..MAX_NAME && it.none(Char::isISOControl) }
    }

    private class Entry(val name: String, val body: JSONObject)

    /** The stored document, or null when it is damaged. */
    private fun readDocument(): List<Entry>? {
        val raw = ce.getString(KEY, null)
        if (raw.isNullOrEmpty()) return emptyList()
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        try {
            val root = JSONObject(raw)
            if (root.optInt("v", -1) != FORMAT) return null
            val array = root.optJSONArray("p") ?: return null
            if (array.length() > MAX_PROFILES) return null
            val entries = ArrayList<Entry>()
            val seen = HashSet<String>()
            for (i in 0 until array.length()) {
                val body = array.optJSONObject(i) ?: return null
                val name = body.optString("n", "")
                if (nameOf(name) != name || !seen.add(name.lowercase(Locale.ROOT))) return null
                entries.add(Entry(name, body))
            }
            return entries
        } catch (_: JSONException) {
            return null
        }
    }

    private fun parse(entry: Entry): Profile {
        val damaged = Profile(entry.name, emptyMap(), emptySet(), true)
        val s = entry.body.optJSONObject("s") ?: return damaged
        val d = entry.body.optJSONArray("d") ?: JSONArray()
        val values = LinkedHashMap<String, Any>()
        for (key in s.keys()) {
            if (ProfileKeys.classOf(key) == KeyClass.EXCLUDED) return damaged
            values[key] = ProfileKeys.validate(key, s.opt(key)) ?: return damaged
        }
        val defaults = LinkedHashSet<String>()
        for (i in 0 until d.length()) {
            val key = d.optString(i, "")
            if (ProfileKeys.classOf(key) != KeyClass.INCLUDED || key in values) return damaged
            defaults.add(key)
        }
        return Profile(entry.name, values, defaults, false)
    }

    private fun write(entries: List<Entry>): ProfileOutcome {
        val array = JSONArray()
        entries.forEach { array.put(it.body) }
        val text = JSONObject().put("v", FORMAT).put("p", array).toString()
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return ProfileOutcome.TOO_LARGE
        return if (ce.edit().putString(KEY, text).commit()) ProfileOutcome.DONE else ProfileOutcome.WRITE_FAILED
    }

    fun list(): ProfileList {
        if (locked()) return ProfileList.Locked
        val entries = readDocument() ?: return ProfileList.Damaged
        return ProfileList.Ok(entries.map(::parse))
    }

    private inline fun editing(block: (List<Entry>) -> ProfileOutcome): ProfileOutcome {
        if (locked()) return ProfileOutcome.LOCKED
        val entries = readDocument() ?: return ProfileOutcome.DAMAGED
        return block(entries)
    }

    private fun List<Entry>.find(name: String) = firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Saves the current INCLUDED and PRIVACY settings of [de] as a new profile [name]. */
    fun saveCurrent(name: String): ProfileOutcome = editing { entries ->
        val clean = nameOf(name) ?: return@editing ProfileOutcome.INVALID_NAME
        if (entries.find(clean) != null) return@editing ProfileOutcome.DUPLICATE_NAME
        if (entries.size >= MAX_PROFILES) return@editing ProfileOutcome.FULL
        val current = de.all
        val values = JSONObject()
        val defaults = JSONArray()
        val keys = LinkedHashSet<String>(ProfileKeys.savedKeys)
        keys.addAll(extraKeys())
        current.keys.filterTo(keys) { ProfileKeys.classOf(it) == KeyClass.INCLUDED }
        for (key in keys) {
            if (ProfileKeys.classOf(key) != KeyClass.INCLUDED) continue
            val value = ProfileKeys.validate(key, current[key])
            if (value != null) values.put(key, value) else defaults.put(key) // not set, or not a valid value: applying resets it
        }
        for (key in ProfileKeys.privacyKeys) values.put(key, ProfileKeys.validate(key, current[key]) ?: ProfileKeys.privacyDefault(key))
        val body = JSONObject().put("n", clean).put("s", values).put("d", defaults)
        write(entries + Entry(clean, body))
    }

    /** What applying [name] would change in the settings, for the list shown before it is applied; null when it cannot be applied. */
    fun plan(name: String): List<SettingChange>? {
        if (locked()) return null
        val entry = (readDocument() ?: return null).find(name) ?: return null
        val profile = parse(entry)
        if (profile.damaged) return null
        return SettingsApply.plan(de, profile.values, profile.defaults)
    }

    /** Applies [name] to the settings in one commit; privacy keys only become stricter. Nothing is written to [de] unless every check passed. */
    fun apply(name: String): ProfileOutcome = editing { entries ->
        val entry = entries.find(name) ?: return@editing ProfileOutcome.NOT_FOUND
        val profile = parse(entry)
        if (profile.damaged) return@editing ProfileOutcome.PROFILE_DAMAGED
        if (SettingsApply.commit(de, SettingsApply.plan(de, profile.values, profile.defaults))) ProfileOutcome.DONE else ProfileOutcome.WRITE_FAILED
    }

    fun rename(old: String, new: String): ProfileOutcome = editing { entries ->
        val entry = entries.find(old) ?: return@editing ProfileOutcome.NOT_FOUND
        val clean = nameOf(new) ?: return@editing ProfileOutcome.INVALID_NAME
        if (entries.any { it !== entry && it.name.equals(clean, ignoreCase = true) }) return@editing ProfileOutcome.DUPLICATE_NAME
        write(entries.map { if (it === entry) Entry(clean, JSONObject(it.body.toString()).put("n", clean)) else it })
    }

    fun delete(name: String): ProfileOutcome = editing { entries ->
        val entry = entries.find(name) ?: return@editing ProfileOutcome.NOT_FOUND
        write(entries.filter { it !== entry })
    }

    /** Removes the stored value, also when it is damaged (the way out of a damaged list). */
    fun deleteAll(): ProfileOutcome {
        if (locked()) return ProfileOutcome.LOCKED
        return if (ce.edit().remove(KEY).commit()) ProfileOutcome.DONE else ProfileOutcome.WRITE_FAILED
    }
}
