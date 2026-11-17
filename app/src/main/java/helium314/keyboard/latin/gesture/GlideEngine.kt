// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
/*
 * Glide (gesture) typing engine: the finger path is compared with the ideal path of every word of the
 * dictionary, the "statistical glide typing classifier" of FlorisBoard v0.4.0 (Apache-2.0, Copyright The
 * FlorisBoard Contributors, app/.../ime/text/gestures/StatisticalGlideTypingClassifier.kt, git commit
 * 80fb20885ba335947b6fd8f3449de8be11c0e13d), which in turn follows Etienne Desticourt's write-up for
 * AnySoftKeyboard (https://github.com/AnySoftKeyboard/AnySoftKeyboard/pull/1870, Apache-2.0).
 * What is kept: the model (ideal gesture through the key centres, with a small loop for double letters;
 * shape distance of the box-normalised paths and location distance in key sizes, both Gaussian, same standard
 * deviations; pruning by first and last key and by path length) and the constants. What is new: the code is a
 * rewrite without Android types, the scores are costs in the log domain (the product of probabilities
 * underflows on long paths), the dictionary probability is a weighted cost term, paths are resampled to an
 * exact point count, candidates are first ranked on a coarse resampling and only the best are rescored on a
 * fine one (the reference phone budget), and the last touch point is never dropped by the distance filter.
 * No network, no model file, no stored data: the engine keeps nothing of a gesture after suggest() returns.
 */
package helium314.keyboard.latin.gesture

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A letter key in keyboard pixels: centre and size. [code] is the lower-case letter. */
class GlideKey(val code: Int, val x: Float, val y: Float, val width: Float, val height: Float)

/** The letter keys of the current layout, sorted by code (so every layout with the same letters shares word indexes). */
class GlideKeyboard(keys: List<GlideKey>) {
    private val sorted: List<GlideKey> = keys.map { GlideKey(Character.toLowerCase(it.code), it.x, it.y, it.width, it.height) }
        .distinctBy { it.code }.sortedBy { it.code }
    val count = sorted.size
    val codes = IntArray(count) { sorted[it].code }
    val x = FloatArray(count) { sorted[it].x }
    val y = FloatArray(count) { sorted[it].y }

    /** The size of a key: the smaller side of the average key. */
    val radius: Float = if (count == 0) 1f else min(sorted.map { it.width }.average().toFloat(), sorted.map { it.height }.average().toFloat())

    // the key a character is typed on: the character itself, else its base letter ("é" on the "e" key)
    private val keyOfChar = IntArray(TABLE_SIZE) { -1 }

    init {
        val byCode = HashMap<Int, Int>()
        codes.forEachIndexed { i, c -> byCode[c] = i }
        for (c in 0 until TABLE_SIZE) {
            val lower = Character.toLowerCase(c)
            val exact = byCode[lower]
            keyOfChar[c] = exact
                ?: byCode[Normalizer.normalize(String(Character.toChars(lower)), Normalizer.Form.NFD).codePointAt(0)]
                ?: -1
        }
    }

    /** The key index of [c], or -1 when no key types it (apostrophe, hyphen, other scripts). */
    fun keyOf(c: Char): Int = if (c.code < TABLE_SIZE) keyOfChar[c.code] else -1

    fun sameLetters(other: GlideKeyboard) = codes.contentEquals(other.codes)

    private companion object {
        const val TABLE_SIZE = 0x600 // Latin, Greek, Cyrillic
    }
}

