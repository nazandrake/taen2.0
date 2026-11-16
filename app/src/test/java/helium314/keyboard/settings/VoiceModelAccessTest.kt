// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.voice.VoiceModelSpec
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Android side of the voice model setup (P20): the browser intents, the picker intent, a picked document, and the absence of network access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceModelAccessTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun downloadOpensEachPinnedUrlInTheBrowserWithAPlainViewIntent() {
        val intents = VoiceModelAccess.downloadIntents()
        assertEquals(VoiceModelSpec.entries.map { it.url }, intents.map { it.data.toString() })
        assertEquals(4, intents.size)
        for (intent in intents) {
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https", intent.data!!.scheme)
            assertEquals("huggingface.co", intent.data!!.host)
            assertNull(intent.component) // the system chooses the browser; no class or package of ours or anyone's is named
            assertNull(intent.`package`)
            assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
        }
        assertEquals(VoiceModelSpec.entries.map { "/${it.name}" }, intents.map { "/" + it.data!!.lastPathSegment })
    }

    @Test fun theArchiveAndEachSingleFileOpenTheirPinnedAddressInTheBrowser() {
        val archive = VoiceModelAccess.archiveIntent()
        assertEquals(VoiceModelSpec.archive.url, archive.data.toString())
        assertEquals("github.com", archive.data!!.host)
        assertEquals(Intent.ACTION_VIEW, archive.action)
        assertNull(archive.component)
        assertNull(archive.`package`)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), archive.categories)
        for (entry in VoiceModelSpec.entries) {
            val one = VoiceModelAccess.downloadIntent(entry)
            assertEquals(entry.url, one.data.toString())
            assertEquals("true", one.data!!.getQueryParameter("download")) // forces a download where the server honours it
            assertEquals(Intent.ACTION_VIEW, one.action)
            assertNull(one.component)
        }
    }

    @Test fun theInstallPickerIsAOneTimeMultiSelectGrantStartingInDownload() {
        val intent = VoiceModelAccess.PickDownloads().createIntent(ctx, arrayOf("*/*"))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertEquals(VoiceModelAccess.downloadFolder, intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java))
        assertEquals("primary:Download", DocumentsContract.getDocumentId(VoiceModelAccess.downloadFolder))
        val flags = intent.flags
        assertTrue(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0, "write access is needed to delete the downloaded copies")
        assertEquals(0, flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, "no permission is kept after the install")
        assertEquals(0, flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }

    /** One document with a name, a size and content; deleteDocument arrives as the provider call that DocumentsContract makes. */
    private class FakeDocuments(val name: String?, val bytes: ByteArray, val size: Long?) : ContentProvider() {
        var deletes = 0
        val file: File = File.createTempFile("taen-doc", ".bin").also { it.deleteOnExit(); it.writeBytes(bytes) }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(columns).also { c -> c.addRow(columns.map<String, Any?> { if (it == OpenableColumns.DISPLAY_NAME) name else size }.toTypedArray()) }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method == "android:deleteDocument") { deletes++; return Bundle() }
            return super.call(method, arg, extras)
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
    }

    private fun register(authority: String, provider: FakeDocuments) {
        provider.attachInfo(ctx, ProviderInfo().apply { this.authority = authority; exported = true; grantUriPermissions = true })
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    @Test fun aPickedDocumentTellsItsNameAndSize() {
        val provider = FakeDocuments("encoder.int8.onnx", "abc".toByteArray(), 3)
        register("taen.test.docs1", provider)
        val source = VoiceModelAccess.source(ctx.contentResolver, Uri.parse("content://taen.test.docs1/document/1"))
        assertEquals("encoder.int8.onnx", source.name)
        assertEquals(3L, source.size)
    }

    @Test fun aDocumentWithoutNameOrSizeGetsAQuestionMarkAndNoSize() {
        val provider = FakeDocuments(null, ByteArray(0), null)
        register("taen.test.docs2", provider)
        val source = VoiceModelAccess.source(ctx.contentResolver, Uri.parse("content://taen.test.docs2/document/1"))
        assertEquals("?", source.name)
        assertNull(source.size)
        assertTrue(VoiceModelSpec.entries.none { it.name == source.name })
    }

    @Test fun aDocumentWhoseProviderIsGoneIsReportedNotThrown() {
        val source = VoiceModelAccess.source(ctx.contentResolver, Uri.parse("content://taen.test.nobody/document/1"))
        assertEquals("?", source.name)
        assertFalse(source.delete()) // no provider answers for this authority: false, no exception
    }

    @Test fun deleteGoesThroughTheDocumentProviderOnce() {
        val provider = FakeDocuments("tokens.txt", "x".toByteArray(), 1)
        register("taen.test.docs3", provider)
        val source = VoiceModelAccess.source(ctx.contentResolver, Uri.parse("content://taen.test.docs3/document/1"))
        assertTrue(source.delete())
        assertEquals(1, provider.deletes)
    }

    @Test fun theMergedManifestStillHasNoNetworkPermission() {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toList() ?: emptyList()
        assertTrue(Manifest.permission.RECORD_AUDIO in requested, "positive control: the manifest is read and lists the microphone permission")
        for (forbidden in listOf("INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_NETWORK_STATE"))
            assertFalse("android.permission.$forbidden" in requested, forbidden)
    }
}
