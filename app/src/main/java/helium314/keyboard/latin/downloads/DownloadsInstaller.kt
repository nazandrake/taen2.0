// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.downloads

import java.io.File
import java.io.InputStream

/**
 * What the dictionary rows need from the dictionary engine, behind one interface so that the install rules run without the native library (the
 * app implements it with the existing NewDictionaryDialog checks: header, isValidDictionary).
 */
interface DictionaryCheck {
    /** The language tag in the header of the dictionary [file], or null when the file has no readable dictionary header. */
    fun headerLocale(file: File): String?
    /** True when the engine can open [file] as a dictionary of [locale]. */
    fun isValid(file: File, locale: String): Boolean
}

/**
 * "Install downloaded" for the catalogue: every picked document is handled on its own, by content. Pass 1 [PinnedInstaller.identify] finds the row
 * (size and SHA-256, nothing written); pass 2 [PinnedInstaller.install] copies it to the staging directory, verifies it again, and only then, on the
 * verified `.part` file, the dictionary checks run (header locale equals the row's locale, the engine opens it). The file is renamed to the row's target
 * and [onInstalled] runs once (the app sends NEW_DICTIONARY_INTENT_ACTION there). A dictionary of the same language that is not this row's file (one the user
 * imported by hand) is never replaced: that is [Kind.CONFLICT].
 */
class DownloadsInstaller(
    private val entries: List<DownloadEntry>,
    private val installer: PinnedInstaller,
    private val store: DownloadsStore,
    private val targetOf: (DownloadEntry) -> File,
    private val check: DictionaryCheck,
    private val onInstalled: (DownloadEntry) -> Unit,
) {
    interface Source {
        val name: String
        fun open(): InputStream?
        fun delete(): Boolean
    }

    enum class Kind { INSTALLED, NOT_A_DOWNLOAD, WRONG_FILE, REJECTED, CONFLICT, NO_SPACE, FAILED, CANCELLED, BUSY }

    /** What happened to one document; [entry] is the row it was recognised as (null when it is none). */
    class Outcome(val source: String, val kind: Kind, val entry: DownloadEntry? = null)

    class Report(val outcomes: List<Outcome>, val notDeleted: List<String>)

    /** [onEntry] is told which row a document was recognised as, before its install starts (the page shows that row as installing). */
    fun install(sources: List<Source>, deleteDownloaded: Boolean, hooks: PinnedInstaller.Hooks = PinnedInstaller.Hooks.NONE, onEntry: (DownloadEntry) -> Unit = {}): Report {
        val outcomes = ArrayList<Outcome>()
        val notDeleted = ArrayList<String>()
        installer.discardStaged()
        for (source in sources) {
            if (hooks.cancelled()) { outcomes.add(Outcome(source.name, Kind.CANCELLED)); break }
            val outcome = installOne(source, hooks, onEntry)
            outcomes.add(outcome)
            if (deleteDownloaded && outcome.kind == Kind.INSTALLED && !source.delete()) notDeleted.add(source.name)
            if (outcome.kind == Kind.CANCELLED) break
        }
        return Report(outcomes, notDeleted)
    }

    private fun installOne(source: Source, hooks: PinnedInstaller.Hooks, onEntry: (DownloadEntry) -> Unit): Outcome {
        val entry = installer.identify(entries, hooks) { source.open() }
        if (entry == null) return Outcome(source.name, if (hooks.cancelled()) Kind.CANCELLED else Kind.NOT_A_DOWNLOAD)
        onEntry(entry)
        val target = targetOf(entry)
        if (target.exists() && store.diskState(entry) != DownloadsStore.State.VERIFIED && store.diskState(entry) != DownloadsStore.State.DAMAGED)
            return Outcome(source.name, Kind.CONFLICT, entry)
        // a model has no check besides its pinned size and hash; a dictionary must also open as a dictionary of its row's language
        val accept = if (entry.kind != DownloadEntry.DICTIONARY) { _: File -> true }
            else { part: File -> check.headerLocale(part)?.let { sameLanguage(it, entry.locale) } == true && check.isValid(part, entry.locale) }
        val result = installer.install(entry, target, hooks, accept) { source.open() }
        val kind = when (result) {
            PinnedInstaller.Result.Installed -> Kind.INSTALLED
            PinnedInstaller.Result.WrongFile -> Kind.WRONG_FILE
            PinnedInstaller.Result.Rejected -> Kind.REJECTED
            PinnedInstaller.Result.NoSpace -> Kind.NO_SPACE
            PinnedInstaller.Result.Cancelled -> Kind.CANCELLED
            PinnedInstaller.Result.Busy -> Kind.BUSY
            PinnedInstaller.Result.Failed -> Kind.FAILED
        }
        if (kind == Kind.INSTALLED) onInstalled(entry)
        return Outcome(source.name, kind, entry)
    }

    /** "en_US", "en-US" and "en-us" are one language tag; the header writes the underscore form. */
    private fun sameLanguage(header: String, row: String) = header.replace('_', '-').equals(row.replace('_', '-'), ignoreCase = true)
}
