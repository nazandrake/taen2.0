// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The data keys that Privacy caches belong to the directory they were made for: a store of another directory never uses another directory's key.
 * On a phone there is one directory per tier, so nothing changes there; the cache only must not mix two of them.
 */
@RunWith(RobolectricTestRunner::class)
class StoreKeyBindingTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val roots = ArrayList<File>()

    /** A device-protected context whose no_backup directory is [root] (Privacy takes the directory of the tier from it). */
    private fun contextAt(root: File): Context = object : ContextWrapper(ctx) {
        override fun getApplicationContext(): Context = this
        override fun isDeviceProtectedStorage(): Boolean = true
        override fun getNoBackupFilesDir(): File = root
    }

    private fun newRoot(): File = tempDir().also { roots.add(it) }

    // the start work of this test's application (App.onCreate: the app lock reads its store) opens the real directory of the tier, which replaces a key
    // of these test directories in the cache: it finishes first, so that only the calls of the test use the cache
    @Before fun before() { AppStartWork.finishEarlier(); Privacy.forgetCachedState() }
    @After fun after() { Privacy.forgetCachedState(); roots.forEach { it.deleteRecursively() } }

    @Test fun aStoreOfAnotherDirectoryGetsTheKeyOfItsOwnDirectory() {
        val first = newRoot()
        val second = newRoot()
        Privacy.store(contextAt(first), Tier.DE, "probe", 1)!!.write("one".toByteArray())
        assertTrue(File(first, "taen/dek_de.bin").isFile, "positive control: the first directory has its key")
        Privacy.store(contextAt(second), Tier.DE, "probe", 1)!!.write("two".toByteArray())
        assertTrue(File(second, "taen/dek_de.bin").isFile, "the second directory has a key of its own")
    }

    // ---- pass 2 ----

    private fun read(root: File, keys: DataKeys? = null): String {
        val dir = File(root, "taen")
        val store = if (keys == null) Privacy.store(contextAt(root), Tier.DE, "probe", 1)!! else SecureStore(dir, "probe", 1, keys::key)
        return String(assertIs<StoreContent.Data>(store.read()).bytes)
    }

    private fun freshKeys(root: File) = DataKeys(Privacy.keyVault(), Tier.DE, File(root, "taen/dek_de.bin"))

    @Test fun eachDirectoryKeepsItsOwnKeyWhileTheCallsAlternate() {
        val first = newRoot()
        val second = newRoot()
        Privacy.store(contextAt(first), Tier.DE, "probe", 1)!!.write("one".toByteArray())
        Privacy.store(contextAt(second), Tier.DE, "probe", 1)!!.write("two".toByteArray())
        assertEquals("one", read(first)) // back to the first directory after the second one
        assertEquals("two", read(second))
        // a new process (keys read from each directory's own key file) reads both: each file was written under the key of its own directory
        assertEquals("one", read(first, freshKeys(first)))
        assertEquals("two", read(second, freshKeys(second)))
        assertNotEquals(File(first, "taen/dek_de.bin").readBytes().toList(), File(second, "taen/dek_de.bin").readBytes().toList())
    }

    @Test fun theSameDirectoryReusesItsCachedKey() { // no change on a phone: one directory per tier, its key unwrapped once
        val root = newRoot()
        Privacy.store(contextAt(root), Tier.DE, "probe", 1)!!.write("one".toByteArray())
        val keyFile = File(root, "taen/dek_de.bin")
        val wrapped = keyFile.readBytes()
        assertTrue(keyFile.delete()) // only the cached key can still read the store now
        assertEquals("one", read(root), "the cached key of the same directory was used")
        assertFalse(keyFile.exists(), "and no new key was made for it")
        keyFile.writeBytes(wrapped)
    }

    /** The screen lock wipes only the cached key of a tier, so a key that another directory's key replaced in the cache is wiped at once. */
    @Test fun aKeyReplacedInTheCacheIsWipedFromMemory() {
        val first = newRoot()
        val second = newRoot()
        val handedOut = Privacy.store(contextAt(first), Tier.DE, "probe", 1)!!
        handedOut.write("one".toByteArray())
        val keyFile = File(first, "taen/dek_de.bin")
        val wrapped = keyFile.readBytes()
        keyFile.delete()
        assertEquals("one", String(assertIs<StoreContent.Data>(handedOut.read()).bytes), "positive control: the key is still in memory")
        keyFile.writeBytes(wrapped)
        Privacy.store(contextAt(second), Tier.DE, "probe", 1)!!.write("two".toByteArray()) // replaces the cached key of the tier
        keyFile.delete()
        // the memory copy is gone: the store handed out earlier needs the key file again and finds none (a new key, so its data no longer authenticates)
        assertIs<StoreContent.Reset>(handedOut.read())
    }
}
