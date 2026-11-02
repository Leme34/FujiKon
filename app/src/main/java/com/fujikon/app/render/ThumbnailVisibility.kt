package com.fujikon.app.render

import android.graphics.Bitmap

private const val OPAQUE_BLACK = -0x1000000 // 0xFF000000

/**
 * A failed off-screen render can produce an all-zero bitmap. Sampling a 3x3
 * grid avoids treating a legitimately dark thumbnail as a failure while still
 * rejecting completely black/transparent output.
 */
fun Bitmap.hasVisiblePixels(): Boolean {
    if (isRecycled || width <= 0 || height <= 0) return false
    for (row in 0 until 3) {
        val y = ((row + 0.5f) * height / 3f).toInt().coerceIn(0, height - 1)
        for (col in 0 until 3) {
            val x = ((col + 0.5f) * width / 3f).toInt().coerceIn(0, width - 1)
            val pixel = getPixel(x, y)
            if (pixel != 0 && pixel != OPAQUE_BLACK) return true
        }
    }
    return false
}
