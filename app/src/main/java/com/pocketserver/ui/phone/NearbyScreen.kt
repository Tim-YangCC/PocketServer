package com.pocketserver.ui.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketserver.data.model.DeviceType
import com.pocketserver.data.model.NearbyDevice
import com.pocketserver.net.PeerClient
import com.pocketserver.ui.common.Labels
import com.pocketserver.ui.components.Banner
import com.pocketserver.ui.components.BannerTone
import com.pocketserver.ui.components.EmptyState
import com.pocketserver.ui.components.SectionCard
import com.pocketserver.ui.vm.ConsoleViewModel
import com.pocketserver.ui.vm.FilesViewModel
import com.pocketserver.ui.vm.NearbyViewModel
import com.pocketserver.util.Formatters

/**
 * 附近设备（FR-11.1 ~ FR-11.6）。手机之间的双向对等传输入口。
 *
 * ## 这一页要回答的问题
 * "局域网里还有谁开着口袋服务器？我能拿谁的文件、能给谁发文件？"
 * 所以每台设备卡片展开后只有四个动作：**推给它、看它的文件、加入免确认、移除**。
 * 不引入"配对管理"这类抽象概念 —— 用户脑子里的模型只有"这台设备我信不信它"。
 *
 * ## 为什么推送前先挑文件
 * 用户点"推送到这台设备"时，脑子里想的是"我要把刚才那张照片发过去"。
 * 所以第一个动作必须是**挑文件**，而不是先问"从哪个目录推送"。
 */
