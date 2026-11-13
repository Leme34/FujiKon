package com.fujikon.app.render

import android.content.Context
import android.net.Uri
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
        val saved = mutableListOf<Uri>()
        val failures = mutableListOf<String>()
        // 初始最长边上限：由可用堆反推，内存够尽量保原分辨率，不够才降。
        val maxLongest = BitmapLoader.maxExportLongest()
        photos.forEachIndexed { index, photo ->
            coroutineContext.ensureActive()
            onProgress(Progress(index, photos.size, photo.displayName, ""))
            val photoPreset = presetFor(photo.id, preset, presetsByPhoto)
            when (val r = exportOne(photo, photoPreset, options, maxLongest, finalImageUris, index, photos.size, onProgress)) {
                is OneResult.Saved -> saved.add(r.uri)
                is OneResult.Failed -> failures.add(r.message)
            }
            onProgress(Progress(index + 1, photos.size, photo.displayName, ""))
        }
        onProgress(Progress(photos.size, photos.size, "", ""))
        Result(saved, failures)
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
