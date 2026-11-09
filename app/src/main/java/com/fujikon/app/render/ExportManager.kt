package com.fujikon.app.render

import android.content.Context
import android.net.Uri
import com.fujikon.app.data.AppSettings
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
        val currentName: String
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
        photos.forEachIndexed { index, photo ->
            coroutineContext.ensureActive()
            onProgress(Progress(index, photos.size, photo.displayName))
            val photoPreset = presetFor(photo.id, preset, presetsByPhoto)
            var source: android.graphics.Bitmap? = null
            var rendered: android.graphics.Bitmap? = null
            try {
                val finalUri = finalImageUris[photo.id]
                if (finalUri != null) {
                    rendered = BitmapLoader.decodeFull(context, finalUri)
                        ?: throw IllegalStateException("无法读取 AI 修图结果")
                } else {
                    source = BitmapLoader.decodeFull(context, photo.uri)
                        ?: throw IllegalStateException("无法读取照片")
                    rendered = renderer.render(source, photoPreset, forExport = true)
                }
                val uri = GallerySaver.save(
                    context = context,
                    bitmap = rendered,
                    format = options.format,
                    album = options.album,
                    baseName = photo.displayName,
                    presetName = photoPreset.name,
                    sourceUri = photo.uri,
                    dateTaken = if (options.preserveExif) ExifWriter.readDateTaken(context, photo.uri) else null,
                    preserveExif = options.preserveExif
                )
                saved.add(uri)
            } catch (e: OutOfMemoryError) {
                failures.add("${photo.displayName}：内存不足，已跳过")
                System.gc()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failures.add("${photo.displayName}：${e.message ?: "导出失败"}")
            } finally {
                if (rendered != null && rendered !== source) rendered.recycle()
                source?.recycle()
            }
        }
        onProgress(Progress(photos.size, photos.size, ""))
        Result(saved, failures)
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
