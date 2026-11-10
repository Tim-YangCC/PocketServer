package com.pocketserver.ui.phone

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketserver.core.AppConstants
import com.pocketserver.data.model.SharedPermission
import com.pocketserver.ui.common.Labels
import com.pocketserver.ui.components.Banner
import com.pocketserver.ui.components.BannerTone
import com.pocketserver.ui.components.AboutCard
import com.pocketserver.ui.components.SectionCard
import com.pocketserver.ui.vm.ConsoleViewModel
import com.pocketserver.ui.vm.SettingsViewModel

/**
 * 设置（FR-8）。
 *
 * ## 全篇只有一条设计原则：说清楚"改了会发生什么"
 * 一个设置项如果没有告诉用户"立即生效"还是"要重启"，用户改完发现没变化就会
 * 反复开关、最后去应用商店打一星。所以：
 *  - **立即生效**的（权限、可见性、配对策略）在改动后弹一句确认
 *  - **需要重启**的（端口、FTP 开关）在顶部挂一条常驻提示，并给一个明确的"立即重启"按钮
 *  - **当前版本不生效**的（TLS 加密）如实说明，不装作能用
 *
 * ## 危险操作单独成组
 * 「清除所有设备授权」是这里唯一有破坏性的操作，所以它被放在最后一组、并且要二次确认。
 */
