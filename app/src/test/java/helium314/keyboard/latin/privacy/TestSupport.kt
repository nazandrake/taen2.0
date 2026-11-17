// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** For the AppLock of a test without a Looper: it is never on the main thread (the check itself is tested in AppLockMainThreadTest). */
val notMainThread: () -> Boolean = { false }

/** Runs [body] on another thread and returns its result (the AppLock writers refuse the main thread, which is the test thread under Robolectric). */
fun <T> offMain(body: () -> T): T {
    var result: Result<T>? = null
    val t = Thread({ result = runCatching(body) }, "off-main")
    t.start()
    t.join(60_000)
    return result!!.getOrThrow()
}

/** In-memory [KeyVault] with the behaviour of the Android one that matters here: a locked device and a deleted key. */
class FakeKeyVault : KeyVault {
    private val keys = HashMap<Tier, SecretKey>()
    var locked = false
    var wrapCalls = 0
    var unwrapCalls = 0
    /** Thrown by the next calls of wrap and unwrap while set (a transient or permanent failure of the Keystore). */
    var wrapError: Exception? = null
    var unwrapError: Exception? = null
    /** Thrown by the next unwrap only (a permanent loss that the caller repairs by starting a new key). */
    var failNextUnwrap: Exception? = null

    override fun wrap(tier: Tier, plain: ByteArray): ByteArray {
        wrapError?.let { throw it }
        if (locked && tier == Tier.CE) throw VaultLockedException("locked")
        wrapCalls++
        val key = keys.getOrPut(tier) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(tier.alias.toByteArray())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(tier: Tier, wrapped: ByteArray): ByteArray {
        unwrapError?.let { throw it }
        failNextUnwrap?.let { failNextUnwrap = null; throw it }
        if (locked && tier == Tier.CE) throw VaultLockedException("locked")
        unwrapCalls++
        val key = keys[tier] ?: throw VaultKeyException("no key for ${tier.alias}")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, 12))
            cipher.updateAAD(tier.alias.toByteArray())
            return cipher.doFinal(wrapped, 12, wrapped.size - 12)
        } catch (e: Exception) {
            throw VaultKeyException("bad wrapped key", e)
        }
    }

    override fun deleteKey(tier: Tier) { keys.remove(tier) }
}

// Test directories live under the build directory (on disk, not in the RAM-backed /tmp) and are removed when the JVM ends.
private val testRoot: File by lazy {
    val root = File("build/tmp/taen-tests").absoluteFile.also { it.mkdirs() }
    Runtime.getRuntime().addShutdownHook(Thread { root.deleteRecursively() })
    root
}

/** A directory that exists for one test. */
fun tempDir(): File = Files.createTempDirectory(testRoot.toPath(), "t").toFile()

fun fixedKey(seed: Int = 1): ByteArray = ByteArray(32) { (it * 7 + seed).toByte() }

/** Store whose data key is fixed, for tests that do not involve the vault. */
fun testStore(dir: File, name: String = "test", version: Int = 1, key: ByteArray = fixedKey(), files: StoreFiles = DefaultStoreFiles) =
    SecureStore(dir, name, version, { key.copyOf() }, files, SecureRandom()) // the store zeroes the array it gets

/** All encodings in which a text could appear in a file: UTF-8, UTF-16 LE and BE, Windows-1251 (Cyrillic). */
fun encodings(text: String): List<ByteArray> = listOf(
    text.toByteArray(Charsets.UTF_8),
    text.toByteArray(Charsets.UTF_16LE),
    text.toByteArray(Charsets.UTF_16BE),
    text.toByteArray(charset("windows-1251")),
)

fun ByteArray.indexOfSub(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

/** Files below [dir] that contain [text] in any of the [encodings]. */
fun filesContaining(dir: File, text: String): List<String> =
    dir.walkTopDown().filter { it.isFile }.filter { f ->
        val bytes = f.readBytes()
        encodings(text).any { bytes.indexOfSub(it) >= 0 }
    }.map { it.name }.toList()

/** Runs the executor tasks at once, so tests see the result of apply() without waiting. */
val directExecutor = java.util.concurrent.Executor { it.run() }
/** Runs each task on another thread and waits for it (for code that refuses the main thread, which is the test thread under Robolectric). */
val offMainExecutor = java.util.concurrent.Executor { offMain { it.run() } }
val directNotify: (Runnable) -> Unit = { it.run() }

/** The preferences as a typed map (SharedPreferences.getAll returns a star-projected map). */
fun android.content.SharedPreferences.map(): Map<String, Any?> = HashMap<String, Any?>(all)
