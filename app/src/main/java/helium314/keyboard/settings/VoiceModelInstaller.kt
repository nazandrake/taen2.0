// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.voice.VoiceModelSpec
import helium314.keyboard.latin.voice.VoiceModelStore
import java.io.InputStream

/**
 * "Install downloaded model" (P20, 1.0.2). The user picks what was downloaded: the one archive of the model, and/or any of the four model files, in one
 * round or several, under any name (the content decides: size first, then the SHA-256 pinned in [VoiceModelSpec]). Every document is handled on its own:
 * a good file stays installed when another one is wrong, so the model can be completed in several rounds. The model is complete (and usable) as soon as all
 * four files are installed; [Report.complete] says so. The downloaded copies that were installed are deleted afterwards when [install] is asked to.
 */
class VoiceModelInstaller(
    private val store: VoiceModelStore,
    private val entries: List<VoiceModelSpec.Entry> = VoiceModelSpec.entries,
    private val archive: VoiceModelSpec.Archive? = VoiceModelSpec.archive,
    private val decompress: (InputStream) -> InputStream = { Bzip2InputStream(it) },
) {
    /** One picked document. [size] is null when the provider does not tell it. */
    interface Source {
        val name: String
        val size: Long?
        fun open(): InputStream?
        fun delete(): Boolean
    }

    enum class Kind { FILE_INSTALLED, ARCHIVE_INSTALLED, NOT_A_MODEL_FILE, NOT_THE_ARCHIVE, BAD_ARCHIVE, ENTRY_REJECTED, NO_SPACE, FAILED, CANCELLED }

    /** What happened to one document: [detail] is the model file for FILE_INSTALLED and ENTRY_REJECTED, the reason for BAD_ARCHIVE. */
    class Outcome(val source: String, val kind: Kind, val detail: String? = null)

    /** [installed]: the model files this run installed; [missing]: the model files still absent afterwards; [notDeleted]: installed copies that could not be deleted. */
    class Report(val outcomes: List<Outcome>, val installed: List<String>, val missing: List<String>, val notDeleted: List<String>) {
        val complete: Boolean get() = missing.isEmpty()
    }

    fun install(sources: List<Source>, deleteDownloaded: Boolean, control: InstallControl = InstallControl()): Report {
        val outcomes = ArrayList<Outcome>()
        val installed = ArrayList<String>()
        val notDeleted = ArrayList<String>()
        for (source in sources) {
            if (control.cancelled) { outcomes.add(Outcome(source.name, Kind.CANCELLED)); break }
            val outcome = installOne(source, control)
            outcomes.add(outcome)
            when (outcome.kind) {
                Kind.FILE_INSTALLED -> installed.add(outcome.detail!!)
                Kind.ARCHIVE_INSTALLED -> installed.addAll(entries.map { it.name })
                else -> Unit
            }
            if (deleteDownloaded && (outcome.kind == Kind.FILE_INSTALLED || outcome.kind == Kind.ARCHIVE_INSTALLED) && !source.delete()) notDeleted.add(source.name)
            if (outcome.kind == Kind.CANCELLED) break
        }
        return Report(outcomes, installed, store.missing().map { it.name }, notDeleted)
    }

    private fun installOne(source: Source, control: InstallControl): Outcome {
        val size = source.size
        val isArchive = archive != null && size == archive.size
        val isFile = size == null || entries.any { it.size == size }
        if (!isArchive && !isFile) return Outcome(source.name, Kind.NOT_A_MODEL_FILE) // no entry has this size: nothing is read
        if (isArchive) return installArchive(source, control)
        val result = installFile(source, size, control)
        if (result.kind == Kind.NOT_A_MODEL_FILE && size == null && archive != null) { // the provider told no size: it may still be the archive
            val asArchive = installArchive(source, control)
            return if (asArchive.kind == Kind.NOT_THE_ARCHIVE) result else asArchive
        }
        return result
    }

    private fun installFile(source: Source, size: Long?, control: InstallControl): Outcome {
        control.step(source.name, if (size != null) size * 2 else 0) // two passes: hash, then copy
        val result = store.importFile(control.hooks) { source.open()?.also { control.reading(it) } }
        control.reading(null)
        return when {
            control.cancelled -> Outcome(source.name, Kind.CANCELLED)
            result is VoiceModelStore.ImportResult.Imported -> Outcome(source.name, Kind.FILE_INSTALLED, result.name)
            result is VoiceModelStore.ImportResult.NotAModelFile -> Outcome(source.name, Kind.NOT_A_MODEL_FILE)
            result is VoiceModelStore.ImportResult.NoSpace -> Outcome(source.name, Kind.NO_SPACE)
            else -> Outcome(source.name, Kind.FAILED)
        }
    }

    private fun installArchive(source: Source, control: InstallControl): Outcome {
        val result = VoiceArchiveInstaller(store, archive!!, entries, decompress).install({ source.open() }, control)
        return when (result) {
            is VoiceArchiveInstaller.Result.Installed -> Outcome(source.name, Kind.ARCHIVE_INSTALLED)
            VoiceArchiveInstaller.Result.NotThisArchive -> Outcome(source.name, Kind.NOT_THE_ARCHIVE)
            is VoiceArchiveInstaller.Result.BadArchive -> Outcome(source.name, Kind.BAD_ARCHIVE, result.reason)
            is VoiceArchiveInstaller.Result.EntryRejected -> Outcome(source.name, Kind.ENTRY_REJECTED, result.name)
            VoiceArchiveInstaller.Result.NoSpace -> Outcome(source.name, Kind.NO_SPACE)
            VoiceArchiveInstaller.Result.Cancelled -> Outcome(source.name, Kind.CANCELLED)
            VoiceArchiveInstaller.Result.Failed -> Outcome(source.name, if (control.cancelled) Kind.CANCELLED else Kind.FAILED)
        }
    }
}
