package com.fujikon.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.fujikon.app.agnes.AgnesImageService
import com.fujikon.app.agnes.AgnesKeyCheck
import com.fujikon.app.agnes.AgnesKeyStatus
import com.fujikon.app.ui.components.AgnesKeyCheckUiState
import com.fujikon.app.ui.components.AgnesKeySettingsCard
import com.fujikon.app.ui.components.AiApiKeyRequiredDialog
import com.fujikon.app.ui.components.AiEditDialog
import com.fujikon.app.ui.components.AiErrorDialog
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

    private fun card(
        configured: Boolean = false,
        savedKey: String = "",
        checkState: AgnesKeyCheckUiState = AgnesKeyCheckUiState.Idle,
        onSave: (String) -> Unit = {},
        onClear: () -> Unit = {},
        onRegionChange: (String) -> Unit = {},
        onVerify: () -> Unit = {}
    ) {
        rule.setContent {
            AgnesKeySettingsCard(
                configured = configured,
                savedKey = savedKey,
                modelName = AgnesImageService.MODEL,
                region = AgnesImageService.REGION_CN,
                checkState = checkState,
                onSave = onSave,
                onClear = onClear,
                onRegionChange = onRegionChange,
                onVerify = onVerify
            )
        }
    }

    private data class Sem(val text: String, val editable: String) {
        override fun toString() = "Text=[$text] Editable=[$editable]"
    }

    private fun semantics(): Sem {
        val config = rule.onNodeWithTag("agnes_key_input").fetchSemanticsNode().config
        val text = if (config.contains(SemanticsProperties.Text)) {
            config[SemanticsProperties.Text].joinToString("")
        } else {
            "<none>"
        }
        val editable = if (config.contains(SemanticsProperties.EditableText)) {
            config[SemanticsProperties.EditableText].text
        } else {
            "<none>"
        }
        return Sem(text, editable)
    }

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
        card(onSave = { saved = it })
        rule.onNodeWithTag("agnes_key_status").assertExists()
        rule.onNodeWithTag("agnes_key_input").performTextInput("sk-user-key")
        rule.onNodeWithTag("agnes_key_save").performClick()
        assertEquals("sk-user-key", saved)
    }

    @Test
    fun settingsCardCanClearConfiguredKey() {
        var cleared = false
        card(configured = true, savedKey = "sk-existing", onClear = { cleared = true })
        rule.onNodeWithTag("agnes_key_clear").performClick()
        assertTrue(cleared)
    }

    /** Regression for "密钥保存后直接消失，无法知道是否保存成功". */
    @Test
    fun savedKeyIsPrefilledAndKeptVisibleAfterSave() {
        val saved = mutableStateOf("")
        val configured = mutableStateOf(false)
        rule.setContent {
            AgnesKeySettingsCard(
                configured = configured.value,
                savedKey = saved.value,
                modelName = AgnesImageService.MODEL,
                region = AgnesImageService.REGION_CN,
                checkState = AgnesKeyCheckUiState.Idle,
                onSave = { saved.value = it; configured.value = true },
                onClear = {},
                onRegionChange = {},
                onVerify = {}
            )
        }
        rule.onNodeWithTag("agnes_key_status").assertTextContains("未配置", substring = true)
        rule.onNodeWithTag("agnes_key_input").performTextInput("sk-user-key-9876")
        rule.onNodeWithTag("agnes_key_save").performClick()

        rule.onNodeWithTag("agnes_key_status").assertTextContains("已配置", substring = true)
        rule.onNodeWithTag("agnes_key_fingerprint").assertTextContains("9876", substring = true)
        rule.onNodeWithTag("agnes_key_fingerprint").assertTextContains("16 位", substring = true)
        // The field must not be wiped: an enabled save button proves it is not empty.
        rule.onNodeWithTag("agnes_key_save").assertIsEnabled()
        // Masked by default; revealing must show the exact saved key.
        assertTrue("保存后默认应为掩码: ${semantics()}", !semantics().editable.contains("sk-user-key-9876"))
        rule.onNodeWithTag("agnes_key_toggle").performClick()
        assertTrue("回显的明文应与保存的密钥一致: ${semantics()}", semantics().editable.contains("sk-user-key-9876"))
    }

    /** Regression for "点眼睛无法看到明文密钥". */
    @Test
    fun eyeToggleRevealsPlaintextKey() {
        val key = "sk-plain-123456"
        card(configured = true, savedKey = key)
        rule.onNodeWithTag("agnes_key_fingerprint").assertExists()
        val before = semantics()
        rule.onNodeWithTag("agnes_key_toggle").performClick()
        val after = semantics()
        // Robolectric observation: Editable goes from a masked string to the
        // real key, so this asserts the plaintext is genuinely visible.
        assertTrue("默认应为掩码: $before", !before.editable.contains(key))
        assertTrue("默认应显示掩码占位符: $before", before.editable.contains("•"))
        // The toggle really changed state (icon description flips).
        rule.onNodeWithContentDescription("隐藏密钥").assertExists()
        assertTrue(
            "点击眼睛后必须能读到完整明文密钥: before=$before after=$after",
            after.editable.contains(key)
        )
        rule.onNodeWithTag("agnes_key_toggle").performClick()
        rule.onNodeWithContentDescription("显示密钥").assertExists()
    }

    /** A real verification result stays on screen instead of vanishing. */
    @Test
    fun verificationResultIsShownPersistently() {
        card(
            configured = true,
            savedKey = "sk-abc",
            checkState = AgnesKeyCheckUiState.Result(
                AgnesKeyCheck(AgnesKeyStatus.VALID, "密钥有效 · 已连通 api.agnes-ai.cn", 200)
            )
        )
        rule.onNodeWithTag("agnes_key_verify_result").assertTextContains("密钥有效", substring = true)
        rule.onNodeWithTag("agnes_key_verify").assertIsEnabled()
    }

    @Test
    fun invalidKeyVerificationResultIsShownInPlace() {
        card(
            configured = true,
            savedKey = "sk-bad",
            checkState = AgnesKeyCheckUiState.Result(
                AgnesKeyCheck(AgnesKeyStatus.INVALID, "密钥无效或没有权限：无效的令牌", 401)
            )
        )
        rule.onNodeWithTag("agnes_key_verify_result").assertTextContains("无效的令牌", substring = true)
    }

    @Test
    fun verifyButtonIsDisabledUntilAKeyIsConfigured() {
        card(configured = false)
        rule.onNodeWithTag("agnes_key_verify").assertIsNotEnabled()
    }

    @Test
    fun regionSwitchIsReportedToTheCaller() {
        var region: String? = null
        card(onRegionChange = { region = it })
        rule.onNodeWithTag("agnes_region_global").performClick()
        assertEquals(AgnesImageService.REGION_GLOBAL, region)
        rule.onNodeWithTag("agnes_region_cn").performClick()
        assertEquals(AgnesImageService.REGION_CN, region)
    }

    @Test
    fun errorDialogOffersAShortcutToFixTheKey() {
        var goToSettings = false
        rule.setContent {
            AiErrorDialog(
                message = "API 密钥无效或没有权限：无效的令牌（当前端点 api.agnes-ai.cn）",
                onDismiss = {},
                onGoToSettings = { goToSettings = true }
            )
        }
        rule.onNodeWithText("无效的令牌", substring = true).assertExists()
        rule.onNodeWithText("去设置").performClick()
        assertTrue(goToSettings)
    }
}
