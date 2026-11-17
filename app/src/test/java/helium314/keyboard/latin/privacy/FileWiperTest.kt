// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.privacy

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Deleting the files of a wipe against real directories (a credential-encrypted and a device-protected data directory). */
class FileWiperTest {
    private val ce = tempDir()
    private val de = tempDir()

    // every kind of file the app keeps, by data directory and wipe scope
    private val learned = listOf("no_backup/taen/learned_en-US.enc", "no_backup/taen/learned_bg.enc.tmp", "no_backup/taen/snippets.enc", "files/blacklists/en-US.txt", "files/UserHistoryDictionary.en.dict")
    private val clipboard = listOf("no_backup/taen/clipboard.enc", "databases/heliboard.db", "files/clipboard/clip1.png", "files/temp_clip")
    private val settings = listOf("no_backup/taen/prefs_ce.enc", "no_backup/taen/prefs_de.enc", "no_backup/taen/background_day_portrait.enc", "no_backup/taen/unknown_future_store.enc",
        "shared_prefs/old.xml", "files/layouts/custom.main.json", "files/custom_font", "files/theme/x.json", "no_backup/other.bin")
    private val config = listOf("no_backup/taen/security.enc", "no_backup/taen/selfdestruct.enc")
    // the imported pictures, a downloaded dictionary with its staging file and the downloaded next-word models are imported data: removed with that scope,
    // never with the others (the models would otherwise fall to the settings rule for unknown files)
    private val imported = listOf("files/dicts/bg/main_bg.dict", "files/dicts/en_US/user.dict", "files/dicts/bg/main_user.dict", "no_backup/taen/media_0123456789abcdef.enc", "no_backup/taen/media_index.enc",
        "no_backup/taen/media_0123456789abcdef.enc.tmp", "no_backup/taen-downloads/dict-bg.part", "no_backup/${Privacy.MODELS_DIR}/model-en.onnx",
        "no_backup/${Privacy.MODELS_DIR}/model-bg.onnx", "no_backup/taen-downloads/model-bg.part")
    private val always = listOf("no_backup/taen/activity.enc", "cache/temp_dict", "cache/sub/x.tmp")
    private val keys = listOf("no_backup/taen/dek_ce.bin", "no_backup/taen/dek_de.bin", "no_backup/taen/dek_ce.bin.tmp")
    private val marker = "no_backup/taen/wipe.pending"
    private val untouched = listOf("code_cache/x", "lib/libjni.so")

    private fun seed(root: File) {
        for (p in learned + clipboard + settings + config + imported + always + keys + marker + untouched) {
            val f = File(root, p)
            f.parentFile.mkdirs()
            f.writeText("canary-${root.name}")
        }
    }

    private fun present(root: File): Set<String> =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toSet()

    private val all = learned + clipboard + settings + config + imported + always + keys + marker + untouched
    private fun wiper() = FileWiper(ce, de)

    @Test fun aFullScopeRemovesEveryUserFileOfBothDirectoriesAndLeavesTheMarkerAndRuntimeFiles() {
        seed(ce); seed(de)
        assertTrue(wiper().delete(WipeScope.ALL, setOf("wipe.pending"), ceOnly = false))
        for (root in listOf(ce, de)) assertEquals(setOf(marker) + untouched, present(root), root.name)
        assertEquals(emptyList(), filesContaining(ce, "canary") .filter { it !in listOf("wipe.pending", "x", "libjni.so") })
    }

    @Test fun theKeysAndTheirTempFilesGoOnlyWithAShred() {
        seed(ce)
        wiper().delete(WipeScope(learned = false), setOf("wipe.pending"), ceOnly = false) // partial: no shred
        for (k in keys) assertTrue(File(ce, k).exists(), k)
        seed(ce)
        wiper().delete(WipeScope.ALL, setOf("wipe.pending"), ceOnly = false)
        for (k in keys) assertFalse(File(ce, k).exists(), k)
    }

    @Test fun eachScopeFlagRemovesExactlyItsFilesAndNothingElse() {
        val cases = listOf(
            WipeScope(learned = true, clipboard = false, settings = false, config = false, imported = false) to learned,
            WipeScope(learned = false, clipboard = true, settings = false, config = false, imported = false) to clipboard,
            WipeScope(learned = false, clipboard = false, settings = true, config = false, imported = false) to settings,
            WipeScope(learned = false, clipboard = false, settings = false, config = true, imported = false) to config,
            WipeScope(learned = false, clipboard = false, settings = false, config = false, imported = true) to imported,
        )
        for ((scope, removed) in cases) {
            val d = tempDir()
            seed(d)
            assertTrue(FileWiper(d, null).delete(scope, setOf("wipe.pending"), ceOnly = false), "$scope")
            val expectedLeft = (all - removed.toSet() - always.toSet()).toSet() // the always-files (caches, activity time) go with any wipe
            assertEquals(expectedLeft, present(d), "$scope")
        }
    }

    @Test fun theCachesAndTheActivityTimeGoWithAnyWipe() {
        val d = tempDir(); seed(d)
        FileWiper(d, null).delete(WipeScope(learned = true, clipboard = false, settings = false, config = false, imported = false), setOf("wipe.pending"), ceOnly = false)
        for (p in always) assertFalse(File(d, p).exists(), p)
    }

