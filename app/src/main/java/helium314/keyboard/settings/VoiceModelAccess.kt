// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import helium314.keyboard.latin.voice.VoiceModelSpec
import java.io.InputStream

/**
 * The two Android hand-overs of the voice model setup (P20). The app has no network: "Download model files" gives the pinned URLs to the browser
 * (a plain ACTION_VIEW, no component, no download service of our own), and "Install downloaded model" lets the user pick the downloaded documents with the system
 * picker (a one-time grant for this result only, never persisted; write access so that the downloaded copies can be deleted after the install).
 */
object VoiceModelAccess {
    /** The browser intent for one address: a plain ACTION_VIEW, the system chooses the browser. */
    fun browserIntent(url: String): Intent = DownloadAccess.browserIntent(url)

    /** The browser intent for the one archive of the model (the preferred download, one file). */
    fun archiveIntent(archive: VoiceModelSpec.Archive = VoiceModelSpec.archive): Intent = browserIntent(archive.url)

    /** The browser intent for one model file ("Download this file"). */
    fun downloadIntent(entry: VoiceModelSpec.Entry): Intent = browserIntent(entry.url)

    /** One browser intent per model file, in the order of [VoiceModelSpec.entries]. */
    fun downloadIntents(entries: List<VoiceModelSpec.Entry> = VoiceModelSpec.entries): List<Intent> = entries.map { downloadIntent(it) }

    /** Where the system picker opens: the Download folder of the main storage. */
    val downloadFolder: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

    /** Multi-select document picker that starts in Download and grants one-time read and write access (no persistable permission is taken). */
    class PickDownloads : ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadFolder)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    /** A picked document as an installer source: name and size from the provider, deletion through DocumentsContract. */
    class DocumentSource(private val resolver: ContentResolver, private val uri: Uri, override val name: String, override val size: Long?) : VoiceModelInstaller.Source {
        override fun open(): InputStream? = runCatching { resolver.openInputStream(uri) }.getOrNull()
        override fun delete(): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
    }

    /** The document behind [uri]; a document that tells no name gets "?" (it is never one of the four names). */
    fun source(resolver: ContentResolver, uri: Uri): DocumentSource {
        var name = "?"
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return DocumentSource(resolver, uri, name, size)
    }
}
