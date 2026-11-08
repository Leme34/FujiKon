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
    val monochrome: Boolean = false,
    /**
     * Optional composite retouch recipe (colour mixer, skin retouch, soft
     * focus). Left at its neutral default by the stock film looks so their
     * rendering is unchanged; the studio portrait look uses it to apply a full
     * one-tap retouch. Resolved by [EditAdjustments.withFilterLook].
     */
    val look: FilterLook = FilterLook(),
    /** One-line usage hint shown under the filter chips. */
    val tip: String = ""
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
    /**
     * 写真人像：按一组户外日光写真样张（白色蕾丝裙 / 木廊 / 绿植背景）量化
     * 调制的一键配方。样张的实测特征：亮度均值 0.53、对比 0.167、
     * 高光 >0.95 仅 0.01%（白裙不死白）、暗部 <0.05 为 0%（无死黑）、
     * 整体饱和度 0.156、肤色 H≈345–358° 且低饱和高明度、绿植 H≈140° 偏青。
     *
     * 注意：样张是「成片」，把「抬黑压白 + 低对比 + 褪色 + 微冷」原样套到
     * 未修原片上会叠成一层灰雾——黑位被抬虚、皮肤微对比被压平（1.6.20 的
     * 教训）。因此这里只保留美白与定向校色，影调改为「黑位扎实、保留对比」：
     * 不抬 blacks、不用 fade、contrast 微正、vibrance 微正、暗部用暖中性
     * 分离色调；质感侧用轻磨皮 + 强 contour 回补 + 正向 texture，柔焦只留
     * 极弱的负 clarity。目标是「白皙但通透、有质感、不发灰」，而非低对比胶片感。
     */
    PORTRAIT_STUDIO(
        id = "portrait_studio",
        displayName = "写真",
        group = "人像",
        profile = FilterProfile(
            // 影调：黑位扎实、保留对比与层次；只轻抬暗部、压住高光不死白，绝不 fade/抬黑
            exposure = 0.01f,
            contrast = 0.04f,
            highlights = -0.14f,
            shadows = 0.06f,
            whites = -0.05f,
            blacks = 0f,
            fade = 0f,
            // 近中性微暖 + 一丝洋红：肤色粉润，不发青灰
            temperature = 0.01f,
            tint = 0.015f,
            // 不做全局降饱和；vibrance 微正只补低彩度像素，保住通透与气色
            saturation = 0f,
            vibrance = 0.05f,
            // 暗部暖中性、高光暖粉白，极弱分离色调只留空气感，不把面部阴影压成冷灰
            shadowTint = 0xFF6B5A54.toInt(),
            highlightTint = 0xFFFFF0E4.toInt(),
            splitStrength = 0.07f,
            look = FilterLook(
                hsl = HslAdjustments(
                    bands = listOf(
                        HslBand(hue = -3f, saturation = 2f, luminance = 9f),   // 红：白皙且保气色
                        HslBand(hue = -6f, saturation = 2f, luminance = 11f), // 橙：去蜡黄、白皙，不掉彩度
                        HslBand(saturation = -4f, luminance = 1f),            // 黄：轻压木质黄
                        HslBand(hue = 42f, saturation = -12f, luminance = 6f),// 绿：去黄偏青、轻降饱和
                        HslBand(luminance = 2f),                              // 青：通透，不降饱和
                        HslBand(),                                             // 蓝
                        HslBand(),                                             // 紫
                        HslBand(saturation = 1f, luminance = 1f)              // 洋红：保唇色
                    )
                ),
                // 轻磨皮 + 白皙 + 气色 + 强立体回补：保住毛孔与皮肤微纹理，不做塑料脸
                portrait = PortraitRetouch(
                    style = PortraitStyle.CUSTOM.id,
                    smooth = 18f,
                    whiten = 28f,
                    blush = 18f,
                    contour = 24f
                ),
                clarity = -2f,   // 只留一丝柔焦，不压局部对比
                texture = 8f,    // 正向微纹理，回补磨皮/柔化丢失的皮肤质感
                vignette = 2f
            ),
            tip = "参考户外写真样张调制：黑位扎实、保留对比与皮肤微纹理，只轻抬暗部、轻压高光留住白裙层次；肤色白皙粉润、绿植去黄偏青，配合轻磨皮与立体回补，不发灰不糊脸。强度越低越接近原图，适合日光户外人像。"
        )
    ),
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