/** All words of one dictionary with their probability (0..255), kept as flat arrays (a Bulgarian list has 400 000 words). */
class GlideLexicon private constructor(
    private val chars: CharArray,
    private val starts: IntArray,
    private val probabilities: ByteArray,
) {
    val size get() = probabilities.size

    fun word(i: Int) = String(chars, starts[i], starts[i + 1] - starts[i])
    fun probability(i: Int) = probabilities[i].toInt() and 0xFF
    fun charAt(i: Int, j: Int) = chars[starts[i] + j]
    fun length(i: Int) = starts[i + 1] - starts[i]

    class Builder {
        private var chars = CharArray(1 shl 16)
        private var charCount = 0
        private var starts = IntArray(1 shl 12)
        private var probabilities = ByteArray(1 shl 12)
        private var size = 0

        fun add(word: String, probability: Int) {
            if (size + 1 >= starts.size) {
                starts = starts.copyOf(starts.size * 2)
                probabilities = probabilities.copyOf(starts.size)
            }
            if (charCount + word.length > chars.size) chars = chars.copyOf(max(chars.size * 2, charCount + word.length))
            word.toCharArray(chars, charCount)
            starts[size] = charCount
            probabilities[size] = probability.coerceIn(0, 255).toByte()
            charCount += word.length
            size++
        }

        fun build(): GlideLexicon {
            starts[size] = charCount
            return GlideLexicon(chars.copyOf(charCount), starts.copyOf(size + 1), probabilities.copyOf(size))
        }
    }

    /** Words by (first key, last key) for one set of letter keys. */
    class Index(val keyCount: Int, private val bucketStart: IntArray, private val wordIds: IntArray) {
        fun words(first: Int, last: Int, out: IntList) {
            val b = first * keyCount + last
            for (i in bucketStart[b] until bucketStart[b + 1]) out.add(wordIds[i])
        }
    }

    private var cachedFor: GlideKeyboard? = null
    private var cached: Index? = null

    @Synchronized
    fun indexFor(keyboard: GlideKeyboard): Index {
        val known = cached
        if (known != null && cachedFor!!.sameLetters(keyboard)) return known
        val k = keyboard.count
        val first = IntArray(size)
        val last = IntArray(size)
        val counts = IntArray(k * k + 1)
        for (w in 0 until size) {
            var f = -1
            var l = -1
            var mapped = 0
            var distinctSteps = false
            for (j in 0 until length(w)) {
                val key = keyboard.keyOf(charAt(w, j))
                if (key < 0) continue
                if (f < 0) f = key else if (key != l) distinctSteps = true
                l = key
                mapped++
            }
            // a word that stays on one key has no path to follow
            if (mapped < 2 || !distinctSteps) { first[w] = -1; continue }
            first[w] = f
            last[w] = l
            counts[f * k + l + 1]++
        }
        for (b in 0 until k * k) counts[b + 1] += counts[b]
        val fill = counts.copyOf()
        val ids = IntArray(counts[k * k])
        for (w in 0 until size) if (first[w] >= 0) ids[fill[first[w] * k + last[w]]++] = w
        return Index(k, counts, ids).also { cached = it; cachedFor = keyboard }
    }
}

/** A growable list of ints without boxing. */
class IntList(capacity: Int = 256) {
    var data = IntArray(capacity)
    var size = 0
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun clear() { size = 0 }
}

class GlideCandidate(val word: String, val cost: Float)

/** Tunable numbers; the defaults are the ones the quality gate (GlideQualityGateTest) is run with. */
class GlideParams(
    /** Std of the summed distance of two shapes of 200 points each, box-normalised (FlorisBoard SHAPE_STD). */
    val shapeStd: Float = 22.08f,
    /** Std of the mean location distance in key sizes (FlorisBoard LOCATION_STD). */
    val locationStd: Float = 0.5109f,
    /** Weight of the dictionary probability cost, ln(256 / (probability + 1)). */
    val frequencyWeight: Float = 0.5f,
    /** Path length band: user length within [ideal * lengthLow - slack keys, ideal * lengthHigh + slack keys]. */
    val lengthLow: Float = 0.6f,
    val lengthHigh: Float = 1.6f,
    val lengthSlackKeys: Float = 1.5f,
    val coarsePoints: Int = 24,
    val finePoints: Int = 100,
    val rescored: Int = 40,
    val nearestStartKeys: Int = 2,
    val nearestEndKeys: Int = 2,
)

class GlideEngine(private val params: GlideParams = GlideParams()) {
    private val maxPoints = 500

    // scratch, reused: the engine is used by one thread at a time (GlideSuggester serialises)
    private var px = FloatArray(64)
    private var py = FloatArray(64)

