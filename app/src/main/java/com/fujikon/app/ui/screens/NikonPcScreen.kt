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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.fujikon.app.nikonpc.NikonPcPreset
import com.fujikon.app.ui.components.MetaChip
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Danger
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.Outline
import com.fujikon.app.ui.theme.Success
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import com.fujikon.app.ui.viewmodel.NikonPcViewModel
import com.fujikon.app.ui.viewmodel.NikonPcViewModel.ItemState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NikonPcScreen(
    navController: NavHostController,
    viewModel: NikonPcViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
        viewModel.clearNotice()
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = TextPrimary
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            "nikonpc 在线预设",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            "Nikon Picture Control 社区库",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.loadCatalog(force = true) },
                        enabled = !state.loading && !state.importingAll
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "刷新",
                            tint = if (state.loading) TextSecondary.copy(alpha = 0.4f) else TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                CatalogHeader(
                    catalogCount = state.catalog.size,
                    importableCount = state.importableCount,
                    allDone = state.allDone,
                    loading = state.loading,
                    importingAll = state.importingAll,
                    onImportAll = viewModel::importAll,
                    onRefresh = { viewModel.loadCatalog(force = true) }
                )
                Spacer(Modifier.height(10.dp))
                SearchField(
                    query = state.query,
                    onQueryChange = viewModel::setQuery
                )
                if (state.loading && state.catalog.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Amber,
                        trackColor = CardHigh
                    )
                }
                state.error?.takeIf { state.catalog.isNotEmpty() }?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "刷新失败：$error",
                        style = MaterialTheme.typography.bodySmall,
                        color = Danger
                    )
                }
                if (state.importingAll) {
                    Spacer(Modifier.height(10.dp))
                    ImportProgressCard(
                        done = state.progressDone,
                        total = state.progressTotal,
                        currentName = state.progressName,
                        onCancel = viewModel::cancelImportAll
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    state.loading && state.catalog.isEmpty() -> LoadingState()
                    state.error != null && state.catalog.isEmpty() -> ErrorState(
                        message = state.error!!,
                        onRetry = { viewModel.loadCatalog(force = true) }
                    )
                    else -> RemotePresetList(
                        presets = state.filteredCatalog,
                        allCatalogEmpty = state.catalog.isEmpty(),
                        statuses = state.statuses,
                        onImport = viewModel::importOne
                    )
                }
            }
        }
    }

    if (state.errors.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::clearErrors,
            containerColor = Card,
            title = { Text("部分预设未能导入", color = TextPrimary) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    state.errors.forEach { error ->
                        Text(
                            "• $error",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::clearErrors) {
                    Text("知道了", color = Amber)
                }
            }
        )
    }
}

@Composable
private fun CatalogHeader(
    catalogCount: Int,
    importableCount: Int,
    allDone: Boolean,
    loading: Boolean,
    importingAll: Boolean,
    onImportAll: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "NIKONPC.COM",
                        style = MaterialTheme.typography.labelSmall,
                        color = Amber,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "在线 Picture Control",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                }
                MetaChip(if (catalogCount > 0) "$catalogCount 个" else "在线")
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "由社区维护的 Nikon Picture Control 集合。导入时从 nikonpc.com 获取预设参数并生成 NCP 文件，不会修改原始文件。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onImportAll,
                    modifier = Modifier.weight(1f),
                    enabled = catalogCount > 0 && importableCount > 0 && !importingAll && !loading,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
                ) {
                    Text(
                        when {
                            allDone -> "已全部导入"
                            importableCount > 0 -> "全部导入 ($importableCount)"
                            else -> "全部导入"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !loading && !importingAll,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("刷新", color = TextPrimary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "预设版权归原作者所有；批量导入首次下载约 300 次请求，之后会使用本地缓存。",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("搜索名称、分类或槽位") },
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "清除搜索", tint = TextSecondary)
                }
            }
        },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Amber,
            unfocusedBorderColor = Outline,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedPlaceholderColor = TextSecondary,
            unfocusedPlaceholderColor = TextSecondary
        )
    )
}

@Composable
private fun ImportProgressCard(
    done: Int,
    total: Int,
    currentName: String,
    onCancel: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardHigh),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "正在导入在线预设",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        if (currentName.isBlank()) {
                            "$done / $total"
                        } else {
                            "$done / $total · $currentName"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onCancel) {
                    Text("取消", color = Danger)
                }
            }
            Spacer(Modifier.height(8.dp))
            val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Outline, RoundedCornerShape(50))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .background(Amber, RoundedCornerShape(50))
                )
            }
        }
    }
}

@Composable
private fun RemotePresetList(
    presets: List<NikonPcPreset>,
    allCatalogEmpty: Boolean,
    statuses: Map<String, ItemState>,
    onImport: (NikonPcPreset) -> Unit
) {
    if (allCatalogEmpty) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("在线预设列表为空", color = TextSecondary)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (presets.isEmpty()) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("没有匹配的预设", color = TextSecondary)
                }
            }
        } else {
            items(presets, key = { it.path }) { preset ->
                RemotePresetRow(
                    preset = preset,
                    status = statuses[preset.path] ?: ItemState.Idle,
                    onImport = { onImport(preset) }
                )
            }
        }
    }
}

@Composable
private fun RemotePresetRow(
    preset: NikonPcPreset,
    status: ItemState,
    onImport: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(CardHigh, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    preset.slot,
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    preset.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${preset.categoryLabel} · NCP · 槽位 ${preset.slot}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                (status as? ItemState.Failed)?.let { failed ->
                    Spacer(Modifier.height(3.dp))
                    Text(
                        failed.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = Danger,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            ImportAction(status = status, onImport = onImport)
        }
    }
}

@Composable
private fun ImportAction(
    status: ItemState,
    onImport: () -> Unit
) {
    when (status) {
        ItemState.Importing -> CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            color = Amber,
            strokeWidth = 2.dp
        )
        ItemState.Imported -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text("已导入", style = MaterialTheme.typography.labelSmall, color = Success)
        }
        ItemState.Duplicate -> Text(
            "已存在",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        is ItemState.Failed -> TextButton(onClick = onImport) {
            Text("重试", color = Amber)
        }
        ItemState.Idle -> TextButton(onClick = onImport) {
            Icon(
                Icons.Filled.Download,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text("导入")
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Amber, strokeWidth = 2.dp)
            Spacer(Modifier.height(14.dp))
            Text("正在获取 nikonpc 预设列表…", color = TextSecondary)
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(42.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "无法加载在线预设",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
            ) {
                Text("重试", fontWeight = FontWeight.Bold)
            }
        }
    }
}
