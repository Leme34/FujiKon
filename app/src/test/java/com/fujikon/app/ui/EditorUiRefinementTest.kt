package com.fujikon.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.fujikon.app.ui.components.AUTO_ENHANCE_DETAILS_DEFAULT_EXPANDED
import com.fujikon.app.ui.components.AutoEnhanceButtonFill
import com.fujikon.app.ui.components.EditorPageTransition
import com.fujikon.app.ui.components.linearScopeColors
import com.fujikon.app.ui.theme.FujiKonTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression guards for the 1.6.25 UI refinements: the linear mask's red
 * scope shadow, the collapsed-by-default zone / recommendation panel, the
 * fully transparent one-tap button, and animated sub-page transitions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorUiRefinementTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun zoneAndRecommendationPanelIsCollapsedByDefault() {
        assertFalse(
            "分区与推荐应默认收起",
            AUTO_ENHANCE_DETAILS_DEFAULT_EXPANDED
        )
    }

    @Test
    fun oneTapButtonHasFullyTransparentFill() {
        assertEquals(
            "一键优化按钮底色应为全透明",
            0f, AutoEnhanceButtonFill.alpha, 0f
        )
    }

    @Test
    fun linearScopeShadowIsRedAndRampsAlongTheGradient() {
        val normal = linearScopeColors(invert = false)
        val inverted = linearScopeColors(invert = true)
        assertEquals(2, normal.size)
        assertEquals(2, inverted.size)
        // Every stop is red-dominant (matches MaskScopeRed #FF3B30).
        (normal + inverted).forEach { c ->
            assertTrue("红色通道应占主导", c.red > c.green && c.red > c.blue)
        }
        // Start faint -> end strong; inverting flips the direction.
        assertTrue("起点应淡于终点", normal[1].alpha > normal[0].alpha)
        assertTrue("反转后方向应翻转", inverted[0].alpha > inverted[1].alpha)
        assertTrue("弱端应接近透明", normal[0].alpha < 0.1f)
        assertTrue("强端应明显可见", normal[1].alpha > 0.2f)
    }

    @Test
    fun pageTransitionAddsAndRemovesContentWithAnimation() {
        rule.setContent {
            FujiKonTheme {
                var show by remember { mutableStateOf(false) }
                Button(
                    onClick = { show = !show },
                    modifier = Modifier.testTag("toggle")
                ) { Text("切换") }
                EditorPageTransition(visible = show) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .background(Color.Red)
                            .testTag("page")
                    )
                }
            }
        }
        rule.onNodeWithTag("page").assertDoesNotExist()
        rule.onNodeWithTag("toggle").performClick()
        rule.onNodeWithTag("page").assertExists()
        rule.onNodeWithTag("toggle").performClick()
        rule.mainClock.advanceTimeBy(1000L)
        rule.waitForIdle()
        rule.onNodeWithTag("page").assertDoesNotExist()
    }
}
