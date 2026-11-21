package com.fujikon.app.dazz

import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.Preset
import com.fujikon.app.ncp.BaseProfiles
import com.fujikon.app.ncp.ColorGrading
import com.fujikon.app.ncp.PictureControl
import com.fujikon.app.ncp.PictureControlFormat

/**
 * Dazz 相机里最受欢迎的几款「富士胶片」相机风格，按用户提供的 Dazz 成片样张
 * 反推色调指纹 + 富士官方胶片模拟色彩科学设计。
 *
 * 真正的对标实现（1.6.65 重做）：
 *  - 胶片模拟的本质是一张 RGB→RGB 的 3D 色彩映射。1D 逐通道曲线在数学上无法
 *    表达「阴影青绿 / 高光洋红」这种随亮度耦合的色相分离，所以旧版三款参数
 *    几乎重合、套用后「相差无几」；而把青影全局铺满又让肤色发脏。
 *  - 现改为每款一张 33³ 3D LUT（[DazzFilmLut]），把「保色影调 S 曲线 + 随亮度
 *    色相分离 + 全局色温 + 饱和度 + 肤色保护」一次烘进映射。逐像素只剩固定的
 *    三线性插值，套用/导出接近无滤镜速度。
 *  - 三款差异落在环境色与影调，而非肤色：FXN R 经典负片（青影洋红高光、低饱和
 *    高对比）、Classic U（80s 冷调蓝绿、红色敏感、浓郁）、Nostalgic Neg（琥珀
 *    高光 + 深蓝阴影、柔和低对比）。肤色区域三款都做保护，保持清透通透。
 *
 * 诚实边界：没有原图—成片配对，无法逐像素反推 Dazz 的内部 LUT；这里复刻的是
 * 样张呈现的风格指纹 + 富士公开色彩科学，属风格接近而非像素级移植。
 */
object DazzFujiPresets {

    const val SOURCE_NAME = "Dazz 富士"

    /** 1.6.65 用新前缀让覆盖安装的用户退役旧 dazz2:/dazz: 并补入 3D LUT 新效果。 */
    private const val HASH_PREFIX = "dazz3:"

    private data class Spec(
        val slug: String,
        val name: String,
        val description: String,
        val filmLook: Int,
        val grain: Float
    )

    private val SPECS: List<Spec> = listOf(
        Spec(
            slug = "fxn_r_classic_neg",
            name = "FXN R · 经典负片",
            description = "Dazz 最火的富士平替（FXN R / Classic Negative）：青绿阴影配洋红暖高光，低饱和、高对比，通透有胶片颗粒。适合街拍、绿植、美食与日常随拍。",
            filmLook = DazzFilmLut.LOOK_FXN_R,
            grain = 12f
        ),
        Spec(
            slug = "classic_u_superia400",
            name = "Classic U · 冷调胶片",
            description = "Dazz Classic U，80 年代胶片韵味：整体偏冷、蓝绿调明显，红色敏感、色彩浓郁，颗粒扎实。适合阳光下的户外人像与色彩丰富的场景。",
            filmLook = DazzFilmLut.LOOK_CLASSIC_U,
            grain = 14f
        ),
        Spec(
            slug = "nostalgic_neg",
            name = "Nostalgic Neg · 怀旧负片",
            description = "富士 Nostalgic Neg 风格：琥珀色高光 + 深蓝阴影，柔和低对比但色彩浓郁，家庭相册式暖调记忆感。适合旅行、聚会与人像。",
            filmLook = DazzFilmLut.LOOK_NOSTALGIC_NEG,
            grain = 10f
        )
    )

    fun all(): List<Preset> = SPECS.map { it.toPreset() }

    private fun Spec.toPreset(): Preset {
        // curve 必须为 null：3D LUT 已含保色影调曲线，避免 1D 路径二次施加。
        val control = PictureControl(
            format = PictureControlFormat.NP3,
            version = "dazz3",
            name = name,
            baseProfile = BaseProfiles.NEUTRAL,
            baseProfileName = BaseProfiles.nameOf(BaseProfiles.NEUTRAL),
            curve = null,
            colorBlender = emptyList(),
            colorGrading = ColorGrading(),
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
        ).copy(
            name = name,
            filmLook = filmLook,
            adjustments = EditAdjustments(grain = grain)
        )
    }
}

/** 与官方云创 / nikonpc 等内置一起播种进预置列表。 */
fun builtInDazzFujiPresets(): List<Preset> = DazzFujiPresets.all()
