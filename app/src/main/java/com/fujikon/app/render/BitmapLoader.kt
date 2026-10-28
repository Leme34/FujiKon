package com.fujikon.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Decodes gallery images with EXIF rotation applied and sane memory limits. */
object BitmapLoader {

    suspend fun decodePreview(context: Context, uri: Uri, maxDimension: Int = 1800): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val orientation = readOrientation(context, uri)
                val bounds = readBounds(context, uri) ?: return@runCatching null
                var sample = 1
                while (max(bounds.first, bounds.second) / sample > maxDimension * 2) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: return@runCatching null
                val oriented = applyOrientation(decoded, orientation)
                val longest = max(oriented.width, oriented.height)
                val result = if (longest > maxDimension) {
                    val scale = maxDimension.toFloat() / longest
                    Bitmap.createScaledBitmap(
                        oriented,
                        (oriented.width * scale).roundToInt().coerceAtLeast(1),
                        (oriented.height * scale).roundToInt().coerceAtLeast(1),
                        true
                    ).also { if (it !== oriented) oriented.recycle() }
                } else oriented
                result
            }.getOrNull()
        }

    suspend fun decodeFull(context: Context, uri: Uri, maxPixels: Long = maxExportPixels()): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val orientation = readOrientation(context, uri)
                val bounds = readBounds(context, uri) ?: return@runCatching null
                var sample = 1
                while ((bounds.first.toLong() / sample) * (bounds.second.toLong() / sample) > maxPixels) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: return@runCatching null
                applyOrientation(decoded, orientation)
            }.getOrNull()
        }

    fun readOrientation(context: Context, uri: Uri): Int {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }

    private fun readBounds(context: Context, uri: Uri): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return options.outWidth to options.outHeight
    }

    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return source
        }
        return runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                .also { if (it !== source) source.recycle() }
        }.getOrDefault(source)
    }

    fun scaleForPreview(source: Bitmap, maxDimension: Int): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxDimension) return source
        val scale = maxDimension.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    fun thumbnailSize(width: Int, height: Int, target: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= 0) return target to target
        val scale = min(1f, target.toFloat() / longest)
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /** Keeps peak export memory within the app's heap while preserving resolution where possible. */
    private fun maxExportPixels(): Long =
        (Runtime.getRuntime().maxMemory() / 10L).coerceIn(12_000_000L, 36_000_000L)
}
