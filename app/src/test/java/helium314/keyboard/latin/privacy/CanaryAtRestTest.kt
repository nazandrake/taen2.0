// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.ShadowInputMethodManager2
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.ShadowLocaleManagerCompat
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.ShadowFacilitator2
import helium314.keyboard.latin.ShadowKeyboardSwitcher
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P7 for the encrypted stores: a known canary typed into each feature (setting, clipboard entry, personal word and snippet,
 * background image) never appears as plaintext in the data directories, in any of UTF-8, UTF-16 LE/BE or Windows-1251.
 * The first tests use the stores directly; the others run the real keyboard service (LatinIME, its clipboard listener and
 * screen-lock receiver) and the production accessors. Positive controls: the stores return the canary through their own
 * API, the expected files exist, and the scanner finds a plain file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLocaleManagerCompat::class, ShadowInputMethodManager2::class, ShadowInputMethodService::class,
    ShadowKeyboardSwitcher::class, ShadowFacilitator2::class])
class CanaryAtRestTest {
    private val latin = "TAENcanary-Qx93"
    private val cyrillic = "ТАЕНканарче-Жщ93"
    private val vault = FakeKeyVault()

    private fun store(dir: File, tier: Tier, name: String): SecureStore {
        val keys = DataKeys(vault, tier, File(dir, "dek_${tier.name.lowercase()}.bin"))
        return SecureStore(dir, name, 1, keys::key)
    }