@Composable
fun NearbyScreen() {
    val vm: NearbyViewModel = hiltViewModel()
    val consoleVm: ConsoleViewModel = hiltViewModel()

    val devices by vm.devices.collectAsState()
    val message by vm.message.collectAsState()
    val working by vm.working.collectAsState()
    val remembered by vm.remembered.collectAsState()
    val remote by vm.remote.collectAsState()
    val serverState by consoleVm.state.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    var expandedId by remember { mutableStateOf<String?>(null) }
    var pushTarget by remember { mutableStateOf<NearbyDevice?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "附近设备",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("同一 Wi-Fi · 在线 ${devices.count { it.reachable }} 台",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (working) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    OutlinedButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("刷新")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 服务没开就一定发现不到任何人，先说清楚，免得用户一直点刷新
            if (!serverState.running) {
                Banner(
                    text = "本机服务还没开启。开启后既能被别人发现，也能发现别人。",
                    tone = BannerTone.Warn
                )
                Spacer(Modifier.height(10.dp))
            }

            if (devices.isEmpty()) {
                EmptyState(
                    title = "还没有发现其他设备",
                    hint = "请依次确认三件事：1）对方的手机或电视也打开了口袋服务器；" +
                        "2）两台设备连的是同一个 Wi-Fi；3）路由器没有开启「AP 隔离」" +
                        "（这个功能会让同一 Wi-Fi 下的设备互相看不见）。"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(devices, key = { it.deviceId }) { device ->
                        val trusted = remembered
                            .firstOrNull { it.deviceId == device.deviceId }
                            ?.trusted == true
                        DeviceCard(
                            device = device,
                            trusted = trusted,
                            expanded = expandedId == device.deviceId,
                            onToggle = {
                                expandedId =
                                    if (expandedId == device.deviceId) null else device.deviceId
                            },
                            onPush = { pushTarget = device },
                            onBrowse = { vm.browseRemote(device) },
                            onToggleTrust = { vm.toggleTrust(device) },
                            onForget = { vm.forget(device) }
                        )
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
        )
    }

    // 1) 挑本机文件 → 推送
    pushTarget?.let { target ->
        FilePickerDialog(
            title = "选择要发给「${target.name}」的文件",
            onDismiss = { pushTarget = null },
            onPick = { relPath ->
                vm.push(target, relPath)
                pushTarget = null
            }
        )
    }

    // 2) 浏览对端目录 → 拉取
    remote?.let { session ->
        RemoteBrowseDialog(
            session = session,
            onUp = { vm.remoteUp() },
            onRefresh = { vm.remoteRefresh() },
            onOpen = { vm.pullEntry(it) },
            onDismiss = { vm.closeRemote() }
        )
    }
}

// ==================== 设备卡片 ====================

@Composable
private fun DeviceCard(
    device: NearbyDevice,
    trusted: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPush: () -> Unit,
    onBrowse: () -> Unit,
    onToggleTrust: () -> Unit,
    onForget: () -> Unit
) {
    SectionCard {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = deviceTypeIcon(device.type),
                    contentDescription = null,
                    tint = if (device.reachable) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = device.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (trusted) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "免确认",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (device.reachable) "在线" else "可能已离线",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (device.reachable) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = device.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(Icons.Default.ChevronRight,
                        contentDescription = "更多设备信息", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPush, modifier = Modifier.weight(1f), enabled = device.reachable) {
                        Icon(Icons.Default.Upload, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("推送文件")
                    }
                    OutlinedButton(onClick = onBrowse, modifier = Modifier.weight(1f), enabled = device.reachable) {
                        Icon(Icons.Default.Download, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("浏览文件")
                    }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text("设备类型：${Labels.deviceType(device.type)} · 共享权限：${Labels.permission(device.permission)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onToggleTrust, modifier = Modifier.weight(1f)) {
                        Text(if (trusted) "取消免确认" else "加入免确认")
                    }
                    TextButton(onClick = onForget, modifier = Modifier.weight(1f)) {
                        Text("移除记录")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "首次连接时，对方屏幕上会弹出一个确认框，需要对方点「允许」。" +
                        "加入免确认后，这台设备以后就不用再确认了。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun deviceTypeIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.TV -> Icons.Default.Tv
    DeviceType.PC -> Icons.Default.Laptop
    else -> Icons.Default.Smartphone
}

// ==================== 通用：一行文件/目录 ====================

/**
 * 目录行。两个对话框（本机选择、对端浏览）共用，靠 [trailingIcon] 区分"选中"与"拉取"。
 *
 * 抽出来不只是省代码：两处的行高、图标、字号必须完全一致，
 * 否则用户会在两个长得很像的弹窗之间犯迷糊。
 */
@Composable
private fun EntryRow(
    name: String,
    isDirectory: Boolean,
    size: Long,
    trailingIcon: ImageVector?,
    trailingDescription: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (!isDirectory) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = Formatters.bytes(size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = trailingDescription,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 对话框公共外壳：统一的圆角、内边距与高度上限。 */
@Composable
private fun PickerShell(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    footer: @Composable () -> Unit,
    // 必须是 ColumnScope：调用方要在里面用 Modifier.weight 撑满剩余高度，
    // 普通 () -> Unit 拿不到 weight 这个扩展函数
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                content()
                Spacer(Modifier.height(10.dp))
                footer()
            }
        }
    }
}

// ==================== 本机文件选择对话框 ====================

/**
 * 推送前的文件选择器。
 *
 * 用对话框而不是二级页面：选完文件马上要回到设备卡片继续操作，
 * 跳转整页再返回会丢掉"我刚才在跟哪台设备说话"的上下文。
 */
@Composable
private fun FilePickerDialog(
    title: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val vm: FilesViewModel = hiltViewModel()
    val state by vm.state.collectAsState()

    PickerShell(
        title = title,
        subtitle = if (state.path.isBlank()) "共享根目录" else "/${state.path}",
        onDismiss = onDismiss,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.up() },
                    enabled = state.path.isNotBlank()
                ) { Text("上一级") }
            }
            Spacer(Modifier.height(8.dp))

            val error = state.error
            when {
                state.loading -> CenterBox { CircularProgressIndicator() }

                error != null -> CenterBox {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                else -> LazyColumn(Modifier.fillMaxHeight(0.8f)) {
                    items(state.entries, key = { it.relPath }) { entry ->
                        EntryRow(
                            name = entry.name,
                            isDirectory = entry.isDirectory,
                            size = entry.size,
                            trailingIcon = if (entry.isDirectory) null else Icons.Default.PlayArrow,
                            trailingDescription = "选择这个文件",
                            onClick = {
                                if (entry.isDirectory) {
                                    vm.open(entry.relPath)
                                } else {
                                    onPick(entry.relPath)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ==================== 对端目录浏览对话框 ====================

@Composable
private fun RemoteBrowseDialog(
    session: NearbyViewModel.RemoteBrowse,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (PeerClient.PeerEntry) -> Unit,
    onDismiss: () -> Unit
) {
    PickerShell(
        title = session.deviceName,
        subtitle = if (session.path.isBlank()) "对方共享根目录" else "/${session.path}",
        onDismiss = onDismiss,
        footer = {
            Column {
                Text(
                    text = "点文件即开始拉取到本机共享根目录；遇到同名文件会自动改名，不会覆盖已有文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onUp, enabled = !session.atRoot) { Text("上一级") }
                OutlinedButton(onClick = onRefresh) { Text("刷新") }
            }
            Spacer(Modifier.height(8.dp))

            val error = session.error
            when {
                session.loading -> CenterBox { CircularProgressIndicator() }

                error != null -> CenterBox {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                session.entries.isEmpty() -> CenterBox {
                    Text(
                        text = "这个目录是空的",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyColumn(Modifier.fillMaxHeight(0.8f)) {
                    items(session.entries, key = { it.path }) { entry ->
                        EntryRow(
                            name = entry.name,
                            isDirectory = entry.isDirectory,
                            size = entry.size,
                            trailingIcon = if (entry.isDirectory) null else Icons.Default.Download,
                            trailingDescription = "拉取到本机",
                            onClick = { onOpen(entry) }
                        )
                    }
                }
            }
        }
    }
}

/** 对话框内的居中占位区域（加载中 / 错误 / 空）。 */
@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center
    ) { content() }
}
