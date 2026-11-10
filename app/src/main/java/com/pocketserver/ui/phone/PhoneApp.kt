package com.pocketserver.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pocketserver.data.model.NearbyDevice
import com.pocketserver.ui.components.EmptyState
import com.pocketserver.ui.components.TvFocusableCard
import com.pocketserver.ui.vm.NearbyViewModel
import com.pocketserver.ui.vm.SettingsViewModel

private data class PhoneTab(val route: String, val label: String, val icon: ImageVector)

private val PHONE_TABS = listOf(
    PhoneTab("console", "控制台", Icons.Default.WifiTethering),
    PhoneTab("nearby", "附近", Icons.Default.Devices),
    PhoneTab("files", "文件", Icons.Default.Folder),
    PhoneTab("transfers", "传输", Icons.Default.SwapVert),
    PhoneTab("settings", "设置", Icons.Default.Settings)
)

/**
 * 手机端应用外壳。
 *
 * ## 底栏只放 5 个，其余都收进二级页
 * 需求里手机端有 9 个页面，但底栏超过 5 个就会挤成图标墙、点错率上升。
 * 所以「访问日志 / 已连接设备」放控制台与设置的二级入口，
 * 「接入引导」只在首次启动时出现一次。
 */
@Composable
fun PhoneApp(
    pendingShare: List<String> = emptyList(),
    onShareHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val settings by settingsViewModel.settings.collectAsState()

    val nearbyViewModel: NearbyViewModel = hiltViewModel()
    val shareTargets by nearbyViewModel.devices.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val nearbyMessage by nearbyViewModel.message.collectAsState()
    LaunchedEffect(nearbyMessage) {
        nearbyMessage?.let {
            snackbarHostState.showSnackbar(it)
            nearbyViewModel.consumeMessage()
        }
    }

    // 首次启动先看引导（FR-9 首启引导）
    if (!settings.onboarded) {
        OnboardingScreen(onFinish = { settingsViewModel.setOnboarded(true) })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                PHONE_TABS.forEach { tab ->
                    val selected = currentRoute?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo("console") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "console",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable("console") {
                ConsoleScreen(
                    onOpenQr = { navController.navigate("qr") },
                    onOpenLogs = { navController.navigate("logs") },
                    onOpenConnected = { navController.navigate("connected") },
                    onOpenSettings = { navController.navigate("settings") }
                )
            }
            composable("nearby") { NearbyScreen() }
            composable("files") { FilesScreen() }
            composable("transfers") { TransfersScreen() }
            composable("settings") {
                SettingsScreen(
                    onOpenLogs = { navController.navigate("logs") },
                    onOpenCleanup = { navController.navigate("cleanup") }
                )
            }
            composable("logs") { LogsScreen() }
            composable("connected") { ConnectedScreen() }
            composable("qr") { QrDisplayScreen() }
            // 清理页做成独立路由而不是设置页里的一段：它有自己的扫描/清理异步状态，
            // 混在设置页里会让那个页面一边滚动一边跳数字
            composable("cleanup") { StorageCleanupScreen(onBack = { navController.popBackStack() }) }
        }
    }

    // 系统「分享到」进来的文件：先让用户选目标设备，再推送（FR-11.6）
    if (pendingShare.isNotEmpty()) {
        ShareTargetSheet(
            files = pendingShare,
            devices = shareTargets,
            onDismiss = onShareHandled,
            onPick = { device ->
                pendingShare.forEach { nearbyViewModel.push(device, it) }
                onShareHandled()
            }
        )
    }
}

/**
 * 分享目标选择面板的紧凑实现。
 *
 * 刻意不用 ModalBottomSheet 的实验 API 之外的任何东西：这里只需要一个
 * "列出设备 + 点一下"的面板，用最朴素的实现最不容易在低端机上出问题。
 */
@Composable
private fun ShareTargetSheet(
    files: List<String>,
    devices: List<NearbyDevice>,
    onDismiss: () -> Unit,
    onPick: (NearbyDevice) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    if (!expanded) return

    androidx.compose.material3.AlertDialog(
        onDismissRequest = {
            expanded = false
            onDismiss()
        },
        title = { Text("发送到哪台设备？") },
        text = {
            Column {
                Text(
                    "已准备 ${files.size} 个文件。选择目标设备后会立即开始推送。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (devices.isEmpty()) {
                    Text(
                        "暂时没发现其他设备。请确认对方已开启口袋服务器、并且连的是同一个 Wi-Fi。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(devices, key = { it.deviceId }) { device ->
                            TvFocusableCard(onClick = { onPick(device) }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "${device.host} · ${if (device.reachable) "在线" else "离线"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                expanded = false
                onDismiss()
            }) { Text("取消") }
        }
    )
}
