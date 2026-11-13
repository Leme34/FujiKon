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

    /**
     * 把最长边缩放到 [maxLongest] 像素后解码整图。
     * 用于大图导出失败时的逐级降级重试：相比按像素面积 [maxExportPixels]，
     * 这里直接约束最长边，缩放更直观，且同样不会重新读盘。
     */
    suspend fun decodeFullLimited(
        context: Context,
        uri: Uri,
        maxLongest: Int = maxExportLongest()
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val orientation = readOrientation(context, uri)
            val bounds = readBounds(context, uri) ?: return@runCatching null
            val longest = max(bounds.first, bounds.second)
            var sample = 1
            while (longest / sample > maxLongest) {
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

    /**
     * 全分辨率导出时的安全像素上限。
     *
     * 旧实现把 `maxMemory() / 10` 当成「像素数」使用：maxMemory() 是字节数
     * （largeHeap 设备可达 512MB），/10 得到约 53M，随后又被 clamp 到 36M 像素。
     * 而渲染管线同一时刻实际持有的全帧副本约为 16~20 字节/像素
     * （pixels 4B + 输出 Bitmap 4B + 解码临时 4B + 可选的降噪快照 / 原图副本 /
     * 亮度平面），所以 36M 像素 ≈ 600MB，远超 heap → 必然 OOM、进程被杀、
     * 弹窗消失。这里按「可用堆 × 余量系数 ÷ 峰值字节/像素」反推一个真实
     * 安全上限：内存够就尽量保留原分辨率，内存不够才降，绝不硬扛到 OOM。
     */
    fun maxExportPixels(): Long {
        val maxMem = Runtime.getRuntime().maxMemory().coerceAtLeast(64_000_000L)
        val peakBytesPerPixel = 18L // 含降噪快照 / 原图副本等可选副本的保守峰值
        val usable = (maxMem * 0.55).toLong() // 留 45% 给系统与其他对象 / 碎片
        return (usable / peakBytesPerPixel).coerceIn(8_000_000L, 24_000_000L)
    }

    /** 与 [maxExportPixels] 配套的最长边上限（按面积反推），用于 [decodeFullLimited]。 */
    fun maxExportLongest(): Int {
        val px = maxExportPixels()
        // 取正方形近似的最长边
        return kotlin.math.sqrt(px.toDouble()).toInt().coerceIn(2600, 4900)
    }
}
