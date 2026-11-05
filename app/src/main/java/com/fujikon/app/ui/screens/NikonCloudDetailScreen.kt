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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.fujikon.app.nic.NikonCloudDetail
import com.fujikon.app.nic.NikonCloudRecipe
import com.fujikon.app.ui.components.MetaChip
import com.fujikon.app.ui.components.SectionHeader
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card as CardColor
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Danger
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.Success
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import com.fujikon.app.ui.viewmodel.NikonCloudViewModel
import com.fujikon.app.ui.viewmodel.NikonCloudViewModel.DetailState
import com.fujikon.app.ui.viewmodel.NikonCloudViewModel.ItemState
import java.util.Locale

/**
 * Full detail page for one Nikon Imaging Cloud recipe: the complete creator
 * description plus every published picture-control parameter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NikonCloudDetailScreen(
    navController: NavHostController,
    recipeId: String?,
    viewModel: NikonCloudViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recipe = state.catalog.firstOrNull { it.id == recipeId }
    val detailState = recipeId?.let { state.details[it] }
    val status = recipeId?.let { state.statuses[it] } ?: ItemState.Idle

    LaunchedEffect(recipeId) {
        if (!recipeId.isNullOrBlank()) viewModel.requestDetail(recipeId)
    }

    Scaffold(
        containerColor = Ink,
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
                    Text(
                        recipe?.name ?: "色彩方案详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink)
            )
        },
        bottomBar = {
            if (recipe != null) {
                Surface(color = Ink) {
                    CloudDetailImportBar(
                        status = status,
                        onImport = { viewModel.importOne(recipe) }
                    )
                }
            }
        }
    ) { padding ->
        if (recipe == null) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("色彩方案不存在或列表已刷新", color = TextSecondary)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { navController.popBackStack() }) {
                        Text("返回", color = Amber)
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(CardHigh)
                    ) {
                        val thumbnail = recipe.thumbnailUrl
                            ?: (detailState as? DetailState.Ready)?.detail?.thumbnailUrl
                        if (thumbnail != null) {
                            AsyncImage(
                                model = thumbnail,
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
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            recipe.name,
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            listOfNotNull(
                                recipe.creator.takeIf { it.isNotBlank() },
                                recipe.pictureControlName.takeIf { it.isNotBlank() }
                            ).joinToString(" · ").ifBlank { "Nikon Imaging Cloud" },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MetaChip("官方云创")
                            if (recipe.isNew) MetaChip("NEW", Amber)
                            recipe.publishedDate.take(10).takeIf { it.isNotBlank() }?.let {
                                MetaChip(it)
                            }
                        }
                    }
                }
            }

            when (detailState) {
                DetailState.Loading, null -> item { CloudDetailLoading() }
                is DetailState.Failed -> item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardColor),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                detailState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = Danger
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                if (!recipeId.isNullOrBlank()) {
                                    viewModel.requestDetail(recipeId, force = true)
                                }
                            }) { Text("重新加载", color = Amber) }
                        }
                    }
                }
                is DetailState.Ready -> item {
                    CloudDetailContent(detailState.detail)
                }
            }
        }
    }
}

@Composable
private fun CloudDetailContent(detail: NikonCloudDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("说明")
        Card(
            colors = CardDefaults.cardColors(containerColor = CardColor),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    detail.note.ifBlank { detail.brief.ifBlank { "官方未提供说明。" } },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                if (detail.brief.isNotBlank() && detail.brief != detail.note) {
                    Spacer(Modifier.height(10.dp))
                    Text(detail.brief, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }

        SectionHeader("拍摄参数")
        Card(
            colors = CardDefaults.cardColors(containerColor = CardColor),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CloudInfoRow("相机", detail.camera ?: "—")
                CloudInfoRow("镜头", detail.lens ?: "—")
            }
        }

        SectionHeader("色彩方案参数")
        Card(
            colors = CardDefaults.cardColors(containerColor = CardColor),
            shape = RoundedCornerShape(20.dp)
        ) {
            CloudParameterGrid(detail)
        }
    }
}

@Composable
private fun CloudParameterGrid(detail: NikonCloudDetail) {
    val control = detail.control
    val params = listOf(
        "基准" to control.baseName,
        "锐化" to formatSignedFloat(control.sharpening),
        "中范围锐化" to formatSignedFloat(control.middleRangeSharp),
        "清晰度" to formatSignedFloat(control.clarity),
        "饱和度" to formatSigned(control.saturation),
        "对比度" to formatZone(control.contrast),
        "高光" to formatZone(control.highlight),
        "阴影" to formatZone(control.shadow),
        "白阶" to formatZone(control.whiteLevel),
        "黑阶" to formatZone(control.blackLevel)
    )
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        params.chunked(2).forEach { row ->
            Row {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Spacer(Modifier.height(3.dp))
                        Text(value, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(
            "标记为“曲线控制”的参数由尼康未公开的色调曲线决定，应用内按基准 Picture Control 生成近似曲线。",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun CloudInfoRow(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.width(56.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
    }
}

@Composable
private fun CloudDetailLoading() {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = Amber, strokeWidth = 2.dp)
    }
}

@Composable
private fun CloudDetailImportBar(
    status: ItemState,
    onImport: () -> Unit
) {
    when (status) {
        ItemState.Importing -> Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Amber, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("正在导入…", color = TextSecondary)
        }
        ItemState.Imported -> Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Success, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("已导入到我的预设", color = Success)
        }
        ItemState.Duplicate -> Text(
            "该色彩方案已在预设列表中",
            color = TextSecondary,
            modifier = Modifier.padding(16.dp)
        )
        is ItemState.Failed -> Column(Modifier.padding(16.dp)) {
            Text(status.message, style = MaterialTheme.typography.bodySmall, color = Danger)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
            ) { Text("重试导入", fontWeight = FontWeight.Bold) }
        }
        ItemState.Idle -> Button(
            onClick = onImport,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
        ) {
            Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("导入到我的预设", fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatSigned(value: Int): String =
    if (value > 0) "+$value" else value.toString()

private fun formatSignedFloat(value: Float): String {
    if (value > -0.001f && value < 0.001f) return "0"
    val text = String.format(Locale.US, "%.2f", value)
    return if (value > 0) "+$text" else text
}

private fun formatZone(value: Int?): String =
    value?.let(::formatSigned) ?: "曲线控制"
