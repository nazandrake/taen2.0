// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.downloads

import helium314.keyboard.latin.privacy.tempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The pinned install. Expected hashes are computed from the test bytes with the JDK digest, sizes are the lengths of the arrays; the boundaries are
 * size - 1, size and size + 1. Every failure ends with no `.part` file and the earlier target untouched.
 */
class PinnedInstallerTest {
    private val staging = tempDir()
    private val targetDir = tempDir()
    private val target = File(targetDir, "main_user.dict")
    private val payload = ByteArray(300_000) { (it * 31 + 7).toByte() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun entry(b: ByteArray = payload, id: String = "dict-x") = DownloadEntry(id, DownloadEntry.DICTIONARY, "xx", b.size.toLong(), sha(b), "https://codeberg.org/o/r/raw/commit/${"a".repeat(40)}/x.dict", "CC BY 4.0", "src")
    private fun installer(free: Long = Long.MAX_VALUE, move: ((File, File) -> Unit)? = null) =
        if (move == null) PinnedInstaller(staging, { free }) else PinnedInstaller(staging, { free }, move)
    private fun stream(b: ByteArray) = { ByteArrayInputStream(b) as InputStream? }
    private fun leftovers() = staging.list().orEmpty().toList()

    // ---- the pinned file installs, anything else does not ---------------------------------------------

    @Test fun theNamedFileIsInstalledByteForByte() {
        assertEquals(PinnedInstaller.Result.Installed, installer().install(entry(), target, open = stream(payload)))
        assertContentEquals(payload, target.readBytes())
        assertEquals(emptyList(), leftovers())
    }

    @Test fun oneChangedByteIsAWrongFileAndNothingIsRenamed() {
        val changed = payload.copyOf().also { it[1234] = (it[1234].toInt() xor 1).toByte() }
        assertEquals(PinnedInstaller.Result.WrongFile, installer().install(entry(), target, open = stream(changed)))
        assertFalse(target.exists())
        assertEquals(emptyList(), leftovers())
    }

    // ---- boundaries, failures, exclusion --------------------------------------------------------------

    @Test fun sizeMinusOneSizeAndSizePlusOne() {
        assertEquals(PinnedInstaller.Result.WrongFile, installer().install(entry(), target, open = stream(payload.copyOf(payload.size - 1))), "truncated")
        assertFalse(target.exists())
        assertEquals(PinnedInstaller.Result.WrongFile, installer().install(entry(), target, open = stream(payload + byteArrayOf(0))), "one byte too long")
        assertFalse(target.exists())
        assertEquals(PinnedInstaller.Result.Installed, installer().install(entry(), target, open = stream(payload)), "the exact size")
        assertEquals(emptyList(), leftovers())
    }

    @Test fun readingStopsAtSizePlusOne() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int { served++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; b.fill(1, off, off + len); return len }
        }
        assertEquals(PinnedInstaller.Result.WrongFile, installer().install(entry(), target, open = { endless }))
        assertTrue(served <= payload.size + PinnedInstaller.BUFFER, "stopped near the pinned size, read $served")
        assertEquals(emptyList(), leftovers())
        assertNull(installer().identify(listOf(entry()), open = { endless }), "identify stops at the largest size too")
    }

    @Test fun anEarlierTargetSurvivesEveryFailure() {
        target.writeBytes(byteArrayOf(9, 9, 9))
        val failures = listOf<Pair<String, () -> PinnedInstaller.Result>>(
            "wrong content" to { installer().install(entry(), target, open = stream(payload.reversedArray())) },
            "no space" to { installer(free = payload.size + PinnedInstaller.SPACE_MARGIN - 1).install(entry(), target, open = stream(payload)) },
            "reader throws" to { installer().install(entry(), target, open = { object : InputStream() { override fun read() = throw IOException("x") } }) },
            "reader runtime error" to { installer().install(entry(), target, open = { object : InputStream() { override fun read() = throw IllegalStateException("x") } }) },
            "failed rename" to { installer(move = { _, _ -> throw IOException("rename") }).install(entry(), target, open = stream(payload)) },
            "cancel" to { installer().install(entry(), target, PinnedInstaller.Hooks(cancelled = { true }), open = stream(payload)) },
            "rejected" to { installer().install(entry(), target, accept = { false }, open = stream(payload)) },
            "unopenable" to { installer().install(entry(), target, open = { null }) },
        )
        for ((name, run) in failures) {
            val result = run()
            assertTrue(result != PinnedInstaller.Result.Installed, name)
            assertContentEquals(byteArrayOf(9, 9, 9), target.readBytes(), "$name: the earlier file is untouched")
            assertEquals(emptyList(), leftovers(), "$name: no .part is left")
        }
    }

    @Test fun theResultOfEachFailureIsTheNamedOne() {
        assertEquals(PinnedInstaller.Result.NoSpace, installer(free = payload.size + PinnedInstaller.SPACE_MARGIN - 1).install(entry(), target, open = stream(payload)))
        assertEquals(PinnedInstaller.Result.Installed, installer(free = payload.size + PinnedInstaller.SPACE_MARGIN).install(entry(), target, open = stream(payload)), "the margin itself is enough")
        target.delete()
        assertEquals(PinnedInstaller.Result.Cancelled, installer().install(entry(), target, PinnedInstaller.Hooks(cancelled = { true }), open = stream(payload)))
        assertEquals(PinnedInstaller.Result.Failed, installer(move = { _, _ -> throw IOException("rename") }).install(entry(), target, open = stream(payload)))
        assertEquals(PinnedInstaller.Result.Failed, installer().install(entry(), target, open = { object : InputStream() { override fun read() = throw IOException("x") } }))
        assertEquals(PinnedInstaller.Result.Rejected, installer().install(entry(), target, accept = { false }, open = stream(payload)))
        assertFalse(target.exists())
    }

    @Test fun aCancelDuringTheCopyEndsItAndCountsTheBytes() {
        var seen = 0L
        var cancel = false
        val hooks = PinnedInstaller.Hooks(cancelled = { cancel }, onBytes = { seen += it; if (seen > 100_000) cancel = true })
        assertEquals(PinnedInstaller.Result.Cancelled, installer().install(entry(), target, hooks, open = stream(payload)))
        assertTrue(seen in 100_001..payload.size.toLong())
        assertEquals(emptyList(), leftovers())
    }

    @Test fun acceptRunsOnTheVerifiedStagedFileOnly() {
        var seenName: String? = null
        var seenBytes: ByteArray? = null
        val result = installer().install(entry(), target, accept = { f -> seenName = f.name; seenBytes = f.readBytes(); true }, open = stream(payload))
        assertEquals(PinnedInstaller.Result.Installed, result)
        assertEquals("dict-x.part", seenName)
        assertContentEquals(payload, seenBytes)
        // a wrong file never reaches accept
        var called = false
        installer().install(entry(), target, accept = { called = true; true }, open = stream(payload.reversedArray()))
        assertFalse(called)
    }

    @Test fun aSecondInstallInTheSameDirectoryIsRefusedWhileOneRuns() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : InputStream() {
            private val real = ByteArrayInputStream(payload)
            override fun read(): Int = real.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int { inside.countDown(); release.await(10, TimeUnit.SECONDS); return real.read(b, off, len) }
        }
        var first: PinnedInstaller.Result? = null
        val t = thread { first = installer().install(entry(), target, open = { slow }) }
        assertTrue(inside.await(10, TimeUnit.SECONDS))
        assertEquals(PinnedInstaller.Result.Busy, installer().install(entry(id = "dict-y"), File(targetDir, "other.dict"), open = stream(payload)))
        assertFalse(File(targetDir, "other.dict").exists())
        release.countDown()
        t.join(20_000)
        assertEquals(PinnedInstaller.Result.Installed, first)
        // after the first one ended the directory is free again
        assertEquals(PinnedInstaller.Result.Installed, installer().install(entry(id = "dict-y"), File(targetDir, "other.dict"), open = stream(payload)))
    }

    @Test fun identifyFindsTheRowByContentAndRefusesEverythingElse() {
        val other = ByteArray(1000) { it.toByte() }
        val entries = listOf(entry(), entry(other, "dict-o"))
        assertEquals("dict-x", installer().identify(entries, open = stream(payload))?.id)
        assertEquals("dict-o", installer().identify(entries, open = stream(other))?.id)
        assertNull(installer().identify(entries, open = stream(payload.copyOf(payload.size - 1))), "right start, wrong length")
        assertNull(installer().identify(entries, open = stream(ByteArray(10))))
        assertNull(installer().identify(entries, open = { null }))
        assertNull(installer().identify(entries, open = { object : InputStream() { override fun read() = throw IOException("x") } }))
        assertNull(installer().identify(entries, PinnedInstaller.Hooks(cancelled = { true }), open = stream(payload)))
        assertNull(installer().identify(emptyList(), open = stream(payload)))
        assertEquals(emptyList(), leftovers(), "identify writes nothing")
    }

    @Test fun discardStagedNeverTouchesThePartFileOfARunningInstall() {
        val halfway = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        val slow = {
            object : InputStream() {
                private val inner = ByteArrayInputStream(payload)
                private var sent = 0
                override fun read(): Int = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (sent >= payload.size / 2) { halfway.countDown(); goOn.await(10, TimeUnit.SECONDS) }
                    return inner.read(b, off, minOf(len, 4096)).also { if (it > 0) sent += it }
                }
            } as InputStream?
        }
        var result: PinnedInstaller.Result? = null
        val running = thread { result = installer().install(entry(), target, open = slow) }
        assertTrue(halfway.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("dict-x.part"), leftovers(), "the install is halfway")
        installer().discardStaged() // a second install starting meanwhile
        assertEquals(listOf("dict-x.part"), leftovers(), "the running install's file stays")
        goOn.countDown()
        running.join(10_000)
        assertEquals(PinnedInstaller.Result.Installed, result)
        assertContentEquals(payload, target.readBytes())
        File(staging, "dict-y.part").writeBytes(byteArrayOf(1))
        installer().discardStaged()
        assertEquals(emptyList(), leftovers(), "positive control: with no install running a leftover goes")
    }

    @Test fun aLeftoverPartFileIsRemovedByDiscardStaged() {
        File(staging, "dict-x.part").writeBytes(byteArrayOf(1))
        File(staging, "keep.txt").writeBytes(byteArrayOf(1))
        installer().discardStaged()
        assertEquals(listOf("keep.txt"), leftovers())
    }
}
