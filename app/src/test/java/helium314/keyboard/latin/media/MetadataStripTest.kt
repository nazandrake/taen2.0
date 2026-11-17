// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import helium314.keyboard.latin.privacy.indexOfSub
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * After the strip none of the metadata bytes remain and the image data segments are byte-identical. The expected pictures are built segment by segment from the
 * format specifications (MediaFixtures), not by running the strip.
 */
class MetadataStripTest {
    private val canaries = listOf(MediaFixtures.EXIF, MediaFixtures.XMP, MediaFixtures.ICC, MediaFixtures.TEXT, MediaFixtures.TRAIL)

    private fun assertNoCanary(bytes: ByteArray, what: String) {
        for (c in canaries) assertEquals(-1, bytes.indexOfSub(MediaFixtures.ascii(c)), "$what still holds $c")
    }

    // ---- the four formats ---------------------------------------------------------------------------

    @Test fun jpegKeepsTheImageSegmentsAndLosesExifXmpIccIptcCommentAndTrailer() {
        assertTrue(MediaFixtures.jpegDirty.indexOfSub(MediaFixtures.ascii(MediaFixtures.EXIF)) >= 0, "positive control: the fixture holds the canary")
        val out = MetadataStrip.strip(MediaFixtures.jpegDirty, MediaFormat.JPEG)
        assertNoCanary(out, "jpeg")
        assertContentEquals(MediaFixtures.jpegClean, out)
    }

    @Test fun pngKeepsTheImageChunksAndLosesTextExifIccTimeAndTrailer() {
        assertTrue(MediaFixtures.pngDirty.indexOfSub(MediaFixtures.ascii(MediaFixtures.TEXT)) >= 0)
        val out = MetadataStrip.strip(MediaFixtures.pngDirty, MediaFormat.PNG)
        assertNoCanary(out, "png")
        assertContentEquals(MediaFixtures.pngClean, out)
    }

    @Test fun gifKeepsFramesDelayAndLoopAndLosesCommentPlainTextXmpAndTrailer() {
        assertTrue(MediaFixtures.gifDirty.indexOfSub(MediaFixtures.ascii(MediaFixtures.XMP)) >= 0)
        val out = MetadataStrip.strip(MediaFixtures.gifDirty, MediaFormat.GIF)
        assertNoCanary(out, "gif")
        assertContentEquals(MediaFixtures.gifClean, out)
    }

    @Test fun webpKeepsTheImageAndRewritesTheFlagsAndTheSize() {
        assertTrue(MediaFixtures.webpDirty.indexOfSub(MediaFixtures.ascii(MediaFixtures.EXIF)) >= 0)
        val out = MetadataStrip.strip(MediaFixtures.webpDirty, MediaFormat.WEBP)
        assertNoCanary(out, "webp")
        assertContentEquals(MediaFixtures.webpClean, out)
    }

    // ---- invariants, other spots, refusals ------------------------------------------------------------

    @Test fun aCleanPictureComesOutUnchangedAndStrippingTwiceChangesNothing() {
        for ((format, clean) in listOf(MediaFormat.JPEG to MediaFixtures.jpegClean, MediaFormat.PNG to MediaFixtures.pngClean, MediaFormat.GIF to MediaFixtures.gifClean, MediaFormat.WEBP to MediaFixtures.webpClean)) {
            assertContentEquals(clean, MetadataStrip.strip(clean, format), "$format")
            assertContentEquals(clean, MetadataStrip.strip(MetadataStrip.strip(clean, format), format), "$format twice")
        }
    }

    @Test fun theMagicBytesPickTheFormat() {
        assertEquals(MediaFormat.JPEG, MediaFormat.sniff(MediaFixtures.jpegDirty))
        assertEquals(MediaFormat.PNG, MediaFormat.sniff(MediaFixtures.pngDirty))
        assertEquals(MediaFormat.GIF, MediaFormat.sniff(MediaFixtures.gifDirty))
        assertEquals(MediaFormat.GIF, MediaFormat.sniff(MediaFixtures.ascii("GIF87a")))
        assertEquals(MediaFormat.WEBP, MediaFormat.sniff(MediaFixtures.webpDirty))
        for (other in listOf(ByteArray(0), MediaFixtures.ascii("hello world, not a picture"), MediaFixtures.bytes(0xFF, 0xD8), MediaFixtures.ascii("RIFF....WAVE"), MediaFixtures.ascii("GIF90a"), MediaFixtures.bytes(0x89, 0x50, 0x4E, 0x47)))
            assertNull(MediaFormat.sniff(other), other.toList().take(8).toString())
    }

    @Test fun jpegSegmentsBetweenScansAreKeptAndTheScanDataIsNotCutAtAStuffedByteOrRestartMarker() {
        val twoScans = MediaFixtures.concat(MediaFixtures.jpegSoi, MediaFixtures.jpegDqt, MediaFixtures.jpegSof, MediaFixtures.jpegDht, MediaFixtures.jpegSos, MediaFixtures.jpegScan,
            MediaFixtures.jpegDht, MediaFixtures.jpegSos, MediaFixtures.jpegScan, MediaFixtures.jpegEoi)
        assertContentEquals(twoScans, MetadataStrip.strip(twoScans, MediaFormat.JPEG))
    }

