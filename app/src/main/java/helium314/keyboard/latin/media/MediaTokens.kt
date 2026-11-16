// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.media

import android.os.SystemClock
import helium314.keyboard.latin.privacy.Privacy
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** What a token stands for: a picture of the vault, or a starter-pack asset. */
sealed interface MediaSource {
    class Vault(val id: String) : MediaSource
    class Asset(val path: String) : MediaSource
}

/** One live token: the source, its format and stored length (what `query` tells), and when it was issued. */
class MediaGrant(val source: MediaSource, val format: MediaFormat, val size: Int, val issuedAt: Long)

/**
 * The tokens that make a picture readable by one receiving app. A token is 128 random bits in the URI path of [MediaShareProvider]; the registry lives in
 * memory only and is never written. A token expires after [TTL_MILLIS] (counted from [clock], a test seam), and every token is revoked when the screen locks,
 * at a wipe (both through the Privacy lock hooks) and when the input field changes (LatinIME). The provider reads the bytes through [reader] at the moment of the
 * read, so a locked store or a deleted picture gives nothing.
 */
object MediaTokens {
    const val TTL_MILLIS = 10L * 60 * 1000
    private const val HOOK = "media-tokens"

    /** Test seam: the time source. */
    @Volatile var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** Set by the app: gives the bytes of a [MediaSource] (null when unreadable). The provider never touches storage itself. */
    @Volatile var reader: (MediaSource) -> ByteArray? = { null }

    private val grants = ConcurrentHashMap<String, MediaGrant>()
    private val random = SecureRandom()

    /** Issues a token for [source]; registers the revocation at screen lock and wipe. */
    fun issue(source: MediaSource, format: MediaFormat, size: Int): String {
        Privacy.onLock(HOOK) { revokeAll() }
        val token = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        grants[token] = MediaGrant(source, format, size, clock())
        return token
    }

    /** The grant of [token], or null when it is unknown, expired or revoked. An expired token is removed. */
    fun lookup(token: String): MediaGrant? {
        val grant = grants[token] ?: return null
        if (clock() - grant.issuedAt >= TTL_MILLIS) { grants.remove(token); return null }
        return grant
    }

    /** The bytes behind [token] (a fresh array that the caller zeroes), or null. */
    fun bytes(token: String): ByteArray? = lookup(token)?.let { reader(it.source) }

    fun revoke(token: String) { grants.remove(token) }

    fun revokeAll() { grants.clear() }

    /** The number of tokens that are still held (expired ones are only dropped when they are looked up or by [revokeAll]). */
    val liveCount: Int get() = grants.size
}
