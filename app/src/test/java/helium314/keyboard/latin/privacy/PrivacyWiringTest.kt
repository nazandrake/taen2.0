// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.Context
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The production entry points (Context.prefs(), Context.protectedPrefs(), the clipboard DAO, the background image
 * accessors) reach the encrypted stores: each test fails if the wiring in Ktx.kt or Privacy.kt is removed.
 * The credential-encrypted tier is tested with the user unlocked and locked (a locked user gets no store at all).
 */
@RunWith(RobolectricTestRunner::class)
class PrivacyWiringTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val deCtx: Context = ctx.createDeviceProtectedStorageContext()
    private val canary = "TAENcanary-Qx93-Жщ"

    // each test starts with its own stores and leaves nothing cached for the next test class
    @Before fun before() = Privacy.forgetCachedState()
    @After fun after() { Privacy.forgetCachedState(); ClipboardDao.forgetInstance() }

    private fun dataDirs() = listOf(ctx.dataDir, deCtx.dataDir).distinct()

    @Test fun prefsAreTheEncryptedStore() {
        val p = ctx.prefs()
        assertIs<EncryptedPrefs>(p)
        assertTrue(p.edit().putString("canary-key", canary).putStringSet("canary-set", mutableSetOf(canary)).commit())
        assertEquals(canary, p.getString("canary-key", null)) // positive control
        for (dir in dataDirs()) assertEquals(emptyList(), filesContaining(dir, canary), dir.path)
        assertTrue(File(deCtx.noBackupFilesDir, "taen/prefs_de.enc").isFile)
        assertTrue(File(deCtx.noBackupFilesDir, "taen/dek_de.bin").isFile)
    }

    @Test fun plainPreferencesOfAnEarlierVersionAreMigratedAndDeleted() {
        val name = ctx.packageName + "_preferences"
        val canary = "TAENcanary-Qx93" // ASCII: the platform writes preference files as XML, which may escape other characters
        val plainFile = File(deCtx.dataDir, "shared_prefs/$name.xml")
        // The application's background start (SupportedEmojis.load and others) also calls prefs(). The Privacy monitor is held
        // while the "first start" is staged, so that call either comes before it (and its cache is dropped here) or after it
        // (and it moves the old file, which gives the same end state).
        // the application start queues preference writes on the shared writer thread. One that lands after the staging below
        // recreates the store and makes the migration a no-op (seen as a failure of this test in full runs). Waiting for the queue to
        // drain removes the race without changing what is asserted.
        Privacy.writer.submit {}.get()
        synchronized(Privacy) {
            // the application already created the encrypted store at its start: remove it, like a first start of the new version
            File(deCtx.noBackupFilesDir, "taen").deleteRecursively()
            Privacy.forgetCachedState()
            // the way the old version stored them
            deCtx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("old-setting", canary).putInt("n", 3).commit()
            assertTrue(plainFile.isFile) // positive control: the plain file really existed
            assertEquals(listOf(plainFile.name), filesContaining(deCtx.dataDir, canary).map { it }) // and held the canary
        }
        val p = ctx.prefs()
        assertEquals(canary, p.getString("old-setting", null))
        assertEquals(3, p.getInt("n", 0))
        assertFalse(plainFile.exists())
        for (dir in dataDirs()) assertEquals(emptyList(), filesContaining(dir, canary), dir.path)
    }

    // ---- the old credential-encrypted preferences file ------------------------------------------

    private val legacyName get() = ctx.packageName + "_preferences"
    private val legacyFile get() = File(ctx.dataDir, "shared_prefs/$legacyName.xml")

    private fun writeLegacyCredentialPrefs(canary: String) {
        // the old protectedPrefs(): the default preferences file of the credential-encrypted storage
        ctx.getSharedPreferences(legacyName, Context.MODE_PRIVATE).edit().putString("old-protected", canary).putStringSet("old-set", setOf("a", "b")).putInt("n", 7).commit()
        assertTrue(legacyFile.isFile) // positive control: the plain file existed
        assertEquals(listOf(legacyFile.name), filesContaining(ctx.dataDir, canary))
        File(ctx.noBackupFilesDir, "taen").deleteRecursively() // a first start of the new version: no encrypted store yet
        Privacy.forgetCachedState()
    }

    @Test fun plainCredentialEncryptedPreferencesOfAnEarlierVersionAreMigratedAndDeleted() {
        val canary = "TAENcanary-Qx93" // ASCII: the platform writes preference files as XML
        writeLegacyCredentialPrefs(canary)
        val p = ctx.protectedPrefs()
        assertEquals(canary, p.getString("old-protected", null))
        assertEquals(setOf("a", "b"), p.getStringSet("old-set", null))
        assertEquals(7, p.getInt("n", 0))
        assertFalse(legacyFile.exists())
        for (dir in dataDirs()) assertEquals(emptyList(), filesContaining(dir, canary), dir.path)
        // a new start reads the encrypted copy
        Privacy.forgetCachedState()
        assertEquals(canary, ctx.protectedPrefs().getString("old-protected", null))
    }

    @Test fun plainCredentialEncryptedPreferencesWaitWhileLockedAndMoveAfterUnlock() {
        val canary = "TAENcanary-Qx93"
        val users = ctx.getSystemService(UserManager::class.java)
        writeLegacyCredentialPrefs(canary)
        shadowOf(users).setUserUnlocked(false)
        assertNull(ctx.protectedPrefs().getString("old-protected", null)) // locked: empty
        assertTrue(legacyFile.exists()) // and the plain file is neither read into the store nor deleted
        shadowOf(users).setUserUnlocked(true)
        assertEquals(canary, ctx.protectedPrefs().getString("old-protected", null)) // the same object moves it when it can
        assertFalse(legacyFile.exists())
    }

    @Test fun theStartOfTheAppMovesThePlainCredentialEncryptedPreferencesWithoutAnyoneReadingThem() {
        // production reads the credential-encrypted preferences only for an export: the start must not wait for that
        val canary = "TAENcanary-Qx93"
        writeLegacyCredentialPrefs(canary)
        (ctx as android.app.Application).onCreate() // the start of the application, through its real entry point
        assertFalse(legacyFile.exists())
        for (dir in dataDirs()) assertEquals(emptyList(), filesContaining(dir, canary), dir.path)
        assertTrue(File(ctx.noBackupFilesDir, "taen/prefs_ce.enc").isFile)
        assertEquals(canary, ctx.protectedPrefs().getString("old-protected", null))
    }

    @Test fun anUnlockMovesThePlainCredentialEncryptedPreferencesThatCouldNotBeMovedAtTheStart() {
        val canary = "TAENcanary-Qx93"
        val users = ctx.getSystemService(UserManager::class.java)
        writeLegacyCredentialPrefs(canary)
        shadowOf(users).setUserUnlocked(false)
        Privacy.openAtStart(ctx) // the start while the device is locked: the file must stay
        assertTrue(legacyFile.exists())
        shadowOf(users).setUserUnlocked(true)
        Privacy.unlock(ctx) // the screen is unlocked later in the same process
        assertFalse(legacyFile.exists())
        assertEquals(canary, ctx.protectedPrefs().getString("old-protected", null))
    }

    @Test fun credentialEncryptedStoresAreEncrypted() {
        val p = ctx.protectedPrefs()
        assertIs<EncryptedPrefs>(p)
        assertTrue(p.edit().putString("ce-canary", canary).commit())
        assertEquals(canary, p.getString("ce-canary", null))

        // clipboard history through the production DAO
        ClipboardDao.forgetInstance()
        val dao = assertNotNull(ClipboardDao.getInstance(ctx))
        dao.addClip(System.currentTimeMillis(), "copied $canary")
        assertEquals(1, dao.count())
        // personal dictionary and snippets through the production accessor
        assertTrue(assertNotNull(Privacy.personalDictionary(ctx)).add(PersonalWord(canary, "sn$canary", 250, "en")))

        for (dir in dataDirs()) assertEquals(emptyList(), filesContaining(dir, canary), dir.path)
        val stores = File(ctx.noBackupFilesDir, "taen")
        assertEquals(setOf("dek_ce.bin", "prefs_ce.enc", "clipboard.enc", "snippets.enc"), stores.list()!!.toSet())

        // a new start reads everything back
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        assertEquals("copied $canary", assertNotNull(ClipboardDao.getInstance(ctx)).getAt(0).text)
        assertEquals(listOf(PersonalWord(canary, "sn$canary", 250, "en")), assertNotNull(Privacy.personalDictionary(ctx)).forLocale("en"))
        assertEquals(canary, ctx.protectedPrefs().getString("ce-canary", null))
    }

    @Test fun lockedUserGetsNoCredentialEncryptedStores() {
        val users = ctx.getSystemService(UserManager::class.java)
        shadowOf(users).setUserUnlocked(false)
        ClipboardDao.forgetInstance()
        assertFalse(ctx.protectedPrefs().edit().putString("k", canary).commit()) // dropped, not written anywhere
        assertNull(ctx.protectedPrefs().getString("k", null))
        assertNull(Privacy.store(ctx, Tier.CE, "clipboard", 1))
        assertNull(Privacy.loadClipboard(ctx))
        assertNull(Privacy.personalDictionary(ctx))
        assertNull(ClipboardDao.getInstance(ctx)) // no history while the store cannot be opened
        for (dir in dataDirs()) assertEquals(emptyList(), filesContaining(dir, canary))
        assertFalse(File(ctx.noBackupFilesDir, "taen").exists())

        shadowOf(users).setUserUnlocked(true) // positive control: the same calls work once the user unlocked
        assertNotNull(Privacy.store(ctx, Tier.CE, "clipboard", 1))
        assertNotNull(ClipboardDao.getInstance(ctx))
    }

    @Test fun backgroundImageIsStoredEncrypted() {
        val image = byteArrayOf(-1, -40) + canary.toByteArray() + ByteArray(100) { it.toByte() }
        assertFalse(Privacy.hasBackground(ctx, night = false, landscape = false))
        assertTrue(Privacy.writeBackground(ctx, night = false, landscape = false, bytes = image))
        assertContentEquals(image, Privacy.readBackground(ctx, night = false, landscape = false))
        assertNull(Privacy.readBackground(ctx, night = true, landscape = false)) // another variant is separate
        for (dir in dataDirs()) assertEquals(emptyList(), filesContaining(dir, canary), dir.path)
        Privacy.deleteBackground(ctx, night = false, landscape = false)
        assertFalse(Privacy.hasBackground(ctx, night = false, landscape = false))
    }

    @Test fun plainBackgroundImageOfAnEarlierVersionIsMigrated() {
        val image = canary.toByteArray() + ByteArray(50)
        val legacy = File(deCtx.filesDir, "custom_background_image_night")
        legacy.writeBytes(image)
        assertTrue(Privacy.hasBackground(ctx, night = true, landscape = false))
        assertFalse(legacy.exists()) // the plain file is gone
        assertContentEquals(image, Privacy.readBackground(ctx, night = true, landscape = false))
        for (dir in dataDirs()) assertEquals(emptyList(), filesContaining(dir, canary), dir.path)
    }

    @Test fun clipboardDatabaseOfAnEarlierVersionIsMigratedAndDeleted() {
        val now = System.currentTimeMillis()
        val db = ctx.getDatabasePath("heliboard.db")
        db.parentFile!!.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(db, null).use {
            it.execSQL("CREATE TABLE CLIPBOARD (ID INTEGER PRIMARY KEY, TIMESTAMP INTEGER NOT NULL, PINNED TINYINT NOT NULL, TEXT TEXT, FILE TEXT, MIME_TYPE TEXT)")
            it.execSQL("INSERT INTO CLIPBOARD (TIMESTAMP, PINNED, TEXT) VALUES (${now - 60_000}, 0, 'old clip $canary')")
            it.execSQL("INSERT INTO CLIPBOARD (TIMESTAMP, PINNED, TEXT) VALUES (${now - 10 * 3_600_000L}, 1, 'old pinned $canary')")
            it.execSQL("INSERT INTO CLIPBOARD (TIMESTAMP, PINNED, TEXT, FILE, MIME_TYPE) VALUES (${now - 30_000}, 0, 'image label $canary', 'abc.png', 'image/png')")
        }
        val imageDir = File(ctx.filesDir, "clipboard").also { it.mkdirs() }
        File(imageDir, "abc.png").writeBytes(canary.toByteArray())
        assertTrue(filesContaining(ctx.dataDir, canary).isNotEmpty()) // positive control: the plaintext is there

        ClipboardDao.forgetInstance()
        val dao = assertNotNull(ClipboardDao.getInstance(ctx))
        assertEquals(setOf("old pinned $canary", "old clip $canary"), dao.getAll().map { it.text }.toSet()) // image clip dropped
        assertEquals(listOf(true, false), dao.getAll().map { it.pinned }) // pinned entries first
        assertFalse(db.exists())
        assertFalse(imageDir.exists())
        for (dir in dataDirs()) assertEquals(emptyList(), filesContaining(dir, canary), dir.path)
        // a new start reads the encrypted copy
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        assertEquals(2, assertNotNull(ClipboardDao.getInstance(ctx)).count())
    }
}
