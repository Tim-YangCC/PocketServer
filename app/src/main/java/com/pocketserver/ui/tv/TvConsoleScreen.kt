package com.pocketserver.ui.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketserver.data.model.SharedPermission
import com.pocketserver.net.QrCodec
import com.pocketserver.ui.common.Labels
import com.pocketserver.ui.common.rememberNowTick
import com.pocketserver.ui.components.Banner
import com.pocketserver.ui.components.BannerTone
import com.pocketserver.ui.components.KeyValueRow
import com.pocketserver.ui.components.PairingCodeText
import com.pocketserver.ui.components.SectionCard
import com.pocketserver.ui.components.StatusDot
import com.pocketserver.ui.vm.ConsoleViewModel
import com.pocketserver.ui.vm.SettingsViewModel
import com.pocketserver.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 大屏控制台（FR-10.2 / FR-10.4 / FR-10.5）。
 *
 * ## 这一屏要同时服务两个物理距离完全不同的场景
 *  1. **凑近了看**：用户想确认 IP、想看当前速率（视距 1 米）
 *  2. **躺在沙发上扫**：用户想用手机扫二维码（视距 2.5~3 米）
 *
 * 布局就是按这个顺序排的：左边从上往下依次是"地址 → 二维码 → IP 兜底"，
 * 越往下越"靠近"；右边是运行细节。二维码刻意做到屏幕高度的一半，
 * 因为它在电视上只有一个用途 —— 被 3 米外的手机扫到（FR-10.4）。
 *
 * ## 电视端没有的东西（有意为之）
 *  - **没有扫码入口**：电视没有摄像头（FR-10.4 的"反向配对"）
 *  - **没有配对码输入框**：遥控器输 6 位数字的体验会直接毁掉口碑（FR-10.6）
 */
