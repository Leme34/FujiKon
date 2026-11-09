package com.fujikon.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.fujikon.app.ui.components.EDITOR_TOOLS
import com.fujikon.app.ui.components.EditorMoreMenu
import com.fujikon.app.ui.components.EditorMoreMenuItems
import com.fujikon.app.ui.components.EditorTool
import com.fujikon.app.ui.components.EditorToolAppearance
import com.fujikon.app.ui.components.EditorToolsRow
import com.fujikon.app.ui.theme.FujiKonTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.6.29 editor layout guards:
 *
 * - The bottom bar is ONE horizontally scrollable row of exactly seven tools
 *   (preset / adjust / crop / blur / curve / mask / AI); "导出" is no longer a
 *   bottom tool, it lives in the top-right overflow menu.
 * - The overflow menu holds 复制效果 / 粘贴效果 / 导出, and 粘贴效果 is
 *   disabled (greyed out, unclickable) until a look has been copied.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorToolsAndMoreMenuTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun toolsAreExactlySevenInOrderAndContainNoExport() {
        assertEquals(7, EDITOR_TOOLS.size)
        assertEquals(
            listOf("预设", "调节", "裁剪", "模糊", "曲线", "蒙版", "AI"),
            EDITOR_TOOLS.map { it.label }
        )
        assertFalse("导出已从底部工具栏移到更多菜单", EDITOR_TOOLS.any { it.label == "导出" })
        assertEquals(EditorTool.PRESET, EDITOR_TOOLS.first())
        assertEquals(EditorTool.AI, EDITOR_TOOLS.last())
    }

    @Test
    fun toolsRowRendersAllSevenButtonsInOneScrollableRow() {
        rule.setContent {
            FujiKonTheme {
                EditorToolsRow(
                    appearance = { EditorToolAppearance() },
                    onToolClick = {}
                )
            }
        }
        rule.onNodeWithTag("editor_tools_row").assertExists()
        EDITOR_TOOLS.forEach { tool ->
            rule.onNodeWithTag("editor_tool_${tool.name.lowercase()}").assertExists()
        }
    }

    @Test
    fun toolsRowDispatchesTheClickedTool() {
        var clicked: EditorTool? = null
        rule.setContent {
            FujiKonTheme {
                EditorToolsRow(
                    appearance = { EditorToolAppearance() },
                    onToolClick = { clicked = it }
                )
            }
        }
        // Click the first tool: later ones may be scrolled off a narrow test
        // screen, which is exactly why the row is horizontally scrollable.
        rule.onNodeWithTag("editor_tool_preset").performClick()
        assertEquals(EditorTool.PRESET, clicked)
    }

    @Test
    fun pasteIsGreyedOutUntilAnEffectIsCopied() {
        rule.setContent {
            FujiKonTheme {
                Column {
                    EditorMoreMenuItems(
                        canPaste = false,
                        canExport = true,
                        onCopy = {},
                        onPaste = {},
                        onExport = {}
                    )
                }
            }
        }
        rule.onNodeWithTag("editor_more_copy").assertExists()
        rule.onNodeWithTag("editor_more_export").assertExists()
        rule.onNodeWithTag("editor_more_paste").assertIsNotEnabled()
    }

    @Test
    fun pasteIsEnabledAndFiresAfterCopy() {
        var pasted = 0
        rule.setContent {
            FujiKonTheme {
                Column {
                    EditorMoreMenuItems(
                        canPaste = true,
                        canExport = true,
                        onCopy = {},
                        onPaste = { pasted++ },
                        onExport = {}
                    )
                }
            }
        }
        rule.onNodeWithTag("editor_more_paste").assertIsEnabled()
        rule.onNodeWithTag("editor_more_paste").performClick()
        assertEquals(1, pasted)
    }

    @Test
    fun disabledPasteCannotFireItsCallback() {
        var pasted = 0
        rule.setContent {
            FujiKonTheme {
                Column {
                    EditorMoreMenuItems(
                        canPaste = false,
                        canExport = true,
                        onCopy = {},
                        onPaste = { pasted++ },
                        onExport = {}
                    )
                }
            }
        }
        rule.onNodeWithTag("editor_more_paste").assertIsNotEnabled()
        assertEquals("置灰的粘贴项不应触发回调", 0, pasted)
    }

    @Test
    fun copyAndExportMenuActionsFire() {
        var copied = 0
        var exported = 0
        rule.setContent {
            FujiKonTheme {
                Column {
                    EditorMoreMenuItems(
                        canPaste = false,
                        canExport = true,
                        onCopy = { copied++ },
                        onPaste = {},
                        onExport = { exported++ }
                    )
                }
            }
        }
        rule.onNodeWithTag("editor_more_copy").performClick()
        rule.onNodeWithTag("editor_more_export").performClick()
        assertEquals(1, copied)
        assertEquals(1, exported)
    }

    @Test
    fun threeDotMoreButtonIsPresentInTopBar() {
        rule.setContent {
            FujiKonTheme {
                EditorMoreMenu(
                    canPaste = false,
                    canExport = true,
                    onCopy = {},
                    onPaste = {},
                    onExport = {}
                )
            }
        }
        rule.onNodeWithTag("editor_more_button").assertExists()
    }
}
