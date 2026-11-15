// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.content

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.fit.android.setup.ForegroundActivity
import app.fit.core.AppDispatchers
import app.fit.core.Documents
import app.fit.core.OpenedDocument
import app.fit.core.SaveResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.WeakHashMap

// The Storage Access Framework's picker and "save as" dialog: the user picks the file or the place,
// so no storage permission is needed. Each activity registers its launchers when it's created
// ([attach]), which is what lets a result reach the activity recreated while the dialog was open
// (rotation, "Don't keep activities"); the waiting call lives here, outside any activity.
class AndroidDocuments(
    private val context: Context,
    private val foreground: ForegroundActivity,
    private val dispatchers: AppDispatchers,
) : Documents {
    private class Launchers(
        val open: ActivityResultLauncher<Array<String>>,
        val save: ActivityResultLauncher<String>,
    )

    // Main thread only.
    private val launchers = WeakHashMap<ComponentActivity, Launchers>()
    private var waiting: CompletableDeferred<Uri?>? = null
    private var waitingFor: ComponentActivity? = null

    // Call from onCreate, before the activity starts.
    fun attach(activity: ComponentActivity) {
        launchers[activity] =
            Launchers(
                open = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { waiting?.complete(it) },
                save =
                    activity.registerForActivityResult(
                        ActivityResultContracts.CreateDocument("application/zip"),
                    ) { waiting?.complete(it) },
            )
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event != Lifecycle.Event.ON_DESTROY) return@LifecycleEventObserver
                launchers.remove(activity)
                // Gone for good with its dialog still open: no result will come.
                if (activity.isFinishing && waitingFor === activity) waiting?.complete(null)
            },
        )
    }

    override suspend fun open(): OpenedDocument {
        // Any type: providers report .md files as text/markdown, text/plain or just "a file".
        val uri = pick { it.open.launch(arrayOf("*/*")) } ?: return OpenedDocument.Cancelled
        return read(uri)
    }

    override suspend fun save(
        name: String,
        bytes: ByteArray,
    ): SaveResult {
        val uri = pick { it.save.launch(name) } ?: return SaveResult.Cancelled
        return withContext(dispatchers.io) {
            val written =
                try {
                    // "wt": a file that's replaced is cut to the new length.
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
                } catch (_: IOException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            if (written) return@withContext SaveResult.Saved
            // The dialog already made the document: an empty or half-written one mustn't stay.
            try {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } catch (_: Exception) {
                // Not every provider can delete.
            }
            SaveResult.Failed
        }
    }

    // A file another app handed over (shared, or opened with Fit), read whole unless it's too large.
    suspend fun read(uri: Uri): OpenedDocument =
        withContext(dispatchers.io) {
            val name = displayName(uri)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (out.size() + read > MAX_BYTES) return@use OpenedDocument.TooLarge
                        out.write(buffer, 0, read)
                    }
                    OpenedDocument.File(name, out.toByteArray())
                } ?: OpenedDocument.Unreadable
            } catch (_: IOException) {
                OpenedDocument.Unreadable
            } catch (_: SecurityException) {
                OpenedDocument.Unreadable
            }
        }

    private fun displayName(uri: Uri): String {
        val fromProvider =
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (_: RuntimeException) {
                // A provider may refuse the query or not know the column.
                null
            }
        return fromProvider ?: uri.lastPathSegment ?: "file"
    }

    // Null when cancelled, when no activity is in front, or when the device has no documents app.
    private suspend fun pick(launch: (Launchers) -> Unit): Uri? =
        withContext(Dispatchers.Main.immediate) {
            val activity = foreground.activity ?: return@withContext null
            val mine = launchers[activity] ?: return@withContext null
            // One dialog at a time; an earlier call that's still waiting is over.
            waiting?.complete(null)
            val result = CompletableDeferred<Uri?>()
            waiting = result
            waitingFor = activity
            try {
                launch(mine)
                result.await()
            } catch (_: ActivityNotFoundException) {
                null
            } finally {
                if (waiting === result) {
                    waiting = null
                    waitingFor = null
                }
            }
        }

    private companion object {
        // A pack unpacks to 32 MB at most and a backup to 48 MB, so nothing the app takes is larger;
        // a file past this is refused unread instead of filling the phone's memory.
        const val MAX_BYTES = 32 * 1024 * 1024
    }
}
