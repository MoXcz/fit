// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

/** Reads and writes `.zip` files (a content pack, a backup). */
interface Archives {
    /**
     * The files in [bytes] with their names as written, directories left out. It stops unpacking at
     * the first limit passed, so a small file can't unpack into a huge one.
     */
    fun read(
        bytes: ByteArray,
        limits: ArchiveLimits,
    ): ArchiveResult

    fun write(entries: List<ArchiveEntry>): ByteArray
}

class ArchiveEntry(
    val name: String,
    val bytes: ByteArray,
)

data class ArchiveLimits(
    val entries: Int,
    // Unpacked sizes.
    val entryBytes: Long,
    val totalBytes: Long,
)

sealed interface ArchiveResult {
    class Entries(
        val entries: List<ArchiveEntry>,
    ) : ArchiveResult

    data object NotAnArchive : ArchiveResult

    data object TooManyEntries : ArchiveResult

    // [name] is the file that went over its own limit; null when the whole archive did.
    data class TooLarge(
        val name: String?,
    ) : ArchiveResult
}

// The first bytes of every zip file, an empty one included.
internal fun looksLikeZip(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes[0] == 'P'.code.toByte() &&
        bytes[1] == 'K'.code.toByte() &&
        ((bytes[2].toInt() == 3 && bytes[3].toInt() == 4) || (bytes[2].toInt() == 5 && bytes[3].toInt() == 6))

// Strict UTF-8, without a byte order mark; null when the bytes aren't UTF-8 text.
internal fun decodeUtf8(bytes: ByteArray): String? =
    try {
        bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("﻿")
    } catch (_: CharacterCodingException) {
        null
    }
