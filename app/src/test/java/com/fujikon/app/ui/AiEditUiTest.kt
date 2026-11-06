package com.fujikon.app.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.fujikon.app.agnes.AgnesImageService
import com.fujikon.app.ui.components.AgnesKeySettingsCard
import com.fujikon.app.ui.components.AiApiKeyRequiredDialog
import com.fujikon.app.ui.components.AiEditDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose interaction tests running on a Robolectric virtual Android device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiEditUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun customPromptIsPassedThrough() {
        var confirmed: String? = null
        rule.setContent {
            AiEditDialog(
                initialPrompt = "",
                suggestedPrompts = listOf("改成胶片风", "增强天空"),
                onDismiss = {},
                onConfirm = { confirmed = it }
            )
        }
        rule.onNodeWithTag("ai_prompt_input").performTextInput("把天空改成星空，保留构图")
        rule.onNodeWithTag("ai_confirm").performClick()
        assertEquals("把天空改成星空，保留构图", confirmed)
    }

    @Test
    fun emptyOrBlankPromptCannotBeSubmitted() {
        var confirmed: String? = null
        rule.setContent {
            AiEditDialog("", emptyList(), {}, { confirmed = it })
        }
        rule.onNodeWithTag("ai_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("ai_prompt_input").performTextInput("   ")
        rule.onNodeWithTag("ai_confirm").assertIsNotEnabled()
        assertNull(confirmed)
    }

    @Test
    fun suggestionChipFillsThePrompt() {
        var confirmed: String? = null
        rule.setContent {
            AiEditDialog("", listOf("改成胶片风"), {}, { confirmed = it })
        }
        rule.onNodeWithText("改成胶片风").performClick()
        rule.onNodeWithTag("ai_confirm").performClick()
        assertEquals("改成胶片风", confirmed)
    }

    @Test
    fun lastPromptIsPrefilled() {
        var confirmed: String? = null
        rule.setContent {
            AiEditDialog("上次的提示词", emptyList(), {}, { confirmed = it })
        }
        rule.onNodeWithTag("ai_confirm").performClick()
        assertEquals("上次的提示词", confirmed)
    }

    @Test
    fun missingKeyDialogOffersSettingsShortcut() {
        var goToSettings = false
        rule.setContent {
            AiApiKeyRequiredDialog(onDismiss = {}, onGoToSettings = { goToSettings = true })
        }
        rule.onNodeWithText("需要先配置 API 密钥").assertExists()
        rule.onNodeWithTag("ai_go_settings").performClick()
        assertTrue(goToSettings)
    }

    @Test
    fun settingsCardSavesEnteredKey() {
        var saved: String? = null
        rule.setContent {
            AgnesKeySettingsCard(
                configured = false,
                modelName = AgnesImageService.MODEL,
                onSave = { saved = it },
                onClear = {}
            )
        }
        rule.onNodeWithTag("agnes_key_status").assertExists()
        rule.onNodeWithTag("agnes_key_input").performTextInput("sk-user-key")
        rule.onNodeWithTag("agnes_key_save").performClick()
        assertEquals("sk-user-key", saved)
    }

    @Test
    fun settingsCardCanClearConfiguredKey() {
        var cleared = false
        rule.setContent {
            AgnesKeySettingsCard(
                configured = true,
                modelName = AgnesImageService.MODEL,
                onSave = {},
                onClear = { cleared = true }
            )
        }
        rule.onNodeWithTag("agnes_key_clear").performClick()
        assertTrue(cleared)
    }
}
