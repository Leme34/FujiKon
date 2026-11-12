package com.fujikon.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import com.fujikon.app.nic.CloudImportSupport
import com.fujikon.app.ui.screens.CloudDetailImportBar
import com.fujikon.app.ui.screens.CloudImportAction
import com.fujikon.app.ui.theme.FujiKonTheme
import com.fujikon.app.ui.viewmodel.NikonCloudViewModel.ItemState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 列表行与详情页在「暂不支持导入」时不能提供任何导入动作。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class CloudImportUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun tagExists(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun textExists(text: String): Boolean =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun unsupportedActionShowsLabelAndNoImportButton() {
        var clicked = false
        composeRule.setContent {
            FujiKonTheme {
                CloudImportAction(
                    status = ItemState.Idle,
                    supported = false,
                    onImport = { clicked = true }
                )
            }
        }

        assertTrue("应显示暂不支持导入标记", tagExists("cloud_import_unsupported"))
        assertTrue(textExists(CloudImportSupport.UNSUPPORTED_LABEL))
        assertFalse("不应出现「导入」按钮", textExists("导入"))
        assertFalse("不支持的方案不应有可触发的导入动作", clicked)
    }

    @Test
    fun unsupportedStatusAlsoShowsLabelEvenWhenSupportedFlagIsTrue() {
        composeRule.setContent {
            FujiKonTheme {
                CloudImportAction(
                    status = ItemState.Unsupported,
                    supported = true,
                    onImport = {}
                )
            }
        }
        assertTrue(tagExists("cloud_import_unsupported"))
        assertFalse("不应出现「导入」按钮", textExists("导入"))
    }

    @Test
    fun supportedActionShowsImportButton() {
        composeRule.setContent {
            FujiKonTheme {
                CloudImportAction(status = ItemState.Idle, supported = true, onImport = {})
            }
        }
        assertFalse("支持的方案不应显示暂不支持标记", tagExists("cloud_import_unsupported"))
        assertTrue(textExists("导入"))
    }

    @Test
    fun unsupportedDetailBarShowsReasonWithoutImportButton() {
        composeRule.setContent {
            FujiKonTheme {
                CloudDetailImportBar(
                    status = ItemState.Idle,
                    supported = false,
                    onImport = {}
                )
            }
        }
        assertTrue(tagExists("cloud_detail_import_unsupported"))
        assertTrue(textExists(CloudImportSupport.UNSUPPORTED_LABEL))
        assertTrue(textExists(CloudImportSupport.UNSUPPORTED_HINT))
        assertFalse("详情页不应出现导入按钮", textExists("导入到我的预设"))
    }

    @Test
    fun supportedDetailBarShowsImportButton() {
        composeRule.setContent {
            FujiKonTheme {
                CloudDetailImportBar(
                    status = ItemState.Idle,
                    supported = true,
                    onImport = {}
                )
            }
        }
        assertFalse(tagExists("cloud_detail_import_unsupported"))
        assertTrue(textExists("导入到我的预设"))
    }
}
