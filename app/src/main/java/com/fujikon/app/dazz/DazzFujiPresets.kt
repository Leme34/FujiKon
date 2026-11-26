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
 * 1.6.69 新增理光负片 OFM R：与三款富士是两种审美——寡淡、低饱和、褪色抬黑、
 * 中性偏暖浅黄，是 Dazz 近期最火的理光 GR 平替。同样烘成 33³ 3D LUT，套用/导出
 * 与三款一致的速度。
 *
 * 1.6.73 新增胶片直闪人像 Flash：强 S 曲线（压黑提白、中间调下沉）、青绿阴影 +
 * 暖奶油高光、肤色强保护且暖粉通透，叠加轻暗角与颗粒还原直闪质感。同样烘成
 * 33³ 3D LUT。
 *
 * 诚实边界：没有原图—成片配对，无法逐像素反推 Dazz 的内部 LUT；这里复刻的是
 * 样张呈现的风格指纹 + 富士公开色彩科学，属风格接近而非像素级移植。
 */
object DazzFujiPresets {

    const val SOURCE_NAME = "Dazz 富士"

    /** 1.6.65 用新前缀让覆盖安装的用户退役旧 dazz2:/dazz: 并补入 3D LUT 新效果。 */
    private const val HASH_PREFIX = "dazz3:"
    /**
     * 直闪人像配方升级版本后缀，触发老用户原地升级到新效果：
     * v2=1.6.74 主体感知（已废弃）、v3=1.6.77 去橙红+径向闪光、
     * v4=1.6.79 富士青蓝影调 + 理光 matte 空气感（灰蒙蒙，已废弃）、
     * v5=1.6.80 撤回 matte/青蓝，恢复清透浓郁富士色彩、
     * v6=1.6.81 白布中性化，修复白裙/白衣暖粉偏色。
     */
    const val FLASH_HASH_V6 = HASH_PREFIX + "film_flash_portrait_v6"
    /**
     * FXN R 经典负片配方升级后缀（1.6.83）：用原图—成片配对量化拟合重做，
     * 覆盖安装时把旧 `dazz3:fxn_r_classic_neg` 原地升级到新影调，保留用户态。
     */
    const val FXN_HASH_V2 = HASH_PREFIX + "fxn_r_classic_neg_v2"

    private data class Spec(
        val slug: String,
        val name: String,
        val description: String,
        val filmLook: Int,
        val grain: Float,
        /** 暗角强度（0..100，正值压暗四角），用于模拟闪光随距离衰减。 */
        val vignette: Float = 0f,
        /** 全局径向闪光照明强度（0..100），仅直闪人像使用。 */
        val flash: Float = 0f,
        /** 该配方要使用的 contentHash（非 null 时触发老用户原地升级）。 */
        val hashOverride: String? = null
    )

    private val SPECS: List<Spec> = listOf(
        Spec(
            slug = "fxn_r_classic_neg",
            name = "FXN R · 经典负片",
            description = "Dazz 最火的富士平替（FXN R / Classic Negative）：低饱和青灰影调、阴影青蓝、高光去暖，压暗有层次，肤色清透通透，带胶片颗粒。适合街拍、绿植、美食与日常随拍。",
            filmLook = DazzFilmLut.LOOK_FXN_R,
            grain = 12f,
            hashOverride = FXN_HASH_V2
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
        ),
        Spec(
            slug = "ofm_r_ricoh_neg",
            name = "OFM R · 理光负片",
            description = "Dazz 近期最火的理光 GR 平替（OFM R）：低饱和、低对比、轻微褪色抬黑，中性偏暖的浅黄调，过渡柔和自带氛围感。适合日常随拍、街景建筑、静物与暖色室内。",
            filmLook = DazzFilmLut.LOOK_OFM_R,
            grain = 8f
        ),
        Spec(
            slug = "film_flash_portrait",
            name = "Flash · 胶片直闪人像",
            description = "复古胶片直闪人像：全局径向闪光照明把主体打亮、四周随距离衰减，暗部不死黑、高光不死白，肤色清透不泛红；青绿阴影配中性奶油高光，细颗粒还原 90 年代傻瓜相机直闪质感。适合夜间、室内弱光与聚会人像。",
            filmLook = DazzFilmLut.LOOK_FLASH_PORTRAIT,
            grain = 14f,
            vignette = 10f,
            flash = 72f,
            hashOverride = FLASH_HASH_V6
        )
    )

    fun all(): List<Preset> = SPECS.map { it.toPreset() }

    private fun Spec.contentHashOf(): String =
        hashOverride ?: (HASH_PREFIX + slug)

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
            contentHash = contentHashOf(),
            sourceName = SOURCE_NAME
        ).copy(
            name = name,
            filmLook = filmLook,
            adjustments = EditAdjustments(grain = grain, vignette = vignette, flash = flash)
        )
    }
}

/** 与官方云创 / nikonpc 等内置一起播种进预置列表。 */
fun builtInDazzFujiPresets(): List<Preset> = DazzFujiPresets.all()
