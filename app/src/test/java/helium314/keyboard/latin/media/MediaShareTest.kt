// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import android.content.ClipDescription
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.BaseInputConnection
import android.view.View
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.privacy.Privacy
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Insertion and the provider with its tokens: a field that accepts the type gets one commitContent call with the read grant and a token URI; one that does not
 * gets nothing (and no token exists); the provider gives the stored bytes through a pipe, and an unknown, expired, revoked token is a FileNotFoundException.
 */
@RunWith(RobolectricTestRunner::class)
class MediaShareTest {
    private val pkg = "io.github.nazandrake.taen.test"
    private val bytes = MediaFixtures.gifClean
    private var now = 1_000L
    private var served: ByteArray? = bytes.copyOf()

    @Before fun before() {
        MediaTokens.clock = { now }
        MediaTokens.reader = { served?.copyOf() }
        MediaTokens.revokeAll()
        MediaShareProvider.writer = java.util.concurrent.Executor { it.run() } // the host pipe is a file: finish the write before the read
    }

    @After fun after() {
        MediaTokens.revokeAll()
        MediaTokens.clock = { android.os.SystemClock.elapsedRealtime() }
        MediaTokens.reader = { null }
    }

    private fun provider(): MediaShareProvider = Robolectric.buildContentProvider(MediaShareProvider::class.java).create(MediaShareProvider.authority(pkg)).get()

    private fun uriOf(token: String) = MediaShareProvider.uriFor(pkg, token)
    private fun issue() = MediaTokens.issue(MediaSource.Vault("0123456789abcdef"), MediaFormat.GIF, bytes.size)

    private fun read(p: MediaShareProvider, uri: Uri): ByteArray = ParcelFileDescriptor.AutoCloseInputStream(p.openFile(uri, "r")).use { it.readBytes() }

    /** A field with a recording connection: [commits] holds every commitContent call. */
    private class Recording(view: View) : BaseInputConnection(view, false) {
        val commits = ArrayList<Triple<Uri, String, Int>>()
        var result = true
        override fun commitContent(info: InputContentInfo, flags: Int, opts: android.os.Bundle?): Boolean {
            commits.add(Triple(info.contentUri, info.description.getMimeType(0), flags))
            return result
        }
    }

    private fun connection() = Recording(View(ApplicationProvider.getApplicationContext()))
    private fun field(vararg types: String) = EditorInfo().apply { contentMimeTypes = arrayOf(*types) }

    // ---- insertion and the provider's core ----------------------------------------------------------

    @Test fun aFieldThatAcceptsTheTypeGetsOneCommitWithTheGrantFlagAndATokenUri() {
        val ic = connection()
        val result = MediaInserter.insert(pkg, ic, field("image/gif"), MediaSource.Vault("0123456789abcdef"), MediaFormat.GIF, bytes.size)
        assertEquals(MediaInserter.Result.SENT, result)
        val (uri, mime, flags) = ic.commits.single()
        assertEquals(InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, flags)
        assertEquals("image/gif", mime)
        assertEquals("content", uri.scheme)
        assertEquals(MediaShareProvider.authority(pkg), uri.authority)
        val token = assertNotNull(MediaShareProvider.tokenOf(uri))
        assertEquals(1, MediaTokens.liveCount)
        assertNotNull(MediaTokens.lookup(token))
    }

    @Test fun aFieldThatDoesNotAcceptTheTypeGetsNothingAndNoTokenExists() {
        val ic = connection()
        val accepted = MediaInserter.insert(pkg, ic, field("image/gif"), MediaSource.Vault("0123456789abcdef"), MediaFormat.GIF, bytes.size)
        assertEquals(MediaInserter.Result.SENT, accepted) // positive control: the path reaches commitContent
        val before = ic.commits.size
        MediaTokens.revokeAll()
        for (types in listOf(emptyArray(), arrayOf("text/plain"), arrayOf("image/png"), arrayOf("video/*"))) {
            val info = EditorInfo().apply { contentMimeTypes = types }
            assertEquals(MediaInserter.Result.NOT_ACCEPTED, MediaInserter.insert(pkg, ic, info, MediaSource.Vault("0123456789abcdef"), MediaFormat.GIF, bytes.size), types.toList().toString())
        }
        assertEquals(MediaInserter.Result.NOT_ACCEPTED, MediaInserter.insert(pkg, ic, EditorInfo(), MediaSource.Vault("0123456789abcdef"), MediaFormat.GIF, bytes.size), "a field that announces no type")
        assertEquals(MediaInserter.Result.NOT_ACCEPTED, MediaInserter.insert(pkg, null, field("image/gif"), MediaSource.Vault("0123456789abcdef"), MediaFormat.GIF, bytes.size), "no connection")
        assertEquals(MediaInserter.Result.NOT_ACCEPTED, MediaInserter.insert(pkg, ic, null, MediaSource.Vault("0123456789abcdef"), MediaFormat.GIF, bytes.size), "no editor info")
        assertEquals(before, ic.commits.size, "commitContent was not called")
        assertEquals(0, MediaTokens.liveCount, "no token was issued")
    }

    @Test fun theProviderGivesTheStoredBytesThroughAPipe() {
        val token = issue()
        assertContentEquals(bytes, read(provider(), uriOf(token)))
        assertContentEquals(bytes, read(provider(), uriOf(token)), "the grant can be read again until it ends")
    }

    // ---- matching, refusals, expiry, revocation, the shape of the answers -----------------------------

