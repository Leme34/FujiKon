package com.fujikon.app.data

import android.content.Context
import com.fujikon.app.agnes.AgnesImageService

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

    /**
     * Agnes API key for one-tap AI retouching. Stored locally in the app's own
     * SharedPreferences and only attached to requests the user explicitly starts.
     */
    var agnesApiKey: String
        get() = prefs.getString("agnes_api_key", "") ?: ""
        set(value) {
            prefs.edit().putString("agnes_api_key", value.trim()).apply()
        }

    val hasAgnesApiKey: Boolean get() = agnesApiKey.isNotBlank()

    /**
     * Which Agnes gateway the key belongs to. The official China endpoint is
     * the default; the international apihub gateway is kept as an explicit
     * alternative because tokens are not shared between the two.
     */
    var agnesRegion: String
        get() = prefs.getString("agnes_region", null)
            ?.takeIf { it == AgnesImageService.REGION_GLOBAL }
            ?: AgnesImageService.REGION_CN
        set(value) {
            prefs.edit().putString(
                "agnes_region",
                if (value == AgnesImageService.REGION_GLOBAL) {
                    AgnesImageService.REGION_GLOBAL
                } else {
                    AgnesImageService.REGION_CN
                }
            ).apply()
        }

    val agnesBaseUrl: String get() = AgnesImageService.baseUrlFor(agnesRegion)

    /** Last prompt used, offered as the starting point for the next AI edit. */
    var agnesPrompt: String
        get() = prefs.getString("agnes_prompt", "") ?: ""
        set(value) {
            prefs.edit().putString("agnes_prompt", value.trim().take(500)).apply()
        }
}
