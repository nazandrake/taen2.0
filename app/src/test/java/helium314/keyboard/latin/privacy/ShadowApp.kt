// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import helium314.keyboard.latin.App
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowApplication

/**
 * Every unit test creates its own application, which is a new process in effect, while [Privacy] is one object for the whole test JVM.
 * App.onCreate starts background work that asks Privacy for the app lock and, through it, for the data keys. If the objects of an earlier
 * test were still cached, that work (and the test itself) would use the data directory and the data key of the earlier application, and a
 * store of the new directory would be written under another key and read back as damaged. So the application of each test starts
 * from nothing, as a real process does. Registered for all tests in src/test/resources/robolectric.properties; production code has no test switch.
 */
@Implements(App::class)
class ShadowApp : ShadowApplication() {
    @RealObject private lateinit var app: App

    @Implementation
    fun onCreate() {
        Privacy.forgetCachedState()
        Shadow.directlyOn<Any?, App>(app, App::class.java, "onCreate")
    }
}
