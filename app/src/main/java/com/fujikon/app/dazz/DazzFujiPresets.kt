package com.fujikon.app.dazz

import com.fujikon.app.data.ChannelToneCurves
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.Preset
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.data.ToneCurvePoint
import com.fujikon.app.ncp.BaseProfiles
import com.fujikon.app.ncp.ColorGrading
import com.fujikon.app.ncp.PictureControl
import com.fujikon.app.ncp.PictureControlFormat

/**
 * Dazz 相机里最受欢迎的几款「富士胶片」相机风格，按用户提供的 Dazz 成片样张
 * 反推的色调指纹复刻：保留深色与通透反差（样张 luma 标准差≈63~71、暗部占比
 * 明显）、中低饱和、柔和但接近满的白位，配富士标志性的「青色阴影 + 暖色高光 +
 * 去荧光橄榄绿」。
 *
 * 关键校准（第二轮，依据真实样张定量）：样张 p1≈8~22（低 → 黑位不能被抬起）、
 * 标准差≈63~71（高对比 → 不能压平中间调）、人像肤色 R−B≈−2~−6（中性不偏橙）。
 * 第一轮误把样张读成「抬黑雾面」，做出发灰蒙雾效果；本版改为轻微 S 曲线压暗
 * 中间调、保留深色、不加曝光，回到样张那种通透胶片观感。
 *
 * 实现要点（1.6.64 重做）：
 *  - 旧版用「八色带 ColorBlender + 三分区 ColorGrading」去凑胶片色彩，色带每像素
 *    要做 HSL 往返（三角函数），是渲染与导出「转很久」的根因，且调调与样张不符。
 *  - 新版把整条胶片映射烘进三条**查表 LUT**：主影调 curve（S 曲线/深色/柔白）+
 *    逐通道 curve（青影暖高、绿去荧光橄榄化）。这些 LUT 在 buildPlan 阶段一次性
 *    合成好，逐像素只剩 3 次查表，与 nikonpc 预设同速，彻底摆脱 HSL 往返与
 *    clarity 卷积。
 *  - 只保留温和颗粒（grain），去掉暗角与清晰度卷积，进一步压缩全帧 pass。
 *
 * 诚实边界：没有原图—成片配对，无法逐像素反推 Dazz 的色彩再现矩阵；这里复刻的是
 * 样张呈现的**风格指纹**（影调 + 分色 + 褪色），是风格接近而非像素级移植。
 */
object DazzFujiPresets {

    const val SOURCE_NAME = "Dazz 富士"

    /** 独立命名空间；1.6.64 用新前缀让覆盖安装的用户退役旧 dazz: 并补入新效果。 */
    private const val HASH_PREFIX = "dazz2:"

    private data class Spec(
        val slug: String,
        val name: String,
        val description: String,
        val master: IntArray,
        val channels: ChannelToneCurves,
        val adjustments: EditAdjustments
    )

    /** 由 Lightroom 风格控制点生成 256 点单调影调 LUT（0..32767）。 */
    private fun curveOf(vararg xy: Float): IntArray {
        val points = xy.toList().windowed(2, 2).map { ToneCurvePoint(it[0], it[1]) }
        val curve = ToneCurve(points)
        return IntArray(256) { (curve.apply(it / 255f) * 32767f).toInt().coerceIn(0, 32767) }
    }

    /** 由 (x,y) 控制点构造一条 0..1 的逐通道曲线（近似查表输入为满量程 0..1）。 */
    private fun channelCurve(vararg xy: Float): ToneCurve =
        ToneCurve(xy.toList().windowed(2, 2).map { ToneCurvePoint(it[0], it[1]) })

    // ---- 主影调曲线：保留深色 + 轻微 S 曲线增强反差 + 柔白 ----
    // 样张是通透高对比胶片观感（std≈63~71），黑位低（p1≈8~22）。曲线黑位几乎
    // 不抬（0→0.012），中间调下压形成 S 曲线，让天空/白衣保留层次、暗部不下陷。

    /** NC：经典负片，中等 S 曲线，深色通透、白位柔和。 */
    private val MASTER_NC = curveOf(
        0f, 0.012f,
        0.10f, 0.085f,
        0.25f, 0.225f,
        0.50f, 0.475f,
        0.75f, 0.79f,
        0.90f, 0.93f,
        1f, 0.975f
    )

    /** Classic U：Superia 400 日常暖调，S 曲线更缓、阴影更柔。 */
    private val MASTER_CU = curveOf(
        0f, 0.016f,
        0.12f, 0.105f,
        0.30f, 0.285f,
        0.55f, 0.545f,
        0.78f, 0.81f,
        1f, 0.975f
    )

