package com.fujikon.app.render

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume

/** Writes a rendered bitmap into the shared photo gallery. */
object GallerySaver {

    suspend fun save(
        context: Context,
        bitmap: Bitmap,
        format: String,
        album: String,
        baseName: String,
        presetName: String,
        sourceUri: Uri?,
        dateTaken: Long?,
        preserveExif: Boolean
    ): Uri = withContext(Dispatchers.IO) {
        val isPng = format.equals("PNG", true)
        val extension = if (isPng) "png" else "jpg"
        val mime = if (isPng) "image/png" else "image/jpeg"
        val safeAlbum = sanitize(album.ifBlank { "FujiKon" })
        val safePreset = sanitize(presetName).take(24).ifBlank { "Preset" }
        val safeBase = sanitize(baseName.substringBeforeLast('.')).take(48).ifBlank { "IMG" }
        val displayName = "${safeBase}_${safePreset}.$extension"

        val temp = File.createTempFile("fujikon_", ".$extension", context.cacheDir)
        try {
            FileOutputStream(temp).use { output ->
                val compressed = bitmap.compress(
                    if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                    100,
                    output
                )
                if (!compressed) throw IOException("图片编码失败")
            }

            if (preserveExif && !isPng && sourceUri != null) {
                ExifWriter.copy(context, sourceUri, temp, bitmap.width, bitmap.height)
            }

            if (Build.VERSION.SDK_INT >= 29) {
                saveViaMediaStore(context, temp, displayName, mime, safeAlbum, dateTaken)
            } else {
                saveLegacy(context, temp, displayName, mime, safeAlbum)
            }
        } finally {
            temp.delete()
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        file: File,
        displayName: String,
        mime: String,
        album: String,
        dateTaken: Long?
    ): Uri {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$album")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_TAKEN, dateTaken ?: System.currentTimeMillis())
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("无法创建相册条目")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("无法写入相册")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun saveLegacy(
        context: Context,
        file: File,
        displayName: String,
        mime: String,
        album: String
    ): Uri {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val directory = File(pictures, album)
        if (!directory.exists() && !directory.mkdirs()) throw IOException("无法创建相册目录")
        var target = File(directory, displayName)
        if (target.exists()) {
            val stamp = ExifWriter.formatDate(System.currentTimeMillis())
            target = File(directory, "${target.nameWithoutExtension}_$stamp.${target.extension}")
        }
        file.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(mime)
            ) { _, uri ->
                if (continuation.isActive) {
                    continuation.resume(uri ?: Uri.fromFile(target))
                }
            }
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim()
}
