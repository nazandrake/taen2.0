// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.downloads

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import helium314.keyboard.latin.nextword.NextWordGate
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * The folder of the downloaded next-word models (Privacy.nextWordModelDir): `<id>.onnx` (written by PinnedInstaller only), `<id>.speed`, the result of the
 * speed test on this phone, and two markers: `<id>.loading` exists while a load runs (it names the process), `<id>.crashed` records that a process ended
 * while it loaded the model. These files live next to the model and not in the preferences, so that they are never exported or imported onto another phone,
 * go with the model on Remove and with the imported files on a wipe. They hold numbers and a process tag, nothing typed.
 */
class ModelFiles(private val dir: File) {
    fun modelFile(id: String) = File(dir, "$id$MODEL_SUFFIX")

    private fun speedFile(id: String) = File(dir, "$id$SPEED_SUFFIX")
    private fun loadingFile(id: String) = File(dir, "$id$LOADING_SUFFIX")
    private fun crashedFile(id: String) = File(dir, "$id$CRASHED_SUFFIX")

    /** The stored result, or null when there is none or it cannot be read (then the test has to run again). */
    fun speed(id: String): NextWordGate.Speed? = try {
        val f = speedFile(id)
        if (f.isFile && f.length() < 64) NextWordGate.Speed.decode(f.readText()) else null
    } catch (_: IOException) {
        null
    }

    /** Stores [speed] with a write to a temporary file and a rename; false when it could not be stored. A stored result clears an earlier crash. */
    fun storeSpeed(id: String, speed: NextWordGate.Speed): Boolean {
        val tmp = File(dir, "$id$SPEED_SUFFIX.tmp")
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            tmp.writeText(speed.encode())
            val stored = tmp.renameTo(speedFile(id)) || run { tmp.delete(); false }
            stored && crashedFile(id).let { !it.exists() || it.delete() }
        } catch (_: IOException) {
            tmp.delete()
            false
        }
    }

    /** Removes the stored result and both markers (the model was removed). True when they are gone. */
    fun forgetSpeed(id: String): Boolean = listOf(speedFile(id), loadingFile(id), crashedFile(id)).all { !it.exists() || it.delete() }

    /**
     * Written before a load of [id] by [process] (see [processTag]); false when it cannot be written, and then the model must not be loaded. A load holds
     * the lock of its model from this call until [clearLoading]: every load path (the speed test and the keyboard, each with its own ModelFiles) waits
     * here while another load of the same model runs, so two loads never overlap and never share the marker. Call both on the loading thread.
     */
    fun markLoading(id: String, process: String): Boolean {
        val lock = lockOf(id)
        lock.lock()
        val marked = try {
            (dir.isDirectory || dir.mkdirs()) && run { loadingFile(id).writeText(process); true }
        } catch (_: IOException) {
            false
        }
        if (!marked) lock.unlock()
        return marked
    }

    /** The load of [id] returned (loaded or failed with an error): its marker goes and the next load of the model may start. */
    fun clearLoading(id: String) {
        loadingFile(id).delete()
        val lock = lockOf(id)
        if (lock.isHeldByCurrentThread) lock.unlock()
    }

    private fun lockOf(id: String): ReentrantLock = LOAD_LOCKS.computeIfAbsent(loadingFile(id).absolutePath) { ReentrantLock() }

    /** True when a process ended while it loaded [id] and no speed test has passed since. */
    fun loadCrashed(id: String): Boolean = crashedFile(id).exists()

    /**
     * At the start of a process ([process], see [processTag]): a loading marker of another process means that process ended during the load, usually a
     * low-memory kill. Its model is then recorded as crashed and its speed result is removed, so it is not loaded again before a new speed test (no load
     * loop). A marker of [process] itself is a load in progress and stays.
     */
    fun settleCrashedLoads(process: String) {
        val markers = dir.listFiles { f -> f.name.endsWith(LOADING_SUFFIX) } ?: return
        for (marker in markers) {
            val owner = try { marker.readText() } catch (_: IOException) { "" }
            if (owner == process) continue
            val id = marker.name.removeSuffix(LOADING_SUFFIX)
            try {
                crashedFile(id).writeText("crashed")
            } catch (_: IOException) {
                // the marker stays and is settled at the next start; the speed result goes in any case
            }
            speedFile(id).delete()
            if (crashedFile(id).exists()) marker.delete()
        }
    }

    companion object {
        // one lock per model file, shared by every ModelFiles of the process
        private val LOAD_LOCKS = ConcurrentHashMap<String, ReentrantLock>()

        const val MODEL_SUFFIX = ".onnx"
        const val SPEED_SUFFIX = ".speed"
        const val LOADING_SUFFIX = ".loading"
        const val CRASHED_SUFFIX = ".crashed"

        /** This process: its id and start time, so a reused process id of a later process is not taken for this one. */
        fun processTag(): String = "pid=${Process.myPid()} start=${Process.getStartElapsedRealtime()}"

        /** The memory the phone reports free (ActivityManager.MemoryInfo.availMem) in MiB; -1 when it cannot be read (then nothing is loaded). */
        fun freeMb(context: Context): Long {
            val am = context.getSystemService(ActivityManager::class.java) ?: return -1L
            val info = ActivityManager.MemoryInfo()
            return try {
                am.getMemoryInfo(info)
                info.availMem / (1024 * 1024)
            } catch (_: RuntimeException) {
                -1L
            }
        }
    }
}
