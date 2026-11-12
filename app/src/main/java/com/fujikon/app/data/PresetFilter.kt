package com.fujikon.app.data

/**
 * 「我的预设」的三种视图。已删除列表不是 `presets` 的一个过滤条件，
 * 而是 [PresetRepository.deletedPresets] 提供的独立数据源：删除是软删除，
 * 条目仍留在磁盘索引里，因此需要显式切到 DELETED 才看得到。
 */
enum class PresetListFilter { ALL, FAVORITE, DELETED }

/**
 * 预设列表筛选逻辑（纯 JVM，便于单测）。搜索始终叠加在筛选之后，
 * 与 [PresetSearch] 的语义一致：空查询不过滤、多关键词 AND。
 */
object PresetFilter {

    /**
     * 「我的预设」列表：
     * - ALL      -> 全部未删除预设
     * - FAVORITE -> 未删除且已收藏
     * - DELETED  -> 只看已删除列表（可恢复 / 彻底删除）
     */
    fun forHome(
        active: List<Preset>,
        deleted: List<Preset>,
        filter: PresetListFilter,
        query: String
    ): List<Preset> {
        val source = when (filter) {
            PresetListFilter.ALL -> active
            PresetListFilter.FAVORITE -> active.filter { it.favorite }
            PresetListFilter.DELETED -> deleted
        }
        return PresetSearch.filter(source, query)
    }

    /**
     * 「选择预设列表」：收藏筛选与搜索叠加。
     * 「无（保留原图）」是调用方额外插入的哨兵项，不参与这里的过滤，
     * 因此任何筛选条件下都始终保留。
     */
    fun forPicker(
        presets: List<Preset>,
        query: String,
        onlyFavorite: Boolean
    ): List<Preset> {
        val byFavorite = if (onlyFavorite) presets.filter { it.favorite } else presets
        return PresetSearch.filter(byFavorite, query)
    }
}
