package com.fujikon.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.LruCache
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Renders small preset previews for the preset list and picker.
 *
 * The source is the photo currently open in the editor. Before the user has
 * picked a photo, a generated sample scene is used instead so the list still
 * shows what every preset looks like. Results are cached per photo + preset.
 */
class PresetThumbnailStore(
    context: Context,
    private val renderer: ImageRenderer
) {

    private val appContext = context.applicationContext

    private val cache = object : LruCache<String, Bitmap>(48) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val renderSlots = Semaphore(2)
    private val sourceMutex = Mutex()
    private val decodeMutex = Mutex()
    private var sourceKey: String? = null
    private var sourceBitmap: Bitmap? = null

    suspend fun thumbnail(
        photo: PhotoItem?,
        preset: Preset,
        targetSize: Int = 280
    ): Bitmap? {
        val size = targetSize.coerceIn(64, 512)
        val key = cacheKey(photo, preset, size)
        cache.get(key)?.let { cached ->
            if (cached.hasVisiblePixels()) return cached
            cache.remove(key)
        }

        val source = sourceFor(photo, size) ?: run {
            // Never leave the picker with an empty tile: fall back to the
            // built-in sample scene when the gallery photo cannot be decoded.
            val side = max(480, size * 2)
            withContext(Dispatchers.Default) { SamplePhoto.create(side, side) }
        }
        return renderInto(source, preset, size, key)
    }

    /**
     * Renders a preset over an explicit source bitmap, used by the nikonpc list
     * to draw presets on the site's own `cLutImage` preview photo.
     */
    suspend fun thumbnailFromSource(
        sourceKey: String,
        source: Bitmap,
        preset: Preset,
        targetSize: Int = 280
    ): Bitmap? {
        val size = targetSize.coerceIn(64, 512)
        val key = "$sourceKey|${visualSignature(preset)}|$size"
        cache.get(key)?.let { cached ->
            if (cached.hasVisiblePixels()) return cached
            cache.remove(key)
        }
        return renderInto(source, preset, size, key)
    }

    /** Cache hit for [thumbnailFromSource], without rendering. */
    fun cachedFromSource(sourceKey: String, preset: Preset, targetSize: Int = 280): Bitmap? {
        val key = "$sourceKey|${visualSignature(preset)}|${targetSize.coerceIn(64, 512)}"
        val cached = cache.get(key) ?: return null
        if (cached.hasVisiblePixels()) return cached
        cache.remove(key)
        return null
    }

    private suspend fun renderInto(
        source: Bitmap,
        preset: Preset,
        size: Int,
        key: String
    ): Bitmap? = renderSlots.withPermit {
        cache.get(key)?.let { cached ->
            if (cached.hasVisiblePixels()) return@withPermit cached
            cache.remove(key)
        }
        repeat(2) {
            try {
                val rendered = renderer.render(source, preset, forExport = false)
                val scaled = BitmapLoader.scaleForPreview(rendered, size)
                if (scaled !== rendered) rendered.recycle()
                if (!scaled.hasVisiblePixels()) {
                    scaled.recycle()
                    return@repeat
                }
                cache.put(key, scaled)
                return@withPermit scaled
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Retry once; a cancelled background render can leave a
                // partially-written bitmap behind.
            }
        }
        null
    }

    /** Cache hit without rendering, used to avoid flashing a placeholder. */
    fun cached(photo: PhotoItem?, preset: Preset, targetSize: Int = 280): Bitmap? {
        val key = cacheKey(photo, preset, targetSize.coerceIn(64, 512))
        val cached = cache.get(key) ?: return null
        if (cached.hasVisiblePixels()) return cached
        cache.remove(key)
        return null
    }

    private fun cacheKey(photo: PhotoItem?, preset: Preset, size: Int): String =
        "${sourceKey(photo)}|${visualSignature(preset)}|$size"

    /** Stable cache identity for the pixels a preset produces. */
    fun visualSignature(preset: Preset): String = buildString {
        append(preset.id)
        append('|').append(preset.format)
        append('|').append(preset.baseProfile)
        append('|').append(preset.curve?.contentHashCode() ?: 0)
        append('|').append(
            preset.toneCurve?.normalizedPoints?.joinToString(",") { "${it.x}:${it.y}" } ?: ""
        )
        // Per-channel curves change pixels too, so they must invalidate the
        // cached thumbnail; otherwise editing red/green/blue looks like a no-op.
        append('|').append(
            if (preset.channelCurves.isNeutral) "" else {
                listOf(
                    preset.channelCurves.red,
                    preset.channelCurves.green,
                    preset.channelCurves.blue
                ).joinToString(";") { curve ->
                    curve.normalizedPoints.joinToString(",") { "${it.x}:${it.y}" }
                }
            }
        )
        append('|').append(
            runCatching { preset.adjustments.toJson().toString() }
                .getOrElse { preset.adjustments.toString() }
        )
        append('|').append(preset.sharpening)
        append('|').append(preset.clarity)
        append('|').append(preset.midRangeSharpening)
        append('|').append(preset.contrast)
        append('|').append(preset.brightness)
        append('|').append(preset.saturation)
        append('|').append(preset.hue)
        append('|').append(preset.highlights)
        append('|').append(preset.shadows)
        append('|').append(preset.whites)
        append('|').append(preset.blacks)
        append('|').append(preset.filter)
        append('|').append(preset.toning)
        append('|').append(preset.toningStrength)
        append('|').append(preset.colorBlender.joinToString(",") { "${it.hue}:${it.chroma}:${it.brightness}" })
        append('|').append(preset.colorGrading)
    }

    private fun sourceKey(photo: PhotoItem?): String =
        photo?.let { "photo:${it.id}:${it.uri}" } ?: "sample"

    private suspend fun sourceFor(photo: PhotoItem?, targetSize: Int): Bitmap? {
        val key = sourceKey(photo)
        sourceMutex.withLock {
            val cached = sourceBitmap
            if (sourceKey == key && cached != null && !cached.isRecycled) return cached
        }

        // Single-flight decode: a preset list asks for the same photo dozens of
        // times at once. Decoding once avoids memory spikes that used to make
        // BitmapFactory return null and leave black tiles in the picker.
        return decodeMutex.withLock {
            sourceMutex.withLock {
                val cached = sourceBitmap
                if (sourceKey == key && cached != null && !cached.isRecycled) return cached
            }

            val decoded = withContext(Dispatchers.IO) {
                if (photo == null) {
                    SamplePhoto.create()
                } else {
                    BitmapLoader.decodePreview(
                        appContext,
                        photo.uri,
                        max(480, targetSize * 2)
                    )
                }
            }

            if (decoded == null) {
                null
            } else {
                sourceMutex.withLock {
                    if (sourceKey == key) {
                        sourceBitmap?.let { if (it !== decoded && !it.isRecycled) it.recycle() }
                    }
                    sourceKey = key
                    sourceBitmap = decoded
                }
                decoded
            }
        }
    }
}