    /**
     * The best [maxResults] words for the touch points xs[0 until count], ys[0 until count], lowest cost
     * first. A path that is not a glide (shorter than one key) gives none.
     */
    @Synchronized
    fun suggest(
        lexicon: GlideLexicon, keyboard: GlideKeyboard, xs: FloatArray, ys: FloatArray, count: Int, maxResults: Int,
    ): List<GlideCandidate> {
        if (keyboard.count < 2 || count < 2 || maxResults <= 0) return emptyList()
        val radius = keyboard.radius
        val thinned = thin(xs, ys, count, radius / 4, maxPoints)
        val ux = thinned.x
        val uy = thinned.y
        val n = thinned.n
        if (n < 2) return emptyList()
        val userLength = pathLength(ux, uy, n)
        if (userLength < radius) return emptyList()

        val ids = IntList()
        val index = lexicon.indexFor(keyboard)
        val startKeys = nearest(keyboard, ux[0], uy[0], params.nearestStartKeys)
        val endKeys = nearest(keyboard, ux[n - 1], uy[n - 1], params.nearestEndKeys)
        for (s in startKeys) for (e in endKeys) index.words(s, e, ids)

        val userCoarse = Shape(params.coarsePoints).also { it.set(ux, uy, n, userLength) }
        val idealCoarse = Shape(params.coarsePoints)
        val keepCount = params.rescored
        val keptId = IntArray(keepCount + 1)
        val keptCost = FloatArray(keepCount + 1)
        var kept = 0
        val lowerBound = { ideal: Float -> ideal * params.lengthLow - params.lengthSlackKeys * radius }
        val upperBound = { ideal: Float -> ideal * params.lengthHigh + params.lengthSlackKeys * radius }

        for (c in 0 until ids.size) {
            val w = ids.data[c]
            val m = idealPath(lexicon, keyboard, w, false)
            if (m < 2) continue
            val idealLength = pathLength(px, py, m)
            if (userLength < lowerBound(idealLength) || userLength > upperBound(idealLength)) continue
            idealCoarse.set(px, py, m, idealLength)
            val cost = cost(userCoarse, idealCoarse, radius, lexicon.probability(w))
            // insertion into the sorted list of the best
            var pos = kept
            while (pos > 0 && keptCost[pos - 1] > cost) pos--
            if (pos >= keepCount) continue
            val end = min(kept, keepCount - 1)
            for (j in end downTo pos) { keptCost[j + 1] = keptCost[j]; keptId[j + 1] = keptId[j] }
            keptCost[pos] = cost; keptId[pos] = w
            if (kept < keepCount) kept++
        }
        if (kept == 0) return emptyList()

        // fine rescoring of the best, including the looped variant of double letters
        val userFine = Shape(params.finePoints).also { it.set(ux, uy, n, userLength) }
        val idealFine = Shape(params.finePoints)
        val finalIds = ArrayList<Int>()
        val finalCosts = ArrayList<Float>()
        for (i in 0 until kept) {
            val w = keptId[i]
            var best = Float.MAX_VALUE
            for (loops in booleanArrayOf(false, true)) {
                if (loops && !hasDoubleLetter(lexicon, keyboard, w)) continue
                val m = idealPath(lexicon, keyboard, w, loops)
                if (m < 2) continue
                idealFine.set(px, py, m, pathLength(px, py, m))
                best = min(best, cost(userFine, idealFine, radius, lexicon.probability(w)))
            }
            if (best < Float.MAX_VALUE) { finalIds.add(w); finalCosts.add(best) }
        }
        val order = finalIds.indices.sortedBy { finalCosts[it] }
        val seen = HashSet<String>()
        val out = ArrayList<GlideCandidate>()
        for (i in order) {
            val word = lexicon.word(finalIds[i])
            if (!seen.add(word.lowercase())) continue
            out.add(GlideCandidate(word, finalCosts[i]))
            if (out.size == maxResults) break
        }
        return out
    }

    /** A path resampled to [points] evenly spaced points, plus the same scaled to a unit box and centred. */
    private class Shape(val points: Int) {
        val x = FloatArray(points)
        val y = FloatArray(points)
        val nx = FloatArray(points)
        val ny = FloatArray(points)

        fun set(sx: FloatArray, sy: FloatArray, n: Int, length: Float) {
            resample(sx, sy, n, length, points, x, y)
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (i in 0 until points) {
                minX = min(minX, x[i]); maxX = max(maxX, x[i]); minY = min(minY, y[i]); maxY = max(maxY, y[i])
            }
            val side = max(max(maxX - minX, maxY - minY), 0.00001f)
            val cx = (minX + maxX) / 2
            val cy = (minY + maxY) / 2
            for (i in 0 until points) { nx[i] = (x[i] - cx) / side; ny[i] = (y[i] - cy) / side }
        }
    }

    // cost of the ideal path against the user path, both resampled to the same point count
    private fun cost(user: Shape, ideal: Shape, radius: Float, probability: Int): Float {
        val points = user.points
        // location: mean of (|dx| + |dy|) / 2 against the key size
        var loc = 0f
        for (i in 0 until points) loc += abs(ideal.x[i] - user.x[i]) + abs(ideal.y[i] - user.y[i])
        loc /= points * 2f
        // shape: the summed distance, scaled to the 200 points the standard deviation was measured with
        var shape = 0f
        for (i in 0 until points) shape += dist(ideal.nx[i] - user.nx[i], ideal.ny[i] - user.ny[i])
        shape = shape / points * 200f
        val shapeCost = 0.5f * (shape / params.shapeStd).let { it * it }
        val locCost = 0.5f * (loc / (params.locationStd * radius)).let { it * it }
        val freqCost = params.frequencyWeight * ln(256f / (probability + 1))
        return shapeCost + locCost + freqCost
    }

    private fun hasDoubleLetter(lexicon: GlideLexicon, keyboard: GlideKeyboard, w: Int): Boolean {
        var prev = -1
        for (j in 0 until lexicon.length(w)) {
            val key = keyboard.keyOf(lexicon.charAt(w, j))
            if (key < 0) continue
            if (key == prev) return true
            prev = key
        }
        return false
    }

