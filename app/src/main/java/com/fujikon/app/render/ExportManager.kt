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

/** Renders and saves a batch of photos with one preset. */
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
        preset: Preset,
        options: Options,
        onProgress: (Progress) -> Unit
    ): Result = withContext(Dispatchers.Default) {
        val saved = mutableListOf<Uri>()
        val failures = mutableListOf<String>()
        photos.forEachIndexed { index, photo ->
            coroutineContext.ensureActive()
            onProgress(Progress(index, photos.size, photo.displayName))
            var source: android.graphics.Bitmap? = null
            var rendered: android.graphics.Bitmap? = null
            try {
                source = BitmapLoader.decodeFull(context, photo.uri)
                    ?: throw IllegalStateException("无法读取照片")
                rendered = renderer.render(source, preset, forExport = true)
                val uri = GallerySaver.save(
                    context = context,
                    bitmap = rendered,
                    format = options.format,
                    album = options.album,
                    baseName = photo.displayName,
                    presetName = preset.name,
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
}
