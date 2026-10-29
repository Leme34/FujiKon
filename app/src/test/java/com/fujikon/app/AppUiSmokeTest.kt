package com.fujikon.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Launches the real activity under Robolectric to catch startup / navigation
 * crashes without a device. Rendering fidelity is still verified on-device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AppUiSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreenShowsSamples() {
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("选照片套用").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("选照片套用").assertExists()
        composeRule.onNodeWithText("导入预设").assertExists()
        composeRule.onNodeWithText("从 nikonpc 下载").assertExists()
        composeRule.onNodeWithText("Agfa Optima").assertExists()
    }

    @Test
    fun presetPickerOpensFromHome() {
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("选照片套用").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("选照片套用").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("选择预设").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("选择预设").assertExists()
    }

    @Test
    fun nikonPcPickerOpensFromHome() {
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("选照片套用").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("从 nikonpc 下载").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("nikonpc 在线预设").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("nikonpc 在线预设").assertExists()
        composeRule.onNodeWithText("全部导入").assertExists()
    }
}
