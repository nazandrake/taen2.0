// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.settings.dialogs.ImportProgressDialog
import java.io.File
import java.io.IOException

/**
 * Every access to a document that another app provides (a picked file, a link, a save target) from a screen goes through here, so the
 * main thread never waits for the provider: FileUtils runs it on its one worker with a size cap, a time limit and a cancel, and the
 * result comes back here on the main thread. While a job runs the screen shows a progress dialog with Cancel; a job that is still
 * running when the screen goes is given up (no file is left). A second job while one runs is refused (the callback gets a failure).
 */
class ProviderJobs {
    var running by mutableStateOf<FileUtils.ImportHandle?>(null)
        private set

    /** Copies the document to [file] (the caller removes an old one first); [onResult] gets null when the copy is in place. */
    // what the caller of the running job wants done when the job is given up (its callback never runs then)
    private var onGiveUp: (() -> Unit)? = null

    /** Copies the document to [file] (the caller removes an old one first); [onResult] gets null when the copy is in place. */
    fun importFile(uri: Uri, ctx: Context, file: File, onResult: (IOException?) -> Unit) {
        giveUp()
        running = FileUtils.importContentUri(uri, ctx, file) { failure -> running = null; onGiveUp = null; onResult(failure) }
    }

    /**
     * Reads the document into memory (no file), at most [maxBytes]; [onResult] gets the failure, or the bytes and the document's name.
     * [onGiveUp] runs, once and on the main thread, when the job is given up instead (Cancel, the screen leaves, a newer job replaces it):
     * then [onResult] never runs, so it must release what [onResult] would have (a passphrase).
     */
    fun read(uri: Uri, ctx: Context, maxBytes: Long, timeoutMillis: Long = FileUtils.COPY_TIMEOUT_MILLIS, onGiveUp: () -> Unit = {},
             onResult: (IOException?, ByteArray?, String?) -> Unit) {
        giveUp()
        this.onGiveUp = onGiveUp
        running = FileUtils.readContentUri(uri, ctx, maxBytes, timeoutMillis) { failure, data, name ->
            running = null
            this.onGiveUp = null
            onResult(failure, data, name)
        }
    }

    /** Writes [data] to the document the user picked as a save target. */
    fun write(uri: Uri, ctx: Context, data: ByteArray, onResult: (IOException?) -> Unit) {
        giveUp()
        running = FileUtils.writeContentUri(uri, ctx, data, FileUtils.COPY_TIMEOUT_MILLIS) { failure -> running = null; onGiveUp = null; onResult(failure) }
    }

    fun cancel() = giveUp()

    private fun giveUp() {
        val handle = running
        val release = onGiveUp
        running = null
        onGiveUp = null
        handle?.cancel()
        if (handle != null) release?.invoke()
    }
}

/** A [ProviderJobs] for the calling screen: shows the progress dialog while a job runs and gives the job up when the screen leaves the composition. */
@Composable
fun rememberProviderJobs(): ProviderJobs {
    val jobs = remember { ProviderJobs() }
    DisposableEffect(jobs) { onDispose { jobs.cancel() } }
    if (jobs.running != null) ImportProgressDialog(onCancel = jobs::cancel)
    return jobs
}
