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
import com.fujikon.app.ui.theme.CardHigh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows a preset applied to the current photo (or the built-in sample scene).
 * Falls back to [placeholder] until the off-main-thread render is ready.
 */
@Composable
fun PresetThumbnail(
    photo: PhotoItem?,
    preset: Preset,
    store: PresetThumbnailStore,
    modifier: Modifier = Modifier,
    targetSize: Int = 280,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit = {}
) {
    val signature = remember(preset) { store.visualSignature(preset) }
    val sourceKey = photo?.let { "${it.id}:${it.uri}" } ?: "sample"
    var bitmap by remember(signature, sourceKey, targetSize) {
        mutableStateOf(store.cached(photo, preset, targetSize))
    }

    LaunchedEffect(signature, sourceKey, targetSize) {
        if (bitmap == null) {
            bitmap = withContext(Dispatchers.Default) {
                store.thumbnail(photo, preset, targetSize)
            }
        }
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = "${preset.name} 预览",
            contentScale = contentScale,
            modifier = modifier
        )
    } else {
        Box(modifier.background(CardHigh)) {
            Box(Modifier.fillMaxSize()) { placeholder() }
        }
    }
}
