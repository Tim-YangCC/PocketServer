package com.pocketserver.ui.phone

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.pocketserver.core.AppConstants
import com.pocketserver.data.repo.CacheJanitor
import com.pocketserver.ui.components.Banner
import com.pocketserver.ui.components.BannerTone
import com.pocketserver.ui.components.SectionCard
import com.pocketserver.ui.vm.SettingsViewModel
import com.pocketserver.ui.vm.StorageCleanupViewModel
import com.pocketserver.util.Formatters

/**
 * 设置页里的「后台与保活」组（FR-13）。
 *
 * ## 为什么单独抽一个文件而不是塞进 SettingsScreen
 * 这一组和设置页其它内容有一个本质区别：**它管的不是应用自己的偏好，而是系统侧的行为**。
 * 用户在这里做的不是"打开一个开关"，而是"去系统设置里改一项"。这段逻辑要处理
 * 跳转失败、要在回到前台时重新取状态、要按机型给不同的路径指引 —— 放在设置页里
 * 会把那个已经很长的文件撑到难以维护。
 *
 * ## 为什么必须让用户看见白名单状态
 * 这一项比所有唤醒锁加起来都关键：Doze（息屏 + 静止 + 未充电）下系统**直接掐断应用网络**，
 * 而"手机放桌上当服务器"恰好就是这三个条件。前台服务能延缓 Doze，但豁免不了它。
 * 用户在别处抱怨"后台就断"，根因八成在这里，所以状态必须明晃晃地摆出来。
 */
