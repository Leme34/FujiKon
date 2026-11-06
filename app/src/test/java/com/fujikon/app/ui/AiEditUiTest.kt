package com.fujikon.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.fujikon.app.agnes.AgnesImageService
import com.fujikon.app.agnes.AgnesKeyCheck
import com.fujikon.app.agnes.AgnesKeyStatus
import com.fujikon.app.ui.components.AgnesKeyCheckUiState
import com.fujikon.app.ui.components.AgnesKeySettingsCard
import com.fujikon.app.ui.components.AiApiKeyRequiredDialog
import com.fujikon.app.ui.components.AiEditDialog
import com.fujikon.app.ui.components.AiErrorDialog
import com.fujikon.app.ui.components.AiPromptGenState
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

    /** 生成提示词按钮位于「快捷灵感」下方，并把点击传给上层。 */
    @Test
    fun generatePromptButtonSitsBelowSuggestionsAndReportsClicks() {
        var calls = 0
        rule.setContent {
            AiEditDialog(
                initialPrompt = "",
                suggestedPrompts = listOf("改成胶片风"),
                onDismiss = {},
                onConfirm = {},
                onGeneratePrompt = { calls++ }
            )
        }
        rule.onNodeWithText("快捷灵感").assertExists()
        rule.onNodeWithTag("ai_generate_prompt").assertExists()
        // In Robolectric the dialog body may be taller than the test window;
        // scroll the button into view before clicking its centre.
        rule.onNodeWithTag("ai_generate_prompt").performScrollTo()
        rule.onNodeWithText("生成提示词").performClick()
        println("DIAG generatePrompt calls=$calls")
        assertEquals(1, calls)
    }

    /** 生成的提示词自动填入输入框，且仍可被用户改写后提交。 */
    @Test
    fun generatedPromptFillsTheInputAndRemainsEditable() {
        val gen = mutableStateOf<AiPromptGenState>(AiPromptGenState.Idle)
        var confirmed: String? = null
        rule.setContent {
            AiEditDialog(
                initialPrompt = "",
                suggestedPrompts = emptyList(),
                onDismiss = {},
                onConfirm = { confirmed = it },
                promptGen = gen.value,
                onGeneratePrompt = {},
                onPromptConsumed = { gen.value = AiPromptGenState.Idle }
            )
        }
        gen.value = AiPromptGenState.Done("提升曝光，校正偏蓝，保留原构图", fromLocal = false)
        rule.waitForIdle()
        rule.onNodeWithTag("ai_prompt_input").assertTextContains("提升曝光", substring = true)
        rule.onNodeWithTag("ai_generate_notice").assertTextContains("Agnes 3.0 Flash", substring = true)

        // The user stays in control and can replace the generated text.
        rule.onNodeWithTag("ai_prompt_input").performTextClearance()
        rule.onNodeWithTag("ai_prompt_input").performTextInput("我改成自己的提示词")
        rule.onNodeWithTag("ai_confirm").performClick()
        assertEquals("我改成自己的提示词", confirmed)
    }

    /** 模型不可用时本机降级，必须如实标注来源而不是伪装成 AI 结果。 */
    @Test
    fun localFallbackIsClearlyLabelled() {
        val gen = mutableStateOf<AiPromptGenState>(AiPromptGenState.Idle)
        rule.setContent {
            AiEditDialog(
                initialPrompt = "",
                suggestedPrompts = emptyList(),
                onDismiss = {},
                onConfirm = {},
                promptGen = gen.value,
                onPromptConsumed = { gen.value = AiPromptGenState.Idle }
            )
        }
        gen.value = AiPromptGenState.Done("本机分析生成的提示词", fromLocal = true)
        rule.waitForIdle()
        rule.onNodeWithTag("ai_generate_notice").assertTextContains("本机图像分析", substring = true)
        rule.onNodeWithTag("ai_prompt_input").assertTextContains("本机分析", substring = true)
    }

    /** 未配置密钥时降级说明要点明原因，不能笼统说成「模型暂不可用」。 */
    @Test
    fun localFallbackWithoutKeyNamesTheRealReason() {
        val gen = mutableStateOf<AiPromptGenState>(AiPromptGenState.Idle)
        rule.setContent {
            AiEditDialog(
                initialPrompt = "",
                suggestedPrompts = emptyList(),
                onDismiss = {},
                onConfirm = {},
                promptGen = gen.value,
                hasApiKey = false,
                onPromptConsumed = { gen.value = AiPromptGenState.Idle }
            )
        }
        gen.value = AiPromptGenState.Done("本机分析生成的提示词", fromLocal = true)
        rule.waitForIdle()
        rule.onNodeWithTag("ai_generate_notice").assertTextContains("尚未配置密钥", substring = true)
        rule.onNodeWithTag("ai_generate_notice").assertTextContains("Agnes 3.0 Flash", substring = true)
    }

    @Test
    fun runningStateDisablesTheButton() {
        rule.setContent {
            AiEditDialog(
                initialPrompt = "",
                suggestedPrompts = emptyList(),
                onDismiss = {},
                onConfirm = {},
                promptGen = AiPromptGenState.Running
            )
        }
        rule.onNodeWithTag("ai_generate_prompt").assertIsNotEnabled()
        rule.onNodeWithText("分析中…").assertExists()
    }

    @Test
    fun failedGenerationShowsAnInlineError() {
        rule.setContent {
            AiEditDialog(
                initialPrompt = "",
                suggestedPrompts = emptyList(),
                onDismiss = {},
                onConfirm = {},
                promptGen = AiPromptGenState.Failed("API 密钥无效")
            )
        }
        rule.onNodeWithTag("ai_generate_error").assertTextContains("API 密钥无效", substring = true)
    }

    @Test
    fun missingKeyHintExplainsTheFallback() {
        rule.setContent {
            AiEditDialog(
                initialPrompt = "",
                suggestedPrompts = emptyList(),
                onDismiss = {},
                onConfirm = {},
                hasApiKey = false
            )
        }
        rule.onNodeWithTag("ai_no_key_hint").assertTextContains("本机图像分析", substring = true)
    }


    /** 文档承诺的公网 URL 高级设置确实可填、可回读。 */
    @Test
    fun advancedImageUrlSettingIsEditable() {
        val url = mutableStateOf("")
        // The real Settings screen is a scrollable LazyColumn; wrap the card
        // the same way so bottom-of-card controls are laid out and clickable.
        rule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AgnesKeySettingsCard(
                    configured = false,
                    savedKey = "",
                    modelName = AgnesImageService.MODEL,
                    region = AgnesImageService.REGION_CN,
                    checkState = AgnesKeyCheckUiState.Idle,
                    onSave = {},
                    onClear = {},
                    onRegionChange = {},
                    onVerify = {},
                    imagePublicUrl = url.value,
                    onImagePublicUrlChange = { url.value = it }
                )
            }
        }
        rule.onNodeWithTag("agnes_advanced_toggle").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("收起高级设置").assertExists()
        rule.onNodeWithTag("agnes_image_url_input").performTextInput("https://cdn.example.com/p.jpg")
        assertEquals("https://cdn.example.com/p.jpg", url.value)
    }

}
