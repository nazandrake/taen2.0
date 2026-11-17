// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/**
 * bzip2 decompression of the voice model archive (P20, 1.0.2), written from the format description (Burrows-Wheeler transform, move-to-front, run-length
 * and Huffman coding; the reference is libbz2) so that the app needs no compression library (no new dependency, nothing from a network or file library in
 * the release dex). It only reads: a damaged or unsupported stream is an IOException and never a wrong output, because every block and the whole stream end
 * with a CRC that is checked. It decodes bytes that are not yet verified: the installer hashes the compressed bytes while this class reads them and installs
 * nothing unless the archive hash and the hash of every file inside match, so a wrong output is never installed; the decoder itself is still hostile-input
 * code and is bounded (block size 900 kB at most, no recursion, all array indexes checked, the reader above it caps the compressed bytes at the pinned size).
 */
class Bzip2InputStream(source: InputStream) : InputStream() {
    private val input = BufferedInputStream(source, 1 shl 16)
    private var bitBuffer = 0L
    private var bitCount = 0

    private var blockSizeMax = 0
    private var tt = IntArray(0)
    private var blockLength = 0
    private var blockPos = 0
    private var tPos = 0
    private var started = false
    private var finished = false
    private var blockOpen = false
    private var blockCrcStored = 0
    private var blockCrc = -1
    private var combinedCrc = 0

    // run-length stage 1 state (four equal bytes are followed by a repeat count)
    private var lastByte = -1
    private var runLength = 0
    private var repeatLeft = 0
    private var repeatByte = 0

