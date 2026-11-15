// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

// The content check (docs/engineering.md §3): every file in content/ must pass, or the build fails.
class BuiltInContentTest {
    private val root = File(System.getProperty("content.dir"))

    @Test
    fun `every built-in content file is valid`() {
        val files = readContentFiles(root)
        assertTrue(files.isNotEmpty(), "no content files found in $root")
        val check = checkContent(files) { root.resolve(it).isFile }
        if (check.problems.isNotEmpty()) fail(check.problems.joinToString("\n", prefix = "Content check failed:\n") { it.describe() })
    }
}

// Every Markdown file under [root] except the folder's README, with paths relative to it.
fun readContentFiles(root: File): List<ContentFile> =
    root
        .walkTopDown()
        .filter { it.isFile && it.extension == "md" && it.relativeTo(root).path != "README.md" }
        .map { ContentFile(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
        .toList()
