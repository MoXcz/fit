// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.content

import android.content.Context
import app.fit.content.BuiltInContent
import app.fit.content.ContentFile
import app.fit.core.AppDispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

private const val ROOT = "content"

// The repo's content/ folder (Markdown files and images/), copied into the APK's assets by the build (androidApp/build.gradle.kts).
class AssetBuiltInContent(
    private val context: Context,
    private val dispatchers: AppDispatchers,
) : BuiltInContent {
    override suspend fun files(): List<ContentFile> =
        withContext(dispatchers.io) {
            markdownPaths(ROOT).map { path ->
                ContentFile(
                    path.removePrefix("$ROOT/"),
                    context.assets
                        .open(path)
                        .bufferedReader()
                        .use { it.readText() },
                )
            }
        }

    override suspend fun imagePaths(): Set<String> =
        withContext(dispatchers.io) {
            context.assets
                .list("$ROOT/images")
                .orEmpty()
                .map { "images/$it" }
                .toSet()
        }

    override suspend fun image(path: String): ByteArray? =
        withContext(dispatchers.io) {
            try {
                context.assets.open("$ROOT/$path").use { it.readBytes() }
            } catch (_: IOException) {
                null
            }
        }

    // AssetManager.list gives the children of a folder and nothing for a file.
    private fun markdownPaths(folder: String): List<String> =
        context.assets.list(folder).orEmpty().flatMap { name ->
            val path = "$folder/$name"
            if (name.endsWith(".md")) listOf(path) else markdownPaths(path)
        }
}
