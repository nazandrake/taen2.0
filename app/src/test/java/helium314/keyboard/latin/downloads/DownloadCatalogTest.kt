// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.downloads

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The catalogue of the APK equals tools/downloads.lock, and every row is https on an allowed host, pinned to a commit, with a real hash and size. */
class DownloadCatalogTest {
    private val asset = File("src/main/assets/${DownloadCatalog.ASSET}")
    private val lock = File("../tools/downloads.lock")
    private val sha = "a".repeat(64)
    private val commit = "ff0cd52be8d9530200fefe8b296c53d4f662091e"

    private fun row(id: String = "dict-x", url: String = "https://codeberg.org/Helium314/aosp-dictionaries/raw/commit/$commit/dictionaries/main_x.dict", size: Long = 10, hash: String = sha, kind: String = "dictionary") =
        listOf(id, kind, "xx", size.toString(), hash, url, "CC BY 4.0", "wordlists/main_x.source:2").joinToString("\t")

    private fun problems(vararg rows: String) = DownloadCatalog.problems(DownloadCatalog.parse(rows.joinToString("\n")))

    @Test fun theCatalogueOfTheApkEqualsTheLockFileAndIsValid() {
        assertTrue(asset.isFile, "positive control: the catalogue asset was found")
        assertTrue(lock.isFile, "positive control: the lock file was found")
        assertEquals(lock.readText(), asset.readText(), "assets/catalog/downloads.tsv and tools/downloads.lock must be the same file")
        val entries = DownloadCatalog.parse(asset.readText())
        assertTrue(entries.isNotEmpty())
        assertEquals(emptyList(), DownloadCatalog.problems(entries))
        assertTrue(entries.any { it.locale == "bg" && it.kind == DownloadEntry.DICTIONARY }, "the validated Bulgarian dictionary is listed")
        assertEquals(listOf("model-en" to "en", "model-bg" to "bg"), entries.filter { it.kind == DownloadEntry.MODEL }.map { it.id to it.locale }, "the two next-word models")
        assertTrue(entries.filter { it.kind == DownloadEntry.MODEL }.all { it.url.startsWith("https://huggingface.co/") && it.url.endsWith(".onnx") })
    }

    @Test fun aGoodRowHasNoProblem() = assertEquals(emptyList(), problems(row()))

    @Test fun aBranchUrlLatestAndOtherHostsAreRefused() {
        assertEquals(1, problems(row(url = "https://codeberg.org/Helium314/aosp-dictionaries/raw/branch/main/dictionaries/main_x.dict")).size, "a planted /raw/branch/main/ row fails")
        assertEquals(1, problems(row(url = "https://codeberg.org/Helium314/aosp-dictionaries/raw/commit/latest/dictionaries/main_x.dict")).size)
        assertEquals(1, problems(row(url = "https://huggingface.co/o/m/resolve/main/model.onnx")).size, "a branch name instead of a commit")
        assertEquals(1, problems(row(url = "http://codeberg.org/Helium314/aosp-dictionaries/raw/commit/$commit/a.dict")).size, "plain http")
        assertEquals(1, problems(row(url = "https://example.com/Helium314/aosp-dictionaries/raw/commit/$commit/a.dict")).size, "another host")
        assertEquals(1, problems(row(url = "https://codeberg.org.evil.example/o/r/raw/commit/$commit/a.dict")).size, "a host that only starts like an allowed one")
        assertEquals(1, problems(row(url = "https://evilcodeberg.org/o/r/raw/commit/$commit/a.dict")).size, "a host that only ends like an allowed one")
        assertEquals(1, problems(row(url = "https://codeberg.org/o/r/raw/commit/${commit.take(39)}/a.dict")).size, "a short commit")
        assertEquals(0, problems(row(url = "https://huggingface.co/o/m/resolve/$commit/onnx/model.onnx?download=true")).size, "positive control: a pinned Hugging Face address")
    }

    @Test fun hashSizeKindAndDuplicatesAreChecked() {
        assertEquals(1, problems(row(hash = "A".repeat(64))).size, "upper case")
        assertEquals(1, problems(row(hash = "a".repeat(63))).size, "short")
        assertEquals(1, problems(row(hash = "g".repeat(64))).size, "not hex")
        assertEquals(1, problems(row(size = 0)).size)
        assertEquals(1, problems(row(size = -5)).size)
        assertEquals(1, problems(row(kind = "font")).size, "an unknown kind")
        assertEquals(0, problems(row(kind = DownloadEntry.MODEL)).size, "positive control: a model row")
        assertEquals(1, problems(row(), row()).size, "the same id twice")
    }

    @Test fun aRowWithTheWrongShapeIsRejectedWhenParsed() {
        assertFailsWith<IllegalArgumentException> { DownloadCatalog.parse("only\ttwo") }
        assertFailsWith<IllegalArgumentException> { DownloadCatalog.parse(row().replace("\t10\t", "\tten\t")) }
        assertEquals(0, DownloadCatalog.parse("# a comment\n\n").size)
    }
}
