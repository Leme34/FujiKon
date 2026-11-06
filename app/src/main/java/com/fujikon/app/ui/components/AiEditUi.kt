package com.fujikon.app.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card as CardColor
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Danger
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary

/** Prompt editor for one-tap AI retouching; the prompt is fully user-defined. */
@Composable
fun AiEditDialog(
    initialPrompt: String,
    suggestedPrompts: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(initialPrompt) { mutableStateOf(initialPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor,
        title = {
            Text("一键 AI 修图", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
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
fun AiErrorDialog(message: String, onDismiss: () -> Unit) {
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
        dismissButton = {}
    )
}

/** Settings card for entering and saving the Agnes API key. */
@Composable
fun AgnesKeySettingsCard(
    configured: Boolean,
    modelName: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

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
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("API 密钥") },
                placeholder = { Text("粘贴你的 Agnes API 密钥") },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (visible) "隐藏密钥" else "显示密钥",
                            tint = TextSecondary
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
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        onSave(input)
                        input = ""
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.testTag("agnes_key_save"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
                ) {
                    Text("保存密钥")
                }
                if (configured) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onClear, modifier = Modifier.testTag("agnes_key_clear")) {
                        Text("清除", color = Danger)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "密钥仅保存在本机；发起 AI 修图时，当前预览图会上传至 Agnes 服务。未配置密钥时编辑页会提示跳转到这里。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}
