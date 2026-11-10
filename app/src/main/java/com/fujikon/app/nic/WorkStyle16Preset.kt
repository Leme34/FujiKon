package com.fujikon.app.nic

import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.PortraitRetouch
import com.fujikon.app.data.PortraitStyle
import com.fujikon.app.data.Preset
import com.fujikon.app.ncp.BaseProfiles
import com.fujikon.app.ncp.PictureControl
import com.fujikon.app.ncp.PictureControlFormat

/**
 * 「通透胶片」v2 —— 由 20 张目标成片对照 1.6.32 基线做增量拟合。
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
 *  ① 影调 = 拟合的保色 lumaToneLut（暗部柔化、中间调提亮、高光护层次、白点收敛）
 *  ② 光感 = tone LUT 的黑位柔化 + 极轻负 clarity 制造逆光柔雾，不压高光
 *  ③ 色调 = 色彩层恒等 + 后续亮度变化带来的通透感；零色相偏移
 *  ④ 人像美颜 = 肤色感知 portrait（轻磨皮/美白/红润/立体，带边缘保护）
 *  ⑤ 质感 = 正向微纹理回补 + contour 立体，抵消磨皮导致的皮肤塑料感
 *  ⑥ 美感 = 极轻正 vignette 提亮四角（空气感），不加颗粒、不加全局饱和
 *
 * 诚实边界：目标只有成片（after），没有 before/after 配对，也没有机内真实
 * gamma/LUT。因此这是「统计风格拟合」，不是像素级 ΔE00 复刻；逆光光感、
 * 暖橙主体、发丝高光来自拍摄与场景，滤镜不能凭空生成。
 */
object WorkStyle16Preset {
    const val ID = "workstyle16_tonal"
    const val NAME = "通透胶片"
    /** v2：内容从「逐通道 curve」换成「保色 lumaToneLut + 复合 adjustments」。 */
    const val CONTENT_HASH = "local:workstyle16_tonal_v2"
    const val SOURCE_NAME = "20 张作品影调拟合"

    private const val DESCRIPTION =
        "根据用户提供的 20 张风格一致成片，在 1.6.32 基线上做增量拟合的可泛化滤镜：" +
            "覆盖夏日青蓝、暖橙、校园清新、逆光与纯蓝天空等场景。仅复现跨场景稳定的" +
            "影调特征——柔化黑位、提亮暗部到中间调、护住高光层次、白点柔和收敛，整体" +
            "明亮通透而对比略柔。影调走保色亮度耦合 LUT，三通道同比例缩放并做溢出等比" +
            "回缩，灰阶与彩色探针验证色相漂移为 0，色彩层恒等，绝不额外加青或加暖。" +
            "人像侧叠加轻磨皮/美白/红润/立体与微纹理回补，锚定白皙亮度而不锁死肤色色相。" +
            "注：只有成片无配对原图，属统计风格拟合，逆光光感与场景主体来自拍摄" +
            "（非滤镜可生成），不等于像素级复刻。"

    /**
     * 保色影调 LUT，256 点，值域 0..255（luma 域，非 PictureControl.curve 的
     * 0..32767）。由训练脚本从 20 张成片对 13 张中性源图做受约束增量拟合得到；
     * 相对 1.6.32 基线的增量集中在暗部与中间调（+8~+10），高光区几乎不动，
     * 白点收敛到 250。单调、平滑、无溢出裁剪、色相零漂移。
     */
    private val LUMA_TONE_LUT = intArrayOf(
             6,   9,   9,  10,  10,  11,  12,  13,  13,  15,  15,  16,  18,  20,  22,  23,
            24,  26,  27,  28,  30,  31,  32,  34,  36,  37,  39,  40,  41,  43,  45,  46,
            48,  50,  51,  54,  56,  58,  59,  61,  63,  65,  67,  69,  71,  73,  75,  77,
            79,  81,  83,  84,  86,  88,  89,  91,  92,  94,  95,  97,  98,  99, 101, 102,
           103, 105, 107, 108, 109, 111, 112, 113, 114, 115, 116, 118, 119, 120, 121, 122,
           123, 124, 125, 127, 128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139,
           140, 141, 142, 142, 143, 144, 145, 145, 146, 147, 148, 149, 150, 150, 151, 152,
           153, 154, 155, 156, 156, 157, 158, 159, 160, 161, 163, 163, 164, 166, 168, 169,
           171, 172, 173, 175, 176, 178, 179, 181, 182, 182, 183, 184, 185, 186, 187, 188,
           188, 189, 189, 190, 191, 192, 192, 192, 193, 194, 194, 195, 196, 196, 196, 197,
           197, 198, 199, 200, 200, 200, 201, 201, 202, 203, 204, 204, 204, 205, 205, 206,
           207, 207, 208, 209, 209, 210, 211, 212, 212, 213, 214, 214, 215, 215, 216, 217,
           217, 218, 218, 219, 220, 220, 221, 221, 222, 224, 224, 225, 226, 226, 227, 227,
           228, 228, 229, 229, 230, 230, 231, 231, 231, 232, 232, 233, 233, 233, 234, 234,
           235, 235, 236, 236, 236, 237, 237, 238, 238, 239, 240, 241, 241, 242, 243, 243,
           244, 245, 246, 247, 248, 248, 249, 250, 250, 250, 250, 250, 250, 250, 250, 250
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