    /**
     * Writes the ideal path of word [w] to px/py (key centres, letters without a key skipped) and returns the
     * point count. With [loops] a double letter adds a small square loop on its key, so "pool" and "pol" differ.
     */
    private fun idealPath(lexicon: GlideLexicon, keyboard: GlideKeyboard, w: Int, loops: Boolean): Int {
        val len = lexicon.length(w)
        if (px.size < len * 5) { px = FloatArray(len * 5); py = FloatArray(len * 5) }
        var m = 0
        var prev = -1
        for (j in 0 until len) {
            val key = keyboard.keyOf(lexicon.charAt(w, j))
            if (key < 0) continue
            if (key == prev) {
                // the same key again: nothing on a plain path (the finger stays), a loop on the looped one
                if (loops) {
                    val hw = keyboard.radius / 4
                    px[m] = keyboard.x[key] + hw; py[m++] = keyboard.y[key] + hw
                    px[m] = keyboard.x[key] + hw; py[m++] = keyboard.y[key] - hw
                    px[m] = keyboard.x[key] - hw; py[m++] = keyboard.y[key] - hw
                    px[m] = keyboard.x[key] - hw; py[m++] = keyboard.y[key] + hw
                }
            } else {
                px[m] = keyboard.x[key]; py[m++] = keyboard.y[key]
            }
            prev = key
        }
        return m
    }

    private fun nearest(keyboard: GlideKeyboard, x: Float, y: Float, n: Int): IntArray {
        val take = min(n, keyboard.count)
        val best = IntArray(take) { -1 }
        val bestD = FloatArray(take) { Float.MAX_VALUE }
        for (k in 0 until keyboard.count) {
            val dx = keyboard.x[k] - x
            val dy = keyboard.y[k] - y
            val d = dx * dx + dy * dy
            var pos = take
            while (pos > 0 && bestD[pos - 1] > d) pos--
            if (pos >= take) continue
            for (j in take - 2 downTo pos) { bestD[j + 1] = bestD[j]; best[j + 1] = best[j] }
            bestD[pos] = d; best[pos] = k
        }
        return best
    }

    private fun pathLength(x: FloatArray, y: FloatArray, n: Int): Float {
        var l = 0f
        for (i in 1 until n) l += dist(x[i] - x[i - 1], y[i] - y[i - 1])
        return l
    }
}

// Math.hypot is about ten times slower than this on the reference phone and the values are far from overflow
private fun dist(dx: Float, dy: Float) = sqrt(dx * dx + dy * dy)

/** [points] points evenly spaced along the path x/y[0 until n] (first and last included). */
private fun resample(x: FloatArray, y: FloatArray, n: Int, length: Float, points: Int, outX: FloatArray, outY: FloatArray) {
    val step = length / (points - 1)
    var seg = 0
    var segStart = 0f // path length at the start of segment seg
    var segLen = if (n > 1) dist(x[1] - x[0], y[1] - y[0]) else 0f
    for (p in 0 until points) {
        val target = p * step
        while (seg < n - 2 && segStart + segLen < target) {
            segStart += segLen
            seg++
            segLen = dist(x[seg + 1] - x[seg], y[seg + 1] - y[seg])
        }
        val t = if (segLen > 0f) ((target - segStart) / segLen).coerceIn(0f, 1f) else 0f
        outX[p] = x[seg] + (x[seg + 1] - x[seg]) * t
        outY[p] = y[seg] + (y[seg + 1] - y[seg]) * t
    }
}

/** Touch points kept by [thin]: x[0 until n], y[0 until n]. */
internal class ThinnedPoints(val x: FloatArray, val y: FloatArray, val n: Int)

/**
 * The first point, then every point farther than [minDistance] from the one kept before it, at most [limit] points.
 * The last point is always kept (unless it equals the one before): where the finger ends decides the last letter.
 */
internal fun thin(xs: FloatArray, ys: FloatArray, count: Int, minDistance: Float, limit: Int): ThinnedPoints {
    val ux = FloatArray(min(count, limit))
    val uy = FloatArray(ux.size)
    var n = 0
    val minSq = minDistance * minDistance
    for (i in 0 until count) {
        val last = i == count - 1
        if (n > 0) {
            val dx = xs[i] - ux[n - 1]
            val dy = ys[i] - uy[n - 1]
            if (!last && dx * dx + dy * dy <= minSq) continue
            if (last && dx == 0f && dy == 0f) continue
        }
        if (n == ux.size) { if (!last) continue; n-- } // full: only the last point still replaces the final slot
        ux[n] = xs[i]; uy[n] = ys[i]; n++
    }
    return ThinnedPoints(ux, uy, n)
}
