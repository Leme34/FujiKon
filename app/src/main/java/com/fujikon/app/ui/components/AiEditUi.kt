package com.fujikon.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fujikon.app.agnes.AgnesImageService
import com.fujikon.app.agnes.AgnesKeyCheck
import com.fujikon.app.agnes.AgnesKeyStatus
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card as CardColor
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Danger
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary

/**
 * UI state of the "生成提示词" action. Kept in the component layer so the
 * dialog has no dependency on a concrete ViewModel; EditorScreen maps the
 * ViewModel state onto this.
 */
sealed interface AiPromptGenState {
    data object Idle : AiPromptGenState
    data object Running : AiPromptGenState
    /** [fromLocal] is true when Agnes was unavailable and on-device analysis ran. */
    data class Done(val prompt: String, val fromLocal: Boolean) : AiPromptGenState
    data class Failed(val message: String) : AiPromptGenState
}

/** Prompt editor for one-tap AI retouching; the prompt is fully user-defined. */
@Composable
fun AiEditDialog(
    initialPrompt: String,
    suggestedPrompts: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    promptGen: AiPromptGenState = AiPromptGenState.Idle,
    hasApiKey: Boolean = true,
    onGeneratePrompt: () -> Unit = {},
    onPromptConsumed: () -> Unit = {}
) {
    var text by remember(initialPrompt) { mutableStateOf(initialPrompt) }
    var genNotice by remember { mutableStateOf<String?>(null) }

    // A generated prompt is delivered as a one-shot event: fill the field,
    // show where it came from, then let the caller clear the state so the
    // button can be used again. The user stays in full control of the text.
    LaunchedEffect(promptGen) {
        if (promptGen is AiPromptGenState.Done) {
            text = promptGen.prompt.take(500)
            genNotice = if (promptGen.fromLocal) {
                if (hasApiKey) {
                    "云端分析本次未响应，已用本机图像分析生成提示词，可继续修改或重试"
                } else {
                    "尚未配置密钥，已用本机图像分析生成提示词；录入密钥后可由 Agnes 3.0 Flash 云端分析"
                }
            } else {
                "已根据 Agnes 3.0 Flash 的画面分析生成提示词，可继续修改"
            }
            onPromptConsumed()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor,
        title = {
            Text("一键 AI 修图", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        },
        text = {
            // The dialog now has a scrollable body: with the prompt field,
            // suggestion chips and the 生成提示词 action it can exceed the
            // available height on small screens (and in Robolectric tests).
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "用自然语言描述想要的改动，例如「改成胶片色调，保留原构图」。当前预览图会发送给 Agnes 图像大模型处理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(500) },
                    placeholder = { Text("输入自定义修图提示词…") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().testTag("ai_prompt_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Amber,
                        unfocusedBorderColor = CardHigh,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${text.length} / 500",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.align(Alignment.End)
                )
                if (suggestedPrompts.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("快捷灵感", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().testTag("ai_suggestion_row")
                    ) {
                        items(suggestedPrompts) { prompt ->
                            AssistChip(
                                onClick = { text = prompt },
                                label = {
                                    Text(
                                        prompt,
                                        maxLines = 1,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.testTag("ai_suggestion")
                            )
                        }
                    }
                }

                // 生成提示词：上传当前预览图由 Agnes 3.0 Flash 分析画面，结果
                // 自动填入上方输入框，用户可继续修改后再开始修图。
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { genNotice = null; onGeneratePrompt() },
                    enabled = promptGen !is AiPromptGenState.Running,
                    modifier = Modifier.fillMaxWidth().testTag("ai_generate_prompt"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CardHigh, contentColor = Amber)
                ) {
                    if (promptGen is AiPromptGenState.Running) {
                        CircularProgressIndicator(
                            color = Amber,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("分析中…", style = MaterialTheme.typography.labelMedium)
                    } else {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = Amber,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("生成提示词", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "上传当前预览图，由 Agnes 3.0 Flash 分析画面后生成优化提示词，自动填入输入框，可继续修改",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                if (!hasApiKey) {
                    Text(
                        "尚未配置密钥：生成提示词将使用本机图像分析；开始修图前请到「设置 → AI 修图」录入密钥",
                        style = MaterialTheme.typography.labelSmall,
                        color = Danger,
                        modifier = Modifier.testTag("ai_no_key_hint")
                    )
                }
                if (genNotice != null) {
                    Text(
                        genNotice!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = Amber,
                        modifier = Modifier.testTag("ai_generate_notice")
                    )
                }
                if (promptGen is AiPromptGenState.Failed) {
                    Text(
                        "生成失败：${promptGen.message}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Danger,
                        modifier = Modifier.testTag("ai_generate_error")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("ai_confirm"),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
            ) {
                Text("开始修图")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        }
    )
}

/** Shown when the user taps AI retouching without a saved API key. */
@Composable
fun AiApiKeyRequiredDialog(
    onDismiss: () -> Unit,
    onGoToSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor,
        icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Amber) },
        title = { Text("需要先配置 API 密钥", color = TextPrimary) },
        text = {
            Text(
                "使用一键 AI 修图前，请先在「设置 → Agnes AI 修图」录入并保存你的密钥。\n\n" +
                    "密钥只保存在本机；仅在你主动发起修图时，当前预览图才会上传给 Agnes 服务。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        },
        confirmButton = {
            Button(
                onClick = onGoToSettings,
                modifier = Modifier.testTag("ai_go_settings"),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
            ) {
                Text("去设置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后", color = TextSecondary) }
        }
    )
}

@Composable
fun AiProgressDialog(prompt: String, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = CardColor,
        title = { Text("AI 修图中", color = TextPrimary) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Amber, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("正在调用 Agnes 图像大模型…", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        prompt,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "生成通常需要十几秒到数十秒，请保持网络畅通。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("取消", color = TextSecondary) } }
    )
}

@Composable
fun AiErrorDialog(
    message: String,
    onDismiss: () -> Unit,
    onGoToSettings: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor,
        title = { Text("AI 修图失败", color = TextPrimary) },
        text = { Text(message, style = MaterialTheme.typography.bodySmall, color = TextSecondary) },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
            ) { Text("知道了") }
        },
        dismissButton = {
            if (onGoToSettings != null) {
                TextButton(onClick = onGoToSettings) { Text("去设置", color = Amber) }
            }
        }
    )
}

/** UI state of the "verify key" action, persisted while Settings is open. */
sealed interface AgnesKeyCheckUiState {
    data object Idle : AgnesKeyCheckUiState
    data object Checking : AgnesKeyCheckUiState
    data class Result(val check: AgnesKeyCheck) : AgnesKeyCheckUiState
}

/**
 * Settings card for entering, inspecting and verifying the Agnes API key.
 *
 * The saved key is pre-filled on every entry (masked by default), so the user
 * can always confirm what is stored and reveal it with the eye button. Save no
 * longer wipes the field, and a real verification request reports whether the
 * key and the selected region actually work.
 */
@Composable
fun AgnesKeySettingsCard(
    configured: Boolean,
    savedKey: String,
    modelName: String,
    region: String,
    checkState: AgnesKeyCheckUiState,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onRegionChange: (String) -> Unit,
    onVerify: () -> Unit,
    imagePublicUrl: String = "",
    onImagePublicUrlChange: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var field by remember { mutableStateOf(TextFieldValue(savedKey)) }
    var visible by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    // Re-entry, save and clear all converge here; never clear the field behind
    // the user's back while they are typing something different.
    LaunchedEffect(savedKey) {
        if (field.text != savedKey) field = TextFieldValue(savedKey)
    }

    val dirty = field.text.trim() != savedKey.trim()
    val global = region == AgnesImageService.REGION_GLOBAL

    Card(
        colors = CardDefaults.cardColors(containerColor = CardColor),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Agnes AI 修图",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = if (configured) Amber.copy(alpha = 0.16f) else CardHigh,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        if (configured) "已配置" else "未配置",
                        color = if (configured) Amber else TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("agnes_key_status")
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "模型 $modelName · 编辑页点「AI」输入自定义提示词即可一键修图",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(12.dp))

            // key() rebuilds the field on toggle so the new visual
            // transformation is always applied, even on Compose versions that
            // otherwise reuse the masked layout.
            key(visible) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    label = { Text("API 密钥") },
                    placeholder = { Text("粘贴你的 Agnes API 密钥") },
                    singleLine = true,
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { visible = !visible },
                            modifier = Modifier.testTag("agnes_key_toggle")
                        ) {
                            Icon(
                                if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (visible) "隐藏密钥" else "显示密钥",
                                tint = Amber
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("agnes_key_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Amber,
                        unfocusedBorderColor = CardHigh,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            }
            Spacer(Modifier.height(6.dp))
            if (configured) {
                Text(
                    "已保存密钥：${AgnesImageService.maskKey(savedKey)}（共 ${savedKey.length} 位）· 点右侧眼睛可查看完整明文",
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber,
                    modifier = Modifier.testTag("agnes_key_fingerprint")
                )
            } else {
                Text(
                    "尚未保存密钥。保存后这里会常驻显示密钥指纹，可随时点眼睛核对明文。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            if (dirty && field.text.isNotBlank()) {
                Text(
                    "输入框有未保存的修改，点「保存密钥」生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = Danger
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("接入区域", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !global,
                    onClick = { onRegionChange(AgnesImageService.REGION_CN) },
                    label = { Text("中国区 api.agnes-ai.cn") },
                    modifier = Modifier.testTag("agnes_region_cn"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Amber,
                        selectedLabelColor = Ink
                    )
                )
                FilterChip(
                    selected = global,
                    onClick = { onRegionChange(AgnesImageService.REGION_GLOBAL) },
                    label = { Text("国际区 apihub") },
                    modifier = Modifier.testTag("agnes_region_global"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Amber,
                        selectedLabelColor = Ink
                    )
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "密钥不跨区域通用：按密钥申请站点选择，选错会提示「无效的令牌」。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onSave(field.text) },
                    enabled = field.text.isNotBlank(),
                    modifier = Modifier.testTag("agnes_key_save"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
                ) {
                    Text("保存密钥")
                }
                if (configured) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onClear, modifier = Modifier.testTag("agnes_key_clear")) {
                        Text("清除", color = Danger)
                    }
                }
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = onVerify,
                    enabled = configured && checkState !is AgnesKeyCheckUiState.Checking,
                    modifier = Modifier.testTag("agnes_key_verify")
                ) {
                    Text("验证密钥", color = Amber)
                }
            }

            Spacer(Modifier.height(8.dp))
            when (checkState) {
                AgnesKeyCheckUiState.Idle -> {
                    Text(
                        if (configured) "建议点「验证密钥」确认密钥与接入区域是否匹配。"
                        else "保存密钥后可一键验证是否可用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                AgnesKeyCheckUiState.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color = Amber,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "正在向 ${AgnesImageService.hostFor(region)} 验证密钥…",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
                is AgnesKeyCheckUiState.Result -> {
                    val ok = checkState.check.status == AgnesKeyStatus.VALID
                    val tint = if (ok) Amber else Danger
                    Surface(
                        color = tint.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            (if (ok) "✓ " else "✕ ") + checkState.check.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            modifier = Modifier.fillMaxWidth().padding(10.dp)
                                .testTag("agnes_key_verify_result")
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "验证会发起最小化生成请求（当前体验价免费），只发送一句测试提示词、不上传照片；当前区域拒绝时会自动尝试另一区域。密钥仅保存在本机；发起 AI 修图时，当前预览图才会上传至所选 Agnes 端点。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )

            // Optional advanced setting: the Agnes 3.0 Flash doc asks for a
            // publicly reachable image_url. Data URI works on most gateways;
            // this field is only needed when the account rejects Data URIs.
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.testTag("agnes_advanced_toggle")
            ) {
                Text(
                    if (showAdvanced) "收起高级设置" else "高级设置 · 图像公网 URL",
                    color = Amber,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = imagePublicUrl,
                    onValueChange = onImagePublicUrlChange,
                    label = { Text("图片公网 URL（选填）") },
                    placeholder = { Text("https://…/photo.jpg") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("agnes_image_url_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Amber,
                        unfocusedBorderColor = CardHigh,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "「生成提示词」默认把当前预览图编码为 Data URI 上传，无需填写。" +
                        "仅当网关明确要求公开可访问的 image_url 时，才把照片上传到你自己的图床并在此粘贴链接。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}
