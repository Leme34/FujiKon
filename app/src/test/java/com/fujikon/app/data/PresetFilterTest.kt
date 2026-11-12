package com.fujikon.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首页三种视图 + 选择预设列表收藏筛选的纯 JVM 回归测试。 */
class PresetFilterTest {

    private fun preset(
        id: String,
        name: String,
        favorite: Boolean = false,
        deleted: Boolean = false,
        description: String? = null
    ): Preset = Preset.none().copy(
        id = id,
        name = name,
        favorite = favorite,
        deleted = deleted,
        description = description
    )

    private val active = listOf(
        preset("a", "日系清透", favorite = true, description = "低饱和胶片感"),
        preset("b", "Kodak Portra 160NC", description = "人像暖调"),
        preset("c", "通透胶片", favorite = true, description = "清透有质感")
    )
    private val deleted = listOf(
        preset("d", "旧的人像预设", favorite = true, deleted = true),
        preset("e", "Negative Cyan", deleted = true)
    )

    @Test
    fun allViewShowsEveryActivePresetAndIgnoresDeleted() {
        assertEquals(listOf("a", "b", "c"),
            PresetFilter.forHome(active, deleted, PresetListFilter.ALL, "").map { it.id })
        assertEquals(0, PresetFilter.forHome(active, deleted, PresetListFilter.ALL, "")
            .count { it.deleted })
    }

    @Test
    fun favoriteViewOnlyKeepsFavoritedActivePresets() {
        val shown = PresetFilter.forHome(active, deleted, PresetListFilter.FAVORITE, "")
        assertEquals(listOf("a", "c"), shown.map { it.id })
        assertTrue("已删除的收藏项不能出现在已收藏视图", shown.none { it.id == "d" })
    }

    @Test
    fun deletedViewUsesTheSeparateDeletedSource() {
        val shown = PresetFilter.forHome(active, deleted, PresetListFilter.DELETED, "")
        assertEquals(listOf("d", "e"), shown.map { it.id })
        assertTrue(shown.all { it.deleted })
    }

    @Test
    fun searchStacksOnTopOfEachFilter() {
        assertEquals(listOf("a"),
            PresetFilter.forHome(active, deleted, PresetListFilter.ALL, "胶片感").map { it.id })
        // 「清透」同时命中 a 的名称（日系清透）与 c 的说明（清透有质感）。
        assertEquals(listOf("a", "c"),
            PresetFilter.forHome(active, deleted, PresetListFilter.FAVORITE, "清透").map { it.id })
        assertTrue(PresetFilter.forHome(active, deleted, PresetListFilter.FAVORITE, "人像").isEmpty())
        assertEquals(listOf("e"),
            PresetFilter.forHome(active, deleted, PresetListFilter.DELETED, "cyan").map { it.id })
    }

    @Test
    fun emptySourcesBehave() {
        assertTrue(PresetFilter.forHome(emptyList(), emptyList(), PresetListFilter.ALL, "").isEmpty())
        assertTrue(PresetFilter.forHome(emptyList(), deleted, PresetListFilter.FAVORITE, "").isEmpty())
        assertEquals(2, PresetFilter.forHome(emptyList(), deleted, PresetListFilter.DELETED, "").size)
    }

    @Test
    fun pickerFavoriteFilterKeepsOnlyFavorites() {
        assertEquals(listOf("a", "b", "c"),
            PresetFilter.forPicker(active, "", onlyFavorite = false).map { it.id })
        assertEquals(listOf("a", "c"),
            PresetFilter.forPicker(active, "", onlyFavorite = true).map { it.id })
        assertEquals(listOf("a"),
            PresetFilter.forPicker(active, "胶片感", onlyFavorite = true).map { it.id })
        assertTrue(PresetFilter.forPicker(active, "人像", onlyFavorite = true).isEmpty())
    }

    @Test
    fun pickerNeverReceivesTheNoneSentinel() {
        // 「无（保留原图）」由调用方插入，不进筛选逻辑，因此永远不会被过滤掉。
        assertTrue(PresetFilter.forPicker(active, "", onlyFavorite = true)
            .none { it.id == Preset.NONE_ID })
    }
}