    @Test fun scannerFindsAPlainFileInEveryEncoding() { // control for the 0-hit assertions below
        for (canary in listOf(latin, cyrillic)) {
            for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE, charset("windows-1251"))) {
                val dir = tempDir()
                File(dir, "plain.bin").writeBytes(byteArrayOf(1, 2, 3) + canary.toByteArray(charset) + byteArrayOf(4))
                assertEquals(listOf("plain.bin"), filesContaining(dir, canary), "$canary in ${charset.name()}")
            }
        }
    }

    @Test fun canariesTypedIntoEveryStoreAreNotInTheFiles() { // the stores on their own, without the keyboard service
        val dir = tempDir()
        val ceDir = File(dir, "ce")
        val deDir = File(dir, "de")
        for (canary in listOf(latin, cyrillic)) {
            // settings value (DE tier) and a setting key
            val prefs = EncryptedPrefs({ store(deDir, Tier.DE, "prefs_de") }, directExecutor, directNotify)
            prefs.edit().putString("custom_$canary", canary).putStringSet("set", mutableSetOf(canary)).commit()
            // clipboard history (CE tier)
            val clipStore = store(ceDir, Tier.CE, "clipboard")
            val history = ClipboardHistory(emptyList(), { 1_000L }, { ClipboardLimits.of(60, 50) }) { clipStore.write(ClipboardCodec.encode(it)) }
            history.add("copied $canary", 1_000L)
            // personal dictionary word and snippet (CE tier)
            val snippetStore = store(ceDir, Tier.CE, "snippets")
            val dictionary = PersonalDictionary(emptyList()) { snippetStore.write(PersonalDictionaryCodec.encode(it)) }
            dictionary.add(PersonalWord(canary, "sn$canary", 250, "en"))
            // background image (DE tier): bytes of an image that happen to contain the canary
            store(deDir, Tier.DE, "background_day_portrait").write(byteArrayOf(-1, -40) + canary.toByteArray() + ByteArray(50))

            // positive controls: the data is really there and readable through the stores' own APIs
            assertEquals(canary, EncryptedPrefs({ store(deDir, Tier.DE, "prefs_de") }, directExecutor, directNotify).getString("custom_$canary", null))
            val clips = ClipboardCodec.decode(assertIs<StoreContent.Data>(clipStore.read()).bytes)
            assertEquals("copied $canary", clips.single().text)
            val words = PersonalDictionaryCodec.decode(assertIs<StoreContent.Data>(snippetStore.read()).bytes)
            assertEquals(PersonalWord(canary, "sn$canary", 250, "en"), words.single())
            val image = assertIs<StoreContent.Data>(store(deDir, Tier.DE, "background_day_portrait").read()).bytes
            assertTrue(image.indexOfSub(canary.toByteArray()) >= 0)

            // the files that hold the data exist, so the scan below looked at something, and none holds the canary
            assertEquals(setOf("prefs_de.enc", "background_day_portrait.enc", "dek_de.bin"), deDir.list()!!.toSet())
            assertEquals(setOf("clipboard.enc", "snippets.enc", "dek_ce.bin"), ceDir.list()!!.toSet())
            assertEquals(emptyList(), filesContaining(dir, canary), "plaintext canary $canary under ${dir.path}")
            deDir.deleteRecursively()
            ceDir.deleteRecursively()
        }
    }

    // ---- through the running keyboard service (production wiring) -----------------------------

    // Robolectric's clock for framework code starts at 100 ms, so a copied clip would be stamped 1970 and the retention check
    // would call it expired. Setting it to the real time gives clips the timestamp a device gives them.
    private fun clip(text: String): ClipData {
        SystemClock.setCurrentTimeMillis(System.currentTimeMillis())
        return ClipData.newPlainText("label", text)
    }

    private fun scanApp(ctx: Context, canary: String) =
        listOf(ctx.dataDir, ctx.createDeviceProtectedStorageContext().dataDir).distinct().flatMap { filesContaining(it, canary) }

    @Test fun clipboardCopiedWhileTheKeyboardRunsIsStoredOnlyAsCiphertext() = withKeyboard { ime, ctx ->
        val clipboard = ctx.getSystemService(ClipboardManager::class.java)
        val encrypted = File(ctx.noBackupFilesDir, "taen/clipboard.enc")
        assertFalse(encrypted.exists()) // nothing before the copy
        for (canary in listOf(latin, cyrillic)) {
            clipboard.setPrimaryClip(clip("copied in an app $canary"))
            shadowOf(Looper.getMainLooper()).idle()
        }
        // positive controls: the service stored both copies and its own history shows them
        assertTrue(encrypted.isFile)
        assertEquals(listOf("copied in an app $cyrillic", "copied in an app $latin"), assertNotNull(ClipboardDao.getInstance(ctx)).getAll().map { it.text }.sorted().reversed())
        // the same bytes in the file hold neither canary
        for (canary in listOf(latin, cyrillic)) assertEquals(emptyList(), scanApp(ctx, canary), "plaintext $canary in the app data")
        // the scanner itself can fail: a plain copy of the stored text is found (control for the assertions above)
        val control = File(ctx.noBackupFilesDir, "control.txt").apply { writeText("copied in an app $latin") }
        assertEquals(listOf("control.txt"), scanApp(ctx, latin))
        control.delete()
    }

    @Test fun settingsAndPersonalWordsOfTheRunningAppAreNotPlaintext() = withKeyboard { _, ctx ->
        ctx.prefs().edit().putString("custom_setting", "value-$latin").commit()
        ctx.protectedPrefs().edit().putString("restore", "value-$cyrillic").commit()
        assertTrue(assertNotNull(Privacy.personalDictionary(ctx)).add(PersonalWord("word-$latin", "sc-$cyrillic", 250, "en")))
        assertTrue(Privacy.learnedStorage(ctx, "en-US", null).save("learned-$latin".toByteArray() + ByteArray(20)))
        val ce = File(ctx.noBackupFilesDir, "taen").list()!!.toSet()
        val de = File(ctx.createDeviceProtectedStorageContext().noBackupFilesDir, "taen").list()!!.toSet()
        assertTrue(ce.containsAll(setOf("prefs_ce.enc", "snippets.enc", "learned_en-US.enc", "dek_ce.bin")), ce.toString())
        assertTrue(de.containsAll(setOf("prefs_de.enc", "dek_de.bin")), de.toString())
        for (canary in listOf(latin, cyrillic)) assertEquals(emptyList(), scanApp(ctx, canary), "plaintext $canary")
    }

    @Test fun lockingTheScreenStopsTheServiceFromStoringUntilTheUserIsPresent() = withKeyboard { ime, ctx ->
        val clipboard = ctx.getSystemService(ClipboardManager::class.java)
        val encrypted = File(ctx.noBackupFilesDir, "taen/clipboard.enc")
        fun copy(text: String) { clipboard.setPrimaryClip(clip(text)); shadowOf(Looper.getMainLooper()).idle() }
        fun send(action: String) { ctx.sendBroadcast(Intent(action)); shadowOf(Looper.getMainLooper()).idle() }

        copy("before the lock $latin")
        val stored = encrypted.readBytes() // positive control: the same action stores while the screen is unlocked

        send(Intent.ACTION_SCREEN_OFF)
        assertTrue(Privacy.isLocked)
        copy("while locked $latin")
        copy("while locked too $cyrillic")
        assertContentEquals(stored, encrypted.readBytes())
        assertEquals(emptyList(), scanApp(ctx, "while locked"))

        send(Intent.ACTION_USER_PRESENT)
        assertFalse(Privacy.isLocked)
        copy("after the unlock $latin")
        assertEquals(setOf("before the lock $latin", "after the unlock $latin"), assertNotNull(ClipboardDao.getInstance(ctx)).getAll().map { it.text }.toSet())
    }

    private fun withKeyboard(test: (LatinIME, Context) -> Unit) {
        Privacy.forgetCachedState()
        ClipboardDao.forgetInstance()
        val ime = Robolectric.setupService(LatinIME::class.java)
        try {
            test(ime, ime.applicationContext)
        } finally {
            Privacy.unlock(ime)
            Privacy.forgetCachedState()
            ClipboardDao.forgetInstance()
        }
    }

    @Test fun fileSetPerTier() {
        val dir = tempDir()
        store(dir, Tier.CE, "clipboard").write(byteArrayOf(1))
        store(dir, Tier.CE, "snippets").write(byteArrayOf(1))
        assertEquals(setOf("clipboard.enc", "snippets.enc", "dek_ce.bin"), dir.list()!!.toSet())
        assertContentEquals(byteArrayOf(1), assertIs<StoreContent.Data>(store(dir, Tier.CE, "clipboard").read()).bytes)
    }
}
