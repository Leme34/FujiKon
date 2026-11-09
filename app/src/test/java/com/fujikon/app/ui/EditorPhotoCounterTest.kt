package com.fujikon.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.fujikon.app.ui.components.EditorTopBarTitle
import com.fujikon.app.ui.components.editorPhotoCounter
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
 * 1.6.30 regression: the top-left "current / total" counter must stay COMPLETE
 * and on ONE line even when the five top-bar actions squeeze the title slot to
 * a very narrow width. The old inline title used an unlimited-line
 * `titleMedium` Text, so "1 / 3" wrapped, the two-line Column became three
 * lines, exceeded the 64dp TopAppBar and spilled out (the "1" floated into the
 * status bar and the "/" dropped below the icons).
 *
 * The fix is `maxLines = 1` + `softWrap = false` + `wrapContentWidth(unbounded
 * = true)`: the counter is measured at its intrinsic width instead of the tiny
 * squeezed slot. Robolectric has no real font metrics and does neither
 * soft-wrapping nor intrinsic text measurement, so pixel-level wrapping can
 * only be verified on a device. These tests lock what IS deterministic here:
 * the rendered counter text is complete (never a lone "1" or "/"), the
 * composable renders as a single line inside the bar, and the counter format
 * is a compact single-line string for every photo count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorPhotoCounterTest {

    @get:Rule
    val rule = createComposeRule()

    /** A deliberately tiny title slot, mirroring a squeezed TopAppBar title. */
    private fun renderTitle(index: Int, count: Int, name: String?) {
        rule.setContent {
            FujiKonTheme {
                Box(
                    Modifier
                        .width(28.dp)
                        .height(96.dp)
                        .testTag("title_slot")
                ) {
                    EditorTopBarTitle(index = index, photoCount = count, photoName = name)
                }
            }
        }
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).getBoundsInRoot()

    /** The counter node exists, is one line, and sits inside the bar. */
    private fun assertCounterRenderedSingleLine(label: String) {
        val slot = bounds("title_slot")
        val b = bounds("editor_photo_counter")
        val h = b.bottom.value - b.top.value
        assertTrue("$label 必须保持单行 (实测高=${h}dp)", h <= 45f)
        assertTrue("$label 不能溢出顶栏上边界", b.top.value >= slot.top.value - 1f)
        assertTrue("$label 不能溢出顶栏下边界", b.bottom.value <= slot.bottom.value + 1f)
    }

    /** File name is one ellipsized line that stays inside the bar. */
    private fun assertFileNameSingleLine(label: String) {
        val slot = bounds("title_slot")
        val b = bounds("editor_photo_name")
        val h = b.bottom.value - b.top.value
        assertTrue("$label 必须单行省略 (实测高=${h}dp)", h <= 40f)
        assertTrue("$label 不能溢出顶栏下边界", b.bottom.value <= slot.bottom.value + 1f)
    }

    // ---------------------------------------------------------- formatting

    @Test
    fun emptySelectionShowsViewLabel() {
        assertEquals("查看", editorPhotoCounter(0, 0))
        assertEquals("查看", editorPhotoCounter(3, -1))
    }

    @Test
    fun counterIsOneBasedAndIncludesTotal() {
        assertEquals("1 / 3", editorPhotoCounter(0, 3))
        assertEquals("3 / 3", editorPhotoCounter(2, 3))
    }

    @Test
    fun counterHandlesMultiDigitTotals() {
        assertEquals("12 / 30", editorPhotoCounter(11, 30))
        assertEquals("100 / 250", editorPhotoCounter(99, 250))
    }

    @Test
    fun counterHandlesSinglePhotoAndClampsBadIndex() {
        assertEquals("1 / 1", editorPhotoCounter(0, 1))
        assertEquals("1 / 5", editorPhotoCounter(-3, 5))
    }

    @Test
    fun counterIsAlwaysACompactSingleLineString() {
        for (count in 1..40) {
            for (index in 0 until count) {
                val text = editorPhotoCounter(index, count)
                assertFalse("计数不得含换行符: $text", text.contains('\n'))
                assertFalse("计数不得含回车符: $text", text.contains('\r'))
                assertEquals("计数必须是「当前 / 总数」紧凑格式", 1, text.count { it == '/' })
            }
        }
    }

    // --------------------------------------------------------------- layout

    @Test
    fun counterIsCompleteSingleLineAndInsideTheBar() {
        renderTitle(0, 3, "DSC_0001.JPG")
        // The user-facing regression: the full "1 / 3" is rendered, not a
        // clipped "1" or a stray "/".
        rule.onNodeWithTag("editor_photo_counter").assertTextEquals("1 / 3")
        assertCounterRenderedSingleLine("计数")
        assertFileNameSingleLine("文件名")
    }

    @Test
    fun multiDigitCounterAlsoStaysComplete() {
        renderTitle(11, 30, "DSC_0012.JPG")
        rule.onNodeWithTag("editor_photo_counter").assertTextEquals("12 / 30")
        assertCounterRenderedSingleLine("多位数计数")
    }

    @Test
    fun longFileNameStaysSingleLineAndEllipsized() {
        renderTitle(0, 3, "a_very_long_panorama_file_name_that_overflows.JPG")
        rule.onNodeWithTag("editor_photo_name")
            .assertTextEquals("a_very_long_panorama_file_name_that_overflows.JPG")
        assertFileNameSingleLine("长文件名")
    }

    @Test
    fun emptySelectionRendersViewLabelInTheBar() {
        renderTitle(0, 0, null)
        rule.onNodeWithTag("editor_photo_counter").assertTextEquals("查看")
        rule.onNodeWithTag("editor_photo_name").assertTextEquals("选择照片")
        assertCounterRenderedSingleLine("空态计数")
    }
}
