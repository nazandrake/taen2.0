// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.nextword

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tokenizer files in the APK are exactly the pinned ones of tools/nextword/tokenizers.lock, and every model of the app has its two files there. */
class TokenizerAssetsTest {
    private val assets = File("src/main/assets")
    private val lock = File("../tools/nextword/tokenizers.lock")

    private fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun theShippedTokenizerFilesAreThePinnedOnes() {
        assertTrue(lock.isFile, "positive control: the lock file was found")
        val rows = lock.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        assertEquals(4, rows.size)
        for (r in rows) {
            val f = File(assets, r[0])
            assertEquals(r[1].toLong(), f.length(), r[0])
            assertEquals(r[2], sha256(f), r[0])
            assertTrue(r[3].matches(Regex("https://huggingface\\.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/[\\w.]+")), "commit-pinned source: ${r[3]}")
        }
        val shipped = File(assets, "nextword").walkTopDown().filter { it.isFile }.map { it.relativeTo(assets).path }.toSet()
        assertEquals(rows.map { it[0] }.toSet(), shipped, "no file under assets/nextword that is not pinned")
        for (m in NextWordModels.ALL) for (name in listOf("vocab.json", "merges.txt"))
            assertTrue("${m.tokenizerAssets}/$name" in shipped, "${m.id} has its $name")
    }
}
