// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The starter pictures of the APK: 38 still PNG stickers and 36 animated GIFs, all public, read by plain file name only, within 4 MiB. */
@RunWith(RobolectricTestRunner::class)
class StarterPackTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun frames(gif: ByteArray): Int {
        // a Graphic Control Extension (21 F9 04 ...) starts every frame of the files of the pack (no comment or application block holds that sequence)
        var n = 0
        for (i in 0 until gif.size - 3) if (gif[i] == 0x21.toByte() && gif[i + 1] == 0xF9.toByte() && gif[i + 2] == 0x04.toByte()) n++
        return n
    }

    private fun loopsForever(gif: ByteArray): Boolean {
        val text = String(gif, Charsets.ISO_8859_1)
        val at = text.indexOf("NETSCAPE2.0")
        // application block: 21 FF 0B "NETSCAPE2.0" 03 01 <loop count 0 = forever>
        return at >= 3 && gif[at + 11] == 0x03.toByte() && gif[at + 12] == 0x01.toByte() && gif[at + 13] == 0.toByte() && gif[at + 14] == 0.toByte()
    }

    @Test fun theListHoldsTheStillAndTheAnimatedStarterPicturesWithTheirTypes() {
        val all = StarterPack.list(ctx)
        val gifs = all.filter { it.endsWith("_animated.gif") }
        assertEquals(74, all.size)
        assertEquals(36, gifs.size)
        assertEquals(38, all.count { it.endsWith(".png") })
        assertTrue(all.all { it.startsWith("stickers/") && it.substringAfterLast('/').let { n -> n == n.lowercase() } })
        assertEquals(all.sorted(), all, "in name order")
        for (path in gifs) {
            val bytes = assertNotNullBytes(path)
            assertEquals(MediaFormat.GIF, StarterPack.format(bytes), path)
            assertEquals("image/gif", MediaFormat.GIF.mime)
        }
        for (path in all.filter { it.endsWith(".png") }) assertEquals(MediaFormat.PNG, StarterPack.format(assertNotNullBytes(path)), path)
    }

    private fun assertNotNullBytes(path: String): ByteArray = StarterPack.read(ctx, path) ?: error("no bytes for $path")

    @Test fun everyAnimatedPictureAnimatesAndLoopsAndStaysSmall() {
        val gifs = StarterPack.list(ctx).filter { it.endsWith("_animated.gif") }
        var total = 0L
        for (path in gifs) {
            val bytes = assertNotNullBytes(path)
            total += bytes.size
            assertTrue(frames(bytes) >= 5, "$path has ${frames(bytes)} frames")
            assertTrue(loopsForever(bytes), "$path loops forever")
            // 128 x 128 logical screen (little-endian width and height after the 6-byte header)
            assertEquals(128, (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8), path)
            assertEquals(128, (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8), path)
        }
        assertTrue(total < 3L * 1024 * 1024, "the animated set is $total bytes")
        val whole = StarterPack.list(ctx).sumOf { assertNotNullBytes(it).size.toLong() }
        assertTrue(whole <= StarterPack.MAX_BYTES, "the whole pack is $whole bytes")
    }

    @Test fun theTagsFollowTheFormat() {
        assertEquals(listOf("gif"), StarterPack.tags(MediaFormat.GIF))
        assertEquals(listOf("sticker"), StarterPack.tags(MediaFormat.PNG))
        assertEquals(listOf("sticker"), StarterPack.tags(MediaFormat.WEBP))
    }

    @Test fun onlyAPlainFileNameInsideTheFolderIsRead() {
        val gif = "stickers/fire_animated.gif"
        assertTrue(StarterPack.read(ctx, gif)!!.isNotEmpty())
        for (bad in listOf("fire_animated.gif", "stickers/", "stickers/../AndroidManifest.xml", "stickers/a/b.gif", "emoji_search/en.txt", "stickers/missing.gif")) assertNull(StarterPack.read(ctx, bad), bad)
        assertContentEquals(StarterPack.read(ctx, gif), StarterPack.read(ctx, gif))
    }
}
