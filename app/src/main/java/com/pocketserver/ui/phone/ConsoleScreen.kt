package com.pocketserver.ui.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketserver.data.model.AccessRequest
import com.pocketserver.ui.common.Labels
import com.pocketserver.ui.common.rememberNowTick
import com.pocketserver.ui.components.Banner
import com.pocketserver.ui.components.BannerTone
import com.pocketserver.ui.components.KeyValueRow
import com.pocketserver.ui.components.PairingCodeText
import com.pocketserver.ui.components.SectionCard
import com.pocketserver.ui.components.StatusDot
import com.pocketserver.ui.vm.ConsoleViewModel
import com.pocketserver.util.Formatters
import kotlinx.coroutines.delay

/**
 * 手机端控制台（FR-1.1 ~ FR-1.9）。整个应用的主屏。
 *
 * ## 界面顺序就是用户的行动顺序
 * 从上到下依次是：**开关 → 给电脑的地址 → 配对码 → 运行情况 → 测速 → 二级入口**。
 * 新用户打开这一屏，眼睛从上往下扫一遍就知道该干什么：先开，再输地址，卡住了看配对码。
 * 把"测速"这种进阶功能放在下面，是因为它只在"我觉得慢"的时候才需要，
 * 平时总在首屏会分散注意力。
 *
 * ## 这一屏不做任何业务判断
 * 该显示 mDNS 域名还是 IP、该提示什么警告，全部由 [com.pocketserver.service.ServerController]
 * 写进 `ServerState`。界面只负责摆出来。这样电视端能保证与手机端完全一致的提示口径。
 */
@Composable
fun ConsoleScreen(
    onOpenQr: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenConnected: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val vm: ConsoleViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val benchStage by vm.benchStage.collectAsState()
    val benchRunning by vm.benchRunning.collectAsState()
    val report by vm.speedReport.collectAsState()
    val requests by vm.accessRequests.collectAsState()
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()

    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val now = rememberNowTick()

    // profile 只在服务重启时变，没必要每次重组都重新拼字符串
    val profileText = remember(state.running, state.storageMode) { vm.currentProfileDescription() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
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
            Text("控制台", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            ServiceHeaderCard(
                running = state.running,
                starting = state.starting,
                mdnsReady = state.mdnsReady,
                busy = busy,
                onToggle = { vm.toggleService() }
            )

            if (state.running) {
                AddressCard(
                    primaryUrl = state.primaryUrl,
                    fallbackUrl = state.fallbackUrl,
                    onCopy = {
                        clipboard.setText(AnnotatedString(state.primaryUrl))
                    },
                    onOpenQr = onOpenQr
                )

                PairingCard(
                    code = state.pairingCode,
                    expiresAt = state.pairingCodeExpiresAt,
                    now = now,
                    onRotate = { vm.rotateCode() }
                )

                LiveStatsCard(
                    clientCount = state.clientCount,
                    activeTransfers = state.activeTransfers,
                    txBps = state.txBytesPerSecond,
                    rxBps = state.rxBytesPerSecond,
                    uptimeMillis = if (state.startedAt > 0) now - state.startedAt else 0L,
                    wifiBand = Labels.wifiBand(state.wifiBand),
                    linkSpeedMbps = state.linkSpeedMbps,
                    storageMode = Labels.storageMode(state.storageMode),
                    profileText = profileText
                )

                SpeedTestCard(
                    running = benchRunning,
                    stageName = benchStage?.name,
                    stageIndex = benchStage?.index ?: 0,
                    stageTotal = benchStage?.total ?: 4,
                    networkMBps = report?.networkMBps ?: 0.0,
                    diskMBps = report?.diskMBps ?: 0.0,
                    endToEndMBps = report?.endToEndMBps ?: 0.0,
                    appCeilingMBps = report?.appCeilingMBps ?: 0.0,
                    advice = report?.advice,
                    bottleneckLabel = report?.let { Labels.bottleneck(it.bottleneck) },
                    bottleneckTone = report?.let { Labels.bottleneckTone(it.bottleneck) }
                        ?: BannerTone.Info,
                    onRun = { vm.runSpeedTest() }
                )
            }

            // 警告与错误分开摆：警告是"能更好"，错误是"现在就不对"，混在一起会让人分不清该不该管
            state.warning?.let { Banner(text = it, tone = BannerTone.Warn) }
            state.error?.let { Banner(text = it, tone = BannerTone.Error) }

            EntriesCard(
                onOpenLogs = onOpenLogs,
                onOpenConnected = onOpenConnected,
                onOpenSettings = onOpenSettings
            )

            Spacer(Modifier.height(4.dp))
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
        )
    }

    // 接入确认闸门（FR-2.11）：只处理队首那一条，避免同时弹多个对话框把用户搞晕
    requests.firstOrNull()?.let { request ->
        AccessConfirmDialog(
            request = request,
            queueSize = requests.size,
            onAllow = { vm.decideAccess(request.id, true) },
            onDeny = { vm.decideAccess(request.id, false) }
        )
    }
}

