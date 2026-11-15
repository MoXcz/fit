// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.content

import app.fit.content.ArchiveEntry
import app.fit.content.ArchiveLimits
import app.fit.content.ArchiveResult
import app.fit.content.ContentPacks
import app.fit.content.PackResult
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Real zip files, as another app would write them, through the real reader and the pack rules.
class ZipArchivesTest {
    private val archives = ZipArchives()
    private val limits = ArchiveLimits(entries = 10, entryBytes = 1_000, totalBytes = 2_500)
    private val md = "---\nformat: 1\nkind: exercise\nid: plank\nname: Plank\n---\n"

    private fun zip(
        vararg entries: Pair<String, ByteArray>,
        level: Int = 9,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(level)
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun problems(vararg entries: Pair<String, ByteArray>) =
        assertIs<PackResult.Unreadable>(ContentPacks.read("pack.zip", zip(*entries), archives)).problems

    @Test
    fun `what is written reads back, names and bytes, without the directories`() {
        val written =
            archives.write(listOf(ArchiveEntry("backup.json", "{}".toByteArray()), ArchiveEntry("images/a.png", byteArrayOf(0, 1, 2, -1))))
        val entries = assertIs<ArchiveResult.Entries>(archives.read(written, limits)).entries
        assertEquals(listOf("backup.json", "images/a.png"), entries.map { it.name })
        assertContentEquals(byteArrayOf(0, 1, 2, -1), entries[1].bytes)
        val withFolder = zip("exercises/" to ByteArray(0), "exercises/plank.md" to md.toByteArray())
        assertEquals(
            listOf("exercises/plank.md"),
            assertIs<ArchiveResult.Entries>(archives.read(withFolder, limits)).entries.map { it.name },
        )
    }

    @Test
    fun `a zip of a content folder is read as a pack`() {
        val pack =
            assertIs<PackResult.Read>(
                ContentPacks.read(
                    "pack.zip",
                    zip("content/exercises/plank.md" to md.toByteArray(), "content/images/plank.png" to byteArrayOf(1, 2)),
                    archives,
                ),
            ).pack
        assertEquals(listOf("exercises/plank.md"), pack.files.map { it.path })
        assertEquals(md, pack.files.single().text)
        assertEquals(setOf("images/plank.png"), pack.images.keys)
    }

    @Test
    fun `a zip bomb is stopped by what it unpacks to, not by what its headers say`() {
        // 100 kB of zeros packs into a few hundred bytes.
        val bomb = zip("images/a.png" to ByteArray(100_000))
        assertTrue(bomb.size < 1_000)
        assertEquals(ArchiveResult.TooLarge("images/a.png"), archives.read(bomb, limits))
        val spread = zip(*(0..3).map { "images/$it.png" to ByteArray(900) }.toTypedArray())
        assertEquals(ArchiveResult.TooLarge(null), archives.read(spread, limits))
        val crowd = zip(*(0..10).map { "e$it.md" to ByteArray(1) }.toTypedArray())
        assertEquals(ArchiveResult.TooManyEntries, archives.read(crowd, limits))
    }

    @Test
    fun `entries with dot-dot, absolute or backslash paths are refused by the pack rules`() {
        val names = listOf("../../data/data/app.fit/databases/fit.db", "exercises/../../evil.md", "/etc/passwd", "exercises\\plank.md")
        names.forEach { name ->
            val found = problems("exercises/ok.md" to md.toByteArray(), name to md.toByteArray())
            assertEquals(1, found.size, name)
            assertTrue(found.single().endsWith("isn't a path inside the pack"), name)
        }
    }

    @Test
    fun `text that isn't UTF-8 and files outside the layout are refused`() {
        assertEquals(
            listOf("exercises/plank.md: isn't UTF-8 text"),
            problems("exercises/plank.md" to byteArrayOf('a'.code.toByte(), 0xE9.toByte(), 0x20)),
        )
        assertTrue(problems("exercises/a/b/plank.md" to md.toByteArray()).single().contains("isn't part of a pack"))
        assertTrue(
            problems(
                "run.sh" to "rm -rf".toByteArray(),
                "exercises/plank.md" to md.toByteArray(),
            ).single().startsWith("run.sh: isn't part of a pack"),
        )
    }

    @Test
    fun `a file that isn't a zip, a cut-off zip and garbage after the magic say so`() {
        assertEquals(ArchiveResult.NotAnArchive, archives.read("hello world".toByteArray(), limits))
        assertEquals(ArchiveResult.NotAnArchive, archives.read(ByteArray(0), limits))
        val whole = zip("exercises/plank.md" to ByteArray(600) { (it * 31).toByte() }, level = 0)
        assertEquals(ArchiveResult.NotAnArchive, archives.read(whole.copyOf(200), limits))
        val garbage = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4) + ByteArray(64) { 0x7F }
        assertEquals(ArchiveResult.NotAnArchive, archives.read(garbage, limits))
        assertEquals(
            PackResult.Unreadable(listOf("pack.zip: isn't a zip file")),
            ContentPacks.read("pack.zip", whole.copyOf(200), archives),
        )
    }

    @Test
    fun `a zip cut off between two entries is refused, not read as a shorter one`() {
        val first = "exercises/plank.md" to md.toByteArray()
        val whole = zip(first, "images/plank.png" to ByteArray(300) { (it * 7).toByte() }, level = 0)
        assertEquals(2, assertIs<ArchiveResult.Entries>(archives.read(whole, limits)).entries.size)
        // Up to where the second entry starts: every byte of the first entry is there.
        val second =
            (4 until whole.size - 3).first {
                whole[it] == 'P'.code.toByte() && whole[it + 1] == 'K'.code.toByte() &&
                    whole[it + 2].toInt() == 3
            }
        assertEquals(ArchiveResult.NotAnArchive, archives.read(whole.copyOf(second), limits))
        // With the end record of a one-entry zip stuck on, the count still gives it away.
        val tail = zip(first).let { it.copyOfRange(it.size - 22, it.size) }
        assertEquals(ArchiveResult.NotAnArchive, archives.read(whole.copyOf(whole.size - 22) + tail.also { it[10] = 5 }, limits))
        assertEquals(
            PackResult.Unreadable(listOf("pack.zip: isn't a zip file")),
            ContentPacks.read("pack.zip", whole.copyOf(second), archives),
        )
    }
}
