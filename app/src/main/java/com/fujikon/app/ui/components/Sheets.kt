package com.fujikon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fujikon.app.data.PhotoItem
import com.fujikon.app.data.Preset
import com.fujikon.app.render.ExportManager
import com.fujikon.app.render.PresetThumbnailStore
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.InkElevated
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetPickerSheet(
    presets: List<Preset>,
    selectedId: String?,
    onSelect: (Preset) -> Unit,
    onDismiss: () -> Unit,
    photo: PhotoItem? = null,
    thumbnailStore: PresetThumbnailStore? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = InkElevated,
        dragHandle = null
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .background(CardHigh, RoundedCornerShape(50))
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(16.dp))
            Text("选择预设", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                "预览：${photo?.displayName ?: "内置样张"} · 长按照片可查看原图，双指缩放查看细节",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(16.dp))
            if (presets.isEmpty()) {
                Text(
                    "还没有预设，请先在首页导入 .NCP / .NP3 文件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(presets, key = { it.id }) { preset ->
                        val selected = preset.id == selectedId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) CardHigh else Card.copy(alpha = 0.55f),
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable { onSelect(preset) }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Card)
                            ) {
                                val fallback: @Composable () -> Unit = {
                                    Box(Modifier.fillMaxSize().padding(7.dp)) {
                                        CurveChart(
                                            points = preset.effectiveCurvePoints(32),
                                            modifier = Modifier.fillMaxSize(),
                                            showSpectrum = false,
                                            showDiagonal = false,
                                            gridAlpha = 0.2f
                                        )
                                    }
                                }
                                if (thumbnailStore != null) {
                                    PresetThumbnail(
                                        photo = photo,
                                        preset = preset,
                                        store = thumbnailStore,
                                        modifier = Modifier.fillMaxSize(),
                                        targetSize = 180,
                                        placeholder = fallback
                                    )
                                } else {
                                    fallback()
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    preset.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${preset.format} · ${preset.baseProfileName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Amber,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportOptionsSheet(
    initial: ExportManager.Options,
    onStart: (ExportManager.Options) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var format by remember { mutableStateOf(if (initial.format == "PNG") "PNG" else "JPEG") }
    var preserveExif by remember { mutableStateOf(initial.preserveExif) }
    var album by remember { mutableStateOf(initial.album) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = InkElevated,
        dragHandle = null
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .navigationBarsPadding()
        ) {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .background(CardHigh, RoundedCornerShape(50))
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(18.dp))
            Text("导出到相册", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "尽量按原图尺寸输出；内存不足时自动降到可处理的最大尺寸。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(20.dp))

            Text("格式", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = format == "JPEG",
                    onClick = { format = "JPEG" },
                    label = { Text("JPEG · 最高画质") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Amber,
                        selectedLabelColor = Ink
                    )
                )
                FilterChip(
                    selected = format == "PNG",
                    onClick = { format = "PNG" },
                    label = { Text("PNG · 无损") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Amber,
                        selectedLabelColor = Ink
                    )
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("保留相机信息 (EXIF)", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                    Text(
                        if (format == "PNG") "PNG 格式不支持写入相机 EXIF" else "保留机型、镜头、光圈、快门、ISO 与 GPS",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = preserveExif && format == "JPEG",
                    onCheckedChange = { preserveExif = it },
                    enabled = format == "JPEG",
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Ink,
                        checkedTrackColor = Amber
                    )
                )
            }

            Spacer(Modifier.height(18.dp))
            Text("输出相册", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = album,
                onValueChange = { album = it.take(32) },
                singleLine = true,
                placeholder = { Text("FujiKon") },
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
                "保存位置：Pictures/${album.trim().ifBlank { "FujiKon" }}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    onStart(
                        ExportManager.Options(
                            format = format,
                            album = album.trim().ifBlank { "FujiKon" },
                            preserveExif = preserveExif && format == "JPEG"
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
            ) {
                Text("开始导出", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
