package com.fujikon.app.data

/**
 * A small, deliberately conservative set of film-inspired looks.
 *
 * Profiles use normalized values rather than display units so the renderer can
 * combine them with the user's manual adjustments without changing the UI
 * scales. [filterStrength] in EditAdjustments blends the whole profile.
 */
data class FilterProfile(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val fade: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val shadowTint: Int? = null,
    val highlightTint: Int? = null,
    val splitStrength: Float = 0f,
    val monochrome: Boolean = false
)

enum class CreativeFilter(
    val id: String,
    val displayName: String,
    val group: String,
    val profile: FilterProfile
) {
    NONE("none", "无", "", FilterProfile()),

    // -------------------------------------------------------------- landscape
    FUJI(
        id = "fuji",
        displayName = "富士",
        group = "风光",
        profile = FilterProfile(
            exposure = 0.04f,
            contrast = 0.16f,
            highlights = 0.04f,
            shadows = -0.04f,
            whites = 0.05f,
            blacks = -0.04f,
            temperature = 0.08f,
            tint = 0.015f,
            saturation = 0.10f,
            vibrance = 0.14f,
            shadowTint = 0xFF3E5B7A.toInt(),
            highlightTint = 0xFFFFD7A8.toInt(),
            splitStrength = 0.20f
        )
    ),
    RICOH(
        id = "ricoh",
        displayName = "理光",
        group = "风光",
        profile = FilterProfile(
            exposure = -0.02f,
            contrast = 0.24f,
            highlights = -0.08f,
            shadows = -0.10f,
            whites = -0.03f,
            blacks = -0.10f,
            fade = 0.02f,
            temperature = -0.04f,
            tint = 0.0f,
            saturation = 0.04f,
            vibrance = 0.10f,
            shadowTint = 0xFF3D5D78.toInt(),
            highlightTint = 0xFFFFE4C2.toInt(),
            splitStrength = 0.24f
        )
    ),
    LEICA(
        id = "leica",
        displayName = "徕卡",
        group = "风光",
        profile = FilterProfile(
            exposure = 0.0f,
            contrast = 0.20f,
            highlights = -0.06f,
            shadows = 0.04f,
            whites = 0.02f,
            blacks = -0.06f,
            fade = 0.04f,
            temperature = 0.045f,
            tint = 0.0f,
            saturation = -0.06f,
            vibrance = 0.13f,
            shadowTint = 0xFF315E66.toInt(),
            highlightTint = 0xFFFFD2A0.toInt(),
            splitStrength = 0.28f
        )
    ),

    // --------------------------------------------------------------- portraits
    PORTRAIT_SOFT(
        id = "portrait_soft",
        displayName = "柔光",
        group = "人像",
        profile = FilterProfile(
            exposure = 0.04f,
            contrast = -0.12f,
            highlights = -0.10f,
            shadows = 0.12f,
            whites = -0.02f,
            blacks = 0.04f,
            fade = 0.05f,
            temperature = 0.06f,
            tint = 0.025f,
            saturation = -0.04f,
            vibrance = 0.06f,
            shadowTint = 0xFF8D5B5B.toInt(),
            highlightTint = 0xFFFFD8C0.toInt(),
            splitStrength = 0.20f
        )
    ),
    PORTRAIT_CREAM(
        id = "portrait_cream",
        displayName = "奶油",
        group = "人像",
        profile = FilterProfile(
            exposure = 0.07f,
            contrast = -0.18f,
            highlights = -0.12f,
            shadows = 0.18f,
            whites = -0.04f,
            blacks = 0.08f,
            fade = 0.08f,
            temperature = 0.10f,
            tint = 0.015f,
            saturation = -0.12f,
            vibrance = 0.03f,
            shadowTint = 0xFF9B6B58.toInt(),
            highlightTint = 0xFFFFE5C7.toInt(),
            splitStrength = 0.26f
        )
    ),
    PORTRAIT_FILM(
        id = "portrait_film",
        displayName = "胶片",
        group = "人像",
        profile = FilterProfile(
            exposure = 0.02f,
            contrast = 0.08f,
            highlights = -0.06f,
            shadows = 0.06f,
            whites = 0.0f,
            blacks = 0.02f,
            fade = 0.06f,
            temperature = 0.03f,
            tint = -0.01f,
            saturation = -0.02f,
            vibrance = 0.07f,
            shadowTint = 0xFF4F6B4D.toInt(),
            highlightTint = 0xFFFFD3A0.toInt(),
            splitStrength = 0.30f
        )
    ),
    PORTRAIT_MONO(
        id = "portrait_mono",
        displayName = "黑白",
        group = "人像",
        profile = FilterProfile(
            exposure = 0.02f,
            contrast = 0.14f,
            highlights = -0.04f,
            shadows = 0.05f,
            whites = 0.02f,
            blacks = -0.04f,
            fade = 0.03f,
            saturation = 0f,
            vibrance = 0f,
            shadowTint = 0xFF4A5A6A.toInt(),
            highlightTint = 0xFFFFE2C2.toInt(),
            splitStrength = 0.22f,
            monochrome = true
        )
    );

    val isNone: Boolean get() = this == NONE

    companion object {
        val landscape: List<CreativeFilter> = entries.filter { it.group == "风光" }
        val portrait: List<CreativeFilter> = entries.filter { it.group == "人像" }

        fun fromId(id: String?): CreativeFilter = entries.firstOrNull { it.id == id } ?: NONE
    }
}
