// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [KeyVault] on the Android Keystore: one AES-256-GCM key per tier that cannot be exported. The CE key can only be used
 * while the device is unlocked, and uses StrongBox when the device has it (otherwise the TEE).
 */
class AndroidKeyVault : KeyVault {
    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    @Synchronized
    override fun wrap(tier: Tier, plain: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(tier))
        cipher.updateAAD(tier.alias.toByteArray())
        cipher.iv + cipher.doFinal(plain)
    } catch (e: Exception) {
        throw translateKeystoreError(e)
    }

    @Synchronized
    override fun unwrap(tier: Tier, wrapped: ByteArray): ByteArray {
        if (wrapped.size <= IV_SIZE + TAG_SIZE) throw VaultKeyException("wrapped key too short")
        try {
            val key = keyStore.getKey(tier.alias, null) as? SecretKey ?: throw VaultKeyException("no key for ${tier.alias}")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE * 8, wrapped, 0, IV_SIZE))
            cipher.updateAAD(tier.alias.toByteArray())
            return cipher.doFinal(wrapped, IV_SIZE, wrapped.size - IV_SIZE)
        } catch (e: Exception) {
            throw translateKeystoreError(e)
        }
    }

    @Synchronized
    override fun deleteKey(tier: Tier) {
        if (keyStore.containsAlias(tier.alias)) keyStore.deleteEntry(tier.alias)
    }

    private fun getOrCreateKey(tier: Tier): SecretKey {
        (keyStore.getKey(tier.alias, null) as? SecretKey)?.let { return it }
        return try {
            generate(tier, strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(tier, strongBox = false)
        }
    }

    private fun generate(tier: Tier, strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(tier.alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .apply { if (tier == Tier.CE) setUnlockedDeviceRequired(true) }
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 16
    }
}

/**
 * Sorts a failure of the Keystore into what the rest of the app acts on. Locked: the key needs an
 * unlocked device (the framework sometimes wraps UserNotAuthenticatedException in an InvalidKeyException). Key: the key was
 * permanently invalidated or is unrecoverable, or the wrapped value does not authenticate: the data key is lost for good and a
 * new one may start. Anything else (service died, provider or I/O error, an odd state) may pass, so nothing may be deleted.
 * The message never quotes the original one.
 */
internal fun translateKeystoreError(e: Throwable): Exception {
    if (e is VaultKeyException || e is VaultLockedException || e is VaultUnavailableException) return e as Exception
    val causes = generateSequence(e) { it.cause }.take(5).toList()
    return when {
        causes.any { it is UserNotAuthenticatedException } -> VaultLockedException("device is locked", e)
        causes.any { it is KeyPermanentlyInvalidatedException || it is UnrecoverableKeyException || it is AEADBadTagException } ->
            VaultKeyException("key lost or wrapped key does not authenticate", e)
        else -> VaultUnavailableException("key operation failed", e)
    }
}
