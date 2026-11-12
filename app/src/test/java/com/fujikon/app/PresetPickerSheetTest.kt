package com.fujikon.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.fujikon.app.data.Preset
import com.fujikon.app.ui.components.PresetPickerSheet
import com.fujikon.app.ui.theme.FujiKonTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PresetPickerSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun noneOptionIsShownAndSelectable() {
        var chosen: Preset? = null
        composeRule.setContent {
            FujiKonTheme {
                PresetPickerSheet(
                    presets = emptyList(),
                    selectedId = null,
                    onSelect = { chosen = it },
                    onDismiss = {}
                )
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("无（保留原图）")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("无（保留原图）") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(Preset.NONE_ID, chosen?.id)
    }

    @Test
    fun detailLinkOpensFullInfoWithoutSelectingThePreset() {
        val preset = Preset.none().copy(
            id = "preset-detail",
            name = "云端胶片",
            format = "NP3",
            baseProfileName = "Flexible Color",
            description = "这是一段用于验证详情入口的完整预设说明。".repeat(4)
        )
        var opened: Preset? = null
        var selected: Preset? = null
        composeRule.setContent {
            FujiKonTheme {
                PresetPickerSheet(
                    presets = listOf(preset),
                    selectedId = null,
                    onSelect = { selected = it },
                    onDismiss = {},
                    onOpenDetail = { opened = it }
                )
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("详情 ›").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("详情 ›") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals("preset-detail", opened?.id)
        assertNull(selected)
    }

    @Test
    fun searchFiltersOptionsButAlwaysKeepsNoneOption() {
        val alpha = Preset.none().copy(
            id = "alpha",
            name = "日系清透",
            description = "低饱和胶片感"
        )
        val beta = Preset.none().copy(
            id = "beta",
            name = "Kodak Portra 160NC",
            description = "人像暖调"
        )
        composeRule.setContent {
            FujiKonTheme {
                PresetPickerSheet(
                    presets = listOf(alpha, beta),
                    selectedId = null,
                    onSelect = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("日系清透").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Kodak Portra 160NC").fetchSemanticsNodes().isNotEmpty()
        }

        // 按说明内容搜索：只命中 alpha，beta 消失，但「无（保留原图）」始终保留。
        composeRule.onNodeWithTag("preset_picker_search_field")
            .performTextInput("胶片感")
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Kodak Portra 160NC").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            "「无（保留原图）」必须在搜索时始终保留",
            composeRule.onAllNodesWithText("无（保留原图）").fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            "按说明命中的预设应保留",
            composeRule.onAllNodesWithText("日系清透").fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun favoriteFilterShowsOnlyFavoritesButKeepsNoneOption() {
        val fav = Preset.none().copy(
            id = "fav",
            name = "收藏的日系",
            favorite = true,
            description = "低饱和胶片感"
        )
        val normal = Preset.none().copy(id = "normal", name = "普通预设")
        composeRule.setContent {
            FujiKonTheme {
                PresetPickerSheet(
                    presets = listOf(fav, normal),
                    selectedId = null,
                    onSelect = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("普通预设").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("preset_picker_favorite_filter").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("普通预设").fetchSemanticsNodes().isEmpty()
        }

        assertTrue("已收藏的预设应保留",
            composeRule.onAllNodesWithText("收藏的日系").fetchSemanticsNodes().isNotEmpty())
        assertTrue("「无（保留原图）」在收藏筛选下也必须保留",
            composeRule.onAllNodesWithText("无（保留原图）").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun favoriteFilterStacksWithSearch() {
        val favA = Preset.none().copy(
            id = "fav-a", name = "日系清透", favorite = true, description = "低饱和胶片感"
        )
        val favB = Preset.none().copy(
            id = "fav-b", name = "人像暖调", favorite = true, description = "柔和肤色"
        )
        val normal = Preset.none().copy(id = "n", name = "通透胶片", description = "胶片感")
        composeRule.setContent {
            FujiKonTheme {
                PresetPickerSheet(
                    presets = listOf(favA, favB, normal),
                    selectedId = null,
                    onSelect = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("preset_picker_favorite_filter").performClick()
        composeRule.onNodeWithTag("preset_picker_search_field").performTextInput("胶片感")
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("人像暖调").fetchSemanticsNodes().isEmpty()
        }
        assertTrue("收藏 + 搜索应只留下同时命中的收藏项",
            composeRule.onAllNodesWithText("日系清透").fetchSemanticsNodes().isNotEmpty())
        assertTrue("非收藏项即使匹配搜索也不能出现",
            composeRule.onAllNodesWithText("通透胶片").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("无（保留原图）").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun opensScrolledToCurrentlySelectedPreset() {
        // 一长串预设，选中的放在靠后位置，验证打开时自动定位到它（而非停在首屏）。
        val presets = (0 until 30).map { i ->
            Preset.none().copy(id = "p$i", name = "预设 $i", description = "说明 $i")
        }
        val selectedId = presets[20].id // 第 21 项（含「无」），远超首屏可见范围
        composeRule.setContent {
            FujiKonTheme {
                PresetPickerSheet(
                    presets = presets,
                    selectedId = selectedId,
                    onSelect = {},
                    onDismiss = {}
                )
            }
        }

        // 未做任何手动滚动，靠后的选中项必须已被自动带进可视区域并渲染。
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("预设 20").fetchSemanticsNodes().isNotEmpty()
        }
        // 同时未选中的靠前项（滚出视口上方）不应被渲染，证明列表确实滚动到了选中位置。
        assertTrue(
            "列表应已滚动到选中项，而非把全部项都渲染出来",
            composeRule.onAllNodesWithText("预设 5").fetchSemanticsNodes().isEmpty()
        )
    }
}
