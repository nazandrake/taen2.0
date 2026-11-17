// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.profiles

import android.content.SharedPreferences
import android.content.res.AssetManager
import helium314.keyboard.latin.privacy.readAtMost
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream

/** A shipped template: public, identical on every install. [titleRes] and [summaryRes] are the names of string resources (en, bg). */
class Template(val id: String, val version: Int, val titleRes: String, val summaryRes: String, val values: Map<String, Any>)

sealed interface TemplateEntry {
    class Ok(val template: Template) : TemplateEntry
    /** The asset is damaged (bad JSON, unknown format, unknown key, value out of range): nothing of it is used. */
    class Unavailable(val id: String) : TemplateEntry
}

/**
 * The templates of the app (P21), read from assets/templates/<id>.json and nothing else reads them. Format 1:
 * `{"format":1,"id":id,"version":n,"title":res name,"summary":res name,"values":{key:value}}`. A template is all or nothing: one bad key or value
 * makes the whole file [TemplateEntry.Unavailable]. Only INCLUDED and PRIVACY keys are allowed, each with a value its validator accepts.
 * [names] lists the asset file names, [open] opens one; both are given so that tests can use fixtures.
 */
class TemplateCatalog(private val names: () -> List<String>, private val open: (String) -> InputStream) {
    companion object {
        const val MAX_BYTES = 16 * 1024
        private val resName = Regex("[a-z0-9_]{1,64}")

        fun fromAssets(assets: AssetManager) = TemplateCatalog(
            { assets.list("templates")?.toList().orEmpty() },
            { assets.open("templates/$it") },
        )
    }

    /** Every template file, sorted by id; a damaged one is listed as unavailable. */
    fun load(): List<TemplateEntry> = names().filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted().map { id ->
        parse(id)?.let { TemplateEntry.Ok(it) } ?: TemplateEntry.Unavailable(id)
    }

    private fun parse(id: String): Template? = try {
        val bytes = open("$id.json").use { it.readAtMost(MAX_BYTES) }
        if (bytes.size > MAX_BYTES) null else build(id, JSONObject(String(bytes, Charsets.UTF_8)))
    } catch (_: JSONException) {
        null
    } catch (_: java.io.IOException) {
        null
    }

    private fun build(id: String, json: JSONObject): Template? {
        if (json.optInt("format", -1) != 1 || json.optString("id", "") != id) return null
        val version = json.optInt("version", 0).takeIf { it >= 1 } ?: return null
        val title = json.optString("title", "").takeIf { resName.matches(it) } ?: return null
        val summary = json.optString("summary", "").takeIf { resName.matches(it) } ?: return null
        val raw = json.optJSONObject("values") ?: return null
        if (raw.length() == 0) return null
        val values = LinkedHashMap<String, Any>()
        for (key in raw.keys()) {
            if (ProfileKeys.classOf(key) == KeyClass.EXCLUDED) return null
            values[key] = ProfileKeys.validate(key, raw.opt(key)) ?: return null
        }
        return Template(id, version, title, summary, values)
    }
}

/** Applying a template: the same writer as a profile ([SettingsApply]), the template's keys only, nothing recorded about which template it was. */
object TemplateApply {
    /** The changes [template] would make (for the list shown before). */
    fun plan(template: Template, de: SharedPreferences): List<SettingChange> = SettingsApply.plan(de, template.values)

    fun apply(template: Template, de: SharedPreferences): ProfileOutcome =
        if (SettingsApply.commit(de, plan(template, de))) ProfileOutcome.DONE else ProfileOutcome.WRITE_FAILED
}