/**
 * Copyright-free sample scene used before the user has selected a photo.
 * Built in code so the APK does not need to ship a photograph.
 */
object SamplePhoto {

    fun create(width: Int = 900, height: Int = 600): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()

        val sky = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h * 0.72f,
                intArrayOf(
                    Color.rgb(28, 48, 92),
                    Color.rgb(92, 118, 158),
                    Color.rgb(232, 168, 116),
                    Color.rgb(245, 202, 146)
                ),
                floatArrayOf(0f, 0.46f, 0.82f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, sky)

        val sun = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 236, 190) }
        canvas.drawCircle(w * 0.72f, h * 0.42f, h * 0.10f, sun)
        sun.color = Color.argb(48, 255, 238, 196)
        canvas.drawCircle(w * 0.72f, h * 0.42f, h * 0.18f, sun)

        val farMountain = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(74, 89, 112) }
        Path().apply {
            moveTo(0f, h * 0.68f)
            lineTo(w * 0.16f, h * 0.48f)
            lineTo(w * 0.30f, h * 0.62f)
            lineTo(w * 0.46f, h * 0.40f)
            lineTo(w * 0.62f, h * 0.61f)
            lineTo(w * 0.78f, h * 0.47f)
            lineTo(w, h * 0.64f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }.also { canvas.drawPath(it, farMountain) }

        val nearMountain = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 49, 63) }
        Path().apply {
            moveTo(0f, h * 0.80f)
            lineTo(w * 0.20f, h * 0.62f)
            lineTo(w * 0.42f, h * 0.78f)
            lineTo(w * 0.60f, h * 0.58f)
            lineTo(w * 0.84f, h * 0.76f)
            lineTo(w, h * 0.68f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }.also { canvas.drawPath(it, nearMountain) }

        val water = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, h * 0.78f, 0f, h,
                intArrayOf(Color.rgb(48, 61, 76), Color.rgb(19, 26, 34)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, h * 0.78f, w, h, water)

        val reflection = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(58, 255, 225, 170)
        }
        canvas.drawRect(w * 0.62f, h * 0.78f, w * 0.82f, h * 0.88f, reflection)

        return bitmap
    }
}
