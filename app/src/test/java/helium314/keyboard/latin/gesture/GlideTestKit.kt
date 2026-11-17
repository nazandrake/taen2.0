// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.gesture

import java.io.File
import java.util.Random
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Test geometry and a synthetic finger model for the glide tests. There are no recorded swipes (the app
 * records none and never will, see the privacy contract), so the gestures of the quality gate are
 * generated from the key geometry of the real layout files with a seeded noise model.
 */
object GlideTestKit {
    const val WIDTH = 1080f
    const val KEY_HEIGHT = 140f

    /** The simple layout files of assets/layouts/main: one key per line (first token is the label), blank line between rows. */
    fun layoutRows(name: String): List<List<String>> {
        val text = File("src/main/assets/layouts/main/$name.txt").readText()
        return text.trimEnd().split("\n\n").map { row -> row.lines().filter { it.isNotBlank() }.map { it.trim().split(" ")[0] } }
    }

    /**
     * Keys of the letter rows the way the keyboard lays them out: every key is as wide as the widest row allows,
     * a shorter row is centred, the last letter row has a functional key (shift, delete) on each side.
     */
    fun keys(layout: String): List<GlideKey> {
        val rows = layoutRows(layout)
        val columns = rows.maxOf { it.size }
        val keyWidth = WIDTH / columns
        val keys = ArrayList<GlideKey>()
        rows.forEachIndexed { r, row ->
            val margin = (columns - row.size) * keyWidth / 2
            row.forEachIndexed { c, label ->
                val x = margin + (c + 0.5f) * keyWidth
                keys.add(GlideKey(label.codePointAt(0), x, (r + 0.5f) * KEY_HEIGHT, keyWidth, KEY_HEIGHT))
            }
        }
        return keys
    }

    fun keyboard(layout: String) = GlideKeyboard(keys(layout))

    /** "word<TAB>probability" lines of a gzip file of src/test/resources/glide (made by tools/gen-glide-fixtures.sh). */
    fun lexiconWords(lang: String): List<Pair<String, Int>> =
        GZIPInputStream(File("src/test/resources/glide/$lang.tsv.gz").inputStream()).bufferedReader().useLines { lines ->
            lines.filter { it.isNotEmpty() }.map { it.substringBefore('\t') to it.substringAfter('\t').toInt() }.toList()
        }

    fun lexicon(words: List<Pair<String, Int>>): GlideLexicon {
        val b = GlideLexicon.Builder()
        words.forEach { b.add(it.first, it.second) }
        return b.build()
    }

    class Gesture(val xs: FloatArray, val ys: FloatArray) {
        val count get() = xs.size
    }

