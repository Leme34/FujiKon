package com.fujikon.app.data

import android.net.Uri

data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateTaken: Long,
    val dateAdded: Long,
    val bucketId: String,
    val bucketName: String,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val size: Long
) {
    val isProbablyRaw: Boolean
        get() = mimeType.contains("raw", true) || displayName.endsWith(".nef", true) ||
            displayName.endsWith(".nrw", true) || displayName.endsWith(".cr2", true) ||
            displayName.endsWith(".arw", true) || displayName.endsWith(".dng", true)
}

data class Album(
    val id: String,
    val name: String,
    val count: Int
)