@Composable
fun SettingsScreen(
    onOpenLogs: () -> Unit,
    onOpenCleanup: () -> Unit
) {
    val vm: SettingsViewModel = hiltViewModel()
    val consoleVm: ConsoleViewModel = hiltViewModel()

    val settings by vm.settings.collectAsState()
    val serverRunning by vm.serverRunning.collectAsState()
    val needsRestart by vm.needsRestart.collectAsState()
    val message by vm.message.collectAsState()

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // 「关于」卡片要显示当前 Wi-Fi 频段，这里统一订阅一次，而不是在字符串模板里现取
    val consoleState by consoleVm.state.collectAsState()

    var editName by remember { mutableStateOf(false) }
    var editHttpPort by remember { mutableStateOf(false) }
    var editFtpPort by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // 存储提示与权限状态不是 Flow，靠"服务是否运行"这个低频信号顺带刷新一次即可
    val storageHint = remember(serverRunning) { vm.storageHint() }
    val hasAllFilesAccess = remember(serverRunning) { consoleVm.hasAllFilesAccess() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            if (needsRestart) {
                Banner(
                    text = "端口或 FTP 开关的改动需要重启服务才能生效。正在传输的任务会被中断。",
                    tone = BannerTone.Warn,
                    actionLabel = "立即重启",
                    onAction = { vm.restartService() }
                )
            }

            // ==================== 设备 ====================

            SectionCard(title = "这台设备") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editName = true }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("设备名称", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "这个名字会显示在附近设备列表里，方便别人认出你",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = settings.deviceName.ifBlank { "未命名" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                SwitchRow(
                    title = "开机自动启动服务",
                    subtitle = "重启手机后自动把服务拉起来，适合固定在家的平板或电视",
                    checked = settings.autoStartOnBoot,
                    onCheckedChange = { vm.setAutoStart(it) }
                )
            }

            // ==================== 接入与安全 ====================

            SectionCard(title = "谁能进来") {
                Text(
                    text = "共享权限",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.permission == SharedPermission.READ_WRITE,
                        onClick = { vm.setPermission(SharedPermission.READ_WRITE) },
                        label = { Text("读写") }
                    )
                    FilterChip(
                        selected = settings.permission == SharedPermission.READ_ONLY,
                        onClick = { vm.setPermission(SharedPermission.READ_ONLY) },
                        label = { Text("只读") }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (settings.permission == SharedPermission.READ_WRITE) {
                        "别人可以浏览、下载，也可以上传、修改和删除文件。这是为双向对等设计的默认值。"
                    } else {
                        "别人只能浏览和下载，任何写入都会被拒绝。适合临时给别人拷资料。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    title = "每次接入都需要配对码",
                    subtitle = "关掉之后，同一个 Wi-Fi 下的设备可以直接进来。新设备首次连接时" +
                        "仍会在手机上弹窗确认。",
                    checked = settings.requirePairing,
                    onCheckedChange = { vm.setRequirePairing(it) }
                )
                SwitchRow(
                    title = "允许免确认设备直连",
                    subtitle = "已经「加入免确认」的设备可以跳过弹窗，直接接入",
                    checked = settings.trustedBypass,
                    onCheckedChange = { vm.setTrustedBypass(it) }
                )
                SwitchRow(
                    title = "显示隐藏文件和文件夹",
                    subtitle = "以点开头的文件（如 .nomedia、.thumbnails）默认不显示",
                    checked = settings.showHiddenFiles,
                    onCheckedChange = { vm.setShowHidden(it) }
                )
            }

            // ==================== 写入权限（电视端不给） ====================

            SectionCard(title = "别人能做什么") {
                SwitchRow(
                    title = "允许上传文件",
                    subtitle = "对方可以往你的存储里写新文件",
                    checked = settings.allowUpload,
                    enabled = settings.permission == SharedPermission.READ_WRITE,
                    onCheckedChange = { vm.setAllowUpload(it) }
                )
                SwitchRow(
                    title = "允许删除文件",
                    subtitle = "对方可以删除你的文件。不确定时就关掉它。",
                    checked = settings.allowDelete,
                    enabled = settings.permission == SharedPermission.READ_WRITE,
                    onCheckedChange = { vm.setAllowDelete(it) }
                )
                SwitchRow(
                    title = "允许重命名与新建文件夹",
                    subtitle = if (vm.isTv) {
                        "电视端始终不提供这一项：遥控器输入文字太难受（需规 FR-10.6）"
                    } else {
                        "允许对方改名和建目录"
                    },
                    checked = settings.allowRename && !vm.isTv,
                    enabled = settings.permission == SharedPermission.READ_WRITE && !vm.isTv,
                    onCheckedChange = { vm.setAllowRename(it) }
                )
            }

            // ==================== 网络 ====================

            SectionCard(title = "端口与协议") {
                ClickableRow(
                    title = "网页版端口",
                    value = settings.httpPort.toString(),
                    subtitle = "电脑浏览器用这个端口访问，默认 8080",
                    onClick = { editHttpPort = true }
                )
                SwitchRow(
                    title = "同时开启 FTP 通道",
                    subtitle = "给只认 FTP 的老设备（旧电视、NAS、打印机）留的兼容入口，" +
                        "平时不需要可以关掉",
                    checked = settings.ftpEnabled,
                    onCheckedChange = { vm.setFtpEnabled(it) }
                )
                ClickableRow(
                    title = "FTP 端口",
                    value = settings.ftpPort.toString(),
                    subtitle = if (settings.ftpEnabled) {
                        "默认 2121（不用 21，避免与系统服务冲突）"
                    } else {
                        "FTP 通道已关闭，这个端口不会监听"
                    },
                    enabled = settings.ftpEnabled,
                    onClick = { editFtpPort = true }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "改端口后需要重启服务生效。网页版与 FTP 走的都是明文，" +
                        "局域网内部的传输不需要额外加密，加密会让速度直接减半。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ==================== 性能 ====================

            SectionCard(title = "传输性能") {
                Text("大文件并行路数", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "单个超过 ${AppConstants.CHUNK_THRESHOLD_BYTES / 1024 / 1024}MB 的文件会拆成几路同时传。" +
                        "路数不是越多越好：无线链路的总带宽是固定的，路数过多反而互相抢带宽。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.laneOverride == 0,
                        onClick = { vm.setLaneOverride(0) },
                        label = { Text("自动") }
                    )
                    listOf(2, 3, 4, 6).forEach { lane ->
                        FilterChip(
                            selected = settings.laneOverride == lane,
                            onClick = { vm.setLaneOverride(lane) },
                            label = { Text("$lane 路") }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    title = "启用加密通道（TLS）",
                    subtitle = "当前版本尚未启用。局域网内明文传输速度接近加密的两倍，" +
                        "跨网访问才有必要开加密。",
                    checked = settings.tlsEnabled,
                    onCheckedChange = { vm.setTls(it) }
                )
            }

            // ==================== 生命周期 ====================

            SectionCard(title = "省电与常驻") {
                Text("无活动时自动停止", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "没有设备连接、也没有传输任务，持续这么久之后自动关掉服务，省电。" +
                        "选「不自动停止」就一直是常驻状态（更耗电，但半夜也能连上）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "不停", 5 to "5 分", 10 to "10 分", 30 to "30 分", 60 to "1 小时")
                        .forEach { (minutes, label) ->
                            FilterChip(
                                selected = settings.idleStopMinutes == minutes,
                                onClick = { vm.setIdleStopMinutes(minutes) },
                                label = { Text(label) }
                            )
                        }
                }
            }

            // ==================== 后台保活（FR-13） ====================

            /*
             * 放在「省电与常驻」之后、存储之前：
             * 它讲的是"息屏之后还能不能传"，正好接在"多久没活动就自动停"这个话题后面，
             * 两者是同一条思路（服务到底活不活）的两端。
             */
            KeepAliveSettingsSection(vm = vm, onOpenCleanup = onOpenCleanup)

            // ==================== 存储 ====================

            SectionCard(title = "共享的存储范围") {
                Text(
                    text = storageHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasAllFilesAccess) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                Spacer(Modifier.height(10.dp))
                if (hasAllFilesAccess) {
                    Banner(
                        text = "已使用直连通道：支持零拷贝与多路并行，这是最快的通路。",
                        tone = BannerTone.Success
                    )
                } else {
                    OutlinedButton(
                        onClick = {
                            consoleVm.allFilesAccessIntent()?.let { intent ->
                                runCatching { context.startActivity(intent) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("去开启「所有文件访问权限」")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "共享根目录固定为手机内部存储的根，不提供「自选共享目录」的开关：" +
                        "让用户在一棵不熟悉的目录树上挑根，出错的概率比收益高得多。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ==================== 维护 ====================

            SectionCard(title = "维护") {
                ClickableRow(
                    title = "访问日志",
                    value = "查看",
                    subtitle = "谁在什么时候访问了什么",
                    onClick = onOpenLogs
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.restartService() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (serverRunning) "重启服务" else "启动服务")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.DeleteSweep,
                        contentDescription = null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("清除所有设备授权", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "清除之后，所有已连接和免确认的设备都会失效，下次必须重新输配对码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onOpenLogs,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("查看访问日志与诊断信息")
                }
            }

            AboutCard {
                Text(
                    text = "把手机变成局域网文件服务器：电脑端零安装、零输入即可接入。" +
                        "传输全程在本地网络内完成，不经过任何第三方服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Banner(
                    text = "当前连接方式：${Labels.wifiBand(consoleState.wifiBand)}。" +
                        "如果用的是 2.4GHz，换成 5GHz 的 Wi-Fi 名称能让传输快上好几倍。",
                    tone = BannerTone.Info
                )
            }

            Spacer(Modifier.height(8.dp))
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
        )
    }

    // ==================== 对话框 ====================

    if (editName) {
        SimpleTextDialog(
            title = "设备名称",
            label = "名称",
            initial = settings.deviceName,
            numeric = false,
            onDismiss = { editName = false },
            onConfirm = {
                editName = false
                vm.setDeviceName(it)
            }
        )
    }

    if (editHttpPort) {
        SimpleTextDialog(
            title = "网页版端口",
            label = "端口号（1024 ~ 65535）",
            initial = settings.httpPort.toString(),
            numeric = true,
            onDismiss = { editHttpPort = false },
            onConfirm = {
                editHttpPort = false
                vm.setHttpPort(it.toInt())
            }
        )
    }

    if (editFtpPort) {
        SimpleTextDialog(
            title = "FTP 端口",
            label = "端口号（1024 ~ 65535）",
            initial = settings.ftpPort.toString(),
            numeric = true,
            onDismiss = { editFtpPort = false },
            onConfirm = {
                editFtpPort = false
                vm.setFtpPort(it.toInt())
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除所有设备授权？") },
            text = {
                Text(
                    "所有已连接的设备会立刻失效，免确认名单也会清空。" +
                        "下次连接必须重新输入配对码。这个操作不会删除任何文件。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearPresence()
                }) { Text("确认清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

// ==================== 复用行组件 ====================

/**
 * 开关行。
 *
 * [enabled] 为 false 时**仍然显示当前值但不允许点** —— 直接隐藏整个开关会让用户
 * 以为"这个功能没了"，而灰掉的开关能顺带告诉他"这项被权限模式管着"。
 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ClickableRow(
    title: String,
    value: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/**
 * 单字段输入对话框。名称与端口共用。
 *
 * [numeric] 为 true 时校验"必须是 1024~65535 的整数"，并且**不把非法值传给上层** ——
 * 端口输错会导致服务起不来，而"起不来"的报错信息对普通用户毫无意义，
 * 所以在输入这一步就拦住。
 */
@Composable
private fun SimpleTextDialog(
    title: String,
    label: String,
    initial: String,
    numeric: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    val isName = !numeric

    val error: String? = when {
        value.isBlank() -> if (isName) "名称不能为空" else null
        isName && (value.contains('/') || value.contains('\\')) -> "名称里不能包含斜杠"
        !isName && value.toIntOrNull() == null -> "请填写数字"
        !isName && (value.toInt() < 1024 || value.toInt() > 65535) -> "端口范围是 1024 ~ 65535"
        else -> null
    }
    val valid = error == null && value.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = if (numeric) it.filter { ch -> ch.isDigit() }.take(5) else it
                    },
                    label = { Text(label) },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = error,
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
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