    @Test fun theKeepSetIsHonouredByNameInTheStoreDirectoryOnly() {
        val d = tempDir(); seed(d)
        File(d, "files/security.enc").apply { writeText("not the store") } // same name elsewhere is not protected
        FileWiper(d, null).delete(WipeScope.ALL, setOf("wipe.pending", "security.enc", "selfdestruct.enc", "dek_de.bin"), ceOnly = false)
        assertEquals(setOf(marker, "no_backup/taen/security.enc", "no_backup/taen/selfdestruct.enc", "no_backup/taen/dek_de.bin") + untouched, present(d))
    }

    @Test fun ceOnlyLeavesTheDeviceProtectedDirectoryAlone() {
        seed(ce); seed(de)
        wiper().delete(WipeScope.ALL, setOf("wipe.pending"), ceOnly = true)
        assertEquals(setOf(marker) + untouched, present(ce))
        assertEquals(all.toSet(), present(de))
    }

    @Test fun aLockedCredentialEncryptedDirectoryIsReportedAndTheDeviceProtectedOneIsStillWiped() {
        seed(de)
        val result = FileWiper(null, de).delete(WipeScope.ALL, setOf("wipe.pending"), ceOnly = false)
        assertFalse(result) // the credential-encrypted files are still to be removed: the marker stays
        assertEquals(setOf(marker) + untouched, present(de))
        assertTrue(FileWiper(null, de).delete(WipeScope.ALL, setOf("wipe.pending"), ceOnly = true).not()) // still locked: still pending
    }

    @Test fun emptyDirectoriesOfDeletedFilesAreRemovedBecauseTheirNamesAreData() {
        val d = tempDir(); seed(d)
        FileWiper(d, null).delete(WipeScope.ALL, setOf("wipe.pending"), ceOnly = false)
        assertFalse(File(d, "files/dicts").exists())
        assertFalse(File(d, "files/layouts").exists())
        assertTrue(File(d, "files").isDirectory) // the data directories themselves stay
        assertTrue(File(d, "no_backup/taen").isDirectory) // holds the marker
    }

    @Test fun aSymbolicLinkIsRemovedAndNeverFollowed() {
        val outside = tempDir()
        File(outside, "precious.txt").writeText("keep me")
        val d = tempDir(); seed(d)
        Files.createSymbolicLink(File(d, "files/link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(d, "no_backup/taen/clipboard.enc.link").toPath(), File(outside, "precious.txt").toPath())
        assertTrue(FileWiper(d, null).delete(WipeScope.ALL, setOf("wipe.pending"), ceOnly = false))
        assertEquals("keep me", File(outside, "precious.txt").readText())
        assertFalse(Files.isSymbolicLink(File(d, "files/link").toPath()))
        assertFalse(Files.isSymbolicLink(File(d, "no_backup/taen/clipboard.enc.link").toPath()))
    }

    @Test fun missingDirectoriesAreFineAndTheWipeIsIdempotent() {
        val d = tempDir()
        assertTrue(FileWiper(d, null).delete(WipeScope.ALL, emptySet(), ceOnly = false))
        seed(d)
        val w = FileWiper(d, null)
        assertTrue(w.delete(WipeScope.ALL, setOf("wipe.pending"), ceOnly = false))
        val after = present(d)
        assertTrue(w.delete(WipeScope.ALL, setOf("wipe.pending"), ceOnly = false))
        assertEquals(after, present(d))
    }

    @Test fun anUndeletableFileMakesTheResultFalseAndTheRestIsStillDeleted() {
        val d = tempDir(); seed(d)
        val dir = File(d, "files/dicts/bg")
        dir.setWritable(false) // a directory that cannot be changed: its file cannot be unlinked
        try {
            if (File(dir, "main_bg.dict").delete()) return // running as a user that ignores permissions: nothing to prove here
            seed(d)
            assertFalse(FileWiper(d, null).delete(WipeScope.ALL, setOf("wipe.pending"), ceOnly = false))
            assertFalse(File(d, "no_backup/taen/clipboard.enc").exists())
        } finally {
            dir.setWritable(true)
        }
    }

    @Test fun theVoiceModelIsPublicDataSoAPartialWipeKeepsItAndOnlyAShredRemovesIt() { // model files are not user data (P20)
        val voice = listOf("no_backup/taen-voice/encoder.int8.onnx", "no_backup/taen-voice/tokens.txt", "no_backup/taen-voice/import.part")
        fun seedVoice(root: File) = voice.forEach { File(root, it).also { f -> f.parentFile.mkdirs() }.writeText("model") }
        val partial = listOf(
            WipeScope(learned = true, clipboard = false, settings = false, config = false, imported = false),
            WipeScope(learned = false, clipboard = true, settings = false, config = false, imported = false),
            WipeScope(learned = false, clipboard = false, settings = true, config = true, imported = false), // settings: the unknown-file rule must not catch the model
            WipeScope(learned = false, clipboard = false, settings = false, config = false, imported = true),
        )
        for (scope in partial) {
            val d = tempDir(); seed(d); seedVoice(d)
            assertFalse(scope.shreds)
            assertTrue(FileWiper(d, null).delete(scope, setOf("wipe.pending"), ceOnly = false))
            assertEquals(voice.toSet(), present(d).filter { it.startsWith("no_backup/taen-voice/") }.toSet(), "$scope")
        }
        // positive control: a shred removes them with everything else, and so does a shred without the configuration
        for (scope in listOf(WipeScope.ALL, WipeScope(config = false))) {
            val d = tempDir(); seed(d); seedVoice(d)
            assertTrue(scope.shreds)
            assertTrue(FileWiper(d, null).delete(scope, setOf("wipe.pending"), ceOnly = false))
            assertEquals(emptySet(), present(d).filter { it.startsWith("no_backup/taen-voice/") }.toSet(), "$scope")
        }
    }
}
