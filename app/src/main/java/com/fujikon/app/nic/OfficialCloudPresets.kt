package com.fujikon.app.nic

import android.content.Context
import com.fujikon.app.data.Preset
import com.fujikon.app.ncp.PictureControlParser

/**
 * 把资料库附件「云创色彩预设.zip」中的 107 个真实 NP3 + 9 个 .cube 3D LUT，
 * **逐一**落地为 App 内置预设。
 *
 * 这是「云创滤镜改用官方真实方案」的主体：此前 App 的云创滤镜用在线接口返回的
 * 简化参数 + [CloudLookCalibration] 的统计拟合假 LUT + 合成 S 曲线，观感与官方
 * 效果图存在巨大差异。这里直接从官方 NP3 原始二进制零拟合解析：
 *  - 256 点真实色调曲线（31 个文件有）、官方参数（±100 口径）、8 分区 Color
 *    Blender、三分区 Color Grading 全部为文件内真实数据；
 *  - 无独立曲线的 76 个文件是官方「以参数 + 分区调色定义影调」的方案，曲线保持
 *    null，由渲染器按官方参数施加，绝不用合成曲线顶替。
 *
 * 9 个 LUT 方案用 [com.fujikon.app.render.CubeLut] 做亮度锚定的相对变换，通过
 * [Preset.cubeLutAsset] 指向 assets 里的紧凑二进制，渲染时按需加载。
 *
 * 结果按 contentHash 幂等，覆盖安装时由 PresetRepository 自动补齐新增方案；
 * 缓存避免每次播种重复解码 107 个 assets。
 */
object OfficialCloudPresets {

    const val RECIPE_SOURCE = CloudRecipeCatalog.SOURCE_NAME
    const val LUT_SOURCE = CloudLutCatalog.SOURCE_NAME

    private const val RECIPE_HASH = "cloudrecipe:"
    private const val LUT_HASH = "cloudlut:"

    @Volatile
    private var cached: List<Preset>? = null

    /** 全部 116 个官方真实方案（107 NP3 + 9 LUT）。解析失败的 assets 被跳过。 */
    fun all(context: Context): List<Preset> {
        cached?.let { return it }
        val result = synchronized(this) {
            cached?.let { return@synchronized it }
            buildAll(context.applicationContext).also { cached = it }
        }
        return result
    }

    private fun buildAll(context: Context): List<Preset> {
        val recipes = CloudRecipeCatalog.ALL.mapNotNull { spec ->
            val bytes = runCatching {
                context.assets.open(spec.asset).use { it.readBytes() }
            }.getOrNull() ?: return@mapNotNull null
            val control = runCatching {
                PictureControlParser.parse(bytes, spec.name)
            }.getOrNull() ?: return@mapNotNull null
            Preset.from(
                control = control,
                id = "",
                fileName = "",
                fileSize = bytes.size,
                isSample = true,
                contentHash = RECIPE_HASH + spec.id,
                sourceName = RECIPE_SOURCE
            ).copy(name = spec.name, description = spec.description)
        }
        val luts = CloudLutCatalog.ALL.map { spec ->
            // 中性基底：1D 曲线 / 参数 / 分区全部恒等，只让 3D LUT 分支生效。
            Preset.none().copy(
                id = "",
                name = spec.name,
                format = "LUT3D",
                version = "1.0",
                sourceName = LUT_SOURCE,
                baseProfileName = "${spec.size}³ 3D LUT",
                description = spec.description,
                isSample = true,
                contentHash = LUT_HASH + spec.id,
                cubeLutAsset = spec.asset
            )
        }
        return recipes + luts
    }

    /** 仅测试用：清空进程内缓存。 */
    fun clearCache() {
        cached = null
    }
}