    @Test fun jpegAdobeMarkerStaysAndOtherApplicationSegmentsGo() {
        val adobe = MediaFixtures.concat(MediaFixtures.bytes(0xFF, 0xEE, 0, 14), MediaFixtures.ascii("Adobe"), MediaFixtures.bytes(0, 100, 0, 0, 0, 0, 1))
        val app11 = MediaFixtures.concat(MediaFixtures.bytes(0xFF, 0xEB, 0, 7), MediaFixtures.ascii("JUMBF"))
        val src = MediaFixtures.concat(MediaFixtures.jpegSoi, app11, adobe, MediaFixtures.jpegDqt, MediaFixtures.jpegSof, MediaFixtures.jpegSos, MediaFixtures.jpegScan, MediaFixtures.jpegEoi)
        val want = MediaFixtures.concat(MediaFixtures.jpegSoi, adobe, MediaFixtures.jpegDqt, MediaFixtures.jpegSof, MediaFixtures.jpegSos, MediaFixtures.jpegScan, MediaFixtures.jpegEoi)
        assertContentEquals(want, MetadataStrip.strip(src, MediaFormat.JPEG))
    }

    @Test fun pngKeepsTheAnimationChunks() {
        val apng = MediaFixtures.concat(MediaFixtures.bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), MediaFixtures.pngIhdr, MediaFixtures.pchunk("acTL", MediaFixtures.bytes(0, 0, 0, 2, 0, 0, 0, 0)),
            MediaFixtures.pchunk("fcTL", ByteArray(26)), MediaFixtures.pngIdat, MediaFixtures.pchunk("fdAT", ByteArray(8)), MediaFixtures.pngIend)
        assertContentEquals(apng, MetadataStrip.strip(apng, MediaFormat.PNG))
    }

    @Test fun animatedWebpFramesStayWhole() {
        val frames = MediaFixtures.concat(MediaFixtures.ascii("ANIM"), MediaFixtures.bytes(6, 0, 0, 0, 255, 255, 255, 255, 0, 0))
        val anmf = MediaFixtures.concat(MediaFixtures.ascii("ANMF"), MediaFixtures.bytes(16, 0, 0, 0), ByteArray(16) { 7 })
        val riff = { chunks: ByteArray -> MediaFixtures.concat(MediaFixtures.ascii("RIFF"), MediaFixtures.bytes(4 + chunks.size, (4 + chunks.size) shr 8, 0, 0), MediaFixtures.ascii("WEBP"), chunks) }
        val vp8x = MediaFixtures.concat(MediaFixtures.ascii("VP8X"), MediaFixtures.bytes(10, 0, 0, 0, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        val src = riff(MediaFixtures.concat(vp8x, frames, anmf))
        assertContentEquals(src, MetadataStrip.strip(src, MediaFormat.WEBP))
    }

    @Test fun brokenPicturesAreRefusedNotPassedThrough() {
        val cut = { b: ByteArray, n: Int -> b.copyOf(n) }
        val refused = listOf(
            MediaFormat.JPEG to cut(MediaFixtures.jpegDirty, MediaFixtures.jpegDirty.size - MediaFixtures.TRAIL.length - 2), // no end-of-image marker
            MediaFormat.JPEG to MediaFixtures.concat(MediaFixtures.jpegSoi, MediaFixtures.bytes(0xFF, 0xE1, 0xFF, 0xFF), ByteArray(10)), // a segment longer than the file
            MediaFormat.JPEG to MediaFixtures.jpegSoi,
            MediaFormat.PNG to cut(MediaFixtures.pngDirty, 40), // cut inside a chunk
            MediaFormat.PNG to MediaFixtures.concat(MediaFixtures.bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), MediaFixtures.pngIhdr, MediaFixtures.pngIdat), // no IEND
            MediaFormat.PNG to MediaFixtures.concat(MediaFixtures.bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), MediaFixtures.pngIdat, MediaFixtures.pngIend), // no IHDR first
            MediaFormat.PNG to MediaFixtures.concat(MediaFixtures.bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), MediaFixtures.pngIhdr, MediaFixtures.pchunk("IDAT", MediaFixtures.bytes(1, 2, 3), badCrc = true), MediaFixtures.pngIend),
            MediaFormat.GIF to MediaFixtures.concat(MediaFixtures.gifClean.copyOf(MediaFixtures.gifClean.size - 1)), // no trailer
            MediaFormat.GIF to MediaFixtures.concat(MediaFixtures.gifClean.copyOf(20), MediaFixtures.bytes(0x99)), // an unknown block
            MediaFormat.GIF to MediaFixtures.gifClean.copyOf(8),
            MediaFormat.WEBP to cut(MediaFixtures.webpDirty, 30), // shorter than the size in the header
            MediaFormat.WEBP to MediaFixtures.concat(MediaFixtures.ascii("RIFF"), MediaFixtures.bytes(4, 0, 0, 0), MediaFixtures.ascii("WEBP")), // no image chunk
            MediaFormat.JPEG to ByteArray(0), MediaFormat.PNG to ByteArray(0), MediaFormat.GIF to ByteArray(0), MediaFormat.WEBP to ByteArray(0),
        )
        for ((format, bytes) in refused) assertFailsWith<MediaParseException>("$format ${bytes.size} bytes") { MetadataStrip.strip(bytes, format) }
    }
}
