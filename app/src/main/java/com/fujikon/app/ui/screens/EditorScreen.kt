package com.fujikon.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.fujikon.app.data.LensBlur
import com.fujikon.app.data.ToneCurve
import com.fujikon.app.render.ExportManager
import com.fujikon.app.ui.components.AdjustmentsSheet
import com.fujikon.app.ui.components.CropEditorOverlay
import com.fujikon.app.ui.components.EditHistorySheet
import com.fujikon.app.ui.components.ExportOptionsSheet
import com.fujikon.app.ui.components.HistogramCard
import com.fujikon.app.ui.components.LensBlurEditorOverlay
import com.fujikon.app.ui.components.PresetPickerSheet
import com.fujikon.app.ui.components.ToneCurveEditorSheet
import com.fujikon.app.ui.components.ZoomableImage
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Danger
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.InkElevated
import com.fujikon.app.ui.theme.Outline
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import com.fujikon.app.ui.viewmodel.EditorViewModel
import com.fujikon.app.ui.viewmodel.FujiKonViewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    navController: NavHostController,
    factory: FujiKonViewModelFactory
) {
    val context = LocalContext.current
    val app = context.applicationContext as com.fujikon.app.FujiKonApp
    val viewModel: EditorViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var comparing by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showCurve by remember { mutableStateOf(false) }
    var showAdjustments by remember { mutableStateOf(false) }
    var showCrop by remember { mutableStateOf(false) }
    var showBlur by remember { mutableStateOf(false) }
    var blurInitial by remember { mutableStateOf(LensBlur.Off) }
    var showHistory by remember { mutableStateOf(false) }
    var savingCurve by remember { mutableStateOf(false) }
    var savingAdjustments by remember { mutableStateOf(false) }
    var pendingOptions by remember { mutableStateOf<ExportManager.Options?>(null) }

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val options = pendingOptions
        pendingOptions = null
        if (granted && options != null) {
            viewModel.export(options)
        } else if (!granted) {
            scope.launch { snackbar.showSnackbar("需要存储权限才能保存到相册") }
        }
    }

    fun startExport(options: ExportManager.Options) {
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingOptions = options
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.export(options)
        }
    }

    LaunchedEffect(state.index, state.presetId) {
        comparing = false
    }

    val preset = state.currentPreset
    val current = state.currentPhoto
    val exporting = state.export is EditorViewModel.ExportState.Running

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
                title = {
                    Column {
                        Text(
                            if (state.photos.isEmpty()) "查看" else "${state.index + 1} / ${state.photos.size}",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            current?.displayName ?: "选择照片",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = state.canUndo && !exporting
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "上一步",
                            tint = if (state.canUndo) TextPrimary else TextSecondary.copy(alpha = 0.45f)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = state.canRedo && !exporting
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "下一步",
                            tint = if (state.canRedo) TextPrimary else TextSecondary.copy(alpha = 0.45f)
                        )
                    }
                    IconButton(
                        onClick = { showHistory = true },
                        enabled = state.historyEntries.isNotEmpty() && !exporting
                    ) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = "历史步骤",
                            tint = if (state.historyEntries.isNotEmpty()) TextPrimary else TextSecondary.copy(alpha = 0.45f)
                        )
                    }
                    if (preset != null) {
                        TextButton(onClick = { showPicker = true }, enabled = !exporting) {
                            Text(
                                preset.name,
                                color = Amber,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(76.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink)
            )
        },
        bottomBar = {
            Surface(color = InkElevated) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    if (state.photos.size > 1) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(state.photos, key = { _, photo -> photo.id }) { index, photo ->
                                val selected = index == state.index
                                Box(
                                    Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) Amber else Outline,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable(enabled = !exporting) { viewModel.show(index) }
                                ) {
                                    AsyncImage(
                                        model = photo.uri,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showPicker = true },
                            enabled = !exporting,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                            modifier = Modifier.weight(1f).height(50.dp)
                        ) {
                            Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("预设", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = { showAdjustments = true },
                            enabled = !exporting && preset != null,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            border = BorderStroke(
                                1.dp,
                                if (state.hasAdjustments) Amber.copy(alpha = 0.7f) else Outline
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (state.hasAdjustments) Amber else TextPrimary
                            ),
                            modifier = Modifier.weight(1f).height(50.dp)
                        ) {
                            Icon(Icons.Filled.Equalizer, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("调节", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = { showCrop = true },
                            enabled = !exporting && preset != null && state.cropBase != null,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            border = BorderStroke(
                                1.dp,
                                if (state.hasCrop) Amber.copy(alpha = 0.7f) else Outline
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (state.hasCrop) Amber else TextPrimary
                            ),
                            modifier = Modifier.weight(1f).height(50.dp)
                        ) {
                            Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("裁剪", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = {
                                blurInitial = state.adjustments.lensBlur
                                showBlur = true
                            },
                            enabled = !exporting && preset != null && state.cropBase != null,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            border = BorderStroke(
                                1.dp,
                                if (state.hasLensBlur) Amber.copy(alpha = 0.7f) else Outline
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (state.hasLensBlur) Amber else TextPrimary
                            ),
                            modifier = Modifier.weight(1f).height(50.dp)
                        ) {
                            Icon(Icons.Filled.BlurOn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("模糊", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = { showCurve = true },
                            enabled = !exporting && preset != null,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            border = BorderStroke(
                                1.dp,
                                if (state.hasToneCurve) Amber.copy(alpha = 0.7f) else Outline
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (state.hasToneCurve) Amber else TextPrimary
                            ),
                            modifier = Modifier.weight(1f).height(50.dp)
                        ) {
                            Icon(Icons.Filled.Timeline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("曲线", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = { showExport = true },
                            enabled = !exporting && preset != null && state.photos.isNotEmpty(),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
                            modifier = Modifier.weight(1f).height(50.dp)
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "导出",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
            ) {
                val rendered = state.rendered
                if (rendered != null) {
                    ZoomableImage(
                        bitmap = rendered,
                        original = state.original,
                        comparing = comparing,
                        onComparingChange = { active ->
                            comparing = active
                            if (active) viewModel.markCompareHintSeen()
                        },
                        modifier = Modifier.fillMaxSize(),
                        resetKey = state.currentPhoto?.uri ?: state.index
                    )
                } else if (state.error != null) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            state.error ?: "渲染失败",
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = {
                                if (state.photos.isEmpty()) navController.popBackStack()
                                else viewModel.retry()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
                        ) {
                            Text(if (state.photos.isEmpty()) "返回" else "重试")
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Amber, strokeWidth = 2.dp)
                    }
                }

                // Histogram overlay: floating at top of preview image area,
                // full-width, transparent background matching adjustments panel.
                // While the long-press compare gesture shows the original, the
                // chart follows what is on screen instead of the edited copy.
                if (preset != null && rendered != null) {
                    HistogramCard(
                        bitmap = if (comparing) state.original ?: rendered else rendered,
                        currentAdjustments = state.adjustments,
                        onApplyAdjustments = { viewModel.setAdjustments(it) },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(0.92f)
                            .padding(top = 4.dp)
                    )
                }

                if (comparing) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        color = Amber,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            "原图",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = Ink,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (state.loading && rendered != null && !showCurve && !showAdjustments && !showCrop) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp),
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                color = Amber,
                                strokeWidth = 1.5.dp,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(7.dp))
                            Text("渲染中", color = TextPrimary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                if (!state.seenCompareHint && rendered != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                            .clickable { viewModel.markCompareHintSeen() },
                        color = Color.Black.copy(alpha = 0.62f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.TouchApp,
                                contentDescription = null,
                                tint = Amber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                "长按图片 · 对比原图   ·   双指缩放",
                                color = TextPrimary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                if (rendered != null && !exporting) {
                    if (state.index > 0) {
                        IconButton(
                            onClick = { viewModel.previous() },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 8.dp)
                                .size(38.dp)
                                .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                        ) {
                            Icon(Icons.Filled.ChevronLeft, contentDescription = "上一张", tint = TextPrimary)
                        }
                    }
                    if (state.index < state.photos.lastIndex) {
                        IconButton(
                            onClick = { viewModel.next() },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 8.dp)
                                .size(38.dp)
                                .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                        ) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = "下一张", tint = TextPrimary)
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        PresetPickerSheet(
            presets = state.presets,
            selectedId = state.presetId,
            onSelect = { selected ->
                viewModel.selectPreset(selected.id)
                showPicker = false
            },
            onDismiss = { showPicker = false },
            photo = current,
            thumbnailStore = app.container.presetThumbnailStore
        )
    }

    if (showExport) {
        ExportOptionsSheet(
            initial = viewModel.currentOptions(),
            onStart = { options ->
                showExport = false
                startExport(options)
            },
            onDismiss = { showExport = false }
        )
    }

    if (showCurve && preset != null) {
        ToneCurveEditorSheet(
            presetName = preset.name,
            baseCurve = preset.baseCurvePoints(),
            presetCurve = state.selectedPreset?.toneCurve ?: ToneCurve.linear(),
            curve = state.effectiveToneCurve,
            rendering = state.loading,
            saving = savingCurve,
            onPreview = viewModel::setToneCurve,
            onClearOverride = viewModel::clearToneCurveOverride,
            onSave = { name ->
                savingCurve = true
                scope.launch {
                    val saved = try {
                        viewModel.saveCurrentLookPreset(name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    savingCurve = false
                    if (saved != null) {
                        showCurve = false
                        snackbar.showSnackbar("已保存并切换到「${saved.name}」")
                    } else {
                        snackbar.showSnackbar("保存失败，请重试")
                    }
                }
            },
            onDismiss = { if (!savingCurve) showCurve = false }
        )
    }

    if (showAdjustments && preset != null) {
        AdjustmentsSheet(
            presetName = preset.name,
            initialAdjustments = state.adjustments,
            initialToneCurve = state.effectiveToneCurve,
            presetToneCurve = state.selectedPreset?.toneCurve ?: ToneCurve.linear(),
            baseCurve = preset.baseCurvePoints(),
            rendering = state.loading,
            saving = savingAdjustments,
            onPreview = { adjustments, field ->
                viewModel.setAdjustments(adjustments, field)
            },
            onToneCurvePreview = viewModel::setToneCurve,
            onClearToneCurve = viewModel::clearToneCurveOverride,
            onSave = { name ->
                savingAdjustments = true
                scope.launch {
                    val saved = try {
                        viewModel.saveCurrentLookPreset(name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    savingAdjustments = false
                    if (saved != null) {
                        showAdjustments = false
                        snackbar.showSnackbar("已保存并切换到「${saved.name}」")
                    } else {
                        snackbar.showSnackbar("保存失败，请重试")
                    }
                }
            },
            onDismiss = { if (!savingAdjustments) showAdjustments = false }
        )
    }

    if (showHistory) {
        EditHistorySheet(
            entries = state.historyEntries,
            currentIndex = state.historyIndex,
            photos = state.photos,
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            onSelect = { index ->
                viewModel.jumpToHistory(index)
                showHistory = false
            },
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onDismiss = { showHistory = false }
        )
    }

    when (val export = state.export) {
        is EditorViewModel.ExportState.Running -> {
            AlertDialog(
                onDismissRequest = { },
                containerColor = Card,
                title = { Text("正在导出", color = TextPrimary) },
                text = {
                    Column {
                        Text(
                            "${export.done} / ${export.total}",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Amber
                        )
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (export.total <= 0) 0f else export.done.toFloat() / export.total
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = Amber,
                            trackColor = CardHigh
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (export.currentName.isBlank()) "正在写入相册…" else export.currentName,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.cancelExport() }) {
                        Text("取消", color = TextSecondary)
                    }
                }
            )
        }

        is EditorViewModel.ExportState.Done -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissExportResult() },
                containerColor = Card,
                title = {
                    Text(
                        if (export.failures.isEmpty()) "导出完成" else "导出完成（部分失败）",
                        color = TextPrimary
                    )
                },
                text = {
                    Column {
                        Text(
                            "已保存 ${export.saved} 张到「Pictures/${export.album}」",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        if (export.failures.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Column(
                                Modifier
                                    .heightIn(max = 180.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                export.failures.take(12).forEach { failure ->
                                    Text(
                                        "• $failure",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Danger
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissExportResult() }) {
                        Text("完成", color = Amber)
                    }
                },
                dismissButton = {
                    val uri = export.firstUri?.takeIf { it.scheme == "content" }
                    if (uri != null) {
                        TextButton(onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "image/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(intent)
                            }
                            viewModel.dismissExportResult()
                        }) {
                            Text("查看照片", color = TextSecondary)
                        }
                    }
                }
            )
        }

        is EditorViewModel.ExportState.Failed -> {
            AlertDialog(
                onDismissRequest = { viewModel.dismissExportResult() },
                containerColor = Card,
                title = { Text("导出失败", color = TextPrimary) },
                text = { Text(export.message, color = TextSecondary) },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissExportResult() }) {
                        Text("知道了", color = Amber)
                    }
                }
            )
        }

        EditorViewModel.ExportState.Idle -> Unit
    }

    val cropBase = state.cropBase
    if (showCrop && cropBase != null) {
        Dialog(
            onDismissRequest = { showCrop = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = true
            )
        ) {
            CropEditorOverlay(
                bitmap = cropBase,
                initial = state.adjustments.crop,
                onApply = { crop ->
                    showCrop = false
                    viewModel.setCrop(crop)
                },
                onCancel = { showCrop = false }
            )
        }
    }

    val blurBase = state.cropBase
    if (showBlur && blurBase != null) {
        Dialog(
            onDismissRequest = {
                viewModel.setLensBlur(blurInitial)
                showBlur = false
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                // Let the window fit the system bars like the crop dialog; the
                // overlay also applies safe drawing padding for edge-to-edge.
                decorFitsSystemWindows = true
            )
        ) {
            LensBlurEditorOverlay(
                bitmap = blurBase,
                initial = blurInitial,
                onPreview = { viewModel.setLensBlur(it) },
                onApply = { lensBlur ->
                    showBlur = false
                    viewModel.setLensBlur(lensBlur)
                },
                onCancel = {
                    viewModel.setLensBlur(blurInitial)
                    showBlur = false
                }
            )
        }
    }
}
