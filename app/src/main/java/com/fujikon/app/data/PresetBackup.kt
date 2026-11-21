package com.fujikon.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * 预设列表备份文件的自包含序列化格式。
 *
 * 一个备份文件就是一份 JSON，包含：
 *  - [SCHEMA] 版本号，用于向前兼容校验；
 *  - 导出时间、导出时的 App 版本；
 *  - counts：全部 / 未删除 / 已收藏 / 已删除 的条目数，作为完整性指纹；
 *  - presets：**全量**预设（含软删除条目与 favorite / note / importedAt 等用户态），
 *    每条直接用 [Preset.toJson] 编码，渲染参数、3D LUT 标识等全部保留；
 *  - binaries：每个带源文件（NP3/NCP/NP2）的预设原二进制的 Base64，按 fileName 索引，
 *    这样重装后连原始二进制一起还原，[PresetRepository.fileFor] 仍可命中。
 *
 * 恢复走「全量替换」而非合并：备份时点是什么样，恢复后 [PresetRepository.presets]
 * （未删除）、[PresetRepository.deletedPresets]（已删除）以及它们的收藏子集就一模一样。
 *
 * 这里只做纯数据编解码，不碰 Android 文件 IO，便于 JVM 单测往返验证。
 */
object PresetBackup {
    const val SCHEMA = 1

    /** 导入前展示给用户看的备份摘要。 */
    data class Info(
        val schema: Int,
        val exportedAt: Long,
        val appVersion: String,
        val countAll: Int,
        val countActive: Int,
        val countFavorite: Int,
        val countDeleted: Int
    ) {
        val isValid: Boolean get() = schema == SCHEMA && countAll >= 0
    }

    /** 解码后的完整负载：预设 + 原二进制（fileName -> 原始字节）。 */
    data class Payload(
        val presets: List<Preset>,
        val binaries: Map<String, ByteArray>,
        val info: Info
    )

    /** 将全量预设与其二进制编码为备份 JSON 字符串。 */
    fun encode(
        presets: List<Preset>,
        binaries: Map<String, ByteArray>,
        appVersion: String,
        exportedAt: Long = System.currentTimeMillis()
    ): String {
        val presetsArray = JSONArray()
        presets.forEach { presetsArray.put(it.toJson()) }

        val binariesObj = JSONObject()
        binaries.forEach { (name, bytes) ->
            binariesObj.put(name, Base64.getEncoder().encodeToString(bytes))
        }

        return JSONObject()
            .put("schema", SCHEMA)
            .put("appVersion", appVersion)
            .put("exportedAt", exportedAt)
            .put(
                "counts", JSONObject()
                    .put("all", presets.size)
                    .put("active", presets.count { !it.deleted })
                    .put("favorite", presets.count { !it.deleted && it.favorite })
                    .put("deleted", presets.count { it.deleted })
            )
            .put("presets", presetsArray)
            .put("binaries", binariesObj)
            .toString()
    }

    /**
     * 只解析摘要，不解析全部预设，供导入前预览。非法文件抛 [IllegalArgumentException]。
     */
    fun inspect(json: String): Info {
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw IllegalArgumentException("不是有效的备份文件") }
        val counts = root.optJSONObject("counts")
        return Info(
            schema = root.optInt("schema", -1),
            exportedAt = root.optLong("exportedAt", 0L),
            appVersion = root.optString("appVersion", ""),
            countAll = counts?.optInt("all", -1) ?: -1,
            countActive = counts?.optInt("active", -1) ?: -1,
            countFavorite = counts?.optInt("favorite", -1) ?: -1,
            countDeleted = counts?.optInt("deleted", -1) ?: -1
        )
    }

    /** 解析完整负载。非法 schema / 缺失预设数组会抛 [IllegalArgumentException]。 */
    fun decode(json: String): Payload {
        val root = runCatching { JSONObject(json) }
            .getOrElse { throw IllegalArgumentException("不是有效的备份文件") }
        val info = inspect(json)
        if (info.schema != SCHEMA) {
            throw IllegalArgumentException("不支持的备份版本（${info.schema}），当前支持 $SCHEMA")
        }
        val array = root.optJSONArray("presets")
            ?: throw IllegalArgumentException("备份文件缺少预设数据")
        val presets = (0 until array.length()).mapNotNull { i ->
            runCatching { Preset.fromJson(array.getJSONObject(i)) }.getOrNull()
        }
        val binariesObj = root.optJSONObject("binaries")
        val binaries = HashMap<String, ByteArray>()
        if (binariesObj != null) {
            for (name in binariesObj.keys()) {
                val decoded = runCatching {
                    Base64.getDecoder().decode(binariesObj.getString(name))
                }.getOrNull()
                if (decoded != null) binaries[name] = decoded
            }
        }
        return Payload(presets, binaries, info)
    }
}
