// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

// What a picked or shared file holds: content files and the images that came with them.
class ContentPack(
    val files: List<ContentFile>,
    // By the path content files name: `images/<name>.webp`.
    val images: Map<String, ByteArray>,
    // One Markdown file on its own: its kind comes from its header, not from a folder.
    val single: Boolean,
)

sealed interface PackResult {
    class Read(
        val pack: ContentPack,
    ) : PackResult

    // Each problem as a line to show: "file: what's wrong".
    data class Unreadable(
        val problems: List<String>,
    ) : PackResult
}

object ContentPacks {
    // What a phone holds in memory while a pack is read: the file (32 MB at most) and what it unpacks to.
    val LIMITS = ArchiveLimits(entries = 2_000, entryBytes = 8L * 1024 * 1024, totalBytes = 32L * 1024 * 1024)
    const val MAX_TEXT_BYTES = 1024 * 1024

    private val FOLDERS = setOf("exercises", "routines", "programs", "recovery", "lessons")
    private const val IMAGES = "images"
    private const val LAYOUT = "a pack holds exercises/, routines/, programs/, recovery/ and lessons/ with .md files, and images/"

    // A `.md` file, or a `.zip` pack laid out like `content/` at its top or inside one folder. Anything
    // else in a pack is a problem, apart from a README.md, hidden files and `__MACOSX`, which are skipped.
    fun read(
        name: String,
        bytes: ByteArray,
        archives: Archives,
    ): PackResult {
        if (!looksLikeZip(bytes) && !name.endsWith(".zip", ignoreCase = true)) return single(name, bytes)
        val entries =
            when (val result = archives.read(bytes, LIMITS)) {
                is ArchiveResult.Entries -> result.entries
                ArchiveResult.NotAnArchive -> return unreadable("$name: isn't a zip file")
                ArchiveResult.TooManyEntries -> return unreadable("$name: holds more than ${LIMITS.entries} files")
                is ArchiveResult.TooLarge -> return unreadable("${result.name ?: name}: is too large")
            }
        val problems = mutableListOf<String>()
        val kept = mutableListOf<Pair<List<String>, ByteArray>>()
        for (entry in entries) {
            val segments = segments(entry.name)
            when {
                segments == null -> problems += "${printable(entry.name)}: isn't a path inside the pack"
                segments.any { it.startsWith(".") } || segments.first() == "__MACOSX" -> Unit
                else -> kept += segments to entry.bytes
            }
        }
        // One wrapping folder (the zip of a folder) is taken off.
        val first = kept.map { it.first.first() }.distinct().singleOrNull()
        val wrapped = first != null && first !in FOLDERS && first != IMAGES && kept.all { it.first.size > 1 }
        val files = mutableListOf<ContentFile>()
        val images = mutableMapOf<String, ByteArray>()
        for ((full, data) in kept) {
            val path = (if (wrapped) full.drop(1) else full).joinToString("/")
            val parts = path.split('/')
            when {
                parts.size == 1 && path.equals("README.md", ignoreCase = true) -> {}

                parts.size == 2 && parts[0] in FOLDERS && parts[1].endsWith(".md") -> {
                    val text = if (data.size > MAX_TEXT_BYTES) null else decodeUtf8(data)
                    when {
                        data.size > MAX_TEXT_BYTES -> problems += "$path: is too large"
                        text == null -> problems += "$path: isn't UTF-8 text"
                        files.any { it.path == path } -> problems += "$path: is in the pack twice"
                        else -> files += ContentFile(path, text)
                    }
                }

                parts.size == 2 && parts[0] == IMAGES && isImagePath(path) -> {
                    if (path in images) problems += "$path: is in the pack twice" else images[path] = data
                }

                parts[0] == IMAGES -> {
                    problems +=
                        "${full.joinToString(
                            "/",
                        )}: an image is images/<name>.webp, .png or .jpg, in lowercase letters, digits, dots and dashes"
                }

                else -> {
                    problems += "${full.joinToString("/")}: isn't part of a pack ($LAYOUT)"
                }
            }
        }
        if (problems.isEmpty() && files.isEmpty() && images.isEmpty()) problems += "$name: holds nothing to import ($LAYOUT)"
        return if (problems.isEmpty()) PackResult.Read(ContentPack(files, images, single = false)) else PackResult.Unreadable(problems)
    }

    private fun single(
        name: String,
        bytes: ByteArray,
    ): PackResult {
        if (bytes.size > MAX_TEXT_BYTES) return unreadable("$name: is too large")
        val text = decodeUtf8(bytes) ?: return unreadable("$name: isn't UTF-8 text (a .md file or a .zip pack can be imported)")
        return PackResult.Read(ContentPack(listOf(ContentFile(name, text)), emptyMap(), single = true))
    }

    private fun unreadable(problem: String) = PackResult.Unreadable(listOf(problem))

    // The entry's path as segments, or null for one that could leave the pack or isn't a plain
    // path: absolute, with a drive or a backslash, `..`, `.`, an empty segment or a control character.
    private fun segments(name: String): List<String>? {
        if (name.isEmpty() || name.startsWith("/") || name.any { it == '\\' || it == ':' || it.code < 0x20 }) return null
        val segments = name.split('/')
        return segments.takeIf { all -> all.none { it.isEmpty() || it == "." || it == ".." } }
    }

    private fun printable(name: String): String = name.map { if (it.code < 0x20) '?' else it }.joinToString("").take(120)
}
