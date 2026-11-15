// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.content

import android.graphics.BitmapFactory
import app.fit.content.ImageProbe

// Decodes as the screens will (BitmapFactory), but sampled down, so checking a large picture
// doesn't need the memory showing it would.
class BitmapImageProbe : ImageProbe {
    override fun readable(bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || longest > MAX_SIDE) return false
        val sampled = BitmapFactory.Options().apply { inSampleSize = (longest / 256).coerceAtLeast(1) }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampled) ?: return false
        bitmap.recycle()
        return true
    }

    private companion object {
        // Wider or taller than this is no picture for a phone screen, and too much to hold decoded.
        const val MAX_SIDE = 4096
    }
}
