// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.downloads

import helium314.keyboard.latin.privacy.tempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dictionary checks before an install, the state and the remove actions, and the page flow of "Install downloaded": every picked document on its own,
 * by content. The engine is replaced by a fake [DictionaryCheck]; the real header and validity checks are the app's own (DownloadsWiring) and run on the device.
 */
class DownloadsInstallerTest {
    private val staging = tempDir()
    private val dictDirs = tempDir()
    private val bg = ByteArray(5000) { (it * 13).toByte() }
    private val en = ByteArray(7000) { (it * 17 + 1).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun entry(id: String, locale: String, b: ByteArray) = DownloadEntry(id, DownloadEntry.DICTIONARY, locale, b.size.toLong(), sha(b), "https://codeberg.org/o/r/raw/commit/${"b".repeat(40)}/$id.dict", "CC BY 4.0", "src")
    private val bgEntry = entry("dict-bg", "bg", bg)
    private val enEntry = entry("dict-en-us", "en-US", en)
    private val entries = listOf(bgEntry, enEntry)
    private fun targetOf(e: DownloadEntry) = File(File(dictDirs, e.locale), "main_user.dict")
    private val store = DownloadsStore(entries, ::targetOf, { 123_456_789L })

    private class Doc(override val name: String, private val bytes: ByteArray, var deletable: Boolean = true) : DownloadsInstaller.Source {
        var deleted = false
        override fun open(): InputStream? = ByteArrayInputStream(bytes)
        override fun delete(): Boolean { if (deletable) deleted = true; return deletable }
    }

    /** headerLocale and validity by file content, standing in for the engine. */
    private class FakeCheck(val locales: Map<Int, String?>, val valid: Boolean = true) : DictionaryCheck {
        var headerCalls = 0
        override fun headerLocale(file: File): String? { headerCalls++; return locales[file.readBytes().size] }
        override fun isValid(file: File, locale: String) = valid
    }

    private var installed = ArrayList<String>()
    private fun installer(check: DictionaryCheck = FakeCheck(mapOf(bg.size to "bg", en.size to "en_US"))) =
        DownloadsInstaller(entries, PinnedInstaller(staging, { Long.MAX_VALUE }), store, ::targetOf, check) { installed.add(it.id) }

    // ---- the matching file installs and announces itself once -------------------------------------------

    @Test fun aMatchingFileInstallsAndTheBroadcastIsSentOnce() {
        val report = installer().install(listOf(Doc("whatever.bin", bg)), deleteDownloaded = false)
        assertEquals(DownloadsInstaller.Kind.INSTALLED, report.outcomes.single().kind)
        assertContentEquals(bg, targetOf(bgEntry).readBytes())
        assertEquals(listOf("dict-bg"), installed)
        assertEquals(DownloadsStore.State.VERIFIED, store.diskState(bgEntry))
        assertEquals(emptyList(), staging.list().orEmpty().toList())
    }

    @Test fun aModelRowNeedsOnlyItsPinnedHashNotTheDictionaryChecks() {
        val model = ByteArray(6000) { (it * 7 + 3).toByte() }
        val modelEntry = DownloadEntry("model-xx", DownloadEntry.MODEL, "xx", model.size.toLong(), sha(model), "https://huggingface.co/o/m/resolve/${"c".repeat(40)}/onnx/model.onnx", "Apache-2.0", "src")
        val modelDir = tempDir()
        fun target(e: DownloadEntry) = if (e.kind == DownloadEntry.MODEL) File(modelDir, "${e.id}.onnx") else targetOf(e)
        val all = entries + modelEntry
        val check = FakeCheck(emptyMap(), valid = false) // a dictionary check would refuse anything
        val inst = DownloadsInstaller(all, PinnedInstaller(staging, { Long.MAX_VALUE }), DownloadsStore(all, ::target, { 0L }), ::target, check) { installed.add(it.id) }
        val report = inst.install(listOf(Doc("model_int8.onnx", model), Doc("x", bg)), deleteDownloaded = false)
        assertEquals(listOf(DownloadsInstaller.Kind.INSTALLED, DownloadsInstaller.Kind.REJECTED), report.outcomes.map { it.kind }, "positive control: the dictionary is still checked")
        assertContentEquals(model, File(modelDir, "model-xx.onnx").readBytes())
        assertEquals(1, check.headerCalls, "the model was not given to the dictionary engine")
        assertEquals(listOf("model-xx"), installed)
        val wrong = model.copyOf().also { it[100] = (it[100] + 1).toByte() }
        assertEquals(DownloadsInstaller.Kind.NOT_A_DOWNLOAD, inst.install(listOf(Doc("other.onnx", wrong)), false).outcomes.single().kind, "one changed byte is no catalogue file")
    }

    // ---- refusals, conflicts, deletion of copies, state, remove -----------------------------------------

    @Test fun aHeaderOfAnotherLanguageOrAnInvalidFileIsNotInstalled() {
        val otherLanguage = installer(FakeCheck(mapOf(bg.size to "en_US")))
        assertEquals(DownloadsInstaller.Kind.REJECTED, otherLanguage.install(listOf(Doc("a", bg)), false).outcomes.single().kind)
        val noHeader = installer(FakeCheck(mapOf(bg.size to null)))
        assertEquals(DownloadsInstaller.Kind.REJECTED, noHeader.install(listOf(Doc("a", bg)), false).outcomes.single().kind)
        val invalid = installer(FakeCheck(mapOf(bg.size to "bg"), valid = false))
        assertEquals(DownloadsInstaller.Kind.REJECTED, invalid.install(listOf(Doc("a", bg)), false).outcomes.single().kind)
        assertFalse(targetOf(bgEntry).exists(), "the shipped dictionary stays active: nothing was written")
        assertEquals(emptyList(), installed, "no broadcast for a refusal")
        assertEquals(emptyList(), staging.list().orEmpty().toList())
    }

    @Test fun theLocaleOfTheHeaderMatchesInEveryWritingOfTheTag() {
        for (header in listOf("en_US", "en-US", "EN_us"))
            assertEquals(DownloadsInstaller.Kind.INSTALLED, installer(FakeCheck(mapOf(en.size to header))).install(listOf(Doc("a", en)), false).outcomes.single().kind, header)
        assertEquals(DownloadsInstaller.Kind.REJECTED, installer(FakeCheck(mapOf(en.size to "en_GB"))).install(listOf(Doc("a", en)), false).outcomes.single().kind, "another region")
    }

    @Test fun aFileThatIsNoRowIsNotInstalledAndIsNeverCopied() {
        val check = FakeCheck(mapOf(bg.size to "bg"))
        val report = installer(check).install(listOf(Doc("photo.jpg", ByteArray(5000) { 3 }), Doc("empty", ByteArray(0))), false)
        assertEquals(listOf(DownloadsInstaller.Kind.NOT_A_DOWNLOAD, DownloadsInstaller.Kind.NOT_A_DOWNLOAD), report.outcomes.map { it.kind })
        assertEquals(0, check.headerCalls)
        assertFalse(targetOf(bgEntry).exists())
    }

    @Test fun aFileOfTheRightSizeWithOtherBytesIsNotARowEither() {
        val report = installer().install(listOf(Doc("a", bg.reversedArray())), false)
        assertEquals(DownloadsInstaller.Kind.NOT_A_DOWNLOAD, report.outcomes.single().kind)
    }

    @Test fun everyDocumentIsHandledOnItsOwn() {
        val report = installer().install(listOf(Doc("junk", ByteArray(3)), Doc("bg", bg), Doc("en", en)), false)
        assertEquals(listOf(DownloadsInstaller.Kind.NOT_A_DOWNLOAD, DownloadsInstaller.Kind.INSTALLED, DownloadsInstaller.Kind.INSTALLED), report.outcomes.map { it.kind })
        assertEquals(listOf("dict-bg", "dict-en-us"), installed)
    }

    @Test fun aDictionaryAddedByHandIsNeverReplaced() {
        targetOf(bgEntry).also { it.parentFile.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3)) // the user's own main dictionary
        val report = installer().install(listOf(Doc("bg", bg)), true)
        assertEquals(DownloadsInstaller.Kind.CONFLICT, report.outcomes.single().kind)
        assertContentEquals(byteArrayOf(1, 2, 3), targetOf(bgEntry).readBytes())
        assertEquals(emptyList(), installed)
        assertFalse(store.remove(bgEntry), "Remove does not delete a file that is not this row's")
        assertContentEquals(byteArrayOf(1, 2, 3), targetOf(bgEntry).readBytes())
        assertEquals(DownloadsStore.State.NOT_INSTALLED, store.diskState(bgEntry))
    }

