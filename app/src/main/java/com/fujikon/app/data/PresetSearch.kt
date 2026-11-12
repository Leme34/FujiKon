package com.fujikon.app.data

/**
 * 「我的预设」与「选择预设列表」共用的搜索匹配逻辑（纯 JVM，便于单测）。
 *
 * 支持按名称、说明内容、备注、来源名、基准 Picture Control 名搜索；大小写不敏感、
 * 中文直接可用；多个关键词以空白分隔，需全部命中（AND），顺序不限。
 */
object PresetSearch {

    /** 空查询视为「不过滤」，全部命中。 */
    fun matches(preset: Preset, query: String): Boolean {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val haystack = buildList {
            add(preset.name)
            preset.description?.let { add(it) }
            add(preset.note)
            add(preset.sourceName)
            add(preset.baseProfileName)
            add(preset.format)
        }.joinToString("\n").lowercase()
        return terms.all { term -> haystack.contains(term.lowercase()) }
    }

    /** 过滤预设列表；空查询原样返回。 */
    fun filter(presets: List<Preset>, query: String): List<Preset> =
        if (query.isBlank()) presets else presets.filter { matches(it, query) }
}
