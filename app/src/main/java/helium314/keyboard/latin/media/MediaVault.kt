// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import helium314.keyboard.latin.privacy.CorruptStoreException
import helium314.keyboard.latin.privacy.SecureStore
import helium314.keyboard.latin.privacy.StoreContent
import helium314.keyboard.latin.privacy.StoreWriteException
import helium314.keyboard.latin.privacy.VaultLockedException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale

/** One imported picture: [id] names its store `media_<id>.enc`; [size] is the stored (stripped) length; [tags] are lower case, 1-32 characters, at most 8. */
class MediaItem(val id: String, val format: MediaFormat, val size: Int, val tags: List<String>)

/** Where the vault's encrypted stores come from: [open] is null while the credential-encrypted tier is locked, [names] lists the store names (null while locked). */
interface MediaStorage {
    fun open(name: String): SecureStore?
    fun names(): List<String>?
}

/**
 * The offline collection of pictures: every picture is one AES-GCM store `media_<random>.enc` of the credential-encrypted tier (the bytes without
 * metadata, see [MetadataStrip]), and `media_index.enc` holds the list (id, format, size and up to 8 tags per picture; no time, no file name, no source).
 * Search and delete run in memory; a search text is never stored. While the tier is locked nothing is read or written ([Locked]). An index that cannot be
 * read, or pictures without an index, are [Damaged]: nothing is listed and only [deleteAll] works. The limits are checked before anything is written.
 */
