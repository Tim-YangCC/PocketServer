package com.pocketserver.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketserver.data.db.AccessLogEntity
import com.pocketserver.ui.components.EmptyState
import com.pocketserver.ui.components.KeyValueRow
import com.pocketserver.ui.components.SectionCard
import com.pocketserver.ui.components.StatusDot
import com.pocketserver.ui.vm.ConsoleViewModel
import com.pocketserver.ui.vm.LogsViewModel
import com.pocketserver.ui.vm.looksAbnormal
import com.pocketserver.util.Formatters

/**
 * 访问日志（FR-7.6 / FR-9 安全可见性）。
 *
 * ## 这一页的真正目的不是"排查问题"而是"消除疑虑"
 * 用户把手机文件共享出去，心里一定会有个疙瘩："有没有别人在偷偷连我的手机？"
 * 这一页就是回答这个问题的。所以设计重心是**让"正常"看起来正常、让"异常"一眼可见**：
 *  - 每条记录都把 HTTP 方法与路径翻译成「下载文件」「浏览目录」这样的动词
 *  - 只有 4xx/5xx 才标红（且排除 favicon 这类必然 404 的探测请求，见 [looksAbnormal]）
 *  - 顶部显示"今天有几台设备来过"，而不是"共有 137 次请求"这种无信息量的数字
 */
@Composable
fun LogsScreen() {
    val vm: LogsViewModel = hiltViewModel()
    val consoleVm: ConsoleViewModel = hiltViewModel()

    val logs by vm.logs.collectAsState()
    val stats by vm.stats.collectAsState()

    // 缓冲池与锁定事件属于"深层诊断"，只有真出问题时才有人看，所以放在这里而不是控制台首屏。
    // 刻意不做 remember 缓存：锁定事件是安全事件，用户就是来看它有没有涨的。
    val diagnostics = "缓冲池：${consoleVm.bufferPoolSummary()} · " +
        "配对码错误锁定：${consoleVm.lockEventCount()} 次"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "访问日志",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "最近 ${logs.size} 条记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (logs.isNotEmpty()) {
                TextButton(onClick = { vm.clear() }) { Text("清空") }
            }
        }

        Spacer(Modifier.height(10.dp))

        SectionCard(title = "最近 24 小时") {
            KeyValueRow(label = "访问次数", value = "${stats.first} 次")
            KeyValueRow(label = "来访设备", value = "${stats.second} 台", valueBold = true)
            if (stats.first == 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "24 小时内没有设备访问过。如果你的电脑刚刚连过却看不到记录，" +
                        "请确认电脑连的是同一个 Wi-Fi。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        SectionCard(title = "诊断信息") {
            Text(
                text = diagnostics,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { vm.refreshStats() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("重新统计") }
        }

        Spacer(Modifier.height(10.dp))

        if (logs.isEmpty()) {
            EmptyState(
                title = "还没有任何访问记录",
                hint = "开启服务后，电脑每打开一个页面、下载一个文件，这里都会记一行。"
            )
        } else {
            LazyColumn(
                // weight 而不是 fillMaxSize：上方统计卡高度会随文案变化，weight 才稳定
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    LogRow(log = log, actionLabel = vm.actionLabel(log))
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
    }
}

@Composable
private fun LogRow(log: AccessLogEntity, actionLabel: String) {
    val abnormal = log.looksAbnormal()
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(8.dp))
            StatusDot(
                color = when {
                    abnormal -> MaterialTheme.colorScheme.error
                    log.success -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = log.status.toString()
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${log.deviceName.ifBlank { "未知设备" }} · ${log.peerIp}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = buildString {
                append(Formatters.relative(log.timestamp))
                if (log.bytesSent > 0) append(" · 发出 ").append(Formatters.bytes(log.bytesSent))
                if (log.bytesReceived > 0) {
                    append(" · 收到 ").append(Formatters.bytes(log.bytesReceived))
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (log.detail.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = log.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (abnormal) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        if (abnormal) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "这是一次失败或被拒绝的请求。如果这条记录不是你自己的电脑产生的，" +
                    "建议在设置里打开「每次接入都需要配对码」，并更换配对码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