    /** Nostalgic Neg：中间调略压、高光更亮更琥珀，家庭相册式记忆感。 */
    private val MASTER_NN = curveOf(
        0f, 0.010f,
        0.10f, 0.082f,
        0.26f, 0.235f,
        0.52f, 0.495f,
        0.76f, 0.805f,
        0.90f, 0.945f,
        1f, 0.98f
    )

    // ---- 逐通道曲线：富士「青影暖高 + 去荧光绿」的核心 ----
    // 输入为影调 LUT 之后的三通道值（0..1）。红通道：暗部压（阴影去红→偏青）、
    // 高光抬（暖高光）；蓝通道：暗部抬（青蓝阴影）、高光压（暖高光）；绿通道：
    // 中间调轻微橄榄化（去荧光绿），保持植被自然不发荧光。

    private val CHANNELS_NC = ChannelToneCurves(
        red = channelCurve(
            0f, 0.0f, 0.12f, 0.105f, 0.35f, 0.35f,
            0.62f, 0.645f, 0.85f, 0.885f, 1f, 1f
        ),
        green = channelCurve(
            0f, 0.0f, 0.14f, 0.15f, 0.35f, 0.355f,
            0.62f, 0.625f, 0.85f, 0.855f, 1f, 0.99f
        ),
        blue = channelCurve(
            0f, 0.0f, 0.12f, 0.145f, 0.35f, 0.36f,
            0.62f, 0.615f, 0.85f, 0.83f, 1f, 0.965f
        )
    )

    private val CHANNELS_CU = ChannelToneCurves(
        red = channelCurve(
            0f, 0.0f, 0.14f, 0.135f, 0.40f, 0.405f,
            0.66f, 0.68f, 1f, 1f
        ),
        green = channelCurve(
            0f, 0.0f, 0.16f, 0.168f, 0.40f, 0.404f,
            0.66f, 0.668f, 1f, 0.992f
        ),
        blue = channelCurve(
            0f, 0.0f, 0.14f, 0.158f, 0.40f, 0.404f,
            0.66f, 0.65f, 1f, 0.972f
        )
    )

    private val CHANNELS_NN = ChannelToneCurves(
        red = channelCurve(
            0f, 0.0f, 0.12f, 0.108f, 0.34f, 0.35f,
            0.60f, 0.63f, 0.84f, 0.875f, 1f, 1f
        ),
        green = channelCurve(
            0f, 0.0f, 0.14f, 0.148f, 0.34f, 0.345f,
            0.60f, 0.605f, 0.84f, 0.85f, 1f, 0.99f
        ),
        blue = channelCurve(
            0f, 0.0f, 0.12f, 0.15f, 0.34f, 0.355f,
            0.60f, 0.60f, 0.84f, 0.815f, 1f, 0.95f
        )
    )

    private val SPECS: List<Spec> = listOf(
        Spec(
            slug = "fxn_r_classic_neg",
            name = "FXN R · 经典负片",
            description = "Dazz 最火的富士平替（FXN R / Classic Negative）。抬黑灰底、柔和漂白高光，青色阴影配暖色高光，克制不发荧光的怀旧绿，中等颗粒。适合街拍、美食、绿植与日常随拍。",
            master = MASTER_NC,
            channels = CHANNELS_NC,
            adjustments = EditAdjustments(
                vibrance = 3f,
                grain = 18f
            )
        ),
        Spec(
            slug = "classic_u_superia400",
            name = "Classic U · 暖调胶片",
            description = "Dazz Classic U，对标 Fujicolor Superia 400 的日常暖调。低对比、阴影柔和、整体偏暖，全天候百搭，随手拍即有胶片氛围。",
            master = MASTER_CU,
            channels = CHANNELS_CU,
            adjustments = EditAdjustments(
                temperature = 5f,
                vibrance = 2f,
                grain = 12f
            )
        ),
        Spec(
            slug = "nostalgic_neg",
            name = "Nostalgic Neg · 怀旧负片",
            description = "富士 Nostalgic Neg 风格：琥珀色高光 + 深蓝阴影 + 家庭相册式暖调，比经典负片更暖更柔。适合旅行、聚会与人像记忆感画面。",
            master = MASTER_NN,
            channels = CHANNELS_NN,
            adjustments = EditAdjustments(
                temperature = 7f,
                vibrance = 2f,
                grain = 14f
            )
        )
    )

    fun all(): List<Preset> = SPECS.map { it.toPreset() }

    private fun Spec.toPreset(): Preset {
        val control = PictureControl(
            format = PictureControlFormat.NP3,
            version = "dazz2",
            name = name,
            baseProfile = BaseProfiles.NEUTRAL,
            baseProfileName = BaseProfiles.nameOf(BaseProfiles.NEUTRAL),
            curve = master,
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
            channelCurves = channels,
            adjustments = adjustments
        )
    }
}

/** 与官方云创 / nikonpc 等内置一起播种进预置列表。 */
fun builtInDazzFujiPresets(): List<Preset> = DazzFujiPresets.all()
