// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Animated tiles of the picture panel, played with the platform decoder (android.graphics.ImageDecoder and AnimatedImageDrawable, API 28+; there is no library).
 * The source is always the bytes that are already in memory ([ImageDecoder.createSource] of a ByteBuffer): no file path, nothing is written. A picture is decoded at
 * tile size, so a large one cannot take much memory.
 */
object TileImages {
    /** The longest side of a decoded tile in pixels. */
    const val TILE_PIXELS = 160

    /** True for the formats that can hold an animation (GIF, WebP); PNG and JPEG are always still. */
    fun isAnimatable(format: MediaFormat): Boolean = format == MediaFormat.GIF || format == MediaFormat.WEBP

    /** The size to decode a [width] x [height] picture at so that its longest side is at most [limit] (the aspect kept, no side below 1), or null when it already fits. */
    fun targetSize(width: Int, height: Int, limit: Int = TILE_PIXELS): Pair<Int, Int>? {
        val longest = maxOf(width, height)
        if (width <= 0 || height <= 0 || longest <= limit) return null
        fun scaled(side: Int) = maxOf(1, ((side.toLong() * limit + longest / 2) / longest).toInt())
        return scaled(width) to scaled(height)
    }

    /** Decodes [bytes] at tile size; an animated picture gives an animatable drawable (an AnimatedImageDrawable), a still one a bitmap drawable. Null when the bytes cannot be decoded. */
    fun decode(bytes: ByteArray): Drawable? =
        try {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                targetSize(info.size.width, info.size.height)?.let { decoder.setTargetSize(it.first, it.second) }
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
}
