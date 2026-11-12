package com.fujikon.app.nic

/**
 * 云创色彩预设库（资料库附件「云创色彩预设.zip」）LUT 目录中 9 个 .cube 3D LUT 的
 * 逐一对照目录。
 *
 * 原始文件为文本 .cube（33³ / 65³），这里统一重采样为 33³、uint16 量化的紧凑二进制
 * （FKL1: magic + size + flags + RGB 三通道，按 cube 规范 r 最慢 / b 最快排列），
 * 由 [com.fujikon.app.render.CubeLut] 读取并做三线性插值。
 *
 * 诚实边界：这些是尼康 Z6III / Z8 / Z9 与 RED 官方的「N-Log / Rec.2020 → Rec.709」
 * 技术转换或电影风格 LUT，输入应为 Log 编码素材，不是给已显影的 sRGB JPEG 用的创意
 * 滤镜。App 通过「亮度锚定的相对变换」把中性灰锁定、只叠加 LUT 的色彩/影调偏移，
 * 因此可以作为可选色彩方案使用而不会毁掉曝光；它不等于机内 N-Log 素材的像素级还原。
 *
 * 云创滤镜观感的准确性来自 [CloudRecipeCatalog] 的 107 个真实 NP3，而不是这些 LUT。
 */
data class CloudLutSpec(
    val id: String,
    val asset: String,
    val name: String,
    val description: String,
    val size: Int,
    val sourceSize: Int,
    val category: String
)

object CloudLutCatalog {
    const val SOURCE_NAME = "云创官方 · 3D LUT"
    const val HASH_PREFIX = "cloudlut:"
    const val HASH_PREFIX_NONE = "cloudlut:none"

