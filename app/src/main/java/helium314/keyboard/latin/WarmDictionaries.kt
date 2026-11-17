// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.dictionary.Dictionary
import java.util.Locale

/**
 * D15: the main dictionaries of the other enabled languages, loaded in the background after the current language, so that the first
 * switch to Bulgarian (or back) finds its dictionary ready instead of reading it and building the glide word list while the user types. Public data only
 * (main dictionaries, no learned or personal words). The facilitator takes a ready dictionary with [take], which hands its ownership over; what is not
 * taken is closed by [clear] (new dictionary files, a setting that changes the dictionary, the end of the service).
 */
class WarmDictionaries(private val maxEntries: Int = MAX_ENTRIES) {
    private val ready = LinkedHashMap<Locale, Dictionary>()
    private var generation = 0

    /** The warm dictionary of [locale] (now owned by the caller), or null. */
    @Synchronized
    fun take(locale: Locale): Dictionary? = ready.remove(locale)

    @Synchronized
    fun locales(): List<Locale> = ready.keys.toList()

    /**
     * Loads, with [load] (slow, called outside the lock), the [wanted] locales that are not in [inUse] and not warm yet, up to the limit. A
     * dictionary that arrives after [clear] or after the same locale was warmed meanwhile is closed. [onReady] sees each stored dictionary (glide word list).
     */
    fun warm(wanted: Collection<Locale>, inUse: Collection<Locale>, load: (Locale) -> Dictionary, onReady: (Dictionary) -> Unit = {}) {
        val start = synchronized(this) { generation }
        for (locale in wanted.distinct()) {
            val skip = synchronized(this) { locale in inUse || locale in ready || ready.size >= maxEntries || generation != start }
            if (skip) continue
            val dictionary = load(locale)
            val keep = synchronized(this) {
                (generation == start && locale !in ready && ready.size < maxEntries).also { if (it) ready[locale] = dictionary }
            }
            if (keep) onReady(dictionary) else dictionary.close()
        }
    }

    /** Closes every warm dictionary and makes loads that are still running discard their result. */
    fun clear() {
        val old = synchronized(this) {
            generation++
            ready.values.toList().also { ready.clear() }
        }
        old.forEach { it.close() }
    }

    companion object {
        const val MAX_ENTRIES = 3
    }
}
