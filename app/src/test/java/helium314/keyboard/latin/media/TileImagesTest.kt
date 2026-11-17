// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import android.content.Context
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tile decoder of the picture panel: which formats animate, the tile size limit, and a real decode with the platform decoder (native graphics). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileImagesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    // 640 x 480, 6 frames of one flat colour each, looping (made with ffmpeg from a colour source; no picture of a person or a place)
    private fun big(): ByteArray = javaClass.classLoader!!.getResourceAsStream("media/big_animated.gif")!!.use { it.readBytes() }

    @Test fun onlyGifAndWebpAreAnimatable() {
        assertEquals(setOf(MediaFormat.GIF, MediaFormat.WEBP), MediaFormat.entries.filter { TileImages.isAnimatable(it) }.toSet())
        assertFalse(TileImages.isAnimatable(MediaFormat.PNG))
        assertFalse(TileImages.isAnimatable(MediaFormat.JPEG))
    }

    @Test fun theTargetSizeKeepsTheAspectAndTheLongestSideWithinTheLimit() {
        assertEquals(160 to 120, TileImages.targetSize(640, 480))
        assertEquals(120 to 160, TileImages.targetSize(480, 640))
        assertEquals(160 to 160, TileImages.targetSize(8000, 8000))
        assertEquals(160 to 1, TileImages.targetSize(4000, 25), "a side never gets below one pixel")
        assertEquals(50 to 100, TileImages.targetSize(500, 1000, 100))
        // at or under the limit: no resize
        assertNull(TileImages.targetSize(160, 160))
        assertNull(TileImages.targetSize(128, 128))
        assertNull(TileImages.targetSize(1, 1))
    }

    @Test fun aStarterGifDecodesToAnAnimatedDrawableAtItsOwnSize() {
        val bytes = StarterPack.read(ctx, "stickers/fire_animated.gif")!!
        val drawable = assertIs<AnimatedImageDrawable>(TileImages.decode(bytes))
        assertEquals(128, drawable.intrinsicWidth)
        assertEquals(128, drawable.intrinsicHeight)
    }

    @Test fun aLargeGifIsDecodedAtTileSizeNotAtItsOwnSize() {
        val drawable = assertIs<AnimatedImageDrawable>(assertNotNull(TileImages.decode(big())))
        assertEquals(160, drawable.intrinsicWidth)
        assertEquals(120, drawable.intrinsicHeight)
    }

    @Test fun aStillPictureComesBackAsABitmapAndGarbageAsNull() {
        val png = StarterPack.read(ctx, "stickers/fire.png")!!
        val still = assertNotNull(TileImages.decode(png))
        assertTrue(still is BitmapDrawable, "a PNG is not an animation: $still")
        assertNull(TileImages.decode(byteArrayOf(1, 2, 3, 4)))
        assertNull(TileImages.decode(ByteArray(0)))
    }
}
