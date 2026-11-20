package com.fujikon.app.dazz

import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.Preset
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.data.ToneCurvePoint
import com.fujikon.app.ncp.BaseProfiles
import com.fujikon.app.ncp.ColorBlendValue
import com.fujikon.app.ncp.ColorGrading
import com.fujikon.app.ncp.ColorGradingZone
import com.fujikon.app.ncp.PictureControl
import com.fujikon.app.ncp.PictureControlFormat

/**
 * Dazz 相机里最受欢迎的几款「富士胶片」相机风格，按公开胶片配方与社区实测
 * 定性特征，用本项目通用的参数化渲染管线复刻（影调 LUT + 三分区色彩分级 +
 * 八色带 + 温和的曝光/色温/颗粒/暗角），不依赖任何二进制 LUT：
 *
 *  - FXN R · 经典负片：对标富士 Classic Negative（官配基于 Superia 100），
 *    特征是克制不发荧光的怀旧绿、偏红棕的暖阴影、带青的中间调、轻微漂白
 *    的高光，中等颗粒，+0.3EV 柔光。社区公认 Dazz 里最像富士 NC 的一款。
 *  - Classic U · 暖调胶片：对标 Fujicolor Superia 400 的日常暖调，低对比、
 *    阴影柔和、色温偏暖，全天候百搭。
 *  - Nostalgic Neg · 怀旧负片：琥珀色高光 + 深蓝阴影 + 家庭相册式暖调，
 *    比经典负片更暖更柔。
 *
 * 诚实边界：这些是「风格复刻」而非像素级移植。Dazz / 富士的机内色彩还含
 * 未公开的色彩再现矩阵与胶片响应曲线，App 在标准 sRGB 上以通用管线近似，
 * 目的是把这套广受欢迎的富士观感带进预设列表，并允许用户继续微调。
 */
object DazzFujiPresets {

    const val SOURCE_NAME = "Dazz 富士"

    /** 独立命名空间，避免与 nic: / nikonpc: / local: 冲突。 */
    private const val HASH_PREFIX = "dazz:"

    private data class Spec(
        val slug: String,
        val name: String,
        val description: String,
        val curve: IntArray,
        val blender: List<ColorBlendValue>,
        val grading: ColorGrading,
        val adjustments: EditAdjustments
    )

    /** 由 Lightroom 风格控制点生成 256 点单调影调 LUT（0..32767）。 */
    private fun curveOf(vararg xy: Float): IntArray {
        val points = xy.toList().windowed(2, 2).map { ToneCurvePoint(it[0], it[1]) }
        val curve = ToneCurve(points)
        return IntArray(256) { (curve.apply(it / 255f) * 32767f).toInt().coerceIn(0, 32767) }
    }

    /** 八色带顺序：红0 / 橙30 / 黄60 / 绿120 / 青180 / 蓝240 / 紫280 / 洋红320。 */
    private fun blender(vararg bands: IntArray): List<ColorBlendValue> =
        bands.map { ColorBlendValue(it[0], it[1], it[2]) }

    private val CURVE_CLASSIC_NEG = curveOf(
        0f, 0.045f,
        0.12f, 0.10f,
        0.30f, 0.27f,
        0.50f, 0.53f,
        0.72f, 0.80f,
        0.88f, 0.92f,
        1f, 0.965f
    )

    private val CURVE_CLASSIC_U = curveOf(
        0f, 0.035f,
        0.25f, 0.24f,
        0.50f, 0.52f,
        0.75f, 0.79f,
        1f, 0.96f
    )

    private val CURVE_NOSTALGIC_NEG = curveOf(
        0f, 0.04f,
        0.20f, 0.19f,
        0.45f, 0.47f,
        0.70f, 0.76f,
        0.88f, 0.90f,
        1f, 0.94f
    )

