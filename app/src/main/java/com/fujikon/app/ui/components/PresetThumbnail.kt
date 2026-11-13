package com.fujikon.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import com.fujikon.app.render.PresetThumbnailStore
import com.fujikon.app.render.PresetWallpaper
import com.fujikon.app.render.hasVisiblePixels
import com.fujikon.app.ui.theme.CardHigh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Shows a preset applied to the current photo (or the built-in sample scene).
 * Falls back to [placeholder] until the off-main-thread render is ready.
 */
@Composable
fun PresetThumbnail(
    photo: PhotoItem? = null,
    preset: Preset,
    store: PresetThumbnailStore,
    modifier: Modifier = Modifier,
    targetSize: Int = 280,
    contentScale: ContentScale = ContentScale.Crop,
    /** Optional explicit source, e.g. nikonpc.com's preview photo. */
    sourceBitmap: Bitmap? = null,
    sourceKeyOverride: String? = null,
    /** 1.6.53：为 true 时固定用 assets 里的 4K 风景壁纸套预设，忽略 [photo]。 */
    wallpaper: Boolean = false,
    placeholder: @Composable () -> Unit = {}
) {
    val signature = remember(preset) { store.visualSignature(preset) }
    val sourceKey = sourceKeyOverride
        ?: if (wallpaper) PresetWallpaper.SOURCE_KEY else photo?.let { "${it.id}:${it.uri}" } ?: "sample"
    var bitmap by remember(signature, sourceKey, targetSize, sourceBitmap, wallpaper) {
        mutableStateOf(
            when {
                wallpaper -> store.cachedFromWallpaper(preset, targetSize)
                sourceBitmap != null -> store.cachedFromSource(sourceKey, preset, targetSize)
                else -> store.cached(photo, preset, targetSize)
            }
        )
    }

    LaunchedEffect(signature, sourceKey, targetSize, sourceBitmap, wallpaper) {
        if (bitmap != null) return@LaunchedEffect
        // A render can be cancelled while the sheet is scrolling, or fail once
        // while the photo is still being decoded. Retry a few times before
        // settling on the curve placeholder.
        repeat(3) { attempt ->
            val rendered = withContext(Dispatchers.Default) {
                when {
                    wallpaper -> store.thumbnailFromWallpaper(preset, targetSize)
                    sourceBitmap != null ->
                        store.thumbnailFromSource(sourceKey, sourceBitmap, preset, targetSize)
                    else -> store.thumbnail(photo, preset, targetSize)
                }
            }
            // Reject black tiles: a failed off-screen render can produce an
            // all-zero bitmap that would read as a black thumbnail.
            if (rendered != null && rendered.hasVisiblePixels()) {
                bitmap = rendered
                return@LaunchedEffect
            }
            delay(140L * (attempt + 1))
        }
    }

    val image = bitmap?.takeIf { it.hasVisiblePixels() }
    if (image != null) {
        // 把预览来源写进语义描述：既方便无障碍朗读，也让「重新选照片后
        // 预览图是否跟着换」可被自动化验证。
        val sourceLabel = when {
            wallpaper -> "4K 风景壁纸"
            photo != null -> photo.displayName
            sourceBitmap != null -> "在线样张"
            else -> "内置样张"
        }
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = "${preset.name} 预览 · $sourceLabel",
            contentScale = contentScale,
            modifier = modifier
        )
    } else {
        Box(modifier.background(CardHigh)) {
            Box(Modifier.fillMaxSize()) { placeholder() }
        }
    }
}
