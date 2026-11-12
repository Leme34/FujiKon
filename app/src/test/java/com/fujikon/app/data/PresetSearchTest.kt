package com.fujikon.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「我的预设」与「选择预设列表」共用搜索逻辑的纯 JVM 回归测试。 */
class PresetSearchTest {

    private fun preset(
        name: String,
        description: String? = null,
        note: String = "",
        sourceName: String = "",
        baseProfileName: String = "",
        format: String = "NP3"
    ): Preset = Preset.none().copy(
        name = name,
        description = description,
        note = note,
        sourceName = sourceName,
        baseProfileName = baseProfileName,
        format = format
    )

    @Test
    fun emptyOrBlankQueryMatchesEverything() {
        val p = preset("通透胶片")
        assertTrue(PresetSearch.matches(p, ""))
        assertTrue(PresetSearch.matches(p, "   "))
        assertEquals(listOf(p), PresetSearch.filter(listOf(p), ""))
        assertEquals(listOf(p), PresetSearch.filter(listOf(p), "  "))
    }

    @Test
    fun matchesNameCaseInsensitively() {
        val p = preset("Vitality Film Pman")
        assertTrue(PresetSearch.matches(p, "vitality"))
        assertTrue(PresetSearch.matches(p, "FILM"))
        assertTrue(PresetSearch.matches(p, "pman"))
        assertFalse(PresetSearch.matches(p, "portra"))
    }

    @Test
    fun matchesChineseNameAndDescription() {
        val p = preset(
            name = "日系清透",
            description = "低饱和、提暗部、压高光的胶片感方案"
        )
        assertTrue("中文名称可搜", PresetSearch.matches(p, "日系"))
        assertTrue("中文说明可搜", PresetSearch.matches(p, "胶片感"))
        assertTrue("说明里的任意片段可搜", PresetSearch.matches(p, "压高光"))
        assertFalse(PresetSearch.matches(p, "浓郁"))
    }

    @Test
    fun searchesNoteSourceBaseProfileAndFormat() {
        val p = preset(
            name = "A",
            note = "婚礼跟拍备用",
            sourceName = "尼康云创官方 · 真实 NP3",
            baseProfileName = "Flexible Color",
            format = "LUT3D"
        )
        assertTrue(PresetSearch.matches(p, "婚礼跟拍"))
        assertTrue(PresetSearch.matches(p, "云创官方"))
        assertTrue(PresetSearch.matches(p, "flexible"))
        assertTrue(PresetSearch.matches(p, "lut3d"))
    }

    @Test
    fun multipleTermsAreAndedRegardlessOfOrder() {
        val p = preset(name = "Warm Portrait", description = "人像暖调 柔和肤色")
        assertTrue(PresetSearch.matches(p, "warm 人像"))
        assertTrue(PresetSearch.matches(p, "人像 warm"))
        assertFalse(PresetSearch.matches(p, "warm 冷调"))
    }

    @Test
    fun filterKeepsOrderAndDropsNonMatches() {
        val a = preset("Agfa Optima")
        val b = preset("通透胶片", description = "日系")
        val c = preset("Kodak Portra 160NC")
        assertEquals(listOf(a, b, c), PresetSearch.filter(listOf(a, b, c), ""))
        assertEquals(listOf(b), PresetSearch.filter(listOf(a, b, c), "日系"))
        assertEquals(listOf(a, c), PresetSearch.filter(listOf(a, b, c), "a"))
        assertTrue(PresetSearch.filter(listOf(a, b, c), "不存在").isEmpty())
    }
}
