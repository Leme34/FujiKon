package com.fujikon.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.fujikon.app.FujiKonApp
import com.fujikon.app.data.Preset
import com.fujikon.app.ui.Routes
import com.fujikon.app.ui.components.CurveChart
import com.fujikon.app.ui.components.MetaChip
import com.fujikon.app.ui.components.SectionHeader
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card as CardColor
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Danger
import com.fujikon.app.ui.theme.HueColors
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.InkElevated
import com.fujikon.app.ui.theme.Outline
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import com.fujikon.app.ui.viewmodel.FujiKonViewModelFactory
import com.fujikon.app.ui.viewmodel.PresetsViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetDetailScreen(
    navController: NavHostController,
    presetId: String?,
    viewModel: PresetsViewModel,
    factory: FujiKonViewModelFactory
) {
    val context = LocalContext.current
    val app = context.applicationContext as FujiKonApp
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val preset = presets.firstOrNull { it.id == presetId }
    val scope = rememberCoroutineScope()

    var showRename by remember { mutableStateOf(false) }
    var showNote by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    if (preset == null && !ready) {
        Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber)
        }
        return
    }

    if (preset == null) {
        Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("预设不存在或已被删除", color = TextSecondary)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { navController.popBackStack() }) { Text("返回", color = Amber) }
            }
        }
        return
    }

    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                    }
                },
                title = {
                    Text(
                        preset.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { viewModel.update(preset.copy(favorite = !preset.favorite)) }
                    }) {
                        Icon(
                            if (preset.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "收藏",
                            tint = if (preset.favorite) Amber else TextSecondary
                        )
                    }
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "删除", tint = Danger)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink)
            )
        },
        bottomBar = {
            Surface(color = Ink) {
                Button(
                    onClick = {
                        app.container.selectionStore.setPreset(preset.id)
                        navController.navigate(Routes.GALLERY)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
                ) {
                    Text("选择照片套用", fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.name, style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MetaChip(preset.format)
                            MetaChip(preset.baseProfileName)
                            if (preset.hasCurve) MetaChip("自定义曲线", Amber)
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardColor),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("色调曲线", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (preset.hasCurve) "256 点 LUT" else "线性",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .background(CardHigh, RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            CurveChart(
                                points = previewPoints(preset),
                                modifier = Modifier.fillMaxSize(),
                                lineColor = Amber,
                                showSpectrum = true
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Text("暗部 0", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Spacer(Modifier.weight(1f))
                            Text("输入亮度", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Spacer(Modifier.weight(1f))
                            Text("255", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                    }
                }
            }

            item { SectionHeader("参数") }

            item {
                val params = parameterList(preset)
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardColor),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        params.chunked(2).forEachIndexed { index, row ->
                            if (index > 0) Spacer(Modifier.height(14.dp))
                            Row {
                                row.forEach { param ->
                                    Column(Modifier.weight(1f)) {
                                        Text(param.first, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                        Spacer(Modifier.height(3.dp))
                                        Text(param.second, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            if (preset.colorBlender.any { it.hue != 0 || it.chroma != 0 || it.brightness != 0 }) {
                item { SectionHeader("色彩混合器 Color Blender") }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardColor),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val names = listOf("红", "橙", "黄", "绿", "青", "蓝", "紫", "洋红")
                            preset.colorBlender.take(8).forEachIndexed { index, value ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .background(HueColors[index % HueColors.size], RoundedCornerShape(50))
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(names[index], style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                    Spacer(Modifier.width(14.dp))
                                    Text(
                                        "色相 ${signed(value.hue)}   彩度 ${signed(value.chroma)}   明度 ${signed(value.brightness)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (preset.colorGrading.highlights != com.fujikon.app.ncp.ColorGradingZone() ||
                preset.colorGrading.midtones != com.fujikon.app.ncp.ColorGradingZone() ||
                preset.colorGrading.shadows != com.fujikon.app.ncp.ColorGradingZone()
            ) {
                item { SectionHeader("色彩分级 Color Grading") }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardColor),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf(
                                "高光" to preset.colorGrading.highlights,
                                "中间调" to preset.colorGrading.midtones,
                                "阴影" to preset.colorGrading.shadows
                            ).forEach { (label, zone) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.width(56.dp))
                                    Text(
                                        "H ${zone.hue}°   彩度 ${signed(zone.chroma)}   明度 ${signed(zone.brightness)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextSecondary
                                    )
                                }
                            }
                            Row {
                                Text(
                                    "混合 ${preset.colorGrading.blending}   平衡 ${signed(preset.colorGrading.balance)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            item { SectionHeader("备注", action = {
                TextButton(onClick = {
                    draft = preset.note
                    showNote = true
                }) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("编辑")
                }
            }) }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardColor),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            preset.note.ifBlank { "还没有备注。可以记录这个预设适合的场景、曝光建议等。" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (preset.note.isBlank()) TextSecondary else TextPrimary
                        )
                        if (!preset.description.isNullOrBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text("文件注释", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Spacer(Modifier.height(3.dp))
                            Text(preset.description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }
            }

            item { SectionHeader("文件信息") }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardColor),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoRow("格式", "${preset.format} · 版本 ${preset.version.ifBlank { "—" }}")
                        InfoRow("基准", preset.baseProfileName)
                        InfoRow("源文件", preset.sourceName)
                        InfoRow("大小", "%.1f KB".format(Locale.US, preset.fileSize / 1024f))
                        InfoRow(
                            "导入时间",
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(preset.importedAt))
                        )
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        draft = preset.name
                        showRename = true
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("重命名预设", color = TextPrimary)
                }
            }
        }
    }

    if (showRename) {
        NameDialog(
            title = "重命名预设",
            initial = draft,
            onDismiss = { showRename = false },
            onConfirm = { value ->
                showRename = false
                if (value.isNotBlank()) {
                    scope.launch { viewModel.update(preset.copy(name = value.trim().take(40))) }
                }
            }
        )
    }

    if (showNote) {
        NameDialog(
            title = "预设备注",
            initial = draft,
            singleLine = false,
            onDismiss = { showNote = false },
            onConfirm = { value ->
                showNote = false
                scope.launch { viewModel.update(preset.copy(note = value.take(500))) }
            }
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            containerColor = CardColor,
            title = { Text("删除预设？", color = TextPrimary) },
            text = {
                Text(
                    "将从应用中移除「${preset.name}」。已经导出的照片不受影响。",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    scope.launch {
                        viewModel.delete(preset.id)
                        navController.popBackStack()
                    }
                }) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消", color = TextSecondary) }
            }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.width(72.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    singleLine: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardColor,
        title = { Text(title, color = TextPrimary) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 4,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Amber,
                    unfocusedBorderColor = CardHigh,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("保存", color = Amber) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        }
    )
}

private fun previewPoints(preset: Preset): List<Pair<Float, Float>> {
    val curve = preset.curve ?: return listOf(0f to 0f, 1f to 1f)
    return (0..64).map { i ->
        val idx = (i * 255 / 64).coerceIn(0, 255)
        (idx / 255f) to (curve[idx] / 32767f)
    }
}

private fun parameterList(preset: Preset): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    fun add(label: String, value: Float) {
        if (value != 0f) result.add(label to formatFloat(value))
    }
    fun addInt(label: String, value: Int) {
        if (value != 0) result.add(label to signed(value))
    }

    add("锐化", preset.sharpening)
    add("清晰度", preset.clarity)
    add("中间锐化", preset.midRangeSharpening)
    addInt("对比度", preset.contrast)
    addInt("亮度", preset.brightness)
    addInt("饱和度", preset.saturation)
    addInt("色相", preset.hue)
    addInt("高光", preset.highlights)
    addInt("阴影", preset.shadows)
    addInt("白色", preset.whites)
    addInt("黑色", preset.blacks)
    if (preset.filter != 0) result.add("单色滤镜" to monoFilterName(preset.filter))
    if (preset.toning != 0) result.add("色调" to toningName(preset.toning))
    addInt("色调强度", preset.toningStrength)

    if (result.isEmpty()) result.add("调整" to "无（仅曲线）")
    return result
}

private fun formatFloat(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    return if (rounded > 0f) "+$rounded" else "$rounded"
}

private fun signed(value: Int): String = if (value > 0) "+$value" else "$value"

private fun monoFilterName(value: Int): String = when (value) {
    1 -> "黄"
    2 -> "橙"
    3 -> "红"
    4 -> "绿"
    else -> "关闭"
}

private fun toningName(value: Int): String = when (value % 9) {
    1 -> "深褐"
    2 -> "蓝晒"
    3 -> "红"
    4 -> "黄"
    5 -> "绿"
    6 -> "蓝"
    7 -> "紫"
    else -> "黑白"
}