// ==================== 各区块 ====================

@Composable
private fun ServiceHeaderCard(
    running: Boolean,
    starting: Boolean,
    mdnsReady: Boolean,
    busy: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        color = if (running) Color(0xFF25A76B) else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (running) Icons.Default.CheckCircle else Icons.Default.PowerSettingsNew,
                contentDescription = null,
                tint = if (running) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (running) "服务运行中" else "服务未开启",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (running) Color.White
                    else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                when {
                    starting && running -> StatusDot(
                        Color.White,
                        if (mdnsReady) "设备名已就绪，网页可用设备名或 IP 访问…"
                        else "网页已可通过 IP 访问，正在准备设备名…"
                    )
                    starting -> StatusDot(MaterialTheme.colorScheme.primary, "正在启动网页服务…")
                    running -> StatusDot(
                        Color.White,
                        if (mdnsReady) "局域网内其他设备可以接入"
                        else "服务已启动，可用 IP 地址访问；设备名发现不可用"
                    )

                    else -> StatusDot(
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        "开启后才能被电脑访问"
                    )
                }
            }
            if (starting || running) {
                Spacer(Modifier.width(8.dp))
                val progress = when {
                    // HTTP 开始监听即可以通过 IP 访问；mDNS 只影响友好名称，不阻塞网页服务。
                    running -> 100
                    starting -> 24
                    else -> 0
                }
                AccessProgressRing(
                    progress = progress,
                    active = starting,
                    color = if (running) Color.White else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(10.dp))
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Switch(checked = running, onCheckedChange = { onToggle() })
            }
        }
    }
}

