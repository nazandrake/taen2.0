// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The one content provider of the app: it hands one picture to the app that received it through `commitContent`. exported=false with grantUriPermissions,
 * so only an app that was given the URI (by the keyboard, for that one URI, read only) can open it. The path is `/m/<token>` ([MediaTokens]); an unknown,
 * expired or revoked token is a [FileNotFoundException]. The bytes go to the reader through a reliable pipe, never through a file: nothing in the clear is written.
 * `query` answers the display name `taen.<ext>` and the size only; `getType` the picture type.
 */
class MediaShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException()
        val token = tokenOf(uri) ?: throw FileNotFoundException()
        val bytes = MediaTokens.bytes(token) ?: throw FileNotFoundException()
        val pipe = try {
            ParcelFileDescriptor.createReliablePipe()
        } catch (_: IOException) {
            bytes.fill(0)
            throw FileNotFoundException()
        }
        writer.execute {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(bytes) }
            } catch (_: IOException) {
                runCatching { pipe[1].closeWithError("write failed") } // the reader has gone, or the write failed: the reader sees an error, not a short picture
            } finally {
                bytes.fill(0)
            }
        }
        return pipe[0]
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val grant = tokenOf(uri)?.let { MediaTokens.lookup(it) } ?: return null
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) "taen.${grant.format.ext}" else grant.size })
        return cursor
    }

    override fun getType(uri: Uri): String? = tokenOf(uri)?.let { MediaTokens.lookup(it) }?.format?.mime

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException()

    companion object {
        /** Runs the write into the pipe, one thread per read. Test seam: the host test pipe is a file, so a test lets the write finish before the read starts. */
        @Volatile internal var writer: Executor = Executors.newCachedThreadPool { r -> Thread(r, "taen-media-pipe").apply { isDaemon = true } }
        private val TOKEN = Regex("[0-9a-f]{32}")

        fun authority(packageName: String) = "$packageName.mediashare"

        fun uriFor(packageName: String, token: String): Uri = Uri.Builder().scheme("content").authority(authority(packageName)).appendPath("m").appendPath(token).build()

        /** The token of a `/m/<token>` URI, or null for any other path. */
        fun tokenOf(uri: Uri): String? {
            val segments = uri.pathSegments
            if (segments.size != 2 || segments[0] != "m") return null
            return segments[1].takeIf { TOKEN.matches(it) }
        }
    }
}