    @Test fun mimeTypesMatchLikeThePlatformDoes() {
        assertTrue(MediaInserter.accepts(arrayOf("image/*"), "image/gif"))
        assertTrue(MediaInserter.accepts(arrayOf("text/plain", "image/gif"), "image/gif"))
        assertTrue(MediaInserter.accepts(arrayOf("*/*"), "image/webp"))
        assertFalse(MediaInserter.accepts(arrayOf("image/png"), "image/jpeg"))
        assertFalse(MediaInserter.accepts(arrayOf("text/*"), "image/gif"))
        assertFalse(MediaInserter.accepts(null, "image/gif"))
        assertFalse(MediaInserter.accepts(emptyArray(), "image/gif"))
    }

    @Test fun aRefusedCommitRevokesItsTokenAgain() {
        val ic = connection().apply { result = false }
        assertEquals(MediaInserter.Result.REFUSED, MediaInserter.insert(pkg, ic, field("image/*"), MediaSource.Vault("0123456789abcdef"), MediaFormat.GIF, bytes.size))
        assertEquals(1, ic.commits.size)
        assertEquals(0, MediaTokens.liveCount)
    }

    @Test fun anUnknownOrMalformedTokenIsFileNotFound() {
        val p = provider()
        issue()
        for (path in listOf("0".repeat(32), "short", "G".repeat(32), "../" + "0".repeat(29)))
            assertFailsWith<FileNotFoundException>(path) { p.openFile(Uri.parse("content://${MediaShareProvider.authority(pkg)}/m/$path"), "r") }
        assertFailsWith<FileNotFoundException> { p.openFile(Uri.parse("content://${MediaShareProvider.authority(pkg)}/x/" + "0".repeat(32)), "r") }
        assertFailsWith<FileNotFoundException> { p.openFile(Uri.parse("content://${MediaShareProvider.authority(pkg)}/"), "r") }
    }

    @Test fun aTokenExpiresAfterTenMinutes() {
        val token = issue()
        val p = provider()
        now += MediaTokens.TTL_MILLIS - 1
        assertContentEquals(bytes, read(p, uriOf(token)), "one millisecond before the end it still works")
        now += 2
        assertFailsWith<FileNotFoundException> { p.openFile(uriOf(token), "r") }
        assertNull(MediaTokens.lookup(token))
        assertEquals(600_000L, MediaTokens.TTL_MILLIS)
    }

    @Test fun revokingEveryTokenAtLockWipeOrFieldChangeEndsTheGrants() {
        val p = provider()
        val a = issue()
        val b = issue()
        MediaTokens.revokeAll()
        for (t in listOf(a, b)) assertFailsWith<FileNotFoundException> { p.openFile(uriOf(t), "r") }
        // the registered hook: a screen lock revokes (the production wiring is Privacy.lock -> onLock hook)
        val c = issue()
        Privacy.lock(ApplicationProvider.getApplicationContext())
        try {
            assertFailsWith<FileNotFoundException>("lock revoked the token") { p.openFile(uriOf(c), "r") }
        } finally {
            Privacy.unlock(ApplicationProvider.getApplicationContext())
        }
    }

    @Test fun aWipeRevokesTheTokens() {
        val token = issue()
        Privacy.beginWipe()
        try {
            assertNull(MediaTokens.lookup(token))
        } finally {
            Privacy.endWipe()
        }
    }

    @Test fun aSourceThatCannotBeReadAnymoreIsFileNotFoundAndNoWritesHappen() {
        val token = issue()
        served = null // the picture was deleted or the store is locked
        assertFailsWith<FileNotFoundException> { provider().openFile(uriOf(token), "r") }
    }

    @Test fun onlyReadModeIsServed() {
        val token = issue()
        for (mode in listOf("w", "rw", "wt", "rwt")) assertFailsWith<FileNotFoundException>(mode) { provider().openFile(uriOf(token), mode) }
    }

    @Test fun queryAnswersNameAndSizeOnly() {
        val token = issue()
        val p = provider()
        val all = assertNotNull(p.query(uriOf(token), null, null, null, null))
        assertEquals(listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), all.columnNames.toList())
        assertTrue(all.moveToFirst())
        assertEquals("taen.gif", all.getString(0))
        assertEquals(bytes.size.toLong(), all.getLong(1))
        all.close()
        // a projection that asks for more gets only the two columns it may have
        val asked = assertNotNull(p.query(uriOf(token), arrayOf("_data", OpenableColumns.SIZE, "owner"), null, null, null))
        assertEquals(listOf(OpenableColumns.SIZE), asked.columnNames.toList())
        asked.close()
        assertEquals("image/gif", p.getType(uriOf(token)))
        MediaTokens.revokeAll()
        assertNull(p.query(uriOf(token), null, null, null, null))
        assertNull(p.getType(uriOf(token)))
    }

    @Test fun theProviderWritesNothing() {
        val p = provider()
        val token = issue()
        assertFailsWith<UnsupportedOperationException> { p.insert(uriOf(token), null) }
        assertFailsWith<UnsupportedOperationException> { p.delete(uriOf(token), null, null) }
        assertFailsWith<UnsupportedOperationException> { p.update(uriOf(token), null, null, null) }
    }

    @Test fun tokensAreRandom128BitHex() {
        val tokens = (1..50).map { issue() }
        assertEquals(50, tokens.toSet().size)
        assertTrue(tokens.all { it.matches(Regex("[0-9a-f]{32}")) })
    }
}
