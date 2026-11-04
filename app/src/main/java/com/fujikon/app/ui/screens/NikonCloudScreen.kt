package com.fujikon.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.fujikon.app.nic.NikonCloudRecipe
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
import com.fujikon.app.ui.viewmodel.NikonCloudViewModel
import com.fujikon.app.ui.viewmodel.NikonCloudViewModel.DetailState
import com.fujikon.app.ui.viewmodel.NikonCloudViewModel.ItemState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NikonCloudScreen(
    navController: NavHostController,
    viewModel: NikonCloudViewModel
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
                            "尼康云创",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            "Nikon Imaging Cloud 官方色彩方案",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.loadCatalog(force = true) },
                        enabled = !state.loading
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
                CloudHeader(
                    count = state.catalog.size,
                    loading = state.loading,
                    onRefresh = { viewModel.loadCatalog(force = true) }
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索色彩方案、创作者或说明") },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary)
                    },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
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
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    state.loading && state.catalog.isEmpty() -> CloudLoading()
                    state.error != null && state.catalog.isEmpty() -> CloudError(
                        message = state.error!!,
                        onRetry = { viewModel.loadCatalog(force = true) }
                    )
                    state.catalog.isEmpty() -> CloudEmpty()
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val presets = state.filteredCatalog
                        if (presets.isEmpty()) {
                            item {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("没有匹配的色彩方案", color = TextSecondary)
                                }
                            }
                        } else {
                            items(presets, key = { it.id }) { recipe ->
                                val detail = state.details[recipe.id]
                                LaunchedEffect(recipe.id) {
                                    viewModel.requestDetail(recipe.id)
                                }
                                CloudRecipeRow(
                                    recipe = recipe,
                                    detail = detail,
                                    status = state.statuses[recipe.id] ?: ItemState.Idle,
                                    onImport = { viewModel.importOne(recipe) },
                                    onRetryDetail = { viewModel.requestDetail(recipe.id, force = true) }
                                )
                            }
                            item {
                                Text(
                                    "色彩方案版权归 Nikon 及创作者所有。导入时按云创公开的参数生成应用内预设；" +
                                        "未公开的色调曲线等参数不会被还原，效果可能与机内直出略有差异。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudHeader(
    count: Int,
    loading: Boolean,
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
                        "NIKON IMAGING CLOUD",
                        style = MaterialTheme.typography.labelSmall,
                        color = Amber,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "官方云创滤镜",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                }
                MetaChip(if (count > 0) "$count 个" else "官方")
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "连接尼康云创公开色彩方案库，查看创作者说明后直接导入预设列表，无需尼康账号。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
            ) {
                Icon(
                    Icons.Filled.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (count > 0) "同步色彩方案" else "连接尼康云创", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CloudRecipeRow(
    recipe: NikonCloudRecipe,
    detail: DetailState?,
    status: ItemState,
    onImport: () -> Unit,
    onRetryDetail: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Card),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardHigh)
            ) {
                if (recipe.thumbnailUrl != null) {
                    AsyncImage(
                        model = recipe.thumbnailUrl,
                        contentDescription = recipe.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("NIKON", style = MaterialTheme.typography.labelSmall, color = Amber)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    recipe.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    listOfNotNull(
                        recipe.creator.takeIf { it.isNotBlank() } ?: "Nikon",
                        recipe.pictureControlName.takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when (detail) {
                        is DetailState.Ready -> detail.detail.brief.ifBlank { "官方色彩方案" }
                        DetailState.Loading, null -> "正在加载说明…"
                        is DetailState.Failed -> "说明加载失败，点击重试"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (detail is DetailState.Failed) Danger else TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (detail is DetailState.Failed) {
                        Modifier.clickable(onClick = onRetryDetail)
                    } else {
                        Modifier
                    }
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
            CloudImportAction(status = status, onImport = onImport)
        }
    }
}

@Composable
private fun CloudImportAction(
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
                Icons.Filled.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text("导入")
        }
    }
}

@Composable
private fun CloudLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Amber, strokeWidth = 2.dp)
            Spacer(Modifier.height(12.dp))
            Text("正在连接尼康云创…", color = TextSecondary)
        }
    }
}

@Composable
private fun CloudError(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
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

@Composable
private fun CloudEmpty() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("云创色彩方案列表为空", color = TextSecondary)
    }
}
