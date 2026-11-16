// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.voice.VoiceModelStore
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** What the voice page does after "Delete the model": the result of VoiceModelStore.deleteAll() was ignored before. */
data class VoiceModelDeleteOutcome(val showError: Boolean, val reloadFromDisk: Boolean) {
    companion object {
        /** [deleted] is the result of VoiceModelStore.deleteAll(). The file state is read from disk again either way, so the rows show what is really there. */
        fun of(deleted: Boolean): VoiceModelDeleteOutcome = VoiceModelDeleteOutcome(showError = !deleted, reloadFromDisk = true)
    }
}

/**
 * The "Delete the model" action of the voice page: runs deleteAll() on [io], then [showResult] gets [errorText] when it failed (null when it
 * worked) and [reloadRows] makes the page read the files from disk again. VoiceScreen calls only this.
 */
internal suspend fun deleteVoiceModel(
    store: VoiceModelStore, io: CoroutineContext, errorText: String, showResult: (String?) -> Unit, reloadRows: () -> Unit,
) {
    val outcome = VoiceModelDeleteOutcome.of(withContext(io) { store.deleteAll() })
    showResult(if (outcome.showError) errorText else null)
    if (outcome.reloadFromDisk) reloadRows()
}