    @Test fun aDamagedInstalledCopyIsReplacedByTheGoodOne() {
        targetOf(bgEntry).also { it.parentFile.mkdirs() }.writeBytes(bg.reversedArray()) // the pinned size, other content
        assertEquals(DownloadsStore.State.DAMAGED, store.diskState(bgEntry))
        assertEquals(DownloadsInstaller.Kind.INSTALLED, installer().install(listOf(Doc("bg", bg)), false).outcomes.single().kind)
        assertEquals(DownloadsStore.State.VERIFIED, store.diskState(bgEntry))
    }

    // the hash is kept per file between calls; a file replaced by another of the same size within the same millisecond must not keep the old answer
    @Test fun aFileReplacedWithinTheSameMillisecondIsHashedAgain() {
        val target = targetOf(bgEntry).also { it.parentFile.mkdirs() }
        target.writeBytes(bg)
        val stamp = target.lastModified()
        assertEquals(DownloadsStore.State.VERIFIED, store.diskState(bgEntry))
        val other = File(target.parentFile, "other").apply { writeBytes(bg.reversedArray()); setLastModified(stamp) }
        java.nio.file.Files.move(other.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals(stamp, target.lastModified(), "same size, same time stamp")
        assertEquals(DownloadsStore.State.DAMAGED, store.diskState(bgEntry), "a replaced file is not taken for the verified one")
        val good = File(target.parentFile, "good").apply { writeBytes(bg); setLastModified(stamp) }
        java.nio.file.Files.move(good.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals(DownloadsStore.State.VERIFIED, store.diskState(bgEntry), "and back")
    }

    @Test fun downloadedCopiesAreDeletedOnlyAfterAnInstallAndOnlyWhenAsked() {
        val kept = Doc("k", bg)
        installer().install(listOf(kept), deleteDownloaded = false)
        assertFalse(kept.deleted)
        val gone = Doc("g", en)
        val stuck = Doc("s", bg, deletable = false)
        val wrong = Doc("w", ByteArray(9))
        val report = installer().install(listOf(gone, stuck, wrong), deleteDownloaded = true)
        assertTrue(gone.deleted)
        assertEquals(listOf("s"), report.notDeleted)
        assertFalse(wrong.deleted, "a file that was not installed is never deleted")
    }

    @Test fun aCancelStopsBeforeTheNextDocument() {
        var cancel = false
        val hooks = PinnedInstaller.Hooks(cancelled = { cancel })
        val first = object : DownloadsInstaller.Source {
            override val name = "first"
            override fun open(): InputStream { cancel = true; return ByteArrayInputStream(bg) }
            override fun delete() = true
        }
        val report = installer().install(listOf(first, Doc("second", en)), false, hooks)
        assertEquals(DownloadsInstaller.Kind.CANCELLED, report.outcomes.last().kind)
        assertFalse(targetOf(enEntry).exists())
        assertFalse(targetOf(bgEntry).exists())
    }

    // ---- state, sizes, remove ---------------------------------------------------------------------------

    @Test fun theOverviewShowsStateSizeOnDiskTotalAndFreeSpace() {
        installer().install(listOf(Doc("bg", bg)), false)
        val o = store.overview()
        val rows = o.rows.associateBy { it.entry.id }
        assertEquals(DownloadsStore.State.VERIFIED, rows.getValue("dict-bg").state)
        assertEquals(bg.size.toLong(), rows.getValue("dict-bg").bytesOnDisk)
        assertEquals(DownloadsStore.State.NOT_INSTALLED, rows.getValue("dict-en-us").state)
        assertEquals(0L, rows.getValue("dict-en-us").bytesOnDisk)
        assertEquals(bg.size.toLong(), o.totalBytes)
        assertEquals(123_456_789L, o.freeBytes)
    }

    @Test fun runningAndRefusedRowsShowTheirOwnStateAndAVerifiedFileWins() {
        val o = store.overview(running = mapOf("dict-en-us" to 42), refused = setOf("dict-bg"))
        val rows = o.rows.associateBy { it.entry.id }
        assertEquals(DownloadsStore.State.INSTALLING, rows.getValue("dict-en-us").state)
        assertEquals(42, rows.getValue("dict-en-us").percent)
        assertEquals(DownloadsStore.State.WRONG_FILE, rows.getValue("dict-bg").state)
        installer().install(listOf(Doc("bg", bg)), false)
        assertEquals(DownloadsStore.State.VERIFIED, store.overview(refused = setOf("dict-bg")).rows.first { it.entry.id == "dict-bg" }.state, "an earlier refusal does not hide the installed file")
        targetOf(enEntry).also { it.parentFile.mkdirs() }.writeBytes(en.reversedArray())
        assertEquals(DownloadsStore.State.DAMAGED, store.overview().rows.first { it.entry.id == "dict-en-us" }.state)
    }

    @Test fun removeDeletesExactlyThatRowsFilesAndRemoveAllEveryDownloadAndNothingElse() {
        installer().install(listOf(Doc("bg", bg), Doc("en", en)), false)
        val bystander = File(File(dictDirs, "bg"), "emoji_user.dict").also { it.writeBytes(byteArrayOf(5)) }
        assertTrue(store.remove(bgEntry))
        assertFalse(targetOf(bgEntry).exists())
        assertTrue(targetOf(enEntry).exists(), "the other row stays")
        assertTrue(bystander.exists())
        assertTrue(store.remove(bgEntry), "removing what is gone is fine")
        assertEquals(emptyList(), store.removeAll())
        assertFalse(targetOf(enEntry).exists())
        assertTrue(bystander.exists(), "Remove all deletes the downloads and nothing else")
        assertNull(store.overview().rows.firstOrNull { it.state != DownloadsStore.State.NOT_INSTALLED })
    }
}
