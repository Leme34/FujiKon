package com.fujikon.app.nic

import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.PortraitRetouch
import com.fujikon.app.data.PortraitStyle
import com.fujikon.app.data.Preset
import com.fujikon.app.ncp.BaseProfiles
import com.fujikon.app.ncp.PictureControl
import com.fujikon.app.ncp.PictureControlFormat

/**
 * 「通透胶片」v4 —— 在 1.6.36 基线上修正「整体偏亮、没有质感」的反馈。
 *
 * 1.6.36 的日系低对比明亮方向过头：把暗部显著抬亮（输入 15→31、30→46，黑位
 * 抬到 12）制造雾感与发亮，叠加 clarity=-3 柔化、vignette=+2 提亮四角，于是
 * 画面既发灰发亮、又缺少局部对比与暗角质感。v4 把方向扳回「通透 + 质感」：
 *
 * 与 1.6.33「偏色非常严重」那一版的根本区别（保色红线不变）：
 *  - 1.6.33 用逐通道 curve（r/g/b 各查一次非线性表），拉伸彩色像素 RGB 比例 ->
 *    色相漂移 -> 偏色。本预设 **curve = null**，彻底不用逐通道 LUT。
 *  - 影调走 [Preset.lumaToneLut]：按 luma 查表，三通道同 scale + lift，高光触顶
 *    时三通道等比回缩（渲染器已实现）。RGB 比例严格不变，灰阶探针 a/b=0、
 *    彩色探针色相漂移≈0°。
 *
 * 色彩层保持恒等（saturation/hue/colorBlender/colorGrading 全为 0/空）：
 * 20 张成片的彩色像素 mean_b 跨度 42，全局加任何一种色偏都会在其反向场景翻车；
 * 肤色色相在不同批次间漂移 13°，也不能锁死某个肤色去染色。唯一跨场景稳定的是
 * skin_L≈74（白皙亮度），因此人像美颜只锚定「亮度/均匀度」，不锁色相。
 *
 * 六维落地（全部走项目现成算子）：
 *  ① 影调 = 保色 lumaToneLut：黑位 5 去雾（不再把黑抬成灰发亮）、暗部到中段
 *     略压降亮并增加对比重量、高光平滑肩部到 254 保留层次——不再是「提亮曲线」。
 *  ② 光感 = 干净的暗部 + 低黑位雾感，光从通透里来，而非从发灰里来。
 *  ③ 色调 = 色彩层恒等 + 亮度变化带来的通透感；零色相偏移（不偏色红线）。
 *  ④ 人像美颜 = 肤色感知 portrait（轻磨皮/美白/红润/立体，带边缘保护）。
 *  ⑤ 质感 = 正向 clarity 增强局部对比（1.6.36 的 -3 是柔化削质感的根因）、
 *     正向微纹理回补、轻暗角聚焦——三者共同把「糊」变成「实」。
 *  ⑥ 美感 = 极轻负 vignette 压暗四角（聚焦、立体、增强质感），取代 1.6.36 的
 *     提亮四角；不加颗粒、不加全局饱和。
 *
 * 诚实边界：目标只有成片（after），没有 before/after 配对，也没有机内真实
 * gamma/LUT。因此这是「统计风格拟合」，不是像素级 ΔE00 复刻；逆光光感、
 * 暖橙主体、发丝高光来自拍摄与场景，滤镜不能凭空生成。
 */
object WorkStyle16Preset {
    const val ID = "workstyle16_tonal"
    const val NAME = "通透胶片"
    /** v4：方向由「日系低对比明亮」扳回「通透 + 质感」——去雾降亮、转正 clarity、压暗角。 */
    const val CONTENT_HASH = "local:workstyle16_tonal_v4"
    const val SOURCE_NAME = "20 张作品影调拟合"

    private const val DESCRIPTION =
        "根据用户提供的 20 张风格一致成片，在 1.6.36 基线上做增量拟合的可泛化滤镜：" +
            "覆盖夏日青蓝、暖橙、校园清新、逆光与纯蓝天空等场景。1.6.36 的日系低对比" +
            "明亮方向过头（暗部被显著抬亮、发灰发亮、缺少质感），v4 把它扳回「通透 + " +
            "质感」——黑位降到 5 去雾（不再把黑抬成灰发亮）、暗部到中段略压降亮并增加" +
            "对比重量、高光平滑肩部保留层次；影调走保色亮度耦合 LUT，三通道同比例缩放并" +
            "做溢出等比回缩，灰阶与彩色探针验证色相漂移≈0°，色彩层恒等，绝不额外加青或" +
            "加暖（守住不偏色红线）。质感侧把 clarity 由柔化改为正向增强局部对比、提高" +
            "微纹理回补、并把 vignette 由提亮四角改为轻压暗角聚焦——三者共同把「糊」变" +
            "成「实」。人像侧叠加轻磨皮/美白/红润/立体，锚定白皙亮度而不锁死肤色色相。" +
            "注：只有成片无配对原图，属统计风格拟合，逆光光感与场景主体来自拍摄" +
            "(非滤镜可生成)，不等于像素级复刻。"

