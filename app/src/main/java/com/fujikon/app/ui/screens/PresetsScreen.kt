package com.fujikon.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.fujikon.app.FujiKonApp
import com.fujikon.app.data.PresetFilter
import com.fujikon.app.data.PresetListFilter
import com.fujikon.app.ui.Routes
import com.fujikon.app.ui.components.PresetPickerSheet
import com.fujikon.app.ui.components.SectionHeader
import com.fujikon.app.ui.components.SwipeRevealPresetCard
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.Outline
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import com.fujikon.app.ui.viewmodel.FujiKonViewModelFactory
import com.fujikon.app.ui.viewmodel.PresetsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen(
    navController: NavHostController,
    viewModel: PresetsViewModel,
    factory: FujiKonViewModelFactory
) {
    val context = LocalContext.current
    val app = context.applicationContext as FujiKonApp
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val deletedPresets by viewModel.deletedPresets.collectAsStateWithLifecycle()
    val ready by viewModel.ready.collectAsStateWithLifecycle()
    val previewPhoto by app.container.selectionStore.previewPhoto.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var showPicker by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(PresetListFilter.ALL) }
    // 「全部 / 已收藏 / 已删除」三种视图叠加名称 / 说明搜索。已删除列表
    // 是独立数据源，软删除条目不会出现在前两种视图里。
    val visiblePresets = remember(presets, deletedPresets, filter, searchQuery) {
        PresetFilter.forHome(presets, deletedPresets, filter, searchQuery)
    }
    val sectionTitle = when (filter) {
        PresetListFilter.ALL -> "我的预设"
        PresetListFilter.FAVORITE -> "已收藏"
        PresetListFilter.DELETED -> "已删除"
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val result = viewModel.importUris(uris)
            importing = false
            val message = buildString {
                if (result.imported.isNotEmpty()) append("已导入 ${result.imported.size} 个预设")
                if (result.duplicates.isNotEmpty()) {
                    if (isNotEmpty()) append("，")
                    append("${result.duplicates.size} 个重复")
                }
                if (result.errors.isNotEmpty()) {
                    if (isNotEmpty()) append("，")
                    append("${result.errors.size} 个失败")
                }
            }.ifBlank { "没有导入任何文件" }
            snackbar.showSnackbar(message)
            if (result.errors.isNotEmpty()) importErrors = result.errors
        }
    }

    fun openImport() = importLauncher.launch(arrayOf("*/*"))

    fun startFlow() {
        // 先去相册选照片，再选预设
        navController.navigate(Routes.GALLERY)
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "FujiKon",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            "尼康预设 · 手机显影",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Ink,
                    titleContentColor = TextPrimary
                )
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Amber, strokeWidth = 2.dp)
                }

                // 所有预设都被删掉时也必须能进入列表切到「已删除」恢复，
                // 否则用户会被困在空首页且永远找不回已删除的预设。
                presets.isEmpty() && deletedPresets.isEmpty() -> EmptyHome(
                    onImport = ::openImport,
                    onNikonPc = { navController.navigate(Routes.NIKONPC) },
                    onNikonCloud = { navController.navigate(Routes.NIKON_CLOUD) },
                    onSamples = {
                        scope.launch {
                            val count = viewModel.restoreSamples()
                            snackbar.showSnackbar(if (count > 0) "已恢复 $count 个示例预设" else "示例预设已存在")
                        }
                    }
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(200.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        HeroCard(
                            count = presets.size,
                            importing = importing,
                            onStart = ::startFlow,
                            onImport = ::openImport,
                            onNikonPc = { navController.navigate(Routes.NIKONPC) },
                            onNikonCloud = { navController.navigate(Routes.NIKON_CLOUD) }
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionHeader(
                            title = "$sectionTitle (${visiblePresets.size})",
                            action = {
                                Row {
                                    TextButton(onClick = { navController.navigate(Routes.NIKONPC) }) {
                                        Icon(
                                            Icons.Filled.CloudDownload,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("nikonpc")
                                    }
                                    TextButton(onClick = ::openImport) {
                                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("导入")
                                    }
                                }
                            }
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("presets_search_field"),
                            singleLine = true,
                            placeholder = { Text("搜索预设名称或说明") },
                            leadingIcon = {
                                Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary)
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "清除搜索",
                                            tint = TextSecondary
                                        )
                                    }
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Amber,
                                unfocusedBorderColor = Outline,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = Amber
                            )
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = filter == PresetListFilter.ALL,
                                onClick = { filter = PresetListFilter.ALL },
                                label = { Text("全部") },
                                modifier = Modifier.testTag("presets_filter_all"),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Amber,
                                    selectedLabelColor = Ink
                                )
                            )
                            FilterChip(
                                selected = filter == PresetListFilter.FAVORITE,
                                onClick = { filter = PresetListFilter.FAVORITE },
                                label = { Text("已收藏") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                modifier = Modifier.testTag("presets_filter_favorite"),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Amber,
                                    selectedLabelColor = Ink
                                )
                            )
                            FilterChip(
                                selected = filter == PresetListFilter.DELETED,
                                onClick = { filter = PresetListFilter.DELETED },
                                label = { Text("已删除 (${deletedPresets.size})") },
                                modifier = Modifier.testTag("presets_filter_deleted"),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Amber,
                                    selectedLabelColor = Ink
                                )
                            )
                        }
                    }
                    val emptyMessage = when {
                        searchQuery.isNotBlank() ->
                            "没有匹配「$searchQuery」的预设，试试名称或说明里的其他关键词。"
                        filter == PresetListFilter.FAVORITE ->
                            "还没有收藏的预设。点击卡片右上角的星标即可收藏。"
                        filter == PresetListFilter.DELETED ->
                            "已删除列表是空的。左滑任意预设并点击「删除」，它会移到这里等待恢复。"
                        presets.isEmpty() ->
                            "所有预设都已删除，点击上方「已删除」即可恢复。"
                        else -> null
                    }
                    if (visiblePresets.isEmpty() && emptyMessage != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                emptyMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(vertical = 20.dp)
                            )
                        }
                    }
                    items(visiblePresets, key = { it.id }) { preset ->
                        val isDeletedView = filter == PresetListFilter.DELETED
                        val restoreAction: (() -> Unit)? = if (isDeletedView) {
                            {
                                scope.launch {
                                    viewModel.restore(preset.id)
                                    snackbar.showSnackbar("已恢复「${preset.name}」")
                                }
                            }
                        } else null
                        SwipeRevealPresetCard(
                            preset = preset,
                            onClick = { navController.navigate(Routes.presetDetail(preset.id)) },
                            onDelete = {
                                scope.launch {
                                    if (isDeletedView) {
                                        viewModel.deletePermanently(preset.id)
                                        snackbar.showSnackbar("已彻底删除「${preset.name}」")
                                    } else {
                                        viewModel.delete(preset.id)
                                        snackbar.showSnackbar("已移到「已删除」，可在已删除列表恢复")
                                    }
                                }
                            },
                            onRestore = restoreAction,
                            deleted = isDeletedView,
                            onFavoriteClick = { scope.launch { viewModel.toggleFavorite(preset) } },
                            thumbnailStore = app.container.presetThumbnailStore,
                            photo = previewPhoto,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "preset_card_${preset.id}"
                        )
                    }
                }
            }

            if (importing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = Amber,
                    trackColor = CardHigh
                )
            }
        }
    }

    if (showPicker) {
        PresetPickerSheet(
            presets = presets,
            selectedId = null,
            onSelect = { preset ->
                app.container.selectionStore.setPreset(preset.id)
                showPicker = false
                navController.navigate(Routes.GALLERY)
            },
            onDismiss = { showPicker = false },
            photo = previewPhoto,
            thumbnailStore = app.container.presetThumbnailStore,
            onOpenDetail = { selected ->
                showPicker = false
                navController.navigate(Routes.presetDetail(selected.id))
            }
        )
    }

    if (importErrors.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { importErrors = emptyList() },
            containerColor = Card,
            title = { Text("部分文件未导入", color = TextPrimary) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    importErrors.take(10).forEach { error ->
                        Text("• $error", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { importErrors = emptyList() }) { Text("知道了", color = Amber) }
            }
        )
    }
}

