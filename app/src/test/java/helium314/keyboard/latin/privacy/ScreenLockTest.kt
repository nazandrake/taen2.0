// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * (P10, P19): after the screen is locked the credential-encrypted data is not stored, not
 * learned and not served from memory until the user unlocks. Each test writes with the lock open (the positive control),
 * locks, tries the same writes through the production objects and compares the files byte for byte, then unlocks.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenLockTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val canary = "TAENcanary-Qx93"
    private fun taen(name: String) = File(ctx.noBackupFilesDir, "taen/$name")

    @Before fun before() { Privacy.unlock(ctx); Privacy.forgetCachedState(); ClipboardDao.forgetInstance(); ShadowAndroidKeyVault.unwrapCalls = 0 }
    @After fun after() { Privacy.unlock(ctx); Privacy.forgetCachedState(); ClipboardDao.forgetInstance() }

    @Test fun clipboardIsNotStoredWhileTheScreenIsLockedAndComesBackAfterUnlock() {
        val dao = assertNotNull(ClipboardDao.getInstance(ctx))
        dao.addClip(System.currentTimeMillis(), "before the lock $canary")
        val file = taen("clipboard.enc")
        val before = file.readBytes()

        Privacy.lock(ctx)
        assertTrue(Privacy.isLocked)
        assertNull(ClipboardDao.getInstance(ctx)) // the opened history is dropped, nothing new is opened
        assertNull(Privacy.loadClipboard(ctx))
        dao.addClip(System.currentTimeMillis(), "while locked $canary") // even a stale object cannot write
        assertContentEquals(before, file.readBytes())
        assertEquals(emptyList(), filesContaining(ctx.dataDir, "while locked"))

        Privacy.unlock(ctx)
        assertFalse(Privacy.isLocked)
        assertEquals(listOf("before the lock $canary"), assertNotNull(ClipboardDao.getInstance(ctx)).getAll().map { it.text })
    }

    @Test fun learnedWordsAreNeitherLoadedNorSavedWhileTheScreenIsLocked() {
        val storage = Privacy.learnedStorage(ctx, "en-US", null)
        val first = "first words $canary".toByteArray() + ByteArray(30)
        assertTrue(storage.save(first))
        val file = taen("learned_en-US.enc")
        val before = file.readBytes()

        Privacy.lock(ctx)
        assertFalse(storage.isAvailable()) // the dictionary sees "storage unavailable": it neither learns nor saves nor loads
        assertFalse(storage.load().available)
        assertFalse(storage.save("words typed while locked".toByteArray()))
        assertContentEquals(before, file.readBytes())

        Privacy.unlock(ctx)
        assertContentEquals(first, assertNotNull(storage.load().bytes))
        assertTrue(storage.save("second".toByteArray()))
    }

    @Test fun personalDictionaryIsWipedFromMemoryAndRestoredAfterUnlock() {
        val personal = assertNotNull(Privacy.personalDictionary(ctx))
        val word = PersonalWord("word-$canary", "sc", 200, "en_US")
        assertTrue(personal.add(word))
        var changes = 0
        personal.addListener { changes++ }
        val file = taen("snippets.enc")
        val before = file.readBytes()

        Privacy.lock(ctx)
        assertNull(Privacy.personalDictionary(ctx))
        assertEquals(emptyList(), personal.all()) // a native dictionary built from it is rebuilt empty
        assertEquals(emptyList(), personal.matching("en_US"))
        assertEquals(1, changes)
        assertFalse(personal.add(PersonalWord("while-locked", null, 100, null)))
        assertFalse(personal.delete(word))
        assertContentEquals(before, file.readBytes())

        Privacy.unlock(ctx)
        assertSame(personal, Privacy.personalDictionary(ctx)) // same object: dictionaries that listen to it stay attached
        assertEquals(listOf(word), personal.all())
        assertEquals(2, changes)
    }

    @Test fun credentialEncryptedPreferencesAreEmptyAndReadOnlyWhileLocked() {
        val ce = ctx.protectedPrefs()
        assertTrue(ce.edit().putString("k", canary).commit())
        val file = taen("prefs_ce.enc")
        val before = file.readBytes()

        Privacy.lock(ctx)
        assertEquals("default", ce.getString("k", "default")) // the object that was handed out earlier is emptied too
        assertFalse(ce.edit().putString("k", "changed").commit())
        assertContentEquals(before, file.readBytes())
        // the device-protected preferences are not affected (positive control that only the credential tier is locked)
        assertTrue(ctx.prefs().edit().putString("de-key", "de-value").commit())
        assertEquals("de-value", ctx.prefs().getString("de-key", null))

        Privacy.unlock(ctx)
        assertEquals(canary, ce.getString("k", null))
    }

    @Test fun lockForgetsTheDataKeyAndUnlockUnwrapsItAgain() {
        assertTrue(assertNotNull(Privacy.store(ctx, Tier.CE, "clipboard", 1)).let { it.write(byteArrayOf(1)); true })
        val unwrapped = ShadowAndroidKeyVault.unwrapCalls
        Privacy.lock(ctx)
        Privacy.unlock(ctx)
        assertContentEquals(byteArrayOf(1), (assertNotNull(Privacy.store(ctx, Tier.CE, "clipboard", 1)).read() as StoreContent.Data).bytes)
        assertEquals(unwrapped + 1, ShadowAndroidKeyVault.unwrapCalls)
    }

    @Test fun lockingTwiceAndUnlockingTwiceIsHarmless() {
        Privacy.lock(ctx); Privacy.lock(ctx)
        assertTrue(Privacy.isLocked)
        Privacy.unlock(ctx); Privacy.unlock(ctx)
        assertFalse(Privacy.isLocked)
        assertNotNull(Privacy.store(ctx, Tier.CE, "clipboard", 1))
    }

    // ---- ----

    @Test fun aClipboardHistoryHeldAcrossTheLockIsWipedFromMemoryAndRefusesCopies() {
        val dao = assertNotNull(ClipboardDao.getInstance(ctx))
        dao.addClip(System.currentTimeMillis(), "before the lock $canary")
        assertEquals(1, dao.count()) // positive control: the history is in memory
        val file = taen("clipboard.enc")
        val before = file.readBytes()

        Privacy.lock(ctx)
        assertEquals(0, dao.count()) // the list itself is gone, not only the way to reach the object
        assertEquals(emptyList(), dao.getAll())
        dao.addClip(System.currentTimeMillis(), "while locked $canary")
        assertEquals(emptyList(), dao.getAll()) // a stale object does not keep new text either
        assertContentEquals(before, file.readBytes())

        Privacy.unlock(ctx)
        assertEquals(listOf("before the lock $canary"), assertNotNull(ClipboardDao.getInstance(ctx)).getAll().map { it.text })
    }

    @Test fun editsThatRaceTheLockAreWrittenBeforeTheWipeOrReportedAsFailed() {
        val personal = assertNotNull(Privacy.personalDictionary(ctx))
        val ce = ctx.protectedPrefs()
        assertTrue(ce.edit().putString("before", "b").commit())
        var addInWindow: Boolean? = null
        var commitInWindow: Boolean? = null
        Privacy.duringLockForTest = { // after the in-flight edits were given their chance, before the data key is forgotten
            assertNull(Privacy.personalDictionary(ctx)) // nothing new of the tier is opened or restored from now on
            assertNull(ClipboardDao.getInstance(ctx))
            addInWindow = personal.add(PersonalWord("racing-$canary", null, 100, null))
            commitInWindow = ce.edit().putString("racing", "r").commit()
        }
        try { Privacy.lock(ctx) } finally { Privacy.duringLockForTest = null }
        assertNotNull(addInWindow) // positive control: the window was reached
        assertNotNull(commitInWindow)

        Privacy.unlock(ctx)
        // every edit is on disk after the unlock, or its caller was told it failed (never "true" and gone)
        assertEquals(addInWindow, personal.all().any { it.word == "racing-$canary" })
        assertEquals(commitInWindow, ce.getString("racing", null) == "r")
        assertEquals("b", ce.getString("before", null))
    }

    @Test fun anApplyThatIsStillQueuedWhenTheScreenLocksIsWrittenFirst() {
        val ce = ctx.protectedPrefs()
        assertTrue(ce.edit().putString("seed", "s").commit())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        Privacy.writer.execute { started.countDown(); release.await() } // the writer thread is busy
        assertTrue(started.await(5, TimeUnit.SECONDS))
        ce.edit().putString("k", "v").apply() // queued behind it: not on disk yet
        Privacy.lock(ctx)
        release.countDown()
        Privacy.writer.submit {}.get(5, TimeUnit.SECONDS) // the late run of the queued write has happened (it must find nothing to do)
        Privacy.unlock(ctx)
        Privacy.forgetCachedState() // the next object reads the file
        assertEquals("v", ctx.protectedPrefs().getString("k", null))
        assertEquals("s", ctx.protectedPrefs().getString("seed", null))
    }

    // ---- the words of the personal dictionary and the learned words do not reach glide ----------

    @Test fun personalAndLearnedWordsAreNotInGlideWhileTheScreenIsLocked() {
        helium314.keyboard.latin.gesture.GlideSuggester.forgetUserWords() // copies of other tests
        val personal = assertNotNull(Privacy.personalDictionary(ctx))
        assertTrue(personal.add(PersonalWord("zork", null, 200, "en_US")))
        val kit = helium314.keyboard.latin.gesture.GlideFacilitatorKit(ctx)
        // like the native dictionary built from the personal dictionary: a copy that stays after the screen lock
        kit.personal.words = personal.matching("en_US").map { it.word to it.frequency }
        assertEquals(listOf("zork" to 200), kit.personal.words)
        kit.warmUp()
        assertEquals("zork", kit.glide("zork", personalized = true).first()) // positive control: before the lock
        assertEquals(2, helium314.keyboard.latin.gesture.GlideSuggester.userWordCount()) // the copies of both user dictionaries
        assertEquals("zork", kit.glide("zork", personalized = true).first())

        Privacy.lock(ctx)
        assertEquals(emptyList(), personal.matching("en_US")) // the owner is wiped; the stand-in for the native copy is not
        assertEquals(listOf("zork" to 200), kit.personal.words)
        assertEquals(0, helium314.keyboard.latin.gesture.GlideSuggester.userWordCount())
        assertFalse("zork" in kit.glide("zork", personalized = true))
        assertEquals(0, helium314.keyboard.latin.gesture.GlideSuggester.userWordCount()) // no copy was made again
        // the learned words (the user history dictionary, which also keeps its words in memory after the lock): same
        kit.history.words = listOf("plonk" to 120)
        kit.history.version++
        assertFalse("plonk" in kit.glide("plonk", personalized = true))
        assertEquals(0, helium314.keyboard.latin.gesture.GlideSuggester.userWordCount())

        Privacy.unlock(ctx)
        assertEquals("plonk", kit.glide("plonk", personalized = true).first()) // positive control: the user dictionaries are a source again
    }
}
