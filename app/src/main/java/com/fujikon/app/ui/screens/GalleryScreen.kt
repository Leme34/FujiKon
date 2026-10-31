package com.fujikon.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.fujikon.app.FujiKonApp
import com.fujikon.app.ui.Routes
import com.fujikon.app.ui.components.PhotoCell
import com.fujikon.app.ui.components.PresetPickerSheet
import com.fujikon.app.ui.theme.Amber
import com.fujikon.app.ui.theme.Card
import com.fujikon.app.ui.theme.CardHigh
import com.fujikon.app.ui.theme.Ink
import com.fujikon.app.ui.theme.InkElevated
import com.fujikon.app.ui.theme.TextPrimary
import com.fujikon.app.ui.theme.TextSecondary
import com.fujikon.app.ui.viewmodel.FujiKonViewModelFactory
import com.fujikon.app.ui.viewmodel.GalleryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    navController: NavHostController,
    factory: FujiKonViewModelFactory
) {
    val context = LocalContext.current
    val app = context.applicationContext as FujiKonApp
    val viewModel: GalleryViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val albumFilter by viewModel.albumFilter.collectAsStateWithLifecycle()
    val presets by app.container.presetRepository.presets.collectAsStateWithLifecycle()
    val presetId by app.container.selectionStore.presetId.collectAsStateWithLifecycle()
    val currentPhoto by app.container.selectionStore.currentPhoto.collectAsStateWithLifecycle()
    val preset = presets.firstOrNull { it.id == presetId }

    var showPicker by remember { mutableStateOf(false) }
    var albumMenuOpen by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refresh() }

    LaunchedEffect(Unit) {
        if (!app.container.photoRepository.hasReadPermission()) {
            permissionLauncher.launch(app.container.photoRepository.requiredPermissions())
        }
    }

    val visible = remember(state.photos, albumFilter) {
        if (albumFilter == null) state.photos else state.photos.filter { it.bucketId == albumFilter }
    }
    val selectionOrder = remember(selectedIds) {
        selectedIds.toList().mapIndexed { index, id -> id to index + 1 }.toMap()
    }
    val allVisibleSelected = visible.isNotEmpty() && visible.all { it.id in selectedIds }
    // Prefer the photo open in the editor, then the first selected photo, then
    // the built-in sample used by PresetThumbnail.
    val previewPhoto = currentPhoto ?: state.photos.firstOrNull { it.id in selectedIds }

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
                    Column {
                        Text("选择照片", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(
                            if (preset != null) "预设：${preset.name}" else "请选择预设",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (preset != null) TextSecondary else Amber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    if (visible.isNotEmpty()) {
                        TextButton(onClick = {
                            if (allVisibleSelected) viewModel.clearSelection()
                            else viewModel.selectAll(visible)
                        }) {
                            Text(
                                if (allVisibleSelected) "取消全选" else "全选",
                                color = Amber
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink)
            )
        },
        bottomBar = {
            Surface(color = InkElevated) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "已选 ${selectedIds.size} 张",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        TextButton(
                            onClick = { showPicker = true },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                Icons.Filled.Tune,
                                contentDescription = null,
                                tint = if (preset != null) TextSecondary else Amber,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                preset?.name ?: "选择预设",
                                color = if (preset != null) TextSecondary else Amber,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Button(
                        onClick = {
                            if (selectedIds.isEmpty()) return@Button
                            if (preset == null) {
                                showPicker = true
                            } else {
                                app.container.selectionStore.setPhotos(viewModel.chosenPhotos())
                                app.container.selectionStore.setCurrentPhoto(
                                    viewModel.chosenPhotos().firstOrNull()
                                )
                                navController.navigate(Routes.EDITOR)
                            }
                        },
                        enabled = selectedIds.isNotEmpty(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
                    ) {
                        Text("下一步", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.permissionDenied && !app.container.photoRepository.hasReadPermission() -> PermissionGate(
                    onRequest = { permissionLauncher.launch(app.container.photoRepository.requiredPermissions()) },
                    onOpenSettings = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }
                )

                state.loading && state.photos.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Amber, strokeWidth = 2.dp)
                }

                state.photos.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Filled.PhotoLibrary,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        state.error ?: "相册中没有找到照片",
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { viewModel.refresh() }) { Text("重新扫描", color = Amber) }
                }

                else -> Column(Modifier.fillMaxSize()) {
                    if (state.loading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Amber,
                            trackColor = CardHigh
                        )
                    }
                    val selectedAlbum = state.albums.firstOrNull { it.id == albumFilter }
                    val albumLabel = selectedAlbum
                        ?.let { "${it.name} (${it.count})" }
                        ?: "全部照片 (${state.photos.size})"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { albumMenuOpen = true },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Filled.PhotoLibrary,
                                    contentDescription = null,
                                    tint = Amber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "相册：$albumLabel",
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Filled.ArrowDropDown,
                                    contentDescription = "选择相册",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = albumMenuOpen,
                                onDismissRequest = { albumMenuOpen = false },
                                modifier = Modifier.heightIn(max = 420.dp)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("全部照片 (${state.photos.size})") },
                                    onClick = {
                                        viewModel.setAlbum(null)
                                        albumMenuOpen = false
                                    },
                                    trailingIcon = {
                                        if (albumFilter == null) {
                                            Icon(Icons.Filled.Check, contentDescription = null, tint = Amber)
                                        }
                                    }
                                )
                                state.albums.forEach { album ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${album.name} (${album.count})",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        onClick = {
                                            viewModel.setAlbum(album.id)
                                            albumMenuOpen = false
                                        },
                                        trailingIcon = {
                                            if (albumFilter == album.id) {
                                                Icon(Icons.Filled.Check, contentDescription = null, tint = Amber)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${visible.size} 张",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(visible, key = { it.id }) { photo ->
                            PhotoCell(
                                uri = photo.uri,
                                selected = photo.id in selectedIds,
                                selectionNumber = selectionOrder[photo.id],
                                onClick = { viewModel.toggle(photo.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        PresetPickerSheet(
            presets = presets,
            selectedId = presetId,
            onSelect = { selected ->
                app.container.selectionStore.setPreset(selected.id)
                showPicker = false
            },
            onDismiss = { showPicker = false },
            photo = previewPhoto,
            thumbnailStore = app.container.presetThumbnailStore
        )
    }
}

@Composable
private fun PermissionGate(
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(84.dp)
                .padding(0.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.PhotoLibrary,
                contentDescription = null,
                tint = Amber,
                modifier = Modifier.size(52.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "需要访问手机相册",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "FujiKon 只在本机读取你选择的照片，不会上传照片或本地预设。请授予照片访问权限后继续。",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRequest,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)
        ) {
            Text("授予照片访问权限", fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onOpenSettings) { Text("打开系统设置", color = TextSecondary) }
        Spacer(Modifier.height(6.dp))
        Text(
            "Android 14 及以上可选择「仅允许访问部分照片」",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}