    private val SPECS: List<Spec> = listOf(
        Spec(
            slug = "fxn_r_classic_neg",
            name = "FXN R · 经典负片",
            description = "Dazz 最火的富士平替（FXN R / Classic Negative）。克制不发荧光的怀旧绿、偏红棕的暖阴影、带青的中间调与轻微漂白的高光，中等颗粒、+0.3EV 柔光。适合街拍、美食、绿植与日常随拍。",
            curve = CURVE_CLASSIC_NEG,
            blender = blender(
                intArrayOf(2, 4, 3),     // 红：肤色微暖提亮
                intArrayOf(2, 3, 2),     // 橙
                intArrayOf(0, 2, 0),     // 黄
                intArrayOf(-25, -20, 2), // 绿：向橄榄黄偏移并降饱和，去荧光绿
                intArrayOf(0, 0, 0),     // 青
                intArrayOf(-18, 6, 0),   // 蓝：向青靠拢，取 Superia 冷蓝气质
                intArrayOf(0, 0, 0),     // 紫
                intArrayOf(0, -4, 0)     // 洋红
            ),
            grading = ColorGrading(
                highlights = ColorGradingZone(hue = 35, chroma = 20, brightness = 8),
                midtones = ColorGradingZone(hue = 190, chroma = 10, brightness = 1),
                shadows = ColorGradingZone(hue = 18, chroma = 34, brightness = -3),
                blending = 55,
                balance = 0
            ),
            adjustments = EditAdjustments(
                exposure = 0.3f,
                temperature = 10f,
                vibrance = 8f,
                clarity = -4f,
                grain = 35f,
                vignette = -10f
            )
        ),
        Spec(
            slug = "classic_u_superia400",
            name = "Classic U · 暖调胶片",
            description = "Dazz Classic U，对标 Fujicolor Superia 400 的日常暖调。低对比、阴影柔和、色温偏暖，全天候百搭，随手拍即有胶片氛围。",
            curve = CURVE_CLASSIC_U,
            blender = blender(
                intArrayOf(2, 5, 3),     // 红
                intArrayOf(2, 4, 2),     // 橙
                intArrayOf(0, 2, 1),     // 黄
                intArrayOf(-12, -12, 2), // 绿：轻微去荧光
                intArrayOf(0, 0, 0),     // 青
                intArrayOf(-8, 4, 0),    // 蓝
                intArrayOf(0, 0, 0),     // 紫
                intArrayOf(0, -3, 0)     // 洋红
            ),
            grading = ColorGrading(
                highlights = ColorGradingZone(hue = 30, chroma = 16, brightness = 6),
                midtones = ColorGradingZone(hue = 35, chroma = 6, brightness = 0),
                shadows = ColorGradingZone(hue = 25, chroma = 20, brightness = 2),
                blending = 50,
                balance = 0
            ),
            adjustments = EditAdjustments(
                exposure = 0.15f,
                temperature = 16f,
                vibrance = 6f,
                clarity = -3f,
                grain = 26f,
                vignette = -8f
            )
        ),
        Spec(
            slug = "nostalgic_neg",
            name = "Nostalgic Neg · 怀旧负片",
            description = "富士 Nostalgic Neg 风格：琥珀色高光 + 深蓝阴影 + 家庭相册式暖调，比经典负片更暖更柔。适合旅行、聚会与人像记忆感画面。",
            curve = CURVE_NOSTALGIC_NEG,
            blender = blender(
                intArrayOf(3, 6, 4),     // 红
                intArrayOf(3, 5, 3),     // 橙
                intArrayOf(0, 3, 2),     // 黄
                intArrayOf(-16, -14, 2), // 绿：橄榄化
                intArrayOf(0, 2, 0),     // 青
                intArrayOf(4, 6, -2),    // 蓝：深化蓝调阴影
                intArrayOf(0, 0, 0),     // 紫
                intArrayOf(0, -3, 0)     // 洋红
            ),
            grading = ColorGrading(
                highlights = ColorGradingZone(hue = 38, chroma = 38, brightness = 10),
                midtones = ColorGradingZone(hue = 30, chroma = 8, brightness = 0),
                shadows = ColorGradingZone(hue = 218, chroma = 30, brightness = -8),
                blending = 58,
                balance = 0
            ),
            adjustments = EditAdjustments(
                exposure = 0.2f,
                temperature = 20f,
                vibrance = 5f,
                clarity = -5f,
                grain = 22f,
                vignette = -12f
            )
        )
    )

    fun all(): List<Preset> = SPECS.map { it.toPreset() }

    private fun Spec.toPreset(): Preset {
        val control = PictureControl(
            format = PictureControlFormat.NP3,
            version = "dazz1",
            name = name,
            baseProfile = BaseProfiles.NEUTRAL,
            baseProfileName = BaseProfiles.nameOf(BaseProfiles.NEUTRAL),
            curve = curve,
            saturation = 0,
            hue = 0,
            colorBlender = blender,
            colorGrading = grading,
            description = description
        )
        return Preset.from(
            control = control,
            id = "",
            fileName = "",
            fileSize = 0,
            isSample = false,
            contentHash = HASH_PREFIX + slug,
            sourceName = SOURCE_NAME
        ).copy(name = name, adjustments = adjustments)
    }
}

/** 与官方云创 / nikonpc 等内置一起播种进预置列表。 */
fun builtInDazzFujiPresets(): List<Preset> = DazzFujiPresets.all()
