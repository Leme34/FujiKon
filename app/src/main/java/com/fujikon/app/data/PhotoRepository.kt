package com.fujikon.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.fujikon.app.render.BitmapLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotoRepository(private val context: Context) {

    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasReadPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val full = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES)
            val selected = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            return full == PackageManager.PERMISSION_GRANTED || selected == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(context, requiredPermission()) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun loadPhotos(): List<PhotoItem> = withContext(Dispatchers.IO) {
        val uri = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE
        )
        val selection = if (Build.VERSION.SDK_INT >= 30) {
            "${MediaStore.Images.Media.IS_TRASHED} = 0"
        } else null
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val result = ArrayList<PhotoItem>(512)
        runCatching {
            context.contentResolver.query(uri, projection, selection, null, sort)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: "IMG_$id"
                    val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                    if (!mime.startsWith("image/")) continue
                    val width = cursor.getInt(widthCol)
                    val height = cursor.getInt(heightCol)
                    if (width <= 0 || height <= 0) continue
                    result.add(
                        PhotoItem(
                            id = id,
                            uri = ContentUris.withAppendedId(uri, id),
                            displayName = displayName,
                            dateTaken = cursor.getLong(takenCol),
                            dateAdded = cursor.getLong(addedCol) * 1000L,
                            bucketId = cursor.getString(bucketIdCol) ?: "0",
                            bucketName = cursor.getString(bucketCol) ?: "其他",
                            width = width,
                            height = height,
                            mimeType = mime,
                            size = cursor.getLong(sizeCol)
                        )
                    )
                }
            }
        }
        result
    }

    /**
     * 把系统图片选择器（ActivityResultContracts.GetMultipleContents）返回的
     * content URI 转成与相册列表同构的 [PhotoItem]，使用户「没选照片就点下一步
     * → 调起系统图库 → 选完返回」这条路径能被真正当成一批待处理照片导入编辑器。
     *
     * 系统 URI 可能来自其它 provider，没有 MediaStore 的 _ID，且授权是一次性的：
     *  - 尽量 takePersistableUriPermission，避免返回后再次读取时权限失效；
     *  - id 用负数（-(index+1)），与 MediaStore 的正数 _ID 永不冲突；
     *  - displayName/size 走 OpenableColumns，mime 走 getType，宽高走 BitmapLoader。
     */
    suspend fun photosFromUris(uris: List<Uri>): List<PhotoItem> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val now = System.currentTimeMillis()
        uris.mapIndexedNotNull { index, uri ->
            runCatching {
                // 尽力持久化读权限；非 persistable provider 会抛异常，忽略即可，
                // 当次结果仍带有临时读授权，足以完成本次渲染/导出。
                runCatching {
                    resolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                var displayName: String? = null
                var size = 0L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                            if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                        }
                    }
                val mime = resolver.getType(uri) ?: "image/jpeg"
                val bounds = BitmapLoader.readBounds(context, uri) ?: return@runCatching null
                val rotate = BitmapLoader.readOrientation(context, uri) ==
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 ||
                    BitmapLoader.readOrientation(context, uri) ==
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
                val (w, h) = if (rotate) bounds.second to bounds.first else bounds
                PhotoItem(
                    id = -(index + 1).toLong(),
                    uri = uri,
                    displayName = displayName ?: "IMG_${now}_${index + 1}",
                    dateTaken = now,
                    dateAdded = now,
                    bucketId = "picked",
                    bucketName = "已选择",
                    width = w,
                    height = h,
                    mimeType = mime,
                    size = size
                )
            }.getOrNull()
        }
    }

    fun albums(photos: List<PhotoItem>): List<Album> {
        val grouped = photos.groupBy { it.bucketId }
        return grouped.map { (id, items) ->
            Album(id = id, name = items.first().bucketName, count = items.size)
        }.sortedByDescending { it.count }
    }
}
