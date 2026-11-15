// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.content

import app.fit.content.ArchiveEntry
import app.fit.content.ArchiveLimits
import app.fit.content.ArchiveResult
import app.fit.content.Archives
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// java.util.zip, read as a stream: sizes are counted as they unpack, never taken from the headers.
class ZipArchives : Archives {
    override fun read(
        bytes: ByteArray,
        limits: ArchiveLimits,
    ): ArchiveResult {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return ArchiveResult.NotAnArchive
        // The stream reader stops quietly where a file was cut off, so the record that ends every
        // whole zip must be there, and must count what was read.
        val expected = entryCount(bytes) ?: return ArchiveResult.NotAnArchive
        var seen = 0
        val entries = mutableListOf<ArchiveEntry>()
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    seen++
                    if (entry.isDirectory) continue
                    if (entries.size >= limits.entries) return ArchiveResult.TooManyEntries
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        if (out.size() + read > limits.entryBytes) return ArchiveResult.TooLarge(entry.name)
                        if (total > limits.totalBytes) return ArchiveResult.TooLarge(null)
                        out.write(buffer, 0, read)
                    }
                    entries += ArchiveEntry(entry.name, out.toByteArray())
                }
            }
        } catch (_: IOException) {
            // ZipException is one: damaged or encrypted.
            return ArchiveResult.NotAnArchive
        } catch (_: IllegalArgumentException) {
            // An entry name that isn't UTF-8.
            return ArchiveResult.NotAnArchive
        }
        if (expected != ZIP64_COUNT && seen != expected) return ArchiveResult.NotAnArchive
        return ArchiveResult.Entries(entries)
    }

    // The entry count in the end-of-central-directory record, or null when the file has none.
    private fun entryCount(bytes: ByteArray): Int? {
        fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        val last = bytes.size - END_RECORD_SIZE
        for (at in last downTo maxOf(0, last - MAX_COMMENT)) {
            val isRecord =
                bytes[at] == 'P'.code.toByte() && bytes[at + 1] == 'K'.code.toByte() && bytes[at + 2].toInt() == 5 &&
                    bytes[at + 3].toInt() == 6
            if (isRecord && u16(at + 20) == last - at) return u16(at + 10)
        }
        return null
    }

    private companion object {
        const val END_RECORD_SIZE = 22
        const val MAX_COMMENT = 0xFFFF

        // A zip64 archive keeps its real count elsewhere.
        const val ZIP64_COUNT = 0xFFFF
    }

    override fun write(entries: List<ArchiveEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (entry in entries) {
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(entry.bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
