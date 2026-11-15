// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import app.fit.guided.FakeArchives
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContentPackTest {
    private val archives = FakeArchives()
    private val md = "---\nformat: 1\nkind: exercise\nid: plank\nname: Plank\n---\n"

    private fun zip(vararg entries: Pair<String, ByteArray>) = archives.write(entries.map { ArchiveEntry(it.first, it.second) })

    private fun read(
        vararg entries: Pair<String, String>,
        name: String = "pack.zip",
    ) = ContentPacks.read(name, FakeArchives.zip(*entries), archives)

    private fun problems(vararg entries: Pair<String, String>) = assertIs<PackResult.Unreadable>(read(*entries)).problems

    @Test
    fun `a pack has the layout of the content folder, with its images`() {
        val pack = assertIs<PackResult.Read>(read("exercises/plank.md" to md, "lessons/plank.md" to md, "images/plank.webp" to "img")).pack
        assertEquals(listOf("exercises/plank.md", "lessons/plank.md"), pack.files.map { it.path })
        assertEquals(md, pack.files.first().text)
        assertContentEquals("img".encodeToByteArray(), pack.images.getValue("images/plank.webp"))
        assertFalse(pack.single)
    }

    @Test
    fun `one folder around the pack is taken off, and a README, hidden files and macOS extras are skipped`() {
        val pack =
            assertIs<PackResult.Read>(
                read(
                    "my-pack/exercises/plank.md" to md,
                    "my-pack/images/plank.png" to "img",
                    "my-pack/README.md" to "About this pack",
                    "my-pack/.DS_Store" to "x",
                    "__MACOSX/my-pack/exercises/._plank.md" to "x",
                ),
            ).pack
        assertEquals(listOf("exercises/plank.md"), pack.files.map { it.path })
        assertEquals(setOf("images/plank.png"), pack.images.keys)
    }

    @Test
    fun `a file anywhere else in a pack is a problem that names it`() {
        assertEquals(
            listOf(
                "notes.md: isn't part of a pack (a pack holds exercises/, routines/, programs/, recovery/ and lessons/ with .md files, and images/)",
            ),
            problems("exercises/plank.md" to md, "notes.md" to "x"),
        )
        assertTrue(problems("exercises/deep/plank.md" to md).single().startsWith("exercises/deep/plank.md: isn't part of a pack"))
        assertTrue(problems("a/b/exercises/plank.md" to md).single().startsWith("a/b/exercises/plank.md: isn't part of a pack"))
        assertTrue(problems("exercises/plank.txt" to md).single().startsWith("exercises/plank.txt: isn't part of a pack"))
        assertTrue(problems("images/Plank.GIF" to "x").single().startsWith("images/Plank.GIF: an image is images/<name>.webp"))
        assertTrue(problems("images/sub/plank.png" to "x").single().startsWith("images/sub/plank.png: an image is"))
    }

    @Test
    fun `an entry that could leave the pack is refused`() {
        listOf(
            "../exercises/plank.md",
            "exercises/../../plank.md",
            "/exercises/plank.md",
            "C:\\exercises\\plank.md",
            "exercises//plank.md",
            "./exercises/plank.md",
            "exercises/pl\u0000ank.md",
        ).forEach { name ->
            val found = problems("exercises/ok.md" to md, name to md)
            assertEquals(1, found.size, name)
            assertTrue(found.single().endsWith(": isn't a path inside the pack"), name)
        }
    }

    @Test
    fun `text that isn't UTF-8, a file given twice, and a file that is too large are problems`() {
        val latin1 = byteArrayOf('n'.code.toByte(), 0xE9.toByte(), 0x20)
        val bad = ContentPacks.read("pack.zip", zip("exercises/plank.md" to latin1), archives)
        assertEquals(PackResult.Unreadable(listOf("exercises/plank.md: isn't UTF-8 text")), bad)
        assertEquals(listOf("exercises/plank.md: is in the pack twice"), problems("exercises/plank.md" to md, "exercises/plank.md" to md))
        val large = zip("exercises/plank.md" to ByteArray(ContentPacks.MAX_TEXT_BYTES + 1) { 'a'.code.toByte() })
        assertEquals(PackResult.Unreadable(listOf("exercises/plank.md: is too large")), ContentPacks.read("pack.zip", large, archives))
    }

    @Test
    fun `a huge image, a huge pack and one with too many files are refused`() {
        val image = zip("images/big.png" to ByteArray(ContentPacks.LIMITS.entryBytes.toInt() + 1))
        assertEquals(PackResult.Unreadable(listOf("images/big.png: is too large")), ContentPacks.read("pack.zip", image, archives))
        val part = ByteArray(ContentPacks.LIMITS.entryBytes.toInt())
        val many = zip(*(0..4).map { "images/p$it.png" to part }.toTypedArray())
        assertEquals(PackResult.Unreadable(listOf("pack.zip: is too large")), ContentPacks.read("pack.zip", many, archives))
        val crowd = zip(*(0..ContentPacks.LIMITS.entries).map { "exercises/e$it.md" to ByteArray(0) }.toTypedArray())
        assertEquals(PackResult.Unreadable(listOf("pack.zip: holds more than 2000 files")), ContentPacks.read("pack.zip", crowd, archives))
    }

    @Test
    fun `a zip in name only, and an empty pack, say so`() {
        assertEquals(
            PackResult.Unreadable(listOf("pack.zip: isn't a zip file")),
            ContentPacks.read("pack.zip", "hello".encodeToByteArray(), archives),
        )
        assertTrue(problems("README.md" to "x").single().startsWith("pack.zip: holds nothing to import"))
    }

    @Test
    fun `a file that isn't a zip is one Markdown file, whatever its name, without a byte order mark`() {
        val pack = assertIs<PackResult.Read>(ContentPacks.read("My plank.md", ("\uFEFF" + md).encodeToByteArray(), archives)).pack
        assertTrue(pack.single)
        assertEquals(listOf(ContentFile("My plank.md", md)), pack.files)
        // A zip is known by its first bytes, so one shared without its name still reads as a pack.
        assertFalse(assertIs<PackResult.Read>(read("exercises/plank.md" to md, name = "document")).pack.single)
        val binary = ContentPacks.read("photo.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), archives)
        assertEquals(
            PackResult.Unreadable(listOf("photo.jpg: isn't UTF-8 text (a .md file or a .zip pack can be imported)")),
            binary,
        )
    }
}
