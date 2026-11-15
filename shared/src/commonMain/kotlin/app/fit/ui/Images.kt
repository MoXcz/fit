// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

// Null for bytes that aren't an image the platform can show: it isn't drawn. Each platform fails its
// own way (Android with a NullPointerException), so everything short of an Error counts.
@OptIn(ExperimentalResourceApi::class)
internal fun decodeImageOrNull(
    bytes: ByteArray,
    decode: (ByteArray) -> ImageBitmap = { it.decodeToImageBitmap() },
): ImageBitmap? =
    try {
        decode(bytes)
    } catch (_: Exception) {
        null
    }