@Composable
fun KeepAliveSettingsSection(
    vm: SettingsViewModel,
    onOpenCleanup: () -> Unit
) {
    val context = LocalContext.current
    val serverRunning by vm.serverRunning.collectAsState()

    /*
     * 电池优化白名单是**系统设置**，用户跳到系统页去改，系统不会给应用任何回调。
     * 所以要在这里主动重取一次。
     *
     * 用 Activity 的 ON_RESUME 触发，而不是定时轮询：
     *  - 用户从系统页返回的那一刻**必然**触发 ON_RESUME，时机刚好
     *  - 轮询要么慢半拍（改完了还显示旧状态），要么白耗电
     *
     * `LocalContext.current` 在这个位置就是宿主 Activity（MainActivity 是
     * ComponentActivity，天然实现 LifecycleOwner），所以强转是安全的；
     * 拿不到就不再刷新，而不是崩掉 —— 这一组只是提示，不值得为它冒崩溃风险。
     */
    var resumeTick by remember { mutableStateOf(0) }
    val owner = context as? LifecycleOwner
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    // 服务重启会让锁状态整体重置，所以它也是个刷新信号
    val whitelisted = remember(serverRunning, resumeTick) { vm.isIgnoringBatteryOptimizations() }
    val status = remember(serverRunning, resumeTick) { vm.batteryStatusText() }
    val oemHint = remember(serverRunning, resumeTick) { vm.oemBackgroundHint() }
    val diagnostics = remember(serverRunning, resumeTick) { vm.keepAliveDiagnostics() }

    SectionCard(title = "后台与保活") {
        Banner(
            text = status,
            tone = if (whitelisted) BannerTone.Success else BannerTone.Warn
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = "手机在「息屏 + 静止不动 + 没充电」持续一段时间后会进入深度省电，" +
                "系统在这一状态下会直接掐断应用的网络访问。这正是「手机放在桌上当服务器」的场景，" +
                "所以哪怕前台服务还在跑，也可能连不上。加入白名单后系统就不会掐网，" +
                "后台传输与被传输才能真正成立。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!whitelisted) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    val intent = vm.batteryOptimizationIntent()
                    if (intent == null) {
                        // 部分定制 ROM 阉割了这两个入口。直接 startActivity 会崩在用户面前，
                        // 所以宁可给手工路径，也不赌它能跳成功。
                        vm.reportNoBatterySettings()
                    } else {
                        runCatching { context.startActivity(intent) }
                            .onFailure { vm.reportNoBatterySettings() }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("去设置里加入白名单")
            }
        }

        if (oemHint != null) {
            Spacer(Modifier.height(12.dp))
            /*
             * 为什么加了系统白名单还要再提示这一条：
             * 小米 / 华为 / OPPO / vivo 这几家的「省电策略」会在 Android 官方白名单之外
             * **再拦一层**。用户按官方做法加完白名单，依然会被杀后台，然后来抱怨这软件不行。
             * 与其让他自己上网搜，不如把路径直接写出来。
             */
            Banner(text = oemHint, tone = BannerTone.Info)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = diagnostics,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onOpenCleanup, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.DeleteSweep, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("存储与清理")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "看看这个应用占了多少空间、哪些是垃圾，以及为什么它不会「用得越久、垃圾越多」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 存储与清理（FR-15）。
 *
 * ## 这一页的核心不是"一个按钮"，而是"让用户能自己核对"
 * 需求原话是"我不希望这个应用用得越多、产生的垃圾文件也越多"。能满足这句话的做法
 * 只有两种：要么真的不产生垃圾，要么让用户能看见并清掉。这里两者都做：
 *  - 每一类垃圾都单独列出**是什么、多少字节、多少条、为什么可以删**
 *  - 还有用的东西（比如 24 小时内能续传的半截上传）**照样列出来，但明确不给清理按钮**
 *
 * 第二点尤其重要。把它藏起来，用户会以为"没占空间"；给它一个按钮，他会误删掉
 * 正在续传的任务。显式地写"正在使用，不提供清理"是唯一诚实的做法。
 */
@Composable
fun StorageCleanupScreen(onBack: () -> Unit) {
    val vm: StorageCleanupViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val message by vm.message.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    var confirmAll by remember { mutableStateOf(false) }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = "存储与清理",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            val report = state.report
            val keepBytes = report?.items.orEmpty()
                .filter { !it.clearable }
                .sumOf { it.bytes }

            SectionCard(title = "可以腾出的空间") {
                when {
                    state.loading && report == null -> Text(
                        text = "正在扫描…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    report == null -> Text(
                        text = state.error ?: "扫描失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )

                    else -> {
                        Text(
                            text = Formatters.bytes(report.totalBytes),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "这是下面各项加起来能释放的空间。" +
                                "每一项都写清了「是什么」和「为什么可以删」，清之前你可以自己核对。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (keepBytes > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "另有 ${Formatters.bytes(keepBytes)} 是还没到期的上传残片，" +
                                    "留着才能续传，不算在这里面。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.rescan() },
                        enabled = !state.busy && !state.loading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重新扫描")
                    }
                    Button(
                        onClick = { confirmAll = true },
                        // 没什么可清的时候按钮是灰的：让用户一眼看出"现在是干净的"，
                        // 比让他点一下再收到一句"没有需要清理的内容"要好。
                        enabled = !state.busy && (report?.totalBytes ?: 0L) > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (state.busy) "清理中…" else "全部清理")
                    }
                }
            }

            report?.items?.forEach { item ->
                CleanupItemCard(
                    item = item,
                    busy = state.busy,
                    onClean = { vm.clean(item.scope) }
                )
            }

            SectionCard(title = "为什么不会越用越脏") {
                Text(
                    text = "这个应用自己会产生四类垃圾，都被管住了：",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                BulletText(
                    "测速临时文件",
                    "单个可达 128MB。正常结束会自己删掉，但测速中途被杀进程就会残留 —— " +
                        "服务每次启动时回收一次。"
                )
                BulletText(
                    "上传中断的残片",
                    "分块上传会先把文件撑到最终大小，所以传到一半断了的文件看起来是" +
                        "「大小完全正常」的，用户和系统都认不出它是废的。靠上传会话表识别，超过 24 小时没继续就随会话一起删。"
                )
                BulletText(
                    "传输记录与访问日志",
                    "两张只增不减的表。传输记录保留 ${AppConstants.TRANSFER_RECORD_KEEP_DAYS} 天，" +
                        "访问日志保留最近的；删除后执行 VACUUM，否则文件大小一点都不会变小。"
                )
                BulletText(
                    "应用缓存目录",
                    "零散临时产物，系统不保证回收，所以由应用自己扫。"
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "以上四类在每次服务启动时自动回收一次，之后每 " +
                        "${AppConstants.AUTO_CLEAN_INTERVAL_MS / 3_600_000} 小时兜底跑一次。" +
                        "自动任务只碰确定是垃圾的东西，绝不碰你的文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("清理全部可清理的内容？") },
            text = {
                Text(
                    "会清掉：临时文件、超过 24 小时没继续的上传残片、过期的传输记录与访问日志。\n\n" +
                        "不会碰：你的任何文件、还在续传有效期内的上传。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    vm.clean(CacheJanitor.Scope.ALL)
                }) { Text("确认清理") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAll = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 一条占用明细。
 *
 * [CacheJanitor.Item.clearable] 为 false 时**不给按钮但照样显示**，
 * 并且把"为什么不能清"直接写在按钮的位置上 —— 理由见 [StorageCleanupScreen] 的注释。
 */
@Composable
private fun CleanupItemCard(
    item: CacheJanitor.Item,
    busy: Boolean,
    onClean: () -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (item.bytes > 0) Formatters.bytes(item.bytes) else "—",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (item.clearable) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        if (item.count > 0 || item.bytes > 0) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (item.count > 0) "${item.count} 项" else "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.clearable) {
                    OutlinedButton(onClick = onClean, enabled = !busy) { Text("清理这一类") }
                } else {
                    Text(
                        text = "正在使用，不提供清理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/** 「名称 —— 说明」两行式条目。用来说明四类垃圾的来源。 */
@Composable
private fun BulletText(title: String, body: String) {
    Row(Modifier.padding(bottom = 8.dp)) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