    /**
     * A finger drawing [word] over [kb]: each letter key is hit with a Gaussian miss of [noise] key sizes
     * plus a shift of the whole hand (0.1 key), corners are rounded by up to 0.35 key, the speed varies, and the
     * touch points (every 6 to 22 px, jittered by a pixel, whole pixels as Android delivers them) follow
     * a smooth curve through the vertices. Same [seed], same gesture.
     */
    fun gesture(word: String, kb: List<GlideKey>, noise: Float, seed: Long, hand: Float = 0.1f, cutMax: Float = 0.35f, bowStd: Float = 0.06f): Gesture {
        val rnd = Random(seed)
        val byCode = kb.associateBy { it.code }
        val keys = word.lowercase().map { byCode[it.code] }.filterNotNull()
        val vertices = ArrayList<FloatArray>()
        val handX = rnd.nextGaussian().toFloat() * hand * keys[0].width
        val handY = rnd.nextGaussian().toFloat() * hand * keys[0].height
        for (k in keys) {
            vertices.add(floatArrayOf(
                k.x + handX + rnd.nextGaussian().toFloat() * noise * k.width,
                k.y + handY + rnd.nextGaussian().toFloat() * noise * k.height,
            ))
        }
        // a double letter is one touch of the key, not two vertices
        val merged = ArrayList<FloatArray>()
        for (i in vertices.indices) if (i == 0 || keys[i] !== keys[i - 1]) merged.add(vertices[i])
        // rounded corners: a turn moves its vertex toward the middle of its neighbours by up to cutMax key widths
        for (i in 1 until merged.size - 1) {
            val mx = (merged[i - 1][0] + merged[i + 1][0]) / 2 - merged[i][0]
            val my = (merged[i - 1][1] + merged[i + 1][1]) / 2 - merged[i][1]
            val dist = hypot(mx, my)
            if (dist == 0f) continue
            val move = minOf(dist, rnd.nextFloat() * cutMax * keys[0].width)
            merged[i][0] += mx / dist * move
            merged[i][1] += my / dist * move
        }
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        if (merged.size == 1) {
            xs.add(merged[0][0]); ys.add(merged[0][1])
        }
        for (i in 0 until merged.size - 1) {
            val a = merged[i]
            val b = merged[i + 1]
            val length = hypot(b[0] - a[0], b[1] - a[1])
            var travelled = 0f
            // a slight sideways bow per segment, like a finger arc
            val bow = (rnd.nextGaussian().toFloat() * bowStd) * length
            val nx = if (length > 0) -(b[1] - a[1]) / length else 0f
            val ny = if (length > 0) (b[0] - a[0]) / length else 0f
            while (travelled < length) {
                val t = travelled / length
                val arc = sin(PI * t).toFloat() * bow
                xs.add(Math.round(a[0] + (b[0] - a[0]) * t + nx * arc + rnd.nextGaussian().toFloat()).toFloat())
                ys.add(Math.round(a[1] + (b[1] - a[1]) * t + ny * arc + rnd.nextGaussian().toFloat()).toFloat())
                travelled += 6f + rnd.nextFloat() * 16f
            }
        }
        val last = merged.last()
        xs.add(Math.round(last[0]).toFloat()); ys.add(Math.round(last[1]).toFloat())
        return Gesture(xs.toFloatArray(), ys.toFloatArray())
    }

    /** A straight sweep over the centres of [word]'s keys, no noise: the ideal gesture. */
    fun idealGesture(word: String, kb: List<GlideKey>): Gesture {
        val byCode = kb.associateBy { it.code }
        val keys = word.lowercase().map { byCode[it.code] }.filterNotNull()
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 0 until keys.size - 1) {
            val a = keys[i]
            val b = keys[i + 1]
            val steps = maxOf(1, (hypot(b.x - a.x, b.y - a.y) / 10f).toInt())
            for (s in 0 until steps) {
                xs.add(a.x + (b.x - a.x) * s / steps); ys.add(a.y + (b.y - a.y) * s / steps)
            }
        }
        xs.add(keys.last().x); ys.add(keys.last().y)
        return Gesture(xs.toFloatArray(), ys.toFloatArray())
    }

    /** The ideal path with a small square loop (a quarter key) on the key of every double letter: how a finger marks "pool". */
    fun loopedGesture(word: String, kb: List<GlideKey>): Gesture {
        val byCode = kb.associateBy { it.code }
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        var previous: GlideKey? = null
        for (c in word.lowercase()) {
            val k = byCode[c.code] ?: continue
            if (previous != null && previous.code == k.code) {
                val q = k.width / 4
                for ((dx, dy) in listOf(q to q, q to -q, -q to -q, -q to q)) { xs.add(k.x + dx); ys.add(k.y + dy) }
            } else if (previous != null) {
                // walk from the previous key to this one in steps of 10 px
                val steps = maxOf(1, (hypot(k.x - previous.x, k.y - previous.y) / 10f).toInt())
                for (s in 1..steps) { xs.add(previous.x + (k.x - previous.x) * s / steps); ys.add(previous.y + (k.y - previous.y) * s / steps) }
            } else {
                xs.add(k.x); ys.add(k.y)
            }
            previous = k
        }
        return Gesture(xs.toFloatArray(), ys.toFloatArray())
    }
}
