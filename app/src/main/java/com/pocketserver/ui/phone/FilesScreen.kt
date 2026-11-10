package com.pocketserver.ui.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketserver.data.storage.StorageEntry
import com.pocketserver.ui.components.Banner
import com.pocketserver.ui.components.BannerTone
import com.pocketserver.ui.components.EmptyState
import com.pocketserver.ui.components.KeyValueRow
import com.pocketserver.ui.components.SectionCard
import com.pocketserver.ui.vm.FilesViewModel
import com.pocketserver.util.Formatters

/**
 * 本机文件浏览（FR-3 的手机端镜像）。
 *
 * ## 它为什么存在
 * 推送文件时要挑源文件、拉取文件时要看落地结果，这两件事都要求用户能直接看本机目录。
 * 没有这一页，手机端的对等传输就只能靠"猜路径"。
 *
 * ## 为什么不做"点开文件"
 * 在本机用系统播放器打开一个文件，需要为每个目录建 FileProvider 授权，
 * 而共享根往往是整个内部存储 —— 那等于给所有应用开了一道任意读取的门。
 * 想预览文件，正确的地方是电脑端的网页版（`/view` 路由）。
 * 这一页只做**管理**：浏览、新建、重命名、删除。
 */
@Composable
fun FilesScreen() {
    val vm: FilesViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val message by vm.message.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    var actionTarget by remember { mutableStateOf<StorageEntry?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<StorageEntry?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "本机文件",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "这些就是共享给电脑的内容",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { vm.open(state.path) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }

            Spacer(Modifier.height(10.dp))

            // 存储通道与剩余空间：用户判断"能不能再传一个大文件"就靠这两行
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Default.Storage, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(10.dp).size(24.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("剩余空间", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (state.freeSpace > 0) Formatters.bytes(state.freeSpace) else "暂不可用",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    if (state.totalSpace > 0) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("已使用 ${Formatters.bytes((state.totalSpace - state.freeSpace).coerceAtLeast(0))}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("共 ${Formatters.bytes(state.totalSpace)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (state.totalSpace > 0) {
                    Spacer(Modifier.height(10.dp))
                    val usedFraction = ((state.totalSpace - state.freeSpace).coerceAtLeast(0).toFloat() /
                        state.totalSpace.toFloat()).coerceIn(0f, 1f)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(progress = { usedFraction }, modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                        Text("${(usedFraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(state.storageMode.ifBlank { "正在读取存储信息…" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!state.canWrite) {
                    Spacer(Modifier.height(8.dp))
                    Banner(
                        text = "当前是只读模式，别人不能往这台设备写文件。可在设置里改成读写。",
                        tone = BannerTone.Warn
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索文件或文件夹") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (searchQuery.isNotEmpty()) ({
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "清除搜索")
                    }
                }) else null,
                shape = MaterialTheme.shapes.large
            )

            Spacer(Modifier.height(8.dp))

            // 路径栏 + 操作
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (state.path.isBlank()) "共享根目录" else "/${state.path}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
                OutlinedButton(
                    onClick = { vm.up() },
                    enabled = state.path.isNotBlank()
                ) { Text("上一级") }
            }

            Spacer(Modifier.height(10.dp))

            val error = state.error
            when {
                state.loading -> Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                error != null -> Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }

                state.entries.isEmpty() -> EmptyState(
                    title = "这个目录是空的",
                    hint = "在电脑上打开网页版把文件传上来，或者点上面的「新建文件夹」先建一个。"
                )

                state.entries.none { it.name.contains(searchQuery, ignoreCase = true) } -> EmptyState(
                    title = "没有匹配的文件",
                    hint = "试试其他文件名，或清除搜索条件。"
                )

                else -> Surface(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    val visibleEntries = state.entries.filter { it.name.contains(searchQuery, ignoreCase = true) }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
                        items(visibleEntries, key = { it.relPath }) { entry ->
                            EntryRow(
                                entry = entry,
                                onOpen = {
                                    if (entry.isDirectory) vm.open(entry.relPath) else actionTarget = entry
                                },
                                onMore = { actionTarget = entry }
                            )
                            if (entry != visibleEntries.lastOrNull()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        item { Spacer(Modifier.height(4.dp)) }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
        )
        if (state.canTextOperations && state.canWrite) {
            FloatingActionButton(
                onClick = { showNewFolder = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) { Icon(Icons.Default.Add, contentDescription = "新建文件夹") }
        }
    }

    // 条目操作面板
    actionTarget?.let { entry ->
        EntryActionDialog(
            entry = entry,
            canText = state.canTextOperations,
            canDelete = state.canWrite,
            onDismiss = { actionTarget = null },
            onRename = {
                actionTarget = null
                renameTarget = entry
            },
            onDelete = {
                actionTarget = null
                vm.delete(entry)
            }
        )
    }

    // 新建文件夹
    if (showNewFolder) {
        TextInputDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            initial = "",
            confirmLabel = "创建",
            onDismiss = { showNewFolder = false },
            onConfirm = {
                showNewFolder = false
                vm.createFolder(it)
            }
        )
    }

    // 重命名
    renameTarget?.let { entry ->
        TextInputDialog(
            title = "重命名",
            label = "新名称",
            initial = entry.name,
            confirmLabel = "确定",
            onDismiss = { renameTarget = null },
            onConfirm = {
                renameTarget = null
                vm.rename(entry, it)
            }
        )
    }
}

// ==================== 列表行 ====================

@Composable
private fun EntryRow(
    entry: StorageEntry,
    onOpen: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when {
                entry.isDirectory -> Icons.Default.Folder
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            tint = if (entry.isDirectory) {
                androidx.compose.ui.graphics.Color(0xFFF4AA22)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (entry.isDirectory) {
                    if (entry.childCount >= 0) "文件夹 · ${entry.childCount} 项" else "文件夹"
                } else {
                    "${Formatters.bytes(entry.size)} · ${Formatters.relative(entry.lastModified)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (entry.isDirectory) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "打开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "查看信息",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
        }
    }
}

// ==================== 对话框 ====================

@Composable
private fun EntryActionDialog(
    entry: StorageEntry,
    canText: Boolean,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            Column {
                KeyValueRow(
                    label = "类型",
                    value = if (entry.isDirectory) "文件夹" else (entry.mimeType.ifBlank { "文件" })
                )
                if (entry.isFile) {
                    KeyValueRow(label = "大小", value = Formatters.bytes(entry.size))
                }
                KeyValueRow(label = "修改时间", value = Formatters.dateTime(entry.lastModified))
                KeyValueRow(label = "相对路径", value = "/${entry.relPath}")

                if (!canText) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "电视端不提供重命名（遥控器输入文字太难受），请用手机或电脑操作。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!canDelete) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "当前是只读模式，不能删除文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (canText) {
                TextButton(onClick = onRename) {
                    Icon(
                        Icons.Default.DriveFileRenameOutline,
                        contentDescription = null,
                        Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("重命名")
                }
            }
        },
        dismissButton = {
            Row {
                if (canDelete) {
                    TextButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

/** 通用单行文本输入对话框（新建文件夹 / 重命名共用）。 */
@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    val valid = value.isNotBlank() && !value.contains('/') && !value.contains('\\')

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    isError = value.isNotBlank() && !valid,
                    modifier = Modifier.fillMaxWidth()
                )
                if (value.isNotBlank() && !valid) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "名称里不能包含斜杠或反斜杠。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = valid
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
