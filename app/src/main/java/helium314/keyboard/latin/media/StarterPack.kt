// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import android.content.Context
import java.io.IOException

/**
 * The starter stickers (still PNG) and GIFs (animated) that ship inside the APK (assets/stickers/, at most 4 MiB, made by tools/make-sticker-pack.sh from pinned sources, credit in NOTICE).
 * Off until the user turns the setting on. They are public files: never stored in the vault, never exported, never imported; a token can point at one so that it
 * is sent like any picture. [read] answers only for a plain file name inside the folder.
 */
object StarterPack {
    const val DIR = "stickers"
    const val MAX_BYTES = 4L * 1024 * 1024

    /** The asset paths (`stickers/<name>`) of the pack in name order; empty when the pack has no files. */
    fun list(context: Context): List<String> =
        try { context.assets.list(DIR).orEmpty().filter { it.substringAfterLast('.', "") in setOf("gif", "png", "jpg", "webp") }.sorted().map { "$DIR/$it" } } catch (_: IOException) { emptyList() }

    /** The bytes of the starter file [path] (as returned by [list]), or null for any other path. */
    fun read(context: Context, path: String): ByteArray? {
        val name = path.removePrefix("$DIR/")
        if (!path.startsWith("$DIR/") || name.isEmpty() || name.contains('/') || name.contains("..")) return null
        return try { context.assets.open(path).use { it.readBytes() } } catch (_: IOException) { null }
    }

    /** The format of a starter file by its content. */
    fun format(bytes: ByteArray): MediaFormat? = MediaFormat.sniff(bytes)

    /** The tags of a starter picture for the tag chips of the panel: the animated GIFs are "gif", the still stickers are "sticker". */
    fun tags(format: MediaFormat): List<String> = if (format == MediaFormat.GIF) listOf("gif") else listOf("sticker")
}
