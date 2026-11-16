// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.downloads

import helium314.keyboard.latin.nextword.NextWordGate
import java.io.File
import java.io.IOException

/**
 * The folder of the downloaded next-word models (Privacy.nextWordModelDir): `<id>.onnx` (written by PinnedInstaller only) and `<id>.speed`, the result of the
 * speed test on this phone. The result lives next to the model and not in the preferences, so that it is never exported or imported onto another phone,
 * goes with the model on Remove and with the imported files on a wipe. The file holds two numbers and nothing typed.
 */
class ModelFiles(private val dir: File) {
    fun modelFile(id: String) = File(dir, "$id$MODEL_SUFFIX")

    private fun speedFile(id: String) = File(dir, "$id$SPEED_SUFFIX")

    /** The stored result, or null when there is none or it cannot be read (then the test has to run again). */
    fun speed(id: String): NextWordGate.Speed? = try {
        val f = speedFile(id)
        if (f.isFile && f.length() < 64) NextWordGate.Speed.decode(f.readText()) else null
    } catch (_: IOException) {
        null
    }

    /** Stores [speed] with a write to a temporary file and a rename; false when it could not be stored. */
    fun storeSpeed(id: String, speed: NextWordGate.Speed): Boolean {
        val tmp = File(dir, "$id$SPEED_SUFFIX.tmp")
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            tmp.writeText(speed.encode())
            tmp.renameTo(speedFile(id)) || run { tmp.delete(); false }
        } catch (_: IOException) {
            tmp.delete()
            false
        }
    }

    /** Removes the stored result (the model was removed). True when it is gone. */
    fun forgetSpeed(id: String): Boolean = speedFile(id).let { !it.exists() || it.delete() }

    companion object {
        const val MODEL_SUFFIX = ".onnx"
        const val SPEED_SUFFIX = ".speed"
    }
}
