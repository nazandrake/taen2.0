// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * which Keystore failures are permanent (the wrapped data key is unrecoverable, a new key may
 * start) and which are transient (fail closed, delete nothing). The expected classes come from the Android documentation
 * of each exception: KeyPermanentlyInvalidatedException and UnrecoverableKeyException say the key is gone; an
 * AEADBadTagException says the wrapped key does not authenticate; the rest (service died, provider error, I/O) can pass.
 */
@RunWith(RobolectricTestRunner::class)
class VaultErrorsTest {
    private fun kind(e: Throwable): Class<out Exception> = translateKeystoreError(e).javaClass

    @Test fun lockedDeviceIsLocked() {
        assertEquals(VaultLockedException::class.java, kind(UserNotAuthenticatedException()))
        // the framework sometimes wraps it
        assertEquals(VaultLockedException::class.java, kind(InvalidKeyException("x", UserNotAuthenticatedException())))
    }

    @Test fun permanentLossOfTheKeyIsAKeyError() {
        for (e in listOf<Throwable>(KeyPermanentlyInvalidatedException(), UnrecoverableKeyException("gone"), AEADBadTagException("tag mismatch"),
            InvalidKeyException("x", KeyPermanentlyInvalidatedException())))
            assertEquals(VaultKeyException::class.java, kind(e), e.toString())
    }

    @Test fun everythingElseIsTransient() {
        for (e in listOf<Throwable>(KeyStoreException("service died"), ProviderException("keystore daemon"), IOException("I/O"),
            RuntimeException("DeadObjectException"), IllegalStateException("not initialized"), InvalidKeyException("busy"),
            GeneralSecurityException("other"), IllegalArgumentException("odd")))
            assertEquals(VaultUnavailableException::class.java, kind(e), e.toString())
    }

    @Test fun lockedWinsWhenTheChainHasBoth() {
        assertEquals(VaultLockedException::class.java,
            kind(KeyStoreException("x", UserNotAuthenticatedException())))
    }

    @Test fun vaultExceptionsPassThroughUnchanged() {
        for (e in listOf<Exception>(VaultKeyException("no key"), VaultLockedException("locked"), VaultUnavailableException("busy")))
            assertSame(e, translateKeystoreError(e))
    }

    @Test fun transientErrorKeepsItsCauseButNotItsMessage() { // the message of a Keystore error could quote data
        val cause = ProviderException("secret-looking text")
        val t = assertIs<VaultUnavailableException>(translateKeystoreError(cause))
        assertSame(cause, t.cause)
        assertEquals(false, t.message!!.contains("secret-looking"))
    }
}