    private val unzftab = IntArray(256)
    private val seqToUnseq = IntArray(256)
    private val selectors = ByteArray(MAX_SELECTORS)
    private val limit = Array(MAX_GROUPS) { IntArray(MAX_CODE_LENGTH + 2) }
    private val base = Array(MAX_GROUPS) { IntArray(MAX_CODE_LENGTH + 2) }
    private val perm = Array(MAX_GROUPS) { IntArray(MAX_ALPHA_SIZE) }
    private val minLens = IntArray(MAX_GROUPS)
    private val maxLens = IntArray(MAX_GROUPS)

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        var n = 0
        try {
            while (n < len) {
                val c = nextByte()
                if (c < 0) break
                b[off + n++] = c.toByte()
            }
        } catch (_: IndexOutOfBoundsException) {
            throw IOException("bzip2: damaged stream") // every index is checked; this is the last guard, a damaged stream is never another exception type
        }
        return if (n == 0) -1 else n
    }

    override fun close() = input.close()

    // ---- output: stage 1 run-length decoding of the block, from the inverse BWT ----------------------------------------

    private fun nextByte(): Int {
        while (true) {
            if (repeatLeft > 0) { repeatLeft--; crc(repeatByte); return repeatByte }
            if (blockPos < blockLength) {
                val c = bwtByte()
                if (runLength == 4) { // the byte after four equal bytes is the number of further repeats
                    runLength = 0
                    lastByte = -1
                    repeatByte = repeatByteAfterRun
                    repeatLeft = c
                    continue
                }
                if (c == lastByte) runLength++ else { runLength = 1; lastByte = c }
                if (runLength == 4) repeatByteAfterRun = c
                crc(c)
                return c
            }
            if (blockOpen) endBlock()
            if (finished) return -1
            if (!nextBlock()) return -1
        }
    }

    private fun crc(b: Int) {
        blockCrc = (blockCrc shl 8) xor CRC_TABLE[((blockCrc ushr 24) xor b) and 0xff]
    }

    /** The whole block was given out: its CRC (bzip2's CRC-32, most significant bit first) must be the one in the block header. */
    private fun endBlock() {
        blockOpen = false
        if (blockCrc.inv() != blockCrcStored) throw IOException("bzip2: block CRC mismatch")
        combinedCrc = ((combinedCrc shl 1) or (combinedCrc ushr 31)) xor blockCrcStored
    }

    private var repeatByteAfterRun = 0

    private fun bwtByte(): Int {
        tPos = tt[tPos]
        val c = tPos and 0xff
        tPos = tPos ushr 8
        blockPos++
        return c
    }

    // ---- bits ------------------------------------------------------------------------------------------------------------

    private fun bits(n: Int): Int {
        while (bitCount < n) {
            val b = input.read()
            if (b < 0) throw IOException("bzip2: the stream ends early")
            bitBuffer = (bitBuffer shl 8) or b.toLong()
            bitCount += 8
        }
        val v = ((bitBuffer shr (bitCount - n)) and ((1L shl n) - 1)).toInt()
        bitCount -= n
        bitBuffer = bitBuffer and ((1L shl bitCount) - 1)
        return v
    }

    private fun bit(): Boolean = bits(1) == 1

    // ---- blocks ----------------------------------------------------------------------------------------------------------

    /** Reads the next block (or the end of the stream); false at the end. */
    private fun nextBlock(): Boolean {
        if (!started) {
            if (bits(8) != 'B'.code || bits(8) != 'Z'.code || bits(8) != 'h'.code) throw IOException("bzip2: not a bzip2 stream")
            val level = bits(8) - '0'.code
            if (level !in 1..9) throw IOException("bzip2: bad block size")
            blockSizeMax = level * 100_000
            tt = IntArray(blockSizeMax)
            started = true
        }
        val magicHigh = bits(24)
        val magicLow = bits(24)
        if (magicHigh == 0x177245 && magicLow == 0x385090) { // end of the stream
            val stored = (bits(16) shl 16) or bits(16)
            if (stored != combinedCrc) throw IOException("bzip2: stream CRC mismatch")
            bitCount = 0; bitBuffer = 0
            finished = true
            return false
        }
        if (magicHigh != 0x314159 || magicLow != 0x265359) throw IOException("bzip2: bad block header")
        blockCrcStored = (bits(16) shl 16) or bits(16)
        if (bit()) throw IOException("bzip2: randomised blocks are not supported")
        val origPtr = bits(24)
        readBlock(origPtr)
        return true
    }

    private fun readBlock(origPtr: Int) {
        // the symbols in use
        val inUse16 = bits(16)
        var nInUse = 0
        for (i in 0 until 16) if (inUse16 and (0x8000 ushr i) != 0) {
            val inUse = bits(16)
            for (j in 0 until 16) if (inUse and (0x8000 ushr j) != 0) seqToUnseq[nInUse++] = i * 16 + j
        }
        if (nInUse == 0) throw IOException("bzip2: no symbols")
        val alphaSize = nInUse + 2
        val nGroups = bits(3)
        if (nGroups !in 2..MAX_GROUPS) throw IOException("bzip2: bad number of tables")
        val nSelectors = bits(15)
        if (nSelectors < 1) throw IOException("bzip2: no selectors")
        // selectors, move-to-front coded
        val order = ByteArray(MAX_GROUPS) { it.toByte() }
        for (i in 0 until nSelectors) {
            var j = 0
            while (bit()) { j++; if (j >= nGroups) throw IOException("bzip2: bad selector") }
            val v = order[j]
            for (k in j downTo 1) order[k] = order[k - 1]
            order[0] = v
            if (i < MAX_SELECTORS) selectors[i] = v
        }
        val usedSelectors = minOf(nSelectors, MAX_SELECTORS)
        // code lengths of each table, delta coded
        val lengths = Array(nGroups) { IntArray(alphaSize) }
        for (t in 0 until nGroups) {
            var curr = bits(5)
            for (i in 0 until alphaSize) {
                while (true) {
                    if (curr < 1 || curr > MAX_CODE_LENGTH) throw IOException("bzip2: bad code length")
                    if (!bit()) break
                    if (!bit()) curr++ else curr--
                }
                lengths[t][i] = curr
            }
        }
        for (t in 0 until nGroups) buildTables(t, lengths[t], alphaSize)

        // the symbols: run-length (RUNA, RUNB) and move-to-front coded
        unzftab.fill(0)
        val mtf = IntArray(256) { it }
        val endOfBlock = nInUse + 1
        var nblock = 0
        groupIndex = -1
        groupLeft = 0
        symbolAlphaSize = alphaSize
        symbolSelectors = usedSelectors
        var sym = nextSymbol()
        while (sym != endOfBlock) {
            if (sym <= 1) { // RUNA / RUNB: a run of the front symbol, its length in bijective base 2
                var run = 0
                var weight = 1
                do {
                    run += if (sym == 0) weight else weight * 2
                    weight = weight shl 1
                    if (run > blockSizeMax) throw IOException("bzip2: run too long")
                    sym = nextSymbol()
                } while (sym <= 1)
                val value = seqToUnseq[mtf[0]]
                if (nblock + run > blockSizeMax) throw IOException("bzip2: block too long")
                unzftab[value] += run
                tt.fill(value, nblock, nblock + run)
                nblock += run
            } else {
                if (nblock >= blockSizeMax) throw IOException("bzip2: block too long")
                val pos = sym - 1
                val front = mtf[pos]
                for (k in pos downTo 1) mtf[k] = mtf[k - 1]
                mtf[0] = front
                val value = seqToUnseq[front]
                unzftab[value]++
                tt[nblock++] = value
                sym = nextSymbol()
            }
        }
        if (nblock == 0 || origPtr >= nblock) throw IOException("bzip2: bad block")

        // inverse Burrows-Wheeler transform: tt[i] = byte | (index of the next position) << 8
        val cftab = IntArray(257)
        for (i in 1..256) cftab[i] = cftab[i - 1] + unzftab[i - 1]
        for (i in 0 until nblock) {
            val c = tt[i] and 0xff
            tt[cftab[c]] = tt[cftab[c]] or (i shl 8)
            cftab[c]++
        }
        blockLength = nblock
        blockPos = 0
        tPos = tt[origPtr] ushr 8
        lastByte = -1
        runLength = 0
        repeatLeft = 0
        blockCrc = -1
        blockOpen = true
    }

    private var groupIndex = 0
    private var groupLeft = 0
    private var groupTable = 0
    private var symbolAlphaSize = 0
    private var symbolSelectors = 0

    /** The next Huffman symbol of the block: the table changes every [GROUP_SIZE] symbols, as the selectors say. */
    private fun nextSymbol(): Int {
        if (groupLeft == 0) {
            groupIndex++
            if (groupIndex >= symbolSelectors) throw IOException("bzip2: out of selectors")
            groupTable = selectors[groupIndex].toInt()
            groupLeft = GROUP_SIZE
        }
        groupLeft--
        val table = groupTable
        var len = minLens[table]
        var code = bits(len)
        while (len > maxLens[table] || code > limit[table][len]) {
            len++
            if (len > MAX_CODE_LENGTH) throw IOException("bzip2: bad code")
            code = (code shl 1) or bits(1)
        }
        val index = code - base[table][len]
        if (index < 0 || index >= symbolAlphaSize) throw IOException("bzip2: bad code")
        return perm[table][index]
    }

    /** The canonical Huffman decoding tables of one coding table (the construction of libbz2: limit, base, perm per code length). */
    private fun buildTables(t: Int, length: IntArray, alphaSize: Int) {
        var minLen = 32
        var maxLen = 0
        for (i in 0 until alphaSize) { if (length[i] > maxLen) maxLen = length[i]; if (length[i] < minLen) minLen = length[i] }
        minLens[t] = minLen
        maxLens[t] = maxLen
        var pp = 0
        for (i in minLen..maxLen) for (j in 0 until alphaSize) if (length[j] == i) perm[t][pp++] = j
        val b = base[t]
        val l = limit[t]
        b.fill(0); l.fill(0)
        for (i in 0 until alphaSize) b[length[i] + 1]++
        for (i in 1 until b.size) b[i] += b[i - 1]
        var vec = 0
        for (i in minLen..maxLen) {
            vec += b[i + 1] - b[i]
            l[i] = vec - 1
            vec = vec shl 1
        }
        for (i in minLen + 1..maxLen) b[i] = ((l[i - 1] + 1) shl 1) - b[i]
    }

    companion object {
        private const val MAX_GROUPS = 6
        private const val MAX_ALPHA_SIZE = 258
        private const val MAX_CODE_LENGTH = 20
        private const val GROUP_SIZE = 50
        private const val MAX_SELECTORS = 18_002
        private val CRC_TABLE = IntArray(256) { i ->
            var c = i shl 24
            repeat(8) { c = if (c < 0) (c shl 1) xor 0x04c11db7 else c shl 1 }
            c
        }
    }
}