@Composable
fun TvConsoleScreen() {
    val vm: ConsoleViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()

    val state by vm.state.collectAsState()
    val benchStage by vm.benchStage.collectAsState()
    val benchRunning by vm.benchRunning.collectAsState()
    val report by vm.speedReport.collectAsState()
    val settings by settingsVm.settings.collectAsState()

    val now = rememberNowTick()
    val remaining = (state.pairingCodeExpiresAt - now).coerceAtLeast(0)

    val payload = remember(state.mdnsHost, state.httpPort, state.pairingCode, state.addresses) {
        QrCodec.payload(
            alias = state.mdnsHost,
            port = state.httpPort,
            code = state.pairingCode,
            deviceName = settings.deviceName,
            ipFallback = state.addresses.firstOrNull() ?: ""
        )
    }
    @Suppress("ProduceStateDoesNotAssignValue")
    val qrBitmap by produceState<android.graphics.Bitmap?>(null, payload) {
        if (payload.isBlank()) {
            value = null
        } else {
            // 生成二维码是 CPU 密集操作，放 Default 线程；电视盒子的 CPU 通常很弱，
            // 在主线程生成会让焦点框卡住半秒，用户会以为遥控器失灵
            value = withContext(Dispatchers.Default) { QrCodec.bitmap(payload, QR_PIXELS) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 二维码边长取"屏高的一半"和"屏宽的三成"中较小的那个：
        // 在 16:9 电视上这是屏高限制的情形，能保证不把右侧状态区挤出去
        val qrSize = minOf(maxWidth * 0.30f, maxHeight * 0.50f)

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            // ==================== 左：给电脑看的信息 ====================
            Column(
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "在电脑浏览器里打开这个地址",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = state.primaryUrl,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                // 二维码容器：白底是必须的，深色背景会让不少扫码器读不出来
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .width(qrSize)
                        .aspectRatio(1f)
                        .background(Color.White, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val image = qrBitmap
                    if (image != null) {
                        Image(
                            bitmap = image.asImageBitmap(),
                            contentDescription = "接入二维码",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }

                Text(
                    text = "用手机相机扫这个码，可以直接接入，不用手输地址和配对码。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                state.fallbackUrl?.let { fallback ->
                    Text(
                        text = "设备名备用地址：$fallback",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "共享权限：${Labels.permission(state.permission)}" +
                        if (state.permission == SharedPermission.READ_ONLY) {
                            "（别人只能看和下载）"
                        } else {
                            "（别人可以上传、修改和删除文件）"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ==================== 右：配对码与运行状态 ====================
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionCard(title = "配对码（电脑第一次接入时输入）") {
                    PairingCodeText(code = state.pairingCode, isTv = true)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (remaining > 0) {
                            "还剩 ${Formatters.countdown(remaining)} 有效期，过期自动更换"
                        } else {
                            "已过期，正在生成新的…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvAction(
                        label = if (state.running) "停止服务" else "启动服务",
                        primary = !state.running,
                        onClick = { vm.toggleService() },
                        modifier = Modifier.weight(1f)
                    )
                    TvAction(
                        label = "换一个配对码",
                        onClick = { vm.rotateCode() },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    TvAction(
                        label = if (report != null) "重新测速" else "测速并诊断",
                        enabled = !benchRunning,
                        onClick = { vm.runSpeedTest() },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (benchRunning) {
                    SectionCard(title = "正在测速") {
                        Text(
                            text = benchStage?.let { "${it.name}（${it.index}/${it.total}）" } ?: "准备中…",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = {
                                (benchStage?.let { it.index.toFloat() / it.total } ?: 0f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                SectionCard(title = "运行情况") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(
                            color = if (state.running) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            label = if (state.running) "服务运行中" else "服务已停止"
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow(label = "当前连接数", value = "${state.clientCount} 条")
                    KeyValueRow(label = "正在传输", value = "${state.activeTransfers} 个")
                    KeyValueRow(
                        label = "发送速率",
                        value = Formatters.speed(state.txBytesPerSecond),
                        valueColor = MaterialTheme.colorScheme.primary,
                        valueBold = true
                    )
                    KeyValueRow(
                        label = "接收速率",
                        value = Formatters.speed(state.rxBytesPerSecond),
                        valueColor = MaterialTheme.colorScheme.secondary,
                        valueBold = true
                    )
                    KeyValueRow(
                        label = "已运行",
                        value = Formatters.duration(
                            if (state.startedAt > 0) now - state.startedAt else 0L
                        )
                    )
                    KeyValueRow(
                        label = "Wi-Fi 频段",
                        value = if (state.linkSpeedMbps > 0) {
                            "${Labels.wifiBand(state.wifiBand)} · ${state.linkSpeedMbps}Mbps"
                        } else {
                            Labels.wifiBand(state.wifiBand)
                        }
                    )
                    KeyValueRow(label = "存储通道", value = Labels.storageMode(state.storageMode))
                }

                if (report != null) {
                    SectionCard(title = "上次测速结论") {
                        Banner(
                            text = report?.advice ?: "",
                            tone = report?.let { Labels.bottleneckTone(it.bottleneck) }
                                ?: BannerTone.Info
                        )
                        Spacer(Modifier.height(10.dp))
                        KeyValueRow(
                            label = "瓶颈",
                            value = report?.let { Labels.bottleneck(it.bottleneck) } ?: "—",
                            valueBold = true
                        )
                        KeyValueRow(
                            label = "端到端",
                            value = Formatters.megaBytesPerSecond(report?.endToEndMBps ?: 0.0)
                        )
                        KeyValueRow(
                            label = "网络上限",
                            value = Formatters.megaBytesPerSecond(report?.networkMBps ?: 0.0)
                        )
                    }
                }

                state.warning?.let { Banner(text = it, tone = BannerTone.Warn) }
                state.error?.let { Banner(text = it, tone = BannerTone.Error) }

                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/**
 * 二维码位图的边长。
 *
 * 取 1024 而不是手机端的 720：电视上的二维码要被 3 米外的手机扫到，
 * 位图分辨率不足时放大后模块边缘会糊，扫码成功率会明显下降。
 * 多出来的内存（1MB 左右）在电视上完全不是问题。
 */
private const val QR_PIXELS = 1024
