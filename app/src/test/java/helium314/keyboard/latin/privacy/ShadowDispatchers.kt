// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

/**
 * Dispatchers.Default is the start dispatcher of [AppStartWork] on the thread that runs App.onCreate and the real Default dispatcher on every
 * other thread, so that the work of onCreate can be waited for. Registered for all tests in src/test/resources/robolectric.properties.
 */
@Implements(Dispatchers::class)
class ShadowDispatchers {
    companion object {
        @Implementation @JvmStatic
        fun getDefault(): CoroutineDispatcher = AppStartWork.starting.get() ?: Shadow.directlyOn(Dispatchers::class.java, "getDefault")
    }
}
