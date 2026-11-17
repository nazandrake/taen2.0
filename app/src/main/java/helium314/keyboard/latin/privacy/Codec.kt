// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** A decoded record file is malformed. Callers treat the whole store as unreadable (fail closed). */
class CorruptStoreException(message: String) : IOException(message)

/**
 * Reads at most [limit] + 1 bytes: a result larger than [limit] means the stream was longer than allowed. Replaces
 * InputStream.readNBytes, which Android has only from API 33 (it crashed an import on a device with API 32).
 */
fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var remaining = limit + 1
    while (remaining > 0) {
        val n = read(buffer, 0, minOf(buffer.size, remaining))
        if (n < 0) break
        out.write(buffer, 0, n)
        remaining -= n
    }
    return out.toByteArray()
}

/** Length-prefixed UTF-8 strings (DataOutputStream.writeUTF is limited to 64 KiB, clips can be longer). */
fun DataOutputStream.writeText(s: String) {
    val bytes = s.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

/** The text is read from [DataInputStream] only if its declared length fits in the remaining bytes. */
fun DataInputStream.readText(): String {
    val length = readInt()
    if (length < 0 || length > available()) throw CorruptStoreException("bad text length $length")
    val bytes = ByteArray(length)
    readFully(bytes)
    return String(bytes, Charsets.UTF_8)
}

/** Reads a count that cannot exceed the remaining bytes (each element takes at least one byte). */
fun DataInputStream.readCount(): Int {
    val count = readInt()
    if (count < 0 || count > available()) throw CorruptStoreException("bad count $count")
    return count
}

inline fun encodeRecords(block: DataOutputStream.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { it.block() }
    return bytes.toByteArray()
}

/** Runs [block] on the decoded bytes; any malformed input becomes a [CorruptStoreException]. */
inline fun <T> decodeRecords(data: ByteArray, block: DataInputStream.() -> T): T {
    try {
        val input = DataInputStream(ByteArrayInputStream(data))
        val result = input.block()
        if (input.available() != 0) throw CorruptStoreException("trailing bytes")
        return result
    } catch (e: CorruptStoreException) {
        throw e
    } catch (e: Exception) { // EOF, bad type tags, and similar
        throw CorruptStoreException("malformed store: ${e.javaClass.simpleName}")
    }
}

/** Binary form of the preference map. Only the types SharedPreferences knows. */
object PrefsCodec {
    private const val VERSION = 1
    private const val T_STRING = 0
    private const val T_INT = 1
    private const val T_LONG = 2
    private const val T_FLOAT = 3
    private const val T_BOOLEAN = 4
    private const val T_SET = 5

    fun encode(map: Map<String, Any>): ByteArray = encodeRecords {
        writeInt(VERSION)
        writeInt(map.size)
        for ((key, value) in map) {
            writeText(key)
            when (value) {
                is String -> { writeByte(T_STRING); writeText(value) }
                is Int -> { writeByte(T_INT); writeInt(value) }
                is Long -> { writeByte(T_LONG); writeLong(value) }
                is Float -> { writeByte(T_FLOAT); writeFloat(value) }
                is Boolean -> { writeByte(T_BOOLEAN); writeBoolean(value) }
                is Set<*> -> {
                    writeByte(T_SET)
                    writeInt(value.size)
                    value.forEach { writeText(it as String) }
                }
                else -> throw IllegalArgumentException("unsupported preference type ${value.javaClass.name}")
            }
        }
    }

    fun decode(data: ByteArray): MutableMap<String, Any> = decodeRecords(data) {
        if (readInt() != VERSION) throw CorruptStoreException("unknown prefs version")
        val count = readCount()
        val map = HashMap<String, Any>()
        repeat(count) {
            val key = readText()
            val value: Any = when (readByte().toInt()) {
                T_STRING -> readText()
                T_INT -> readInt()
                T_LONG -> readLong()
                T_FLOAT -> readFloat()
                T_BOOLEAN -> readBoolean()
                T_SET -> {
                    val n = readCount()
                    val set = HashSet<String>()
                    repeat(n) { set.add(readText()) }
                    set
                }
                else -> throw CorruptStoreException("unknown type tag")
            }
            map[key] = value
        }
        map
    }
}