class MediaVault(
    private val storage: MediaStorage,
    private val random: SecureRandom = SecureRandom(),
    /** The limits; only a test passes smaller ones (the real limits are [MAX_ITEMS] and [MAX_TOTAL_BYTES]). */
    private val maxItems: Int = MAX_ITEMS,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
) {
    sealed interface Listing {
        class Items(val items: List<MediaItem>) : Listing
        object Locked : Listing
        object Damaged : Listing
    }

    sealed interface ImportResult {
        class Added(val item: MediaItem) : ImportResult
        object TooBig : ImportResult
        object TooMany : ImportResult
        object TotalTooBig : ImportResult
        object UnknownFormat : ImportResult
        object ParseError : ImportResult
        object Locked : ImportResult
        object Damaged : ImportResult
        object Failed : ImportResult
    }

    private val lock = Any()

    fun list(): Listing = synchronized(lock) { load() }

    /** Strips and stores [bytes] with [tags]. Nothing is written for a refusal; a failed index write removes the picture again. */
    fun import(bytes: ByteArray, tags: List<String> = emptyList()): ImportResult = synchronized(lock) {
        if (bytes.size > MAX_ITEM_BYTES) return ImportResult.TooBig
        val format = MediaFormat.sniff(bytes) ?: return ImportResult.UnknownFormat
        val stripped = try { MetadataStrip.strip(bytes, format) } catch (_: MediaParseException) { return ImportResult.ParseError }
        val current = when (val l = load()) {
            Listing.Locked -> return ImportResult.Locked
            Listing.Damaged -> return ImportResult.Damaged
            is Listing.Items -> l.items
        }
        if (current.size >= maxItems) return ImportResult.TooMany
        if (current.sumOf { it.size.toLong() } + stripped.size > maxTotalBytes) return ImportResult.TotalTooBig
        val id = newId(current)
        val item = MediaItem(id, format, stripped.size, normalizeTags(tags))
        val store = storage.open(itemName(id)) ?: return ImportResult.Locked
        try {
            store.write(stripped)
        } catch (_: VaultLockedException) {
            return ImportResult.Locked
        } catch (_: StoreWriteException) {
            return ImportResult.Failed
        }
        if (!writeIndex(current + item)) {
            store.delete()
            return ImportResult.Failed
        }
        ImportResult.Added(item)
    }

    /** The stored bytes of picture [id], or null when it is not in the list, the tier is locked or the store cannot be read. */
    fun read(id: String): ByteArray? = synchronized(lock) {
        val items = (load() as? Listing.Items)?.items ?: return null
        if (items.none { it.id == id }) return null
        (storage.open(itemName(id))?.read() as? StoreContent.Data)?.bytes
    }

    fun setTags(id: String, tags: List<String>): Boolean = synchronized(lock) {
        val items = (load() as? Listing.Items)?.items ?: return false
        if (items.none { it.id == id }) return false
        writeIndex(items.map { if (it.id == id) MediaItem(it.id, it.format, it.size, normalizeTags(tags)) else it })
    }

    /** Removes picture [id] and its list entry. False when it is not there or could not be removed. */
    fun delete(id: String): Boolean = synchronized(lock) {
        val items = (load() as? Listing.Items)?.items ?: return false
        if (items.none { it.id == id }) return false
        if (!writeIndex(items.filter { it.id != id })) return false
        storage.open(itemName(id))?.delete()
        true
    }

    /** Removes every picture store and the list, also when the list is damaged. False while locked. */
    fun deleteAll(): Boolean = synchronized(lock) {
        val names = storage.names() ?: return false
        var ok = true
        for (name in names.filter { it.startsWith(PREFIX) }) {
            val store = storage.open(name)
            if (store == null) ok = false else store.delete()
        }
        ok && storage.names()?.none { it.startsWith(PREFIX) } == true
    }

    /** What an export holds of the collection: the list as stored and the bytes of every picture, by id. */
    class Snapshot(val index: ByteArray, val items: Map<String, ByteArray>)

    sealed interface Export {
        /** The whole collection is [maxBytes] or smaller (it may be empty: then [Included.snapshot] has no items). */
        class Included(val snapshot: Snapshot) : Export
        /** The pictures together are larger than the cap: none of them is exported (a part of the set is never exported). */
        object TooBig : Export
        /** Locked, damaged or unreadable: nothing is exported. */
        object Unavailable : Export
    }

    /** The collection for an export when its pictures total at most [maxBytes]; otherwise [Export.TooBig]. Never a partial set. */
    fun exportAll(maxBytes: Long): Export = synchronized(lock) {
        val items = (load() as? Listing.Items)?.items ?: return Export.Unavailable
        if (items.sumOf { it.size.toLong() } > maxBytes) return Export.TooBig
        val bytes = LinkedHashMap<String, ByteArray>()
        for (item in items) bytes[item.id] = (storage.open(itemName(item.id))?.read() as? StoreContent.Data)?.bytes ?: return Export.Unavailable
        Export.Included(Snapshot(encode(items), bytes))
    }

    /**
     * Replaces the whole collection by [snapshot] (an import). The old collection stays until the new one is complete: every imported picture is written
     * to a new store under a new id next to the old ones, with its metadata stripped again (each store write is itself verified), and read back, and only then the list is
     * replaced, in one atomic store write: that write is the switch. The old pictures are removed after it. A crash before the switch leaves the old
     * collection (the new stores are unlisted and removed after the next successful import, [sweep]); a crash after it leaves the new one. Pictures are written and checked one at a
     * time, so no copy of the old collection is held. False, and the old collection untouched, on any failure before the switch.
     */
    fun replaceAll(snapshot: Snapshot): Boolean = synchronized(lock) {
        if (!validate(snapshot)) return false
        val current = when (val l = load()) {
            Listing.Locked -> return false // locked or unreadable: do not touch
            Listing.Damaged -> null // replaced as a whole, the damaged pictures go after the switch
            is Listing.Items -> l.items
        }
        val written = ArrayList<String>()
        fun abandon(): Boolean {
            written.forEach { storage.open(itemName(it))?.delete() }
            return false
        }
        val incoming = decode(snapshot.index)
        val taken = current.orEmpty().toMutableList()
        val renamed = ArrayList<MediaItem>(incoming.size)
        for (item in incoming) {
            val bytes = snapshot.items.getValue(item.id) // validate() checked that every listed picture is there
            // what is stored is always what the metadata strip gives, whatever the backup held (an export of this app is already clean; a changed one may not be)
            val stripped = try { MetadataStrip.strip(bytes, item.format) } catch (_: MediaParseException) { return abandon() }
            val id = newId(taken)
            val store = storage.open(itemName(id)) ?: return abandon()
            written.add(id)
            try {
                store.write(stripped)
            } catch (_: VaultLockedException) {
                return abandon()
            } catch (_: StoreWriteException) {
                return abandon()
            }
            // read back from disk: AES-GCM authenticates the bytes under the store's name, so a picture that decrypts is the one written
            if (store.read() !is StoreContent.Data) return abandon()
            MediaItem(id, item.format, stripped.size, item.tags).also { renamed.add(it); taken.add(it) }
        }
        if (!writeIndex(renamed)) return abandon() // the switch
        sweep(renamed.map(MediaItem::id).toSet())
        true
    }

    /** Removes every picture store that is not in [keep] (the listed ones): what a crashed import or a crash after a switch left behind. */
    private fun sweep(keep: Set<String>) {
        val names = storage.names() ?: return
        for (name in names) if (name.startsWith(PREFIX) && name != INDEX && name.removePrefix(PREFIX) !in keep) storage.open(name)?.delete()
    }

    private fun load(): Listing {
        val names = storage.names() ?: return Listing.Locked
        val index = storage.open(INDEX) ?: return Listing.Locked
        return when (val content = index.read()) {
            StoreContent.Unavailable -> Listing.Locked
            StoreContent.Reset -> Listing.Damaged // the damaged index was removed; the pictures stay until Delete all
            StoreContent.Missing -> if (names.any { it.startsWith(PREFIX) && it != INDEX }) Listing.Damaged else Listing.Items(emptyList())
            is StoreContent.Data -> try {
                Listing.Items(decode(content.bytes))
            } catch (_: CorruptStoreException) {
                Listing.Damaged
            }
        }
    }

    private fun writeIndex(items: List<MediaItem>): Boolean {
        val store = storage.open(INDEX) ?: return false
        return try {
            store.write(encode(items))
            true
        } catch (_: VaultLockedException) {
            false
        } catch (_: StoreWriteException) {
            false
        }
    }

    private fun newId(taken: List<MediaItem>): String {
        while (true) {
            val id = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            if (taken.none { it.id == id }) return id
        }
    }

    companion object {
        const val MAX_ITEM_BYTES = 8 * 1024 * 1024
        const val MAX_ITEMS = 500
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
        const val MAX_TAGS = 8
        const val MAX_TAG_LENGTH = 32
        const val PREFIX = "media_"
        const val INDEX = "media_index"
        private val ID = Regex("[0-9a-f]{16}")

        fun itemName(id: String) = PREFIX + id

        /** Checks an imported [snapshot] without writing: the list follows the format and the limits, and every picture is there with the listed size and format and parses. */
        fun validate(snapshot: Snapshot): Boolean = try {
            val items = decode(snapshot.index)
            items.size == snapshot.items.size && items.sumOf { it.size.toLong() } <= MAX_TOTAL_BYTES && items.all { item ->
                val bytes = snapshot.items[item.id]
                bytes != null && bytes.size == item.size && MediaFormat.sniff(bytes) == item.format && runCatching { MetadataStrip.strip(bytes, item.format) }.isSuccess
            }
        } catch (_: CorruptStoreException) {
            false
        }

        /** Tags as stored: trimmed, lower case, 1-32 characters without spaces or control characters, no duplicates, at most 8 (the first ones win). */
        fun normalizeTags(tags: List<String>): List<String> =
            tags.map { it.trim().lowercase(Locale.ROOT) }.filter { it.length in 1..MAX_TAG_LENGTH && it.none { c -> c.isWhitespace() || c.isISOControl() } }.distinct().take(MAX_TAGS)

        /** The items whose tags match every word of [query] (a word matches the start of a tag; an empty query matches everything). */
        fun search(items: List<MediaItem>, query: String): List<MediaItem> {
            val words = query.lowercase(Locale.ROOT).split(' ', '\t', '\n').filter { it.isNotEmpty() }
            if (words.isEmpty()) return items
            return items.filter { item -> words.all { w -> item.tags.any { it.startsWith(w) } } }
        }

        private fun encode(items: List<MediaItem>): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeByte(1)
                out.writeInt(items.size)
                for (item in items) {
                    out.writeUTF(item.id)
                    out.writeByte(item.format.ordinal)
                    out.writeInt(item.size)
                    out.writeByte(item.tags.size)
                    item.tags.forEach { out.writeUTF(it) }
                }
            }
            return bytes.toByteArray()
        }

        /** Throws [CorruptStoreException] for anything that does not follow the format or breaks a limit. */
        private fun decode(bytes: ByteArray): List<MediaItem> {
            try {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    if (input.readUnsignedByte() != 1) throw CorruptStoreException("unknown version")
                    val count = input.readInt()
                    if (count < 0 || count > MAX_ITEMS) throw CorruptStoreException("bad count")
                    val items = ArrayList<MediaItem>(count)
                    repeat(count) {
                        val id = input.readUTF()
                        val format = MediaFormat.entries.getOrNull(input.readUnsignedByte()) ?: throw CorruptStoreException("bad format")
                        val size = input.readInt()
                        val tagCount = input.readUnsignedByte()
                        if (!ID.matches(id) || size <= 0 || size > MAX_ITEM_BYTES || tagCount > MAX_TAGS) throw CorruptStoreException("bad item")
                        val tags = List(tagCount) { input.readUTF() }
                        if (normalizeTags(tags) != tags) throw CorruptStoreException("bad tag")
                        items.add(MediaItem(id, format, size, tags))
                    }
                    if (input.read() != -1) throw CorruptStoreException("trailing data")
                    if (items.map { it.id }.toSet().size != items.size) throw CorruptStoreException("duplicate id")
                    return items
                }
            } catch (e: IOException) {
                throw CorruptStoreException("truncated")
            }
        }
    }
}
