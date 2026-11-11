package com.fujikon.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.fujikon.app.data.EditAdjustments
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.ui.components.AdjustmentsSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 预设强度控件的 UI 契约：默认页首屏可见、默认满强度、「无」预设时隐藏。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresetStrengthPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setPanel(enabled: Boolean = true) {
        rule.setContent {
            AdjustmentsSheet(
                presetName = "测试预设",
                initialAdjustments = EditAdjustments.Neutral,
                initialToneCurve = ToneCurve.linear(),
                presetToneCurve = ToneCurve.linear(),
                baseCurve = emptyList(),
                rendering = false,
                saving = false,
                presetStrengthEnabled = enabled,
                onPreview = { _, _ -> },
                onToneCurvePreview = {},
                onClearToneCurve = {},
                onClearAllCurves = {},
                onSave = {},
                onDismiss = {}
            )
        }
    }

    @Test
    fun strengthControlIsVisibleWithFullLookByDefault() {
        setPanel()
        // The default tab is LIGHT, so the control must be on the first screen.
        // Exact match for the label: the help sentence also mentions it.
        rule.onNodeWithText("预设强度").assertExists()
        // Exact match: the help text also contains "100%", so substring would
        // match two nodes and onNodeWithText would fail.
        rule.onNodeWithText("100%").assertExists()
    }

    @Test
    fun strengthControlIsHiddenWhenNoPresetIsSelected() {
        setPanel(enabled = false)
        rule.onNodeWithText("预设强度", substring = true).assertDoesNotExist()
    }
}