    val ALL: List<CloudLutSpec> = listOf(
        CloudLutSpec(
            id = "lut_01",
            asset = "cloud_luts/cloud_lut_01.bin",
            name = "N-Log BT2020 to REC709 BT1886 size 33",
            description = "云创色彩预设库 LUT 方案「N-Log BT2020 to REC709 BT1886 size 33」：3D LUT（原始 33³，已重采样 33³），输入域为 N-Log / Rec.2020 Log 编码、输出 Rec.709 BT.1886。App 以亮度锚定的相对变换把它适配到普通 sRGB 照片：中性灰亮度保持不变，只保留官方 LUT 的色彩与影调风格，避免直接套 Log LUT 造成的整体过曝。分类：N-Log 还原 / Rec.709 / BT.2020。随 1.6.43 逐一落地。",
            size = 33,
            sourceSize = 33,
            category = "N-Log 还原 / Rec.709"
        ),
        CloudLutSpec(
            id = "lut_02",
            asset = "cloud_luts/cloud_lut_02.bin",
            name = "RED Achromic Rec2020 N-Log to Rec709 BT1886",
            description = "云创色彩预设库 LUT 方案「RED Achromic Rec2020 N-Log to Rec709 BT1886」：3D LUT（原始 33³，已重采样 33³），输入域为 N-Log / Rec.2020 Log 编码、输出 Rec.709 BT.1886。App 以亮度锚定的相对变换把它适配到普通 sRGB 照片：中性灰亮度保持不变，只保留官方 LUT 的色彩与影调风格，避免直接套 Log LUT 造成的整体过曝。分类：消色 / 低饱和 / 电影风格。随 1.6.43 逐一落地。",
            size = 33,
            sourceSize = 33,
            category = "消色 / 低饱和"
        ),
        CloudLutSpec(
            id = "lut_03",
            asset = "cloud_luts/cloud_lut_03.bin",
            name = "RED FilmBiasBleachBypass Rec2020 N-Log to Rec709 BT1886",
            description = "云创色彩预设库 LUT 方案「RED FilmBiasBleachBypass Rec2020 N-Log to Rec709 BT1886」：3D LUT（原始 33³，已重采样 33³），输入域为 N-Log / Rec.2020 Log 编码、输出 Rec.709 BT.1886。App 以亮度锚定的相对变换把它适配到普通 sRGB 照片：中性灰亮度保持不变，只保留官方 LUT 的色彩与影调风格，避免直接套 Log LUT 造成的整体过曝。分类：漂白旁路 / 高对比 / 电影风格。随 1.6.43 逐一落地。",
            size = 33,
            sourceSize = 33,
            category = "漂白旁路 / 高对比"
        ),
        CloudLutSpec(
            id = "lut_04",
            asset = "cloud_luts/cloud_lut_04.bin",
            name = "RED FilmBiasOffset Rec2020 N-Log to Rec709 BT1886",
            description = "云创色彩预设库 LUT 方案「RED FilmBiasOffset Rec2020 N-Log to Rec709 BT1886」：3D LUT（原始 33³，已重采样 33³），输入域为 N-Log / Rec.2020 Log 编码、输出 Rec.709 BT.1886。App 以亮度锚定的相对变换把它适配到普通 sRGB 照片：中性灰亮度保持不变，只保留官方 LUT 的色彩与影调风格，避免直接套 Log LUT 造成的整体过曝。分类：暖调偏移 / Film Bias / 电影风格。随 1.6.43 逐一落地。",
            size = 33,
            sourceSize = 33,
            category = "暖调偏移 / Film Bias"
        ),
        CloudLutSpec(
            id = "lut_05",
            asset = "cloud_luts/cloud_lut_05.bin",
            name = "RED FilmBias Rec2020 N-Log to Rec709 BT1886",
            description = "云创色彩预设库 LUT 方案「RED FilmBias Rec2020 N-Log to Rec709 BT1886」：3D LUT（原始 33³，已重采样 33³），输入域为 N-Log / Rec.2020 Log 编码、输出 Rec.709 BT.1886。App 以亮度锚定的相对变换把它适配到普通 sRGB 照片：中性灰亮度保持不变，只保留官方 LUT 的色彩与影调风格，避免直接套 Log LUT 造成的整体过曝。分类：Film Bias / 胶片风格 / 电影风格。随 1.6.43 逐一落地。",
            size = 33,
            sourceSize = 33,
            category = "Film Bias / 胶片风格"
        ),
        CloudLutSpec(
            id = "lut_06",
            asset = "cloud_luts/cloud_lut_06.bin",
            name = "Z6III N-Log-Full to REC709-Full 33 V01-00",
            description = "云创色彩预设库 LUT 方案「Z6III N-Log-Full to REC709-Full 33 V01-00」：3D LUT（原始 33³，已重采样 33³），输入域为 N-Log / Rec.2020 Log 编码、输出 Rec.709 BT.1886。App 以亮度锚定的相对变换把它适配到普通 sRGB 照片：中性灰亮度保持不变，只保留官方 LUT 的色彩与影调风格，避免直接套 Log LUT 造成的整体过曝。分类：N-Log 还原 / Rec.709 / 官方技术 LUT。随 1.6.43 逐一落地。",
            size = 33,
            sourceSize = 33,
            category = "N-Log 还原 / Rec.709"
        ),
        CloudLutSpec(
            id = "lut_07",
            asset = "cloud_luts/cloud_lut_07.bin",
            name = "Z 8 N-Log-Full to REC709-Full 33 V01-00",
            description = "云创色彩预设库 LUT 方案「Z 8 N-Log-Full to REC709-Full 33 V01-00」：3D LUT（原始 33³，已重采样 33³），输入域为 N-Log / Rec.2020 Log 编码、输出 Rec.709 BT.1886。App 以亮度锚定的相对变换把它适配到普通 sRGB 照片：中性灰亮度保持不变，只保留官方 LUT 的色彩与影调风格，避免直接套 Log LUT 造成的整体过曝。分类：N-Log 还原 / Rec.709 / 官方技术 LUT。随 1.6.43 逐一落地。",
            size = 33,
            sourceSize = 33,
            category = "N-Log 还原 / Rec.709"
        ),
        CloudLutSpec(
            id = "lut_08",
            asset = "cloud_luts/cloud_lut_08.bin",
            name = "Z 9 N-Log-Full to REC709-Full 33 V02-00",
            description = "云创色彩预设库 LUT 方案「Z 9 N-Log-Full to REC709-Full 33 V02-00」：3D LUT（原始 33³，已重采样 33³），输入域为 N-Log / Rec.2020 Log 编码、输出 Rec.709 BT.1886。App 以亮度锚定的相对变换把它适配到普通 sRGB 照片：中性灰亮度保持不变，只保留官方 LUT 的色彩与影调风格，避免直接套 Log LUT 造成的整体过曝。分类：N-Log 还原 / Rec.709 / 官方技术 LUT。随 1.6.43 逐一落地。",
            size = 33,
            sourceSize = 33,
            category = "N-Log 还原 / Rec.709"
        ),
        CloudLutSpec(
            id = "lut_09",
            asset = "cloud_luts/cloud_lut_09.bin",
            name = "curry nikon lut",
            description = "云创色彩预设库 LUT 方案「curry nikon lut」：3D LUT（原始 65³，已重采样 33³），输入域为 N-Log / Rec.2020 Log 编码、输出 Rec.709 BT.1886。App 以亮度锚定的相对变换把它适配到普通 sRGB 照片：中性灰亮度保持不变，只保留官方 LUT 的色彩与影调风格，避免直接套 Log LUT 造成的整体过曝。分类：达芬奇生成 / 尼康色调 / 65³ 原始。随 1.6.43 逐一落地。",
            size = 33,
            sourceSize = 65,
            category = "达芬奇生成 / 尼康色调"
        )
    )
}
