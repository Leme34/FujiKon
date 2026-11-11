package com.fujikon.app.nic

import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.PortraitRetouch
import com.fujikon.app.data.PortraitStyle
import com.fujikon.app.data.Preset
import com.fujikon.app.ncp.BaseProfiles
import com.fujikon.app.ncp.PictureControl
import com.fujikon.app.ncp.PictureControlFormat

/**
 * 「通透胶片」v3 —— 在 1.6.35 基线上回测控制高光、增强日系影调（20 张成片）。
 *
 * 数据来源：用户提供、风格一致的 20 张成片，覆盖夏日青蓝 / 暖橙 / 校园清新 /
 * 逆光与纯蓝天空四类场景（见训练产物 v2_final_result.json）。相对 1.6.32 的
 * 16 张，新样本最稳定的差异是「暗部到中间调更通透、高光护层次、整体对比略柔」：
 * 新 20 张 luma p10/p25/p50 比旧 16 张高 30/43/27，而 p90~p99 几乎不变，
 * L_std 由 68.8 降到 59.2。
 *
 * 与 1.6.33「偏色非常严重」那一版的根本区别：
 *  - 1.6.33 用逐通道 curve（r/g/b 各查一次非线性表），会拉伸彩色像素的
 *    RGB 比例 -> 色相漂移 -> 偏色。本预设 **curve = null**，彻底不用逐通道 LUT。
 *  - 影调走 [Preset.lumaToneLut]：按 luma 查表，三通道同 scale + lift，
 *    且高光触顶时三通道等比回缩（渲染器已实现）。RGB 比例严格不变，
 *    灰阶探针 a/b=0、彩色探针色相漂移 0°（p95=0°、max=0°）。
 *
 * 色彩层保持恒等（saturation/hue/colorBlender/colorGrading 全为 0/空）：
 * 20 张的彩色像素 mean_b 从 -24 到 +18（跨度 42），全局加任何一种色偏都会
 * 在其反向场景翻车。肤色色相在不同批次间漂移 13°，也不能锁死某个肤色去染色。
 * 唯一跨场景稳定的是 skin_L≈74（白皙亮度），因此人像美颜只锚定「亮度/均匀度」，
 * 不锁色相。
 *
 * 六维落地（全部走项目现成算子，无需新增渲染逻辑）：
 *  ① 影调 = 拟合的保色 lumaToneLut（抬黑位雾感、低对比明亮、高光滑部护层次）
 *  ② 光感 = 黑位柔化 + 极轻负 clarity 制造柔雾/空气感，不压高光
 *  ③ 色调 = 色彩层恒等 + 亮度变化带来的通透感；零色相偏移（不偏色红线）
 *  ④ 人像美颜 = 肤色感知 portrait（轻磨皮/美白/红润/立体，带边缘保护）
 *  ⑤ 质感 = 正向微纹理回补 + contour 立体，抵消磨皮导致的皮肤塑料感
 *  ⑥ 美感 = 极轻正 vignette 提亮四角（空气感），不加颗粒、不加全局饱和
 *  日系影调强化：相对 1.6.35，抬黑位（雾感/空气感）、压低整体对比、高光由死白
 *  平台改为平滑肩部保留层次；纯影调操作，不引入任何色偏。
 *
 * 诚实边界：目标只有成片（after），没有 before/after 配对，也没有机内真实
 * gamma/LUT。因此这是「统计风格拟合」，不是像素级 ΔE00 复刻；逆光光感、
 * 暖橙主体、发丝高光来自拍摄与场景，滤镜不能凭空生成。
 */
object WorkStyle16Preset {
    const val ID = "workstyle16_tonal"
    const val NAME = "通透胶片"
    /** v2：内容从「逐通道 curve」换成「保色 lumaToneLut + 复合 adjustments」。 */
    const val CONTENT_HASH = "local:workstyle16_tonal_v3"
    const val SOURCE_NAME = "20 张作品影调拟合"

    private const val DESCRIPTION =
        "根据用户提供的 20 张风格一致成片，在 1.6.35 基线上做增量拟合的可泛化滤镜：" +
            "覆盖夏日青蓝、暖橙、校园清新、逆光与纯蓝天空等场景。在 1.6.35 基础上回测" +
            "控制高光并增强日系影调——抬黑位制造雾感与空气感、压低整体对比（低对比、" +
            "明亮通透），并修正原高光被压成死白平台的问题，改为平滑肩部、保留高光层次。" +
            "影调走保色亮度耦合 LUT，三通道同比例缩放并做溢出等比回缩，灰阶与彩色探针" +
            "验证色相漂移为 0，色彩层恒等，绝不额外加青或加暖（守住不偏色红线）。人像" +
            "侧叠加轻磨皮/美白/红润/立体与微纹理回补，锚定白皙亮度而不锁死肤色色相。" +
            "注：只有成片无配对原图，属统计风格拟合，逆光光感与场景主体来自拍摄" +
            "(非滤镜可生成)，不等于像素级复刻。"