@Composable
private fun HeroCard(
    count: Int,
    importing: Boolean,
    onStart: () -> Unit,
    onImport: () -> Unit,
    onNikonPc: () -> Unit,
    onNikonCloud: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(CardHigh, Card)),
                RoundedCornerShape(24.dp)
            )
            .border(1.dp, Outline, RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        Column {
            Text(
                "NIKON PICTURE CONTROL",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "把尼康色彩\n装进口袋",
                style = MaterialTheme.typography.displaySmall,
                color = TextPrimary
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "导入 .NCP / .NP3 预设，或从 nikonpc.com 获取社区预设，批量套用到相册照片。长按对比原图，双指缩放细节，尽量保留原图尺寸并保留相机信息。",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onStart,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
                    enabled = !importing
                ) {
                    Text("选照片套用", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onImport,
                    shape = RoundedCornerShape(12.dp),
                    enabled = !importing
                ) {
                    Text("导入预设", color = TextPrimary)
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onNikonPc,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !importing
            ) {
                Icon(
                    Icons.Filled.CloudDownload,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("从 nikonpc 下载", color = TextPrimary)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onNikonCloud,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = !importing
            ) {
                Icon(
                    Icons.Filled.CloudDownload,
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("尼康云创官方滤镜", color = TextPrimary)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (importing) "正在解析预设…" else "照片不上传 · $count 个预设",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun EmptyHome(
    onImport: () -> Unit,
    onNikonPc: () -> Unit,
    onNikonCloud: () -> Unit,
    onSamples: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(30.dp))
        Box(
            Modifier
                .size(96.dp)
                .background(Card, RoundedCornerShape(28.dp))
                .border(1.dp, Outline, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Tune,
                contentDescription = null,
                tint = Amber,
                modifier = Modifier.size(44.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "先导入一个尼康预设",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "支持 Nikon Picture Control 的 .NCP 与 .NP3 文件。\n也可以直接从 nikonpc.com 在线获取社区预设。",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onImport,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
        ) {
            Text("导入预设文件", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onNikonPc,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = Amber,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("从 nikonpc 下载", color = TextPrimary)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onNikonCloud,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = Amber,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("尼康云创官方滤镜", color = TextPrimary)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onSamples,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("加载示例预设", color = TextPrimary)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "已内置 Agfa Optima、NC 400、Negative Cyan 等 4 个示例、" +
                "云创滤镜 Vitality Film by Peng Mango（芒果小明），" +
                "以及社区拟合的「Peng Mango 生命力风格（社区近似，非官方）」",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}
