package com.fujikon.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.fujikon.app.data.Preset
import com.fujikon.app.ui.components.SwipeRevealPresetCard
import com.fujikon.app.ui.theme.FujiKonTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 首页预设卡片「左滑露出操作区」的交互回归测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SwipeRevealPresetCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deleteButtonIsHiddenUntilSwipeLeftAndClickDeletes() {
        var deleted = false
        composeRule.setContent {
            FujiKonTheme {
                SwipeRevealPresetCard(
                    preset = Preset.none().copy(id = "p1", name = "测试预设"),
                    onClick = {},
                    onDelete = { deleted = true },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "preset_card"
                )
            }
        }

        assertTrue("未左滑时语义树上不应存在删除按钮",
            composeRule.onAllNodesWithTag("preset_swipe_delete_p1").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithTag("preset_card").performTouchInput { swipeLeft() }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("preset_swipe_delete_p1").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("preset_swipe_delete_p1").performClick()

        assertTrue("点击露出的删除按钮必须触发删除", deleted)
    }

    @Test
    fun tappingRevealedCardOnlyClosesItWithoutOpeningDetail() {
        var opened = false
        composeRule.setContent {
            FujiKonTheme {
                SwipeRevealPresetCard(
                    preset = Preset.none().copy(id = "p2", name = "测试预设"),
                    onClick = { opened = true },
                    onDelete = {},
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "preset_card"
                )
            }
        }

        composeRule.onNodeWithTag("preset_card").performTouchInput { swipeLeft() }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("preset_swipe_delete_p2").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("preset_card").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("preset_swipe_delete_p2").fetchSemanticsNodes().isEmpty()
        }

        assertFalse("展开状态下点卡片只应收起，不能误进详情页", opened)
    }

    @Test
    fun deletedViewOffersRestoreAndPermanentDelete() {
        var restored = false
        var purged = false
        composeRule.setContent {
            FujiKonTheme {
                SwipeRevealPresetCard(
                    preset = Preset.none().copy(id = "d1", name = "已删除预设", deleted = true),
                    onClick = {},
                    onDelete = { purged = true },
                    onRestore = { restored = true },
                    deleted = true,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "preset_card"
                )
            }
        }

        // 卡片内就地「恢复」按钮，无需左滑即可恢复。
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("preset_restore_d1").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("preset_restore_d1").performClick()
        assertTrue("点击恢复必须触发恢复回调", restored)

        composeRule.onNodeWithTag("preset_card").performTouchInput { swipeLeft() }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("彻底删除").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("preset_swipe_restore_d1").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("preset_swipe_delete_d1").performClick()
        assertTrue("已删除视图的滑动按钮应触发彻底删除", purged)
    }
}
