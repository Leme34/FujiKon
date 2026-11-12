package com.fujikon.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
}
