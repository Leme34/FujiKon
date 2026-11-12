package com.fujikon.app.nic

import android.content.Context
import com.fujikon.app.data.Preset
import com.fujikon.app.ncp.PictureControlParser

/**
 * 用户指定的 4 个精选云创官方真实方案，是 App 唯一预置的云创滤镜。
 *
 * 1.6.43 曾把资料库附件「云创色彩预设.zip」中的 107 个真实 NP3 + 9 个 3D LUT
 * 全部播种为内置预置；1.6.44 起按用户要求收敛为下面 4 个，其余云创滤镜由
 * 用户在「尼康云创官方滤镜」自行下载后通过在线导入 / 本地 NP3 导入加入，
 * 不再随 App 预置。
 *
 * 精选方案仍由 [PictureControlParser] 从官方 NP3 原始二进制零拟合解析：
 *  - 256 点真实色调曲线（Nature Links 含）、官方参数（±100 口径）、
 *    8 分区 Color Blender、三分区 Color Grading 全部为文件内真实数据；
 *  - 无独立曲线的 3 个方案是官方「以参数 + 分区调色定义影调」的方案，曲线
 *    保持 null，由渲染器按官方参数施加，绝不用合成曲线顶替。
 *
 * [CloudRecipeCatalog] / [CloudLutCatalog] 与对应 assets 仍完整保留：它们既是
 * 在线云创导入时 [OfficialCloudLooks] 官方真实数据的来源，也是用户自行导入
 * 时的能力底座，只是不再进入预置列表。3D LUT 渲染能力由
 * [com.fujikon.app.render.CubeLut] 承载，[Preset.cubeLutAsset] 依旧持久化。
 *
 * [PRESET_CONTENT_HASHES] 供 [com.fujikon.app.data.PresetRepository] 在覆盖
 * 安装时把 1.6.43 播种的、现已退役的官方预置清理掉（只清理 isSample 条目，
 * 用户自行导入或调整出的副本不受影响）。
 */
object OfficialCloudPresets {

    const val RECIPE_SOURCE = CloudRecipeCatalog.SOURCE_NAME

    private const val RECIPE_HASH = "cloudrecipe:"

    /** 用户指定的 4 个精选方案，顺序与需求一致。 */
    private val PRESET_RECIPE_IDS = listOf(
        "recipe_103", // vitality film pman
        "recipe_074", // nature links
        "recipe_098", // textured docmntry
        "recipe_065"  // ht harmony hana
    )

    /** 当前仍应预置的官方方案 contentHash，用于覆盖安装时的退役清理。 */
    val PRESET_CONTENT_HASHES: Set<String> =
        PRESET_RECIPE_IDS.map { RECIPE_HASH + it }.toSet()

    @Volatile
    private var cached: List<Preset>? = null

    /** 4 个精选官方真实方案；解析失败的 assets 被跳过。 */
    fun all(context: Context): List<Preset> {
        cached?.let { return it }
        val result = synchronized(this) {
            cached?.let { return@synchronized it }
            buildAll(context.applicationContext).also { cached = it }
        }
        return result
    }

    private fun buildAll(context: Context): List<Preset> =
        PRESET_RECIPE_IDS.mapNotNull { id ->
            val spec = CloudRecipeCatalog.ALL.firstOrNull { it.id == id }
                ?: return@mapNotNull null
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

    /** 仅测试用：清空进程内缓存。 */
    fun clearCache() {
        cached = null
    }
}
