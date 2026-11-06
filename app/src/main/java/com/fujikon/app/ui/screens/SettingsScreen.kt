package com.fujikon.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.fujikon.app.BuildConfig
import com.fujikon.app.FujiKonApp
import com.fujikon.app.agnes.AgnesImageService
import com.fujikon.app.agnes.AgnesKeyStatus
import com.fujikon.app.ui.components.AgnesKeyCheckUiState
import com.fujikon.app.ui.components.AgnesKeySettingsCard
import com.fujikon.app.ui.components.SectionHeader
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card as CardColor
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import com.fujikon.app.ui.viewmodel.PresetsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavHostController,
    presetsViewModel: PresetsViewModel
) {
    val context = LocalContext.current
    val app = context.applicationContext as FujiKonApp
    val settings = app.container.settings
    val presets by presetsViewModel.presets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var album by remember { mutableStateOf(settings.albumName) }
    var format by remember { mutableStateOf(settings.exportFormat) }
    var preserveExif by remember { mutableStateOf(settings.preserveExif) }
    var agnesConfigured by remember { mutableStateOf(settings.hasAgnesApiKey) }
    var agnesSavedKey by remember { mutableStateOf(settings.agnesApiKey) }
    var agnesRegion by remember { mutableStateOf(settings.agnesRegion) }
    var agnesImageUrl by remember { mutableStateOf(settings.agnesImagePublicUrl) }
    var keyCheck by remember { mutableStateOf<AgnesKeyCheckUiState>(AgnesKeyCheckUiState.Idle) }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                    }
                },
                title = { Text("设置", style = MaterialTheme.typography.titleMedium, color = TextPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { SectionHeader("AI 修图") }
            item {
                AgnesKeySettingsCard(
                    configured = agnesConfigured,
                    savedKey = agnesSavedKey,
                    modelName = AgnesImageService.MODEL,
                    region = agnesRegion,
                    checkState = keyCheck,
                    imagePublicUrl = agnesImageUrl,
                    onImagePublicUrlChange = { url ->
                        agnesImageUrl = url
                        settings.agnesImagePublicUrl = url
                    },
                    onSave = { key ->
                        settings.agnesApiKey = key
                        agnesSavedKey = settings.agnesApiKey
                        agnesConfigured = settings.hasAgnesApiKey
                        keyCheck = AgnesKeyCheckUiState.Idle
                        scope.launch {
                            snackbar.showSnackbar(
                                "已保存密钥 ${AgnesImageService.maskKey(settings.agnesApiKey)}，可点「验证密钥」确认是否可用"
                            )
                        }
                    },
                    onClear = {
                        settings.agnesApiKey = ""
                        agnesSavedKey = ""
                        agnesConfigured = false
                        keyCheck = AgnesKeyCheckUiState.Idle
                        scope.launch { snackbar.showSnackbar("已清除 Agnes API 密钥") }
                    },
                    onRegionChange = { region ->
                        if (region != agnesRegion) {
                            settings.agnesRegion = region
                            agnesRegion = settings.agnesRegion
                            keyCheck = AgnesKeyCheckUiState.Idle
                            scope.launch {
                                snackbar.showSnackbar(
                                    "已切换到 ${AgnesImageService.hostFor(region)}，请重新验证密钥"
                                )
                            }
                        }
                    },
                    onVerify = {
                        scope.launch {
                            keyCheck = AgnesKeyCheckUiState.Checking
                            val service = app.container.agnesImageService
                            var result = service.verifyApiKey(settings.agnesApiKey)
                            // Tokens are not shared between the China and the
                            // international gateway. If the current region
                            // rejects the key, retry once on the other one and
                            // keep the region that actually works.
                            if (result.status == AgnesKeyStatus.INVALID &&
                                (result.httpCode == 401 || result.httpCode == 403)
                            ) {
                                val original = settings.agnesRegion
                                val other = if (original == AgnesImageService.REGION_GLOBAL) {
                                    AgnesImageService.REGION_CN
                                } else {
                                    AgnesImageService.REGION_GLOBAL
                                }
                                settings.agnesRegion = other
                                val retry = service.verifyApiKey(settings.agnesApiKey)
                                if (retry.status == AgnesKeyStatus.VALID) {
                                    agnesRegion = other
                                    result = retry.copy(
                                        message = "已自动切换到 " +
                                            "${AgnesImageService.hostFor(other)} · " +
                                            "密钥有效，可以开始 AI 修图"
                                    )
                                } else {
                                    settings.agnesRegion = original
                                }
                            }
                            keyCheck = AgnesKeyCheckUiState.Result(result)
                            snackbar.showSnackbar(result.message)
                        }
                    }
                )
            }

            item { SectionHeader("导出") }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardColor),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("默认格式", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(
                                selected = format == "JPEG",
                                onClick = { format = "JPEG"; settings.exportFormat = "JPEG" },
                                label = { Text("JPEG 最高画质") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Amber,
                                    selectedLabelColor = Ink
                                )
                            )
                            FilterChip(
                                selected = format == "PNG",
                                onClick = { format = "PNG"; settings.exportFormat = "PNG" },
                                label = { Text("PNG 无损") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Amber,
                                    selectedLabelColor = Ink
                                )
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("保留相机信息", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                                Text(
                                    "机型、镜头、曝光参数、GPS 与拍摄时间",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Switch(
                                checked = preserveExif,
                                onCheckedChange = {
                                    preserveExif = it
                                    settings.preserveExif = it
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Ink,
                                    checkedTrackColor = Amber
                                )
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("输出相册名", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = album,
                            onValueChange = {
                                album = it.take(32)
                                settings.albumName = it
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Amber,
                                unfocusedBorderColor = CardHigh,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "照片会保存到 Pictures/${album.trim().ifBlank { "FujiKon" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            item { SectionHeader("预设") }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardColor),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("已导入 ${presets.size} 个预设", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                                Text(
                                    "支持 NCP 0100 / NP3 0300、0310",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        val count = presetsViewModel.restoreSamples()
                                        snackbar.showSnackbar(
                                            if (count > 0) "已恢复 $count 个示例预设" else "示例预设已全部存在"
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CardHigh, contentColor = TextPrimary)
                            ) {
                                Text("恢复示例")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "导入方式：首页「导入预设」选择 .NCP / .NP3 文件，或在文件管理器中用 FujiKon 打开。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            item { SectionHeader("使用说明") }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardColor),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StepRow("1", "导入或选择一个尼康 Picture Control 预设")
                        StepRow("2", "在相册中勾选一张或多张照片")
                        StepRow("3", "预览界面长按对比原图，双指缩放查看细节")
                        StepRow("4", "直方图卡片点「一键优化」，本机自动校正曝光、白平衡、层次与细节")
                        StepRow("5", "编辑页点「AI」，可点「生成提示词」让 Agnes 3.0 Pro 分析画面并自动填词")
                        StepRow("6", "尽量按原图尺寸批量导出，自动保留相机信息")
                    }
                }
            }

            item { SectionHeader("关于") }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardColor),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .background(CardHigh, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("F", color = Amber, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("FujiKon", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                Text(
                                    "版本 ${BuildConfig.VERSION_NAME} · 本地渲染",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "照片与预设都保留在本机，在线预设库只读取公开接口。只有你主动点击「AI 修图」时，当前预览图才会上传至 Agnes 服务；API 密钥仅保存在本机。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "色彩渲染为第三方实现：解析文件内的 256 点色调曲线、色彩混合器与色彩分级参数，并按近似尼康基准色彩进行映射。结果可能与相机直出或尼康官方软件存在差异，属于正常现象。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "FujiKon 与尼康（Nikon）无关联，NCP / NP3 为其相机系统使用的文件格式。",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            item {
                Text(
                    "JPEG 导出为最高画质重新编码，并非二进制层面的无损；PNG 为真正无损但不写入 EXIF。",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun StepRow(number: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(22.dp)
                .background(Amber.copy(alpha = 0.16f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Text(number, color = Amber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}
