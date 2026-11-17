// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import helium314.keyboard.latin.voice.VoiceModelSpec
import helium314.keyboard.latin.voice.VoiceModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * the result of deleteAll() decides what the voice page shows. VoiceScreen calls deleteVoiceModel only, so these tests
 * run the page's action on a real store: ignore the result of deleteAll() there, or drop the call from the screen, and a test here fails.
 */
class VoiceModelDeleteTest {
    private val tokens = "tokens-A".toByteArray()
    private val entries = listOf(VoiceModelSpec.Entry("tokens.txt", 8, "95dcd6c357b3d2b656b1ade801e28b5da10a255a537245e9c5f6d5ee586669ff"))

    @get:Rule val temp = TemporaryFolder(File("build").also { it.mkdirs() }) // under the module's build directory (disk), removed after each test

    private fun dirWithModel(): File = temp.newFolder("voice").also {
        File(it, "tokens.txt").writeBytes(tokens)
    }

    private class Page { val shown = ArrayList<String?>(); var reloads = 0 }

    private fun tap(store: VoiceModelStore, page: Page) = runBlocking {
        deleteVoiceModel(store, Dispatchers.Unconfined, "delete failed", { page.shown.add(it) }, { page.reloads++ })
    }

    @Test fun theOutcomeOfAFailedDeleteShowsTheErrorAndReadsTheFilesAgain() {
        assertEquals(VoiceModelDeleteOutcome(showError = true, reloadFromDisk = true), VoiceModelDeleteOutcome.of(false))
        assertEquals(VoiceModelDeleteOutcome(showError = false, reloadFromDisk = true), VoiceModelDeleteOutcome.of(true))
    }

    @Test fun aSuccessfulDeleteRemovesTheFilesClearsTheMessageAndReloadsTheRows() { // positive control
        val d = dirWithModel()
        val store = VoiceModelStore(d, entries)
        assertEquals(1, store.status().count { it.installed })
        val page = Page()
        tap(store, page)
        assertEquals(listOf<String?>(null), page.shown)
        assertEquals(1, page.reloads)
        assertEquals(0, store.status().count { it.installed })
    }

    @Test fun theVoiceScreenGoesThroughThatActionAndNeverCallsDeleteAllItself() { // no UI test library: the wiring is read from the screen's source
        val source = File("src/main/java/helium314/keyboard/settings/screens/VoiceScreen.kt").readText()
        assertEquals(1, Regex("""\bdeleteVoiceModel\(""").findAll(source).count())
        assertEquals(0, Regex("""\.deleteAll\(""").findAll(source).count(), "an ignored deleteAll() in the screen would bypass the action")
    }

    @Test fun aFailedDeleteIsShownAndTheRowsAreReloaded() {
        val d = dirWithModel()
        val store = VoiceModelStore(d, entries)
        val page = Page()
        // an install holds the directory: deleteAll() is refused and returns false
        store.exclusive(Unit) { tap(store, page) }
        assertEquals(listOf<String?>("delete failed"), page.shown)
        assertEquals(1, page.reloads)
        assertEquals(1, store.status().count { it.installed }) // nothing was removed
    }
}
