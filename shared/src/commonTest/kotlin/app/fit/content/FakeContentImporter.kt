// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import app.fit.backup.BackupCheck
import app.fit.backup.BackupExport
import app.fit.backup.Backups
import app.fit.backup.ReadBackup
import kotlin.time.Instant

// Previews by file name: [previews] holds what each name gives; anything else has a problem.
class FakeContentImporter(
    val previews: MutableMap<String, ImportPreview> = mutableMapOf(),
) : ContentImporter {
    val imported = mutableListOf<ImportPreview.Ready>()
    var result: ImportResult? = null

    override suspend fun preview(
        name: String,
        bytes: ByteArray,
    ): ImportPreview =
        previews[name] ?: ImportPreview.Invalid(name, listOf("$name:1: the file must start with a YAML header between --- lines"))

    override suspend fun import(preview: ImportPreview.Ready): ImportResult {
        result?.let { return it }
        imported += preview
        return ImportResult.Imported(preview.items.size)
    }

    companion object {
        fun ready(
            file: String,
            vararg items: ImportItem,
            newImages: List<String> = emptyList(),
            replacedImages: List<String> = emptyList(),
        ) = ImportPreview.Ready(file, items.toList(), newImages, replacedImages, ContentPack(emptyList(), emptyMap(), single = false))
    }
}

// A backup is its bytes as text: "backup <ISO instant>" is valid, "newer" is from a later version,
// "huge" is over the limits.
class FakeBackups : Backups {
    val calls = mutableListOf<String>()
    var restoreFits = true

    // What export gives instead of a file, when set.
    var exportFails: BackupExport? = null

    private class Read(
        override val exportedAt: Instant,
    ) : ReadBackup

    override suspend fun export(): BackupExport {
        calls += "export"
        return exportFails ?: BackupExport.File("backup 2026-11-09T10:00:00Z".encodeToByteArray())
    }

    override suspend fun check(bytes: ByteArray): BackupCheck {
        val text = bytes.decodeToString()
        return when {
            text.startsWith("backup ") -> BackupCheck.Valid(Read(Instant.parse(text.removePrefix("backup "))))
            text == "newer" -> BackupCheck.Newer
            text == "huge" -> BackupCheck.TooLarge
            else -> BackupCheck.Invalid
        }
    }

    override suspend fun restore(backup: ReadBackup): Boolean {
        if (!restoreFits) return false
        calls += "restore ${backup.exportedAt}"
        return true
    }

    override suspend fun wipeHistory() {
        calls += "wipe history"
    }

    override suspend fun wipeEverything() {
        calls += "wipe everything"
    }
}
