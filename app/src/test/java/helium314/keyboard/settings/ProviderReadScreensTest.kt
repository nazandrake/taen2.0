// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.settings.preferences.readBackgroundImage
import helium314.keyboard.settings.preferences.readBackupFile
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Background image: every way the read ends without the image (provider error, timeout, over size, another import
 * running) reports an error, which shows the error dialog. Backup restore: the passphrase is wiped on every path (success hands it to
 * restoreBackup, which wipes it in its finally; cancel, screen disposed, a newer job, failure and timeout wipe it here).
 */
@RunWith(RobolectricTestRunner::class)
class ProviderReadScreensTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val uri = Uri.parse("content://taen.test/picked")
    private val secret = "correct horse battery staple"

    /** Delivers [first] bytes, then blocks until it is closed (a provider that stalls). */
    private class StallingStream(private val first: Int) : InputStream() {
        private val closed = CountDownLatch(1)
        private var sent = 0
        override fun read(): Int {
            if (sent < first) { sent++; return 7 }
            val end = System.nanoTime() + 30_000_000_000L
            while (closed.count > 0 && System.nanoTime() < end) try { closed.await(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
            throw IOException("closed")
        }
        override fun close() { closed.countDown() }
    }

    private fun until(what: String, millis: Long = 10_000, condition: () -> Boolean) {
        val end = System.nanoTime() + millis * 1_000_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (System.nanoTime() > end) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(5)
        }
    }

    @After fun workerEnds() { until("the import worker to end") { !FileUtils.isImportRunning() } }

    private class Background { var errors = 0; var images = 0 }

    private fun background(jobs: ProviderJobs, max: Long = 1000, timeout: Long = 5000): Background {
        val b = Background()
        readBackgroundImage(jobs, uri, ctx, max, timeout, onError = { b.errors++ }) { b.images++ }
        return b
    }

    // ---- background image: the error dialog ----------------------------------------------------------------------------------

    @Test fun aReadableImageGoesOnWithNoError() { // positive control for the error cases
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(500)))
        val b = background(ProviderJobs())
        until("the result") { b.images + b.errors > 0 }
        assertEquals(1, b.images); assertEquals(0, b.errors)
    }

    @Test fun anUnreadableFileShowsTheError() { // nothing registered: the provider throws
        val b = background(ProviderJobs())
        until("the result") { b.images + b.errors > 0 }
        assertEquals(1, b.errors); assertEquals(0, b.images)
    }

    @Test fun anOversizeFileShowsTheError() {
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(1001)))
        val b = background(ProviderJobs(), max = 1000)
        until("the result") { b.images + b.errors > 0 }
        assertEquals(1, b.errors); assertEquals(0, b.images)
    }

    @Test fun aTimeoutShowsTheError() {
        shadowOf(ctx.contentResolver).registerInputStream(uri, StallingStream(10))
        val b = background(ProviderJobs(), timeout = 300)
        until("the result") { b.images + b.errors > 0 }
        assertEquals(1, b.errors); assertEquals(0, b.images)
    }

    @Test fun anotherImportRunningShowsTheError() {
        val other = Uri.parse("content://taen.test/other")
        shadowOf(ctx.contentResolver).registerInputStream(other, StallingStream(10))
        val first = FileUtils.readContentUri(other, ctx, 1000, 30_000) { _, _, _ -> }
        try {
            assertTrue(FileUtils.isImportRunning(), "the other job runs")
            shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(10)))
            val b = background(ProviderJobs())
            until("the result") { b.images + b.errors > 0 }
            assertEquals(1, b.errors); assertEquals(0, b.images)
        } finally {
            first.cancel()
        }
    }

    // ---- backup restore: the passphrase -----------------------------------------------------------------------------------------

    private class Restore(val pass: CharArray) { var errors = mutableListOf<String>(); var files = 0 }

    private fun restore(jobs: ProviderJobs, timeout: Long = 5000, pass: CharArray = secret.toCharArray()): Restore {
        val r = Restore(pass)
        readBackupFile(jobs, uri, ctx, pass, timeout, onError = { r.errors.add(it) }) { r.files++ }
        return r
    }

    private fun assertWiped(r: Restore) = assertEquals(List(secret.length) { '\u0000' }, r.pass.toList())
    private fun assertKept(r: Restore) = assertEquals(secret.toList(), r.pass.toList())

    @Test fun onSuccessThePassphraseIsHandedOnNotWipedHere() { // positive control: restoreBackup owns and wipes it
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(300)))
        val r = restore(ProviderJobs())
        until("the result") { r.files + r.errors.size > 0 }
        assertEquals(1, r.files); assertEquals(emptyList(), r.errors)
        assertKept(r)
    }

    @Test fun aFailedReadWipesThePassphrase() {
        val r = restore(ProviderJobs()) // nothing registered
        until("the result") { r.files + r.errors.size > 0 }
        assertEquals(1, r.errors.size); assertEquals(0, r.files)
        assertWiped(r)
    }

    @Test fun aTimeoutWipesThePassphrase() {
        shadowOf(ctx.contentResolver).registerInputStream(uri, StallingStream(10))
        val r = restore(ProviderJobs(), timeout = 300)
        until("the result") { r.files + r.errors.size > 0 }
        assertEquals(1, r.errors.size)
        assertWiped(r)
    }

    @Test fun cancellingTheReadWipesThePassphrase() { // the user taps Cancel in the progress dialog: no callback ever runs
        shadowOf(ctx.contentResolver).registerInputStream(uri, StallingStream(10))
        val jobs = ProviderJobs()
        val r = restore(jobs, timeout = 30_000)
        assertNotNull(jobs.running, "the read runs")
        assertKept(r) // positive control: still intact while the read runs
        jobs.cancel()
        assertWiped(r)
        until("the worker to end") { !FileUtils.isImportRunning() }
        assertEquals(0, r.files); assertEquals(emptyList(), r.errors) // no callback ran: only the cancel wiped it
    }

    @Test fun aNewerJobWipesThePassphraseOfTheOneItReplaces() {
        shadowOf(ctx.contentResolver).registerInputStream(uri, StallingStream(10))
        val jobs = ProviderJobs()
        val first = restore(jobs, timeout = 30_000)
        assertKept(first)
        jobs.write(Uri.parse("content://taen.test/target"), ctx, ByteArray(3)) { }
        assertWiped(first)
        jobs.cancel()
        until("the worker to end") { !FileUtils.isImportRunning() }
    }

    @Test fun leavingTheScreenWipesThePassphrase() { // the composition ends: rememberProviderJobs gives the running job up
        shadowOf(ctx.contentResolver).registerInputStream(uri, StallingStream(10))
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) {}
            override fun insertBottomUp(index: Int, instance: Unit) {}
            override fun remove(index: Int, count: Int) {}
            override fun move(from: Int, to: Int, count: Int) {}
            override fun onClear() {}
        }, Recomposer(EmptyCoroutineContext))
        var jobs: ProviderJobs? = null
        composition.setContent { jobs = rememberProviderJobs() }
        val r = restore(assertNotNull(jobs), timeout = 30_000)
        assertKept(r)
        composition.dispose()
        assertWiped(r)
        until("the worker to end") { !FileUtils.isImportRunning() }
    }
}
