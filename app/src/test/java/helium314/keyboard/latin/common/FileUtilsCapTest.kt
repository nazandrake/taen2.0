// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.common

import helium314.keyboard.latin.privacy.tempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The size cap of a copy from another app's link (SettingsActivity2 entry point). */
class FileUtilsCapTest {
    private val dir = tempDir()

    @Test fun aStreamUpToTheCapIsCopiedExactly() {
        for (size in listOf(0, 1, 8191, 8192, 8193, 20_000)) {
            val data = ByteArray(size) { (it * 31).toByte() }
            val out = File(dir, "ok$size")
            FileUtils.copyStreamToNewFile(ByteArrayInputStream(data), out, size.toLong()) // exactly the cap: allowed
            assertContentEquals(data, out.readBytes(), "size $size")
        }
    }

    @Test fun aStreamOneByteOverTheCapFailsAndLeavesNoFile() {
        for (cap in listOf(1L, 8192L, 20_000L)) {
            val out = File(dir, "over$cap")
            assertFailsWith<IOException> { FileUtils.copyStreamToNewFile(ByteArrayInputStream(ByteArray((cap + 1).toInt())), out, cap) }
            assertFalse(out.exists(), "cap $cap")
        }
    }

    @Test fun theExternalCapIsSixtyFourMiB() {
        assertEquals(64L * 1024 * 1024, FileUtils.MAX_EXTERNAL_IMPORT_BYTES)
    }
}