    /**
     * 保色影调 LUT，256 点，值域 0..255（luma 域）。v4 相对 1.6.36 的关键修正：
     *  - 黑位 12 → 5：去掉「日系雾感/空气感」带来的发灰发亮，暗部重新干净有重量；
     *  - 暗部到中段不再抬亮（1.6.36 输入 15→31、30→46），改为略压降亮并增对比；
     *  - 高光平滑肩部收敛到 254、全程保留层次，杜绝死白平台（最长相等连续段 ≤3）。
     * 单调、平滑、无逐通道裁剪、色相零漂移。
     */
    private val LUMA_TONE_LUT = intArrayOf(
              5,   6,   6,   7,   7,   8,   8,   9,  10,  10,  11,  11,  12,  12,  13,  13,
             14,  15,  16,  17,  18,  19,  20,  21,  22,  22,  23,  24,  25,  26,  27,  28,
             29,  30,  31,  32,  33,  34,  35,  36,  36,  37,  38,  39,  40,  41,  42,  43,
             44,  45,  46,  47,  48,  49,  50,  51,  52,  52,  53,  54,  55,  56,  57,  58,
             59,  60,  61,  62,  63,  64,  65,  66,  67,  68,  69,  70,  71,  72,  73,  74,
             75,  76,  77,  78,  79,  80,  81,  82,  83,  84,  85,  86,  87,  88,  89,  90,
             91,  92,  93,  94,  95,  96,  97,  98, 100, 101, 102, 103, 104, 105, 106, 107,
            108, 109, 110, 110, 111, 112, 113, 114, 114, 115, 116, 117, 118, 119, 119, 120,
            121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131, 132, 133, 134, 135, 136,
            137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152,
            153, 154, 155, 156, 157, 158, 159, 160, 162, 163, 164, 165, 166, 167, 168, 169,
            170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184, 185,
            186, 187, 188, 189, 190, 191, 192, 193, 194, 196, 197, 198, 199, 200, 201, 202,
            203, 204, 206, 207, 208, 209, 210, 212, 213, 214, 216, 217, 218, 219, 220, 222,
            223, 224, 225, 226, 227, 228, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239,
            240, 241, 242, 244, 245, 246, 247, 248, 249, 250, 250, 251, 252, 253, 253, 254
    )

    /**
     * 复合效果层。全部取项目现成「写真」look 的约 40% 强度，避免塑料脸与发灰：
     *  - clarity +6 / texture +8：正向增强局部对比与微纹理，把 1.6.36 的柔化（无质感）
     *    扳回「实」；这是「没有质感」反馈的核心修复。
     *  - vignette -6：极轻压暗四角，聚焦 + 立体 + 增质感，取代 1.6.36 的提亮四角
     *    （提亮四角是「整体偏亮」帮凶之一）。
     *  - portrait：轻磨皮 12 / 白皙 12 / 气色 6 / 立体 8，带边缘与肤色掩码保护。
     * 刻意不加 temperature / tint / vibrance / HSL / split-tone / grain / denoise：
     * 任何全局调色都会在 20 张跨度极大的场景色上翻车。
     */
    private val ADJUSTMENTS = EditAdjustments(
        clarity = 6f,
        texture = 8f,
        vignette = -6f,
        portrait = PortraitRetouch(
            style = PortraitStyle.CUSTOM.id,
            smooth = 12f,
            whiten = 12f,
            blush = 6f,
            contour = 8f
        )
    )

    fun toPreset(): Preset {
        val control = PictureControl(
            format = PictureControlFormat.NP3,
            version = "1.0",
            name = NAME,
            baseProfile = BaseProfiles.FLEXIBLE_COLOR,
            baseProfileName = "Flexible Color",
            // 逐通道 curve 会拉伸彩色像素 RGB 比例而引入色偏 —— v2 起明确不用。
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
