package com.fujikon.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.ui.components.AdjustmentsSheet
import com.fujikon.app.ui.components.EditorBottomPanel
import com.fujikon.app.ui.components.PresetPickerSheet
import com.fujikon.app.ui.theme.FujiKonTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the reworked requirement: when a bottom sub-page (preset / adjust /
 * curve / export / history) is open, the editor's TOP action bar (preset
 * switch / undo / redo / version history) stays uncovered and directly
 * clickable. Concretely, the sub-page overlay must not install a full-screen
 * scrim or any pointerInput above the panel, so real touches pass through to
 * the TopAppBar underneath.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class EditorTopBarReachableTest {

    @get:Rule
    val rule = createComposeRule()

    /** A stand-in for the editor's Scaffold + TopAppBar sitting below a panel. */
    @Composable
    private fun topBar(
        undo: () -> Unit,
        history: () -> Unit
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("top_undo")
                    .clickable { undo() }
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .height(56.dp)
                    .fillMaxWidth(0.25f)
                    .testTag("top_history")
                    .clickable { history() }
            )
        }
    }

    @Test
    fun noScrim_topBarActionsReceiveRealTouches() {
        var undo by mutableIntStateOf(0)
        var history by mutableIntStateOf(0)
        rule.setContent {
            FujiKonTheme {
                Box(Modifier.fillMaxSize()) {
                    topBar(undo = { undo++ }, history = { history++ })
                    // Drawn last (on top), just like EditorScreen's overlays.
                    EditorBottomPanel(onDismiss = {}, testTag = "panel") {
                        Text("面板内容", Modifier.testTag("panel_content"))
                    }
                }
            }
        }
        rule.onNodeWithTag("panel").assertExists()
        rule.onNodeWithTag("panel_host").assertExists()
        rule.onNodeWithTag("panel_content").assertExists()

        // Real injected touches must land on the underlying top bar, not be
        // swallowed by a full-screen scrim.
        rule.onNodeWithTag("top_undo").performTouchInput { down(center); up() }
        rule.onNodeWithTag("top_history").performTouchInput { down(center); up() }
        assertEquals("子页面打开时顶部「后退」仍可点", 1, undo)
        assertEquals("子页面打开时顶部「版本历史」仍可点", 1, history)
    }

    /** Panel-internal controls must remain interactive (no blanket gesture layer). */
    @Test
    fun panelContent_isClickable() {
        var taps by mutableIntStateOf(0)
        rule.setContent {
            FujiKonTheme {
                EditorBottomPanel(onDismiss = {}, testTag = "panel") {
                    Text(
                        "面板内容",
                        Modifier.testTag("panel_content").clickable { taps++ }
                    )
                }
            }
        }
        rule.onNodeWithTag("panel_content").performClick()
        assertEquals("面板内部控件应可点击", 1, taps)
    }

    /** Control: a full-screen scrim WOULD block the top bar — proves the test. */
    @Test
    fun scrimControl_fullScreenScrimBlocksTopBar() {
        var undo by mutableIntStateOf(0)
        rule.setContent {
            FujiKonTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("top_undo")
                            .clickable { undo++ }
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) { detectTapGestures { } }
                            .testTag("scrim")
                    )
                }
            }
        }
        rule.onNodeWithTag("top_undo").performTouchInput { down(center); up() }
        assertEquals("全屏 scrim 会拦截顶部点击（对照组）", 0, undo)
    }

    @Test
    fun adjustmentsSheet_hasNoScrim_andLeavesTopBarReachable() {
        var undo by mutableIntStateOf(0)
        rule.setContent {
            FujiKonTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("top_undo")
                            .clickable { undo++ }
                    )
                    AdjustmentsSheet(
                        presetName = "测试预设",
                        initialAdjustments = EditAdjustments.Neutral,
                        initialToneCurve = ToneCurve.linear(),
                        presetToneCurve = ToneCurve.linear(),
                        baseCurve = emptyList(),
                        rendering = false,
                        saving = false,
                        onPreview = { _, _ -> },
                        onToneCurvePreview = {},
                        onClearToneCurve = {},
                        onClearAllCurves = {},
                        onSave = {},
                        onDismiss = {}
                    )
                }
            }
        }
        rule.onNodeWithTag("adjustments_panel").assertExists()
        rule.onNodeWithTag("adjustments_host").assertExists()
        // The tap-eating scrim must be gone.
        rule.onNodeWithTag("adjustments_scrim").assertDoesNotExist()
        rule.onNodeWithTag("top_undo").performTouchInput { down(center); up() }
        assertEquals("调节页打开时顶部操作栏仍可点", 1, undo)
    }

    @Test
    fun presetPicker_noScrim_keepsContent_andExplicitClose() {
        var dismissed = 0
        rule.setContent {
            FujiKonTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("top_undo")
                            .clickable {}
                    )
                    PresetPickerSheet(
                        presets = emptyList(),
                        selectedId = null,
                        onSelect = {},
                        onDismiss = { dismissed++ }
                    )
                }
            }
        }
        rule.onNodeWithTag("preset_picker_panel").assertExists()
        rule.onNodeWithTag("preset_picker_panel_host").assertExists()
        rule.onNodeWithText("选择预设").assertExists()
        rule.onNodeWithTag("preset_picker_close").assertExists()
        // No full-screen scrim anywhere in this page.
        rule.onAllNodes(hasTestTag("preset_picker_scrim"))
            .fetchSemanticsNodes().let { assertEquals(0, it.size) }
        rule.onNodeWithTag("preset_picker_close").performClick()
        assertEquals("面板内关闭按钮应触发关闭", 1, dismissed)
    }
}
