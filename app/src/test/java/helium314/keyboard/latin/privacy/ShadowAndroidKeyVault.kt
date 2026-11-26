// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric has no Android Keystore. This replaces [AndroidKeyVault] in the unit tests (registered for all tests in
 * src/test/resources/robolectric.properties) with the in-memory [FakeKeyVault]. Production code has no test switch.
 */
@Implements(AndroidKeyVault::class)
class ShadowAndroidKeyVault {
    private val fake = FakeKeyVault()

    companion object {
        /** Number of credential-encrypted-tier unwrap calls in this process (tests reset it), to see that a data key is unwrapped again. */
        @JvmStatic var unwrapCalls = 0
        /** Thrown by the credential-encrypted unwrap while set: a Keystore failure that may pass (tests reset it to null). */
        @JvmStatic var ceUnwrapError: Exception? = null
    }

    @Implementation
    protected fun __constructor__() {}

    @Implementation
    fun wrap(tier: Tier, plain: ByteArray): ByteArray = fake.wrap(tier, plain)

    @Implementation
    fun unwrap(tier: Tier, wrapped: ByteArray): ByteArray {
        if (tier == Tier.CE) ceUnwrapError?.let { throw it }
        if (tier == Tier.CE) unwrapCalls++
        return fake.unwrap(tier, wrapped)
    }

    @Implementation
    fun deleteKey(tier: Tier) = fake.deleteKey(tier)
}
