package com.fujikon.app.render

import android.content.Context
import android.net.Uri
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** Renders and saves a batch of photos, each with its own resulting preset. */
class ExportManager(
    private val context: Context,
    private val renderer: ImageRenderer,
    private val settings: AppSettings
) {

    data class Options(
        val format: String = "JPEG",
        val album: String = "FujiKon",
        val preserveExif: Boolean = true
    )

    data class Progress(
        val done: Int,
        val total: Int,
        val currentName: String,
        /** 单张内的阶段：""（总览）/ "解码" / "渲染" / "写入相册"，用于让用户看到进度在动。 */
        val stage: String = ""
    )

    data class Result(
        val saved: List<Uri>,
        val failures: List<String>
    )

    /** 批量导出的并发度与单张解码上限（内存安全前提下算得）。 */
    private data class Parallelism(val permits: Int, val perLongest: Int)

    suspend fun export(
        photos: List<PhotoItem>,
        /** Fallback for photos without an entry in [presetsByPhoto]. */
        preset: Preset,
        options: Options,
        /**
         * Final images produced by one-tap AI retouching. When a photo has an
         * entry here the local renderer is skipped entirely: the AI output is
         * already the finished photo and must not have the preset applied again.
         */
        finalImageUris: Map<Long, Uri> = emptyMap(),
        /**
         * Per-photo grades produced by individual editing. Batch apply only
         * happens once, when the gallery's "下一步" seeds every selected photo
         * with the chosen preset; afterwards each photo is edited on its own,
         * so exporting must use each photo's own look instead of one shared
         * preset. Empty means the legacy single-preset behaviour.
         */
        presetsByPhoto: Map<Long, Preset> = emptyMap(),
        onProgress: (Progress) -> Unit
    ): Result = withContext(Dispatchers.Default) {
        // 1.6.68: 批量导出由「逐张串行」改为「受内存约束的有界并发」。
        // 旧实现一张一张走完 解码→渲染→JPEG100 编码→写相册，多核完全闲置，
        // 十几张大图要累加十几张的单张耗时。
        //
        // 关键约束：单张全分辨率导出峰值约占可用堆的 55%（见
        // BitmapLoader.maxExportPixels），直接并发多张必然 OOM。这里按
        // 「并发数 P × 单张像素 ≈ 原单张像素」把单张解码最长边下调 √P，
        // 使总内存基本恒定，却让多张的单线程阶段（JPEG 解码 / quality100
        // 编码 / 相册写入）在多个核心上重叠。render 内部本就多核并行，
        // 外层并发的主要收益来自这些原本串行的单线程阶段。
        //
        // 每张仍独立走 [exportOne] 的 OOM 逐级降级重试，因此并发不会牺牲
        // 成功率；信号量限制在途张数，进度用原子计数只增不减。
        val parallel = parallelism()
        val saved = Collections.synchronizedList(mutableListOf<Uri>())
        val failures = Collections.synchronizedList(mutableListOf<String>())
        val doneCount = AtomicInteger(0)
        val gate = Semaphore(parallel.permits)

        onProgress(Progress(0, photos.size, "", ""))
        coroutineScope {
            photos.map { photo ->
                async {
                    gate.withPermit {
                        coroutineContext.ensureActive()
                        val inFlight = doneCount.get()
                        onProgress(Progress(inFlight, photos.size, photo.displayName, ""))
                        val photoPreset = presetFor(photo.id, preset, presetsByPhoto)
                        val result = exportOne(
                            photo, photoPreset, options, parallel.perLongest, finalImageUris,
                            inFlight, photos.size, onProgress
                        )
                        when (result) {
                            is OneResult.Saved -> saved.add(result.uri)
                            is OneResult.Failed -> failures.add(result.message)
                        }
                        val finished = doneCount.incrementAndGet()
                        onProgress(Progress(finished, photos.size, photo.displayName, ""))
                    }
                }
            }.awaitAll()
        }
        onProgress(Progress(photos.size, photos.size, "", ""))
        Result(saved, failures)
    }

    /**
     * 计算批量导出的 (并发度, 单张解码最长边上限)。
     *
     * - 并发度上限 4：render 内部已最多 8 协程并行，外层再高并发只会争抢
     *   核心、加剧 GC 抖动，收益递减；单/双核退化为串行或低并发。
     * - 单张上限按 √并发 下调，使 P × perLongest² ≈ singleLongest²，
     *   总像素（也即峰值内存）与串行单张基本相当，不会因并发而 OOM。
     * - perLongest 下限 1200：低于此时画质下降明显，宁可回退并发度。
     */
    private fun parallelism(): Parallelism {
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val single = BitmapLoader.maxExportLongest()
        var permits = when {
            cpus >= 8 -> 4
            cpus >= 4 -> 3
            cpus == 3 -> 2
            else -> 1
        }
        while (permits > 1 && single / kotlin.math.sqrt(permits.toDouble()) < 1200) {
            permits--
        }
        val perLongest = if (permits <= 1) {
            single
        } else {
            (single / kotlin.math.sqrt(permits.toDouble())).toInt().coerceAtLeast(1200)
        }
        return Parallelism(permits, perLongest)
    }

    private sealed interface OneResult {
        data class Saved(val uri: Uri) : OneResult
        data class Failed(val message: String) : OneResult
    }

    /**
     * 导出单张照片，并在内存不足(OOM)时按最长边逐级降级重试，保证「不失败」。
     * 非 OOM 错误（解码失败 / 编码失败）直接记为失败、不重试。
     */
    private suspend fun exportOne(
        photo: PhotoItem,
        preset: Preset,
        options: Options,
        maxLongest: Int,
        finalImageUris: Map<Long, Uri>,
        index: Int,
        total: Int,
        onProgress: (Progress) -> Unit
    ): OneResult {
        val finalUri = finalImageUris[photo.id]
        if (finalUri != null) {
            // AI 精修成片通常较小，直接走原路径，不做降级（降级反而损质）。
            var source: android.graphics.Bitmap? = null
            var rendered: android.graphics.Bitmap? = null
            return try {
                onProgress(Progress(index, total, photo.displayName, "解码"))
                rendered = BitmapLoader.decodeFull(context, finalUri)
                    ?: throw IllegalStateException("无法读取 AI 修图结果")
                onProgress(Progress(index, total, photo.displayName, "写入相册"))
                val uri = GallerySaver.save(
                    context = context, bitmap = rendered, format = options.format,
                    album = options.album, baseName = photo.displayName,
                    presetName = preset.name, sourceUri = photo.uri,
                    dateTaken = if (options.preserveExif) ExifWriter.readDateTaken(context, photo.uri) else null,
                    preserveExif = options.preserveExif
                )
                OneResult.Saved(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OneResult.Failed("${photo.displayName}：${e.message ?: "导出失败"}")
            } finally {
                if (rendered != null && rendered !== source) rendered.recycle()
                source?.recycle()
            }
        }

        // 普通照片：最长边 1.0 → 0.7 → 0.5 → 0.36 逐级降级。每次降级前释放上一份
        // 位图并触发 GC，避免连续 OOM。内存允许时仍保全分辨率（maxLongest 已是安全上限）。
        val steps = listOf(
            maxLongest,
            (maxLongest * 0.7f).toInt(),
            (maxLongest * 0.5f).toInt(),
            (maxLongest * 0.36f).toInt()
        )
        var lastErr: String? = null
        for (longest in steps) {
            var source: android.graphics.Bitmap? = null
            var rendered: android.graphics.Bitmap? = null
            try {
                onProgress(Progress(index, total, photo.displayName, "解码"))
                source = BitmapLoader.decodeFullLimited(context, photo.uri, longest)
                    ?: throw IllegalStateException("无法读取照片")
                onProgress(Progress(index, total, photo.displayName, "渲染"))
                rendered = renderer.render(source, preset, forExport = true)
                onProgress(Progress(index, total, photo.displayName, "写入相册"))
                val uri = GallerySaver.save(
                    context = context, bitmap = rendered, format = options.format,
                    album = options.album, baseName = photo.displayName,
                    presetName = preset.name, sourceUri = photo.uri,
                    dateTaken = if (options.preserveExif) ExifWriter.readDateTaken(context, photo.uri) else null,
                    preserveExif = options.preserveExif
                )
                return OneResult.Saved(uri)
            } catch (e: OutOfMemoryError) {
                lastErr = "${photo.displayName}：内存不足，已自动降级重试"
                rendered?.recycle()
                source?.recycle()
                System.gc()
                yield()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErr = "${photo.displayName}：${e.message ?: "导出失败"}"
                break
            } finally {
                if (rendered != null && rendered !== source) rendered.recycle()
                source?.recycle()
            }
        }
        return OneResult.Failed(lastErr ?: "${photo.displayName}：导出失败")
    }

    fun defaultOptions(): Options = Options(
        format = settings.exportFormat,
        album = settings.albumName,
        preserveExif = settings.preserveExif
    )

    companion object {
        /**
         * Resolves the look used for ONE photo in a batch. [preset] is only a
         * fallback: after individual editing every photo must export with its
         * own grade, never with a single look shared by the whole selection.
         */
        fun presetFor(photoId: Long, fallback: Preset, presetsByPhoto: Map<Long, Preset>): Preset =
            presetsByPhoto[photoId] ?: fallback
    }
}
