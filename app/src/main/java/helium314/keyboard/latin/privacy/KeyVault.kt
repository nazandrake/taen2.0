// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

/**
 * Which storage tier a key or store belongs to.
 * [CE] data is credential encrypted and needs an unlocked device; [DE] data is for non-sensitive values that
 * the keyboard needs before the first unlock.
 */
enum class Tier(val alias: String) {
    CE("taen_k_ce"),
    DE("taen_k_de"),
}

/** The device is locked, so the key cannot be used right now. Nothing is lost: the caller must not delete data. */
class VaultLockedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The key is missing, permanently invalidated or does not authenticate the wrapped data: the wrapped data is unrecoverable
 * and a new key may start. Only these cases may reset data; every other failure is [VaultUnavailableException].
 */
class VaultKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The key operation failed for a reason that can pass (Keystore service died, provider error, I/O error). Nothing is
 * known to be lost, so the caller fails closed (read-only, unavailable until the next try) and deletes or replaces nothing.
 */
class VaultUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Wraps and unwraps small secrets (the data keys) with a non-exportable key per [Tier]. The Android implementation
 * is backed by the Android Keystore; the unit tests use an in-memory fake.
 */
interface KeyVault {
    /** Returns iv + ciphertext + tag of [plain]. Creates the tier key when it does not exist. */
    @Throws(VaultLockedException::class, VaultKeyException::class, VaultUnavailableException::class)
    fun wrap(tier: Tier, plain: ByteArray): ByteArray

    /**
     * Reverses [wrap]. Throws [VaultLockedException] while the device is locked, [VaultKeyException] when the data can never
     * be unwrapped (key gone or invalidated, wrapped data does not authenticate), [VaultUnavailableException] for a failure that can pass.
     */
    @Throws(VaultLockedException::class, VaultKeyException::class, VaultUnavailableException::class)
    fun unwrap(tier: Tier, wrapped: ByteArray): ByteArray

    /** Deletes the tier key: everything wrapped with it becomes unreadable. */
    fun deleteKey(tier: Tier)
}
