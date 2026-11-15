// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.core

/** The system's file picker and "save as" dialog; no storage permission is involved. */
interface Documents {
    /** Lets the user pick one file and reads it. */
    suspend fun open(): OpenedDocument

    /** Lets the user choose where to save [bytes], suggesting [name]. A file that couldn't be written whole is removed. */
    suspend fun save(
        name: String,
        bytes: ByteArray,
    ): SaveResult
}

enum class SaveResult { Saved, Cancelled, Failed }

sealed interface OpenedDocument {
    class File(
        val name: String,
        val bytes: ByteArray,
    ) : OpenedDocument

    data object Cancelled : OpenedDocument

    data object Unreadable : OpenedDocument

    // Larger than anything the app takes; it wasn't read.
    data object TooLarge : OpenedDocument
}
