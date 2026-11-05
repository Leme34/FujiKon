package com.fujikon.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import com.fujikon.app.data.Preset
import com.fujikon.app.ui.components.PresetPickerSheet
import com.fujikon.app.ui.theme.FujiKonTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
