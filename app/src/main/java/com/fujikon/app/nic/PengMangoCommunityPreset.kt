package com.fujikon.app.nic

import com.fujikon.app.data.Preset
import com.fujikon.app.ncp.BaseProfiles
import com.fujikon.app.ncp.ColorBlendValue
import com.fujikon.app.ncp.PictureControl
import com.fujikon.app.ncp.PictureControlFormat

/**
 * 「Peng Mango 生命力风格（社区近似）」——由 color-audit/fit_xhs_style.py 生成，
 * 数据来源见 color-audit/xhs-style-report.md。
 *
 * 拟合方式：以官方 Vitality Film 的 pictureControl 为先验，在小红书博主
 * 「芒果小明 / Peng Mango」6 篇公开作品的 41 张成片上做 v3 无参考统计匹配
 * （共享单调曲线 + 饱和度/色相增益 + 8 分区），再按篇留一法选择轮次。
 *
 * 目标图并未被确认是该滤镜出片，可能含后期调色、加字与平台压缩；
 * 分数只衡量分布相似度，不含空间结构，不等于像素级 ΔE00。
 * 因此本条目是「贴近博主成片风格」的社区近似，不是官方滤镜复刻。
 *
 * 与官方条目 [PENG_MANGO_VITALITY_FILM] 相互独立：内容哈希不同、参数不同，
 * 不会覆盖或替换官方条目。
 */
object PengMangoCommunityPreset {
    const val ID = "pmango_style_approx"
    const val NAME = "Peng Mango 生命力风格（社区近似）"
    const val CONTENT_HASH = "local:pmango_style_approx"
    const val SOURCE_NAME = "社区拟合（非官方）"

    /** 云创锐化整体缩放，与内置官方条目保持同一口径。 */
    private const val DETAIL_SCALE = 0.30f

    /** 拟合得到的饱和度偏移（百分比，NP3 scale=100）。 */
    private const val SATURATION = -8

    /** 拟合得到的色相旋转：Preset.hue = 度数 / 0.3。 */
    private const val HUE = 0

    private const val DESCRIPTION =
        "基于博主公开作品拟合、非官方滤镜：以 6 篇公开作品 41 张成片做分布相似度拟合，" +
            "共享单调曲线 + 饱和度/色相增益 + 8 分区。目标图未确认为该滤镜出片，" +
            "可能含后期调色、加字与平台压缩；分数只衡量分布相似度、不含空间结构、" +
            "不等于像素级 ΔE00。"

    private val CURVE = intArrayOf(
             1927,  1927,  1927,  1927,  1927,  1927,  1927,  1927,  1927,  1927,  1927,  1927,  2184,  2441,  2570,  2827,
             3084,  3084,  3469,  3726,  3983,  4112,  4369,  4497,  4754,  5011,  5140,  5397,  5654,  5782,  6039,  6168,
             6425,  6682,  6810,  7067,  7196,  7453,  7838,  7967,  8224,  8352,  8609,  8738,  8995,  9123,  9380,  9766,
             9894, 10151, 10280, 10408, 10665, 10794, 11051, 11308, 11565, 11693, 11822, 12079, 12207, 12336, 12593, 12850,
            12978, 13235, 13364, 13492, 13621, 13878, 14135, 14263, 14392, 14520, 14777, 14906, 15034, 15291, 15420, 15548,
            15805, 15934, 16062, 16191, 16319, 16576, 16705, 16833, 16962, 17090, 17219, 17347, 17476, 17861, 17990, 18118,
            18247, 18375, 18504, 18632, 18761, 18889, 19018, 19146, 19275, 19403, 19532, 19660, 19789, 19917, 20174, 20303,
            20431, 20560, 20688, 20688, 20817, 20945, 21074, 21202, 21331, 21588, 21716, 21716, 21845, 21973, 22102, 22230,
            22359, 22487, 22616, 22744, 22873, 23001, 23130, 23258, 23387, 23515, 23644, 23772, 23901, 24029, 24158, 24415,
            24415, 24543, 24672, 24929, 25057, 25186, 25186, 25443, 25571, 25700, 25828, 25957, 26085, 26214, 26342, 26471,
            26599, 26728, 26856, 26985, 27113, 27242, 27370, 27499, 27627, 27756, 27756, 27884, 28013, 28141, 28141, 28270,
            28398, 28398, 28527, 28655, 28655, 28784, 28784, 28912, 29041, 29041, 29169, 29169, 29298, 29426, 29426, 29555,
            29555, 29683, 29812, 29812, 29812, 29940, 29940, 30069, 30069, 30197, 30197, 30326, 30326, 30326, 30454, 30454,
            30583, 30583, 30583, 30711, 30711, 30711, 30840, 30840, 30840, 30968, 30968, 30968, 30968, 30968, 31097, 31097,
            31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097,
            31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097, 31097
    )

    private val BLENDER = listOf(
            ColorBlendValue(   0,    0,    0),
            ColorBlendValue(   0,    0,    0),
            ColorBlendValue(   0,    0,    0),
            ColorBlendValue(   0,    0,    0),
            ColorBlendValue(   0,    0,    0),
            ColorBlendValue(   0,    0,    0),
            ColorBlendValue(   0,    0,    0),
            ColorBlendValue(   0,    0,    0)
    )

    fun toPreset(): Preset {
        val control = PictureControl(
            format = PictureControlFormat.NP3,
            version = "1.0",
            name = NAME,
            baseProfile = BaseProfiles.FLEXIBLE_COLOR,
            baseProfileName = "Flexible Color",
            curve = CURVE,
            // 官方 Vitality Film 的发布值 +3.50 / +1.50 / -1.00，按同一 0.30 缩放。
            sharpening = 3.50f * DETAIL_SCALE,
            clarity = -1.00f * DETAIL_SCALE,
            midRangeSharpening = 1.50f * DETAIL_SCALE,
            // 影调已在拟合 LUT 内，五项必须归零，避免 LUT 之后重复施加。
            contrast = 0,
            brightness = 0,
            highlights = 0,
            shadows = 0,
            whites = 0,
            blacks = 0,
            saturation = SATURATION,
            hue = HUE,
            colorBlender = BLENDER,
            description = DESCRIPTION
        )
        return Preset.from(
            control = control,
            id = "",
            fileName = "",
            fileSize = 0,
            isSample = false,
            contentHash = CONTENT_HASH,
            sourceName = SOURCE_NAME
        ).copy(name = NAME)
    }
}

/** 与官方云创条目一起播种进预置列表的社区近似条目。 */
fun builtInCommunityPresets(): List<Preset> = listOf(PengMangoCommunityPreset.toPreset())
