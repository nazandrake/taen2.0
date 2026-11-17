// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.common

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The import of a link from another app: FileUtils owns one asynchronous copy. The caller never waits (the three
 * settings screens call it on the main thread), the result comes back on the main thread, one import runs at a time, and a failure,
 * a timeout or a cancel leaves neither a temporary file nor the target file.
 */
@RunWith(RobolectricTestRunner::class)
class FileUtilsTimeoutTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val uri = Uri.parse("content://taen.test/stalled.dict")
    private val mainThread = Thread.currentThread()

    /** Delivers [first] bytes, then blocks in read until it is closed (a provider that stalls). */
    private class StallingStream(private val first: Int) : InputStream() {
        private val closed = CountDownLatch(1)
        private var sent = 0
        override fun read(): Int {
            if (sent < first) { sent++; return 7 }
            // only a close ends it (an interrupt does not: a real provider read is not interruptible)
            val end = System.nanoTime() + 30_000_000_000L
            while (closed.count > 0 && System.nanoTime() < end) try { closed.await(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
            throw IOException("closed")
        }
        override fun close() { closed.countDown() }
    }

    /** Delivers [size] bytes, then waits [delayMillis] and ends normally, ignoring a close (a provider that finishes just after the timeout). */
    private class LateEndStream(private val size: Int, private val delayMillis: Long) : InputStream() {
        private var sent = 0
        override fun read(): Int {
            if (sent < size) { sent++; return 5 }
            val end = System.nanoTime() + delayMillis * 1_000_000
            while (System.nanoTime() < end) try { Thread.sleep(10) } catch (_: InterruptedException) { } // ignores the interrupt, like a provider that does not notice
            return -1
        }
    }

    private fun dir() = File(ctx.cacheDir, "import-test").apply { deleteRecursively(); mkdirs() }
    private fun names(dir: File) = dir.listFiles()!!.map { it.name }

    private class Outcome { @Volatile var called = false; @Volatile var failure: IOException? = null; @Volatile var thread: Thread? = null }

    private fun start(out: File, timeout: Long, cap: Long = FileUtils.MAX_EXTERNAL_IMPORT_BYTES): Pair<Outcome, FileUtils.ImportHandle> {
        val outcome = Outcome()
        val handle = FileUtils.importContentUri(uri, ctx, out, cap, timeout) { failure ->
            outcome.thread = Thread.currentThread(); outcome.failure = failure; outcome.called = true
        }
        return outcome to handle
    }

    /** Runs the main looper (where the callbacks are posted) until [condition] holds. */
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

    // ---- the caller never waits ------------------------------------------------------------------------------

    @Test fun aStalledProviderDoesNotBlockTheCallerAndTheCallbackReportsTheTimeoutWithNoFileLeft() {
        val out = File(dir(), "temp_dict")
        shadowOf(ctx.contentResolver).registerInputStream(uri, StallingStream(1000))
        val before = System.nanoTime()
        val (outcome, _) = start(out, 400)
        assertTrue(System.nanoTime() - before < 150_000_000L, "the call must return at once, not after the copy or the timeout")
        assertTrue(!outcome.called, "the result is not known yet")
        until("the timeout callback") { outcome.called }
        val failure = assertNotNull(outcome.failure)
        assertTrue(failure.message!!.contains("timed out"), failure.message)
        assertSame(mainThread, outcome.thread) // the callback runs on the main thread
        assertEquals(emptyList(), names(out.parentFile!!)) // no target, no .part
    }

    // ---- the whole contract ----------------------------------------------------------------------------------

    @Test fun aNormalCopyIsDeliveredOnTheMainThreadWithTheWholeFile() { // positive control for every "no file" assertion below
        val out = File(dir(), "temp_dict")
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(5000) { (it % 251).toByte() }))
        val (outcome, _) = start(out, 5000)
        until("the callback") { outcome.called }
        assertNull(outcome.failure)
        assertSame(mainThread, outcome.thread)
        assertEquals(ByteArray(5000) { (it % 251).toByte() }.toList(), out.readBytes().toList())
        assertEquals(listOf("temp_dict"), names(out.parentFile!!))
    }

    @Test fun anOversizeLinkFailsWithNoFile() {
        val out = File(dir(), "temp_dict")
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(5000)))
        val (outcome, _) = start(out, 5000, 4999)
        until("the callback") { outcome.called }
        assertTrue(outcome.failure!!.message!!.contains("too large"), outcome.failure!!.message)
        assertEquals(emptyList(), names(out.parentFile!!))
    }

    @Test fun aLinkThatCannotBeOpenedFailsWithNoFile() { // nothing registered for the uri: the provider throws
        val out = File(dir(), "temp_dict")
        val (outcome, _) = start(out, 5000)
        until("the callback") { outcome.called }
        assertNotNull(outcome.failure)
        assertEquals(emptyList(), names(out.parentFile!!))
    }

    @Test fun aCopyThatFinishesJustAfterTheTimeoutIsNotKept() { // the rename race: the copy ends normally after the failure was reported
        val out = File(dir(), "temp_dict")
        shadowOf(ctx.contentResolver).registerInputStream(uri, LateEndStream(3000, 700))
        val (outcome, _) = start(out, 300)
        // the main looper is not run until the worker has ended: the copy finishes after the timeout, and the answer that is then
        // delivered must still be the timeout, with nothing renamed
        val end = System.nanoTime() + 10_000_000_000L
        while (FileUtils.isImportRunning() && System.nanoTime() < end) Thread.sleep(5)
        assertTrue(!FileUtils.isImportRunning(), "the worker ended")
        assertTrue(!outcome.called)
        until("the timeout callback") { outcome.called }
        assertTrue(outcome.failure!!.message!!.contains("timed out"), outcome.failure!!.message)
        assertEquals(emptyList(), names(out.parentFile!!))
        assertTrue(!out.exists())
    }

    @Test fun aCancelDuringTheCopyDeliversNothingAndLeavesNoFile() {
        val out = File(dir(), "temp_dict")
        shadowOf(ctx.contentResolver).registerInputStream(uri, StallingStream(1000))
        val (outcome, handle) = start(out, 30_000)
        handle.cancel()
        until("the worker to end") { !FileUtils.isImportRunning() }
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(!outcome.called, "a cancelled import calls nobody")
        assertEquals(emptyList(), names(out.parentFile!!))
    }

    @Test fun aCancelAfterTheCopyButBeforeTheDeliveryRemovesTheFile() {
        val out = File(dir(), "temp_dict")
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(100)))
        val (outcome, handle) = start(out, 5000)
        val end = System.nanoTime() + 10_000_000_000L
        while (!out.exists() && System.nanoTime() < end) Thread.sleep(2) // committed, the result is queued on the main looper
        assertTrue(out.exists(), "the copy was committed (positive control)")
        handle.cancel()
        until("the worker to end") { !FileUtils.isImportRunning() }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(!outcome.called)
        assertEquals(emptyList(), names(out.parentFile!!))
    }

    @Test fun aCancelAfterTheDeliveryChangesNothing() {
        val out = File(dir(), "temp_dict")
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(100)))
        val (outcome, handle) = start(out, 5000)
        until("the callback") { outcome.called }
        handle.cancel() // the caller owns the file now
        assertEquals(listOf("temp_dict"), names(out.parentFile!!))
    }

    // ---- the same job for data that is read into memory (no file at all) or written to a picked target ----------------------

    private class ReadOutcome { @Volatile var called = false; @Volatile var failure: IOException? = null; @Volatile var data: ByteArray? = null; @Volatile var name: String? = null; @Volatile var thread: Thread? = null }

    private fun startRead(timeout: Long, cap: Long = 1000): Pair<ReadOutcome, FileUtils.ImportHandle> {
        val o = ReadOutcome()
        val h = FileUtils.readContentUri(uri, ctx, cap, timeout) { failure, data, name ->
            o.thread = Thread.currentThread(); o.failure = failure; o.data = data; o.name = name; o.called = true
        }
        return o to h
    }

    private fun noFilesAnywhere() = assertEquals(emptyList(), names(ctx.cacheDir).filter { it.endsWith(".part") })

    @Test fun aReadDeliversTheBytesAndTheNameOnTheMainThreadAndWritesNoFile() {
        val before = names(ctx.cacheDir).toSet()
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(700) { (it % 7).toByte() }))
        shadowOf(ctx.contentResolver).setCursor(uri, object : org.robolectric.fakes.BaseCursor() {
            override fun moveToFirst() = true
            override fun getString(columnIndex: Int) = "layout.json"
            override fun close() {}
        })
        val (o, _) = startRead(5000)
        until("the callback") { o.called }
        assertNull(o.failure)
        assertEquals(ByteArray(700) { (it % 7).toByte() }.toList(), o.data!!.toList())
        assertEquals("layout.json", o.name)
        assertSame(mainThread, o.thread)
        assertEquals(before, names(ctx.cacheDir).toSet(), "a read leaves no file")
    }

    @Test fun aReadOverTheCapFailsWithNoData() {
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(1001)))
        val (o, _) = startRead(5000, 1000)
        until("the callback") { o.called }
        assertTrue(o.failure!!.message!!.contains("too large"), o.failure!!.message)
        assertNull(o.data)
    }

    @Test fun aReadOfExactlyTheCapIsDelivered() { // boundary control for the case above
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(1000)))
        val (o, _) = startRead(5000, 1000)
        until("the callback") { o.called }
        assertNull(o.failure)
        assertEquals(1000, o.data!!.size)
    }

    @Test fun aStalledReadReturnsAtOnceTimesOutAndDeliversNoData() {
        shadowOf(ctx.contentResolver).registerInputStream(uri, StallingStream(10))
        val before = System.nanoTime()
        val (o, _) = startRead(400)
        assertTrue(System.nanoTime() - before < 150_000_000L)
        until("the timeout callback") { o.called }
        assertTrue(o.failure!!.message!!.contains("timed out"))
        assertNull(o.data)
        assertSame(mainThread, o.thread)
        noFilesAnywhere()
    }

    @Test fun aCancelledReadDeliversNothing() {
        shadowOf(ctx.contentResolver).registerInputStream(uri, StallingStream(10))
        val (o, h) = startRead(30_000)
        h.cancel()
        until("the worker to end") { !FileUtils.isImportRunning() }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(!o.called)
    }

    /** Accepts [first] bytes, then blocks in write until it is closed, ignoring an interrupt (a target that stalls). */
    private class StallingSink(private val first: Int) : java.io.OutputStream() {
        private val closed = CountDownLatch(1)
        var received = 0
        override fun write(b: Int) {
            if (received < first) { received++; return }
            val end = System.nanoTime() + 30_000_000_000L
            while (closed.count > 0 && System.nanoTime() < end) try { closed.await(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
            throw IOException("closed")
        }
        override fun close() { closed.countDown() }
    }

    @Test fun aWriteStoresTheBytesAndCallsBackOnTheMainThread() {
        val sink = java.io.ByteArrayOutputStream()
        shadowOf(ctx.contentResolver).registerOutputStream(uri, sink)
        val o = Outcome()
        FileUtils.writeContentUri(uri, ctx, ByteArray(300) { 9 }, 5000) { f -> o.thread = Thread.currentThread(); o.failure = f; o.called = true }
        assertTrue(!o.called) // not before the looper runs
        until("the callback") { o.called }
        assertNull(o.failure)
        assertSame(mainThread, o.thread)
        assertEquals(ByteArray(300) { 9 }.toList(), sink.toByteArray().toList())
    }

    @Test fun aStalledWriteReturnsAtOnceAndReportsTheTimeout() {
        shadowOf(ctx.contentResolver).registerOutputStream(uri, StallingSink(10))
        val o = Outcome()
        val before = System.nanoTime()
        FileUtils.writeContentUri(uri, ctx, ByteArray(300), 400) { f -> o.failure = f; o.called = true }
        assertTrue(System.nanoTime() - before < 150_000_000L)
        until("the timeout callback") { o.called }
        assertTrue(o.failure!!.message!!.contains("timed out"), o.failure!!.message)
    }

    @Test fun onlyOneImportRunsAtATimeAndNoThreadPilesUp() {
        val out = File(dir(), "temp_dict")
        val stalled = StallingStream(10)
        shadowOf(ctx.contentResolver).registerInputStream(uri, stalled)
        val (first, handle) = start(out, 30_000)
        val threads = ArrayList<Int>()
        val outcomes = ArrayList<Outcome>()
        repeat(5) {
            val (o, _) = start(File(out.parentFile, "second$it"), 30_000)
            outcomes += o
            threads += Thread.getAllStackTraces().keys.count { t -> t.isAlive && t.name.startsWith("taen-import") }
        }
        until("the refusals") { outcomes.all { it.called } }
        assertTrue(outcomes.all { it.failure!!.message!!.contains("another import") }, outcomes.map { it.failure?.message }.toString())
        assertTrue(threads.all { it <= 2 }, "one worker and one timer at most: $threads") // the positive control below proves they were counted
        assertTrue(threads.all { it >= 1 }, "the threads were counted: $threads")
        assertTrue(!first.called)
        handle.cancel()
        until("the worker to end") { !FileUtils.isImportRunning() }
        assertEquals(emptyList(), names(out.parentFile!!))
        // the next import works once the first one has ended
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(ByteArray(10)))
        val (again, _) = start(out, 5000)
        until("the callback") { again.called }
        assertNull(again.failure)
    }
}