    /**
     * 保色影调 LUT，256 点，值域 0..255（luma 域，非 PictureControl.curve 的
     * 0..32767）。在 1.6.35 基线基础上回测得到：抬黑位（12，日系雾感/空气感）、
     * 低中段轻微提亮、中段以上不压暗（明亮通透、低对比），高光改为平滑肩部收敛到
     * 254、全程保留层次——修正了上一版高光被压成 ~250 死白平台（9 级塌 1 级）的
     * 失控问题。单调、平滑、无逐通道裁剪、色相零漂移。
     */
    private val LUMA_TONE_LUT = intArrayOf(
            12,  15,  16,  17,  19,  20,  21,  22,  23,  24,  25,  26,  28,  29,  30,  31,
            32,  33,  34,  35,  36,  37,  39,  40,  41,  42,  43,  44,  46,  47,  48,  50,
            51,  52,  54,  56,  57,  59,  61,  62,  64,  66,  68,  70,  72,  73,  75,  77,
            79,  81,  83,  85,  86,  88,  90,  92,  94,  96,  97,  99, 101, 102, 104, 106,
           107, 109, 110, 111, 113, 114, 115, 116, 118, 119, 120, 121, 122, 123, 124, 125,
           126, 128, 129, 130, 131, 132, 134, 135, 136, 137, 138, 139, 140, 141, 143, 144,
           145, 146, 147, 147, 148, 149, 150, 151, 152, 153, 154, 154, 155, 156, 157, 158,
           158, 159, 160, 161, 162, 163, 164, 164, 165, 166, 167, 168, 169, 169, 170, 171,
           172, 173, 173, 174, 175, 176, 177, 177, 178, 179, 180, 180, 181, 182, 183, 183,
           184, 185, 186, 186, 187, 188, 189, 189, 190, 191, 192, 192, 193, 194, 194, 195,
           196, 197, 197, 198, 198, 199, 200, 200, 201, 202, 202, 203, 204, 204, 205, 205,
           206, 207, 207, 208, 209, 209, 210, 210, 211, 212, 212, 213, 213, 214, 215, 215,
           216, 217, 217, 218, 218, 219, 220, 220, 221, 221, 222, 222, 223, 224, 224, 225,
           225, 226, 226, 227, 228, 228, 229, 230, 230, 231, 231, 232, 233, 233, 234, 235,
           236, 236, 237, 238, 238, 239, 240, 241, 241, 242, 243, 243, 244, 245, 245, 246,
           247, 247, 248, 249, 249, 250, 250, 251, 251, 252, 252, 253, 253, 254, 254, 254
    )

    /**
     * 复合效果层。全部取项目现成「写真」look 的约 40% 强度，避免塑料脸与发灰：
     *  - clarity -3 / texture +4：一丝逆光柔雾 + 微纹理回补，不压局部对比；
     *  - portrait：轻磨皮 14 / 白皙 16 / 气色 8 / 立体 10，带边缘与肤色掩码保护；
     *  - vignette +2：极轻提亮四角，增强空气感。
     * 刻意不加 temperature / tint / vibrance / HSL / split-tone / grain / denoise：
     * 任何全局调色都会在 20 张跨度极大的场景色上翻车。
     */
    private val ADJUSTMENTS = EditAdjustments(
        clarity = -3f,
        texture = 4f,
        vignette = 2f,
        portrait = PortraitRetouch(
            style = PortraitStyle.CUSTOM.id,
            smooth = 14f,
            whiten = 16f,
            blush = 8f,
            contour = 10f
        )
    )

    fun toPreset(): Preset {
        val control = PictureControl(
            format = PictureControlFormat.NP3,
            version = "1.0",
            name = NAME,
            baseProfile = BaseProfiles.FLEXIBLE_COLOR,
            baseProfileName = "Flexible Color",
            // 逐通道 curve 会拉伸彩色像素 RGB 比例而引入色偏 —— v2 明确不用。
            curve = null,
            // 色彩层与其余 11 项全部恒等，影调完全交给 lumaToneLut。
            sharpening = 0f,
            clarity = 0f,
            midRangeSharpening = 0f,
            contrast = 0,
            brightness = 0,
            saturation = 0,
            hue = 0,
            highlights = 0,
            shadows = 0,
            whites = 0,
            blacks = 0,
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
        ).copy(
            name = NAME,
            lumaToneLut = LUMA_TONE_LUT,
            adjustments = ADJUSTMENTS
        )
    }
}

/** 与官方云创、社区近似条目一起播种进预置列表的「通透胶片」条目。 */
fun builtInWorkStylePresets(): List<Preset> = listOf(WorkStyle16Preset.toPreset())
