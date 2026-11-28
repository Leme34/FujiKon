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

    /**
     * 预览解码（1.6.67 性能重写）。
     *
     * 旧实现有一次数量级性能事故：sample 公式用 `maxDimension * 2` 做阈值，
     * 对 3384×6016 这类竖拍大图（长边 6016）在 4096 上限下算出 sample=1，
     * 于是**全分辨率解码 2000 万像素**；随后 applyOrientation 做一次 2000 万
     * 像素整图旋转拷贝，createScaledBitmap 又做一次整图缩放拷贝。三次大分配
     * 在低内存真机上引发剧烈 GC 抖动，点一次预设预览要转很久。
     *
     * 新实现三点改进：
     *  1) sample 公式按「解码后最长边 >= maxDimension 的最小 2 的幂」反推，
     *     配合 inScaled/inTargetDensity 让解码器一步到位缩到目标附近，从源头
     *     避免全分辨率解码（配合下调后的预览上限，6016 竖拍走 sample=2）。
     *  2) 旋转与缩放合并为**一次** Matrix 变换，只分配一张目标位图，彻底
     *     消除中间的两份整图拷贝。
     *  3) 输出尺寸语义不变：最长边 <= maxDimension，EXIF 旋转生效。
     */
    suspend fun decodePreview(context: Context, uri: Uri, maxDimension: Int = 1800): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val orientation = readOrientation(context, uri)
                val bounds = readBounds(context, uri) ?: return@runCatching null
                // 长边不随 90° 旋转改变（只是宽高互换），故直接用解码坐标的长边。
                val decodedLongest = max(bounds.first, bounds.second)
                // 取「解码后最长边仍 >= maxDimension」的最小 2 的幂：既不过度
                // 解码，也保证后续是缩小而非放大（放大才会发糊）。
                var sample = 1
                while (decodedLongest / (sample * 2) >= maxDimension) sample *= 2

                // 1.6.70：上面公式对「长边在 1×~2× 上限之间」的常见手机原图
                // （3024/4032/4608，8~16MP）仍会算出 sample=1，从而把整张原图
                // 全分辨率解码成 ARGB（一张 12MP≈48MB），再叠加旋转拷贝 + 缩放
                // 拷贝，在低内存真机上引发剧烈 GC 抖动 —— 这就是「打开/套用转
                // 一分钟」的真正根因。这里让解码器用 inDensity/inTargetDensity
                // 在**同一次解码**里直接缩到目标尺寸，不再先分配全分辨率位图。
                val sampledLongest = decodedLongest / sample
                val densityBase = 1000
                // 仅当采样后仍超过上限才缩小；永不放大（放大只会发糊）。
                val targetDensity = if (sampledLongest > maxDimension) {
                    (densityBase.toFloat() * maxDimension / sampledLongest)
                        .roundToInt().coerceAtLeast(1)
                } else densityBase
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = true
                    inDensity = densityBase
                    inTargetDensity = targetDensity
                }
                val decoded = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: return@runCatching null
                // 密度缩放已让解码一步到位到目标附近；旋转仍走经过验证的两步法，
                // 避免 createBitmap(matrix) 在部分 Bitmap 实现上产生无效位图。
                // 下面的 createScaledBitmap 仅作兜底：个别解码器忽略 density 缩放时
                // 才需要真正缩放，绝大多数情况下 longest<=maxDimension 直接跳过。
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
     * 把最长边缩放到 [maxLongest] 像素后解码整图，用于导出（含 OOM 降级重试）。
     *
     * 1.6.71 画质修复：旧实现只用 2 的幂采样
     * `while (longest / sample > maxLongest) sample *= 2`，会把尺寸断崖式砍到
     * 「大于 maxLongest 的最小 2 的幂分之一」。例如 6016 长图、上限 4900 时
     * 算出 sample=2 → 实际只有 3008px（比上限少 39%），叠加旧并发再砍半后
     * 十几 MP 原图只剩约 1.5MP，导出仅几百 K。
     *
     * 新实现两步：先用 2 的幂采样保证「解码后仍 >= 上限」（不过度解码），
     * 再用 inDensity/inTargetDensity 在**同一次解码**里精确缩放到上限，
     * 使输出最长边尽可能贴近 [maxLongest]，画质最大化。永不放大。
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
            // 采样后最长边仍 >= 上限的最小 2 的幂；长图不会一次解到全分辨率。
            var sample = 1
            while (longest / (sample * 2) >= maxLongest) sample *= 2
            val sampledLongest = longest / sample
            val densityBase = 1000
            // 采样后仍超过上限时用密度精调到上限；否则保持原样（不放大）。
            val targetDensity = if (sampledLongest > maxLongest) {
                (densityBase.toFloat() * maxLongest / sampledLongest)
                    .roundToInt().coerceAtLeast(1)
            } else densityBase
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = true
                inDensity = densityBase
                inTargetDensity = targetDensity
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

    fun readBounds(context: Context, uri: Uri): Pair<Int, Int>? {
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