/** 启动阶段环形进度：HTTP 监听后进入可访问阶段，mDNS 就绪后到 100%。 */
@Composable
private fun AccessProgressRing(progress: Int, active: Boolean, color: Color) {
    val displayed by animateFloatAsState(
        targetValue = progress.toFloat(),
        animationSpec = tween(durationMillis = 700),
        label = "access-progress"
    )
    val motion = rememberInfiniteTransition(label = "access-ring-motion")
    val rotation by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = if (active) 1500 else 3200), RepeatMode.Restart),
        label = "access-ring-rotation"
    )
    val glow by motion.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse),
        label = "access-ring-glow"
    )
    val percent = displayed.toInt().coerceIn(0, 100)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.5.dp.toPx()
            drawCircle(color.copy(alpha = 0.08f + glow * 0.10f), radius = size.minDimension * 0.47f)
            drawArc(
                color = color.copy(alpha = 0.24f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke)
            )
            rotate(rotation) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(color.copy(alpha = 0.18f), color, Color(0xFF35E6D0), Color(0xFF8C7BFF), color)
                    ),
                    startAngle = -90f,
                    sweepAngle = displayed * 3.6f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
            }
        }
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun AddressCard(
    primaryUrl: String,
    fallbackUrl: String?,
    onCopy: () -> Unit,
    onOpenQr: () -> Unit
) {
    SectionCard(title = "电脑端接入地址") {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp)) {
                // 大字号是刻意的：用户要把它念出来或手敲到另一台设备上
                Text(
                    text = primaryUrl,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("复制地址")
            }
            OutlinedButton(onClick = onOpenQr, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("二维码")
            }
        }

        if (fallbackUrl != null) {
            Spacer(Modifier.height(8.dp))
            Text("设备名备用地址：$fallbackUrl", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PairingCard(
    code: String,
    expiresAt: Long,
    now: Long,
    onRotate: () -> Unit
) {
    val remaining = (expiresAt - now).coerceAtLeast(0)
    SectionCard(title = "本次配对码") {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            PairingCodeText(code = code, isTv = false, modifier = Modifier.padding(vertical = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (remaining > 0) {
                "有效期还剩 ${Formatters.countdown(remaining)}，过期会自动换一个新的"
            } else {
                "已过期，正在生成新的…"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRotate, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("立即更换配对码")
        }
    }
}

@Composable
private fun LiveStatsCard(
    clientCount: Int,
    activeTransfers: Int,
    txBps: Long,
    rxBps: Long,
    uptimeMillis: Long,
    wifiBand: String,
    linkSpeedMbps: Int,
    storageMode: String,
    profileText: String
) {
    SectionCard(title = "运行情况") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveMetric("当前连接数", "${clientCount} 条", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            LiveMetric("上传速度", Formatters.speed(txBps), MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
            LiveMetric("进行中", "${activeTransfers} 个", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text("下载 ${Formatters.speed(rxBps)}  ·  ${if (linkSpeedMbps > 0) "$wifiBand / ${linkSpeedMbps} Mbps" else wifiBand}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("运行 ${Formatters.duration(uptimeMillis)} · $storageMode · $profileText",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LiveMetric(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun SpeedTestCard(
    running: Boolean,
    stageName: String?,
    stageIndex: Int,
    stageTotal: Int,
    networkMBps: Double,
    diskMBps: Double,
    endToEndMBps: Double,
    appCeilingMBps: Double,
    advice: String?,
    bottleneckLabel: String?,
    bottleneckTone: BannerTone,
    onRun: () -> Unit
) {
    SectionCard(title = "测速与瓶颈诊断") {
        if (running) {
            Text(
                text = stageName?.let { "正在测：$it（$stageIndex/$stageTotal）" } ?: "正在准备…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { stageIndex.toFloat() / stageTotal.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth()
            )
        } else if (advice != null) {
            Banner(
                text = advice,
                tone = bottleneckTone
            )
            Spacer(Modifier.height(10.dp))
            KeyValueRow(
                label = "结论",
                value = bottleneckLabel ?: "—",
                valueBold = true
            )
            KeyValueRow(label = "网络上限", value = Formatters.megaBytesPerSecond(networkMBps))
            KeyValueRow(label = "存储上限", value = Formatters.megaBytesPerSecond(diskMBps))
            KeyValueRow(label = "应用通路上限", value = Formatters.megaBytesPerSecond(appCeilingMBps))
            KeyValueRow(
                label = "端到端实测",
                value = Formatters.megaBytesPerSecond(endToEndMBps),
                valueBold = true
            )
        } else {
            Text(
                text = "花十几秒测一下：分别测网络、存储、应用通路和真实端到端，" +
                    "然后告诉你慢在哪一环、下一步该做什么。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRun,
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Speed, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (advice != null) "重新测一次" else "开始测速")
        }
    }
}

@Composable
private fun EntriesCard(
    onOpenLogs: () -> Unit,
    onOpenConnected: () -> Unit,
    onOpenSettings: () -> Unit
) {
    SectionCard(title = "更多") {
        EntryRow(
            icon = Icons.Default.History,
            title = "访问日志",
            subtitle = "谁在什么时候访问了什么",
            onClick = onOpenLogs
        )
        EntryRow(
            icon = Icons.Default.Devices,
            title = "已连接的设备",
            subtitle = "查看授权记录，随时断开某一台",
            onClick = onOpenConnected
        )
        EntryRow(
            icon = Icons.Default.Settings,
            title = "设置",
            subtitle = "权限、端口、性能与共享范围",
            onClick = onOpenSettings
        )
    }
}

@Composable
private fun EntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 接入确认弹窗（FR-2.11）。
 *
 * 文案刻意不写"是否允许 192.168.1.7 建立 TCP 连接"，而是明确说出
 * **对方能做什么**（读你的文件、往你的存储里写文件）。用户是凭"会发生什么"做决定的，
 * 技术细节只会让人下意识点"取消"。
 */
@Composable
private fun AccessConfirmDialog(
    request: AccessRequest,
    queueSize: Int,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* 必须显式选择：误触关闭等于静默拒绝，用户会以为程序坏了 */ },
        title = { Text("有人想接入你的手机") },
        text = {
            Column {
                Text(
                    text = "${request.deviceName}（${Labels.deviceType(request.deviceType)}，" +
                        "${request.peerIp}）请求访问本机文件。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "允许后，对方可以浏览你的共享文件，并且可以上传、修改和删除文件。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (queueSize > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "另外还有 ${queueSize - 1} 个请求在排队等候。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "60 秒内不做选择会自动拒绝。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text("允许接入") } },
        dismissButton = { TextButton(onClick = onDeny) { Text("拒绝") } }
    )
}
