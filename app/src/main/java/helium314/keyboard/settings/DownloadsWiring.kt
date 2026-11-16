// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import helium314.keyboard.dictionarypack.DictionaryPackConstants
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.dictionary.ReadOnlyBinaryDictionary
import helium314.keyboard.latin.downloads.DictionaryCheck
import helium314.keyboard.latin.downloads.DownloadCatalog
import helium314.keyboard.latin.downloads.DownloadEntry
import helium314.keyboard.latin.downloads.DownloadsInstaller
import helium314.keyboard.latin.downloads.DownloadsStore
import helium314.keyboard.latin.downloads.ModelFiles
import helium314.keyboard.latin.downloads.PinnedInstaller
import helium314.keyboard.latin.nextword.NextWordModel
import helium314.keyboard.latin.nextword.NextWordSpeedTest
import helium314.keyboard.latin.privacy.Privacy
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The Android side of the downloads page: where a dictionary row lives, the dictionary checks of the engine (the same header and isValidDictionary
 * checks as the free-form "Add dictionary" dialog) and the broadcast that makes the running keyboard reload its dictionaries. A downloaded main dictionary
 * is `main_user.dict` in the folder of its language, which the keyboard prefers over the dictionary of the APK; Remove deletes that file, and the dictionary of the
 * APK is used again (the extracted copy `main.dict` that the install deleted is made from the APK again). A model row is `<id>.onnx` in the model folder
 * (ModelFiles), with no check besides its pinned hash; its speed test on this phone runs here too.
 */
class DownloadsWiring(private val ctx: Context) {
    val entries: List<DownloadEntry> = DownloadCatalog.load(ctx)

    val models = ModelFiles(Privacy.nextWordModelDir(ctx))

    fun targetOf(entry: DownloadEntry): File {
        if (entry.kind == DownloadEntry.MODEL) return models.modelFile(entry.id)
        val dir = DictionaryInfoUtils.getCacheDirectoryForLocale(Locale.forLanguageTag(entry.locale), ctx) ?: throw IOException("no folder")
        return File(dir, "${Dictionary.TYPE_MAIN}_${DictionaryInfoUtils.USER_DICTIONARY_SUFFIX}")
    }

    // a folder that cannot be made is a row that is not installed (the install then fails on its own)
    val store = DownloadsStore(entries, { runCatching { targetOf(it) }.getOrDefault(File("/nonexistent")) }, { Privacy.downloadsStagingDir(ctx).parentFile?.usableSpace ?: 0L })

    fun installer() = DownloadsInstaller(entries, PinnedInstaller(Privacy.downloadsStagingDir(ctx)), store, ::targetOf, EngineCheck, onInstalled = { entry ->
        if (entry.kind == DownloadEntry.DICTIONARY) {
            // the extracted copy of the dictionary of the APK is replaced by the downloaded one
            File(targetOf(entry).parentFile, DictionaryInfoUtils.MAIN_DICT_FILE_NAME).delete()
            reload()
        } // a model is picked up by the keyboard at the next text field
    })

    /** Remove: the row's own files only (a model with its speed result), then the dictionaries are reloaded. */
    fun remove(entry: DownloadEntry): Boolean {
        val removed = store.remove(entry) && (entry.kind != DownloadEntry.MODEL || models.forgetSpeed(entry.id))
        if (entry.kind == DownloadEntry.DICTIONARY) reload()
        return removed
    }

    fun removeAll(): List<DownloadEntry> {
        val failed = store.removeAll().toMutableList()
        for (e in entries) if (e.kind == DownloadEntry.MODEL && e !in failed && !models.forgetSpeed(e.id)) failed.add(e)
        reload()
        return failed
    }

    /**
     * The speed test of [model] on this phone (NextWordSpeedTest), on the calling (background) thread: the file is checked against its pinned hash, then the
     * result is stored next to the model. False when the model could not be checked, loaded or stored, or the test was cancelled.
     */
    fun speedTest(model: NextWordModel, cancelled: () -> Boolean, progress: (Int, Int) -> Unit): Boolean {
        val entry = entries.firstOrNull { it.id == model.id } ?: return false
        if (store.diskState(entry) != DownloadsStore.State.VERIFIED) return false
        val sentences = NextWordSpeedTest.SAMPLES[model.language] ?: return false
        val speed = try {
            NextWordSpeedTest(System::nanoTime, ::residentKb, ::freeMb).run({ model.load(ctx.assets, models.modelFile(model.id).path, SPEED_TEST_THREADS) }, sentences,
                needMb = entry.size / (1024 * 1024), cancelled = cancelled, progress = progress)
        } catch (_: Exception) {
            null
        } ?: return false
        return models.storeSpeed(model.id, speed)
    }

    // the resident memory of this process (VmRSS of /proc/self/status) in KiB; -1 when it cannot be read (the speed test then fails)
    private fun residentKb(): Long = try {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }?.filter { it.isDigit() }?.toLongOrNull() ?: -1L
    } catch (_: IOException) {
        -1L
    }

    // the memory the phone reports free (ActivityManager.MemoryInfo.availMem) in MiB; -1 when it cannot be read (the model is then not loaded)
    private fun freeMb(): Long {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return -1L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    private fun reload() { ctx.sendBroadcast(Intent(DictionaryPackConstants.NEW_DICTIONARY_INTENT_ACTION)) }

    private companion object {
        const val SPEED_TEST_THREADS = 4 // the thread count of the keyboard (NextWordTyping)
    }

    private object EngineCheck : DictionaryCheck {
        override fun headerLocale(file: File): String? = DictionaryInfoUtils.getDictionaryFileHeaderOrNull(file)?.mLocaleString

        override fun isValid(file: File, locale: String): Boolean {
            val dict = ReadOnlyBinaryDictionary(file.absolutePath, 0, file.length(), false, locale.constructLocale(), "test")
            val valid = dict.isValidDictionary
            dict.close()
            return valid
        }
    }
}
