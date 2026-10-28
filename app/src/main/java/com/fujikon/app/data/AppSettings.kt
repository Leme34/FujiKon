package com.fujikon.app.data

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("fujikon_settings", Context.MODE_PRIVATE)

    var albumName: String
        get() = prefs.getString("album_name", "FujiKon")?.takeIf { it.isNotBlank() } ?: "FujiKon"
        set(value) {
            prefs.edit().putString("album_name", value.trim().ifBlank { "FujiKon" }).apply()
        }

    var preserveExif: Boolean
        get() = prefs.getBoolean("preserve_exif", true)
        set(value) {
            prefs.edit().putBoolean("preserve_exif", value).apply()
        }

    /** "JPEG" or "PNG". */
    var exportFormat: String
        get() = prefs.getString("export_format", "JPEG") ?: "JPEG"
        set(value) {
            prefs.edit().putString("export_format", value).apply()
        }

    var hasSeenCompareHint: Boolean
        get() = prefs.getBoolean("seen_compare_hint", false)
        set(value) {
            prefs.edit().putBoolean("seen_compare_hint", value).apply()
        }
}
