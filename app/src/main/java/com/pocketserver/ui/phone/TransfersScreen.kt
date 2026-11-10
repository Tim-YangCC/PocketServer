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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
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
import com.pocketserver.data.db.TransferTaskEntity
import com.pocketserver.data.model.TransferDirection
import com.pocketserver.data.model.TransferStatus
import com.pocketserver.ui.components.Banner
import com.pocketserver.ui.components.BannerTone
import com.pocketserver.ui.components.EmptyState
import com.pocketserver.ui.components.KeyValueRow
import com.pocketserver.ui.components.SectionCard
import com.pocketserver.ui.components.StatusDot
import com.pocketserver.ui.vm.TransferAction
import com.pocketserver.ui.vm.TransfersViewModel
import com.pocketserver.util.Formatters

/**
 * 传输任务（FR-7.1 ~ FR-7.7）。
 *
 * ## 这一页的存在理由是"心里有底"
 * 局域网传输大多很快，用户平时不会盯着它。真正需要这一页的时刻只有两个：
 * **传大文件时想确认它还在动**，以及**想让它停下来**。
 *
 * 所以这一页必须同时给出三样东西：
 *  - 一份**概览**：几个在跑、几个停着、几个出错了。用户扫一眼就知道全局，
 *    不用在几十张卡片里自己数。
 *  - 每个进行中任务的进度、实时字节数、**用了几路并行** ——
 *    第三项是这款产品相对普通文件浏览器传输的唯一可感知差异，值得单独显示。
 *  - 每个任务该有的**按钮**。而按钮的可见性不看数据库里的状态，
 *    看服务端内存里那份活跃闸门 —— 理由见 [TransfersViewModel] 的类注释。
 */
@Composable
fun TransfersScreen() {
    val vm: TransfersViewModel = hiltViewModel()
    val transfers by vm.transfers.collectAsState()
    val activeCount by vm.activeCount.collectAsState()
    val overview by vm.overview.collectAsState()
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    var historySelected by remember { mutableStateOf(false) }
    var confirmHistoryClear by remember { mutableStateOf(false) }
    val batchTasks = remember(transfers, overview.batch.batchId) {
        overview.batch.batchId?.takeIf { it.isNotBlank() }?.let { id -> transfers.filter { it.batchId == id } }.orEmpty()
    }
    val visibleTasks = if (historySelected) transfers else batchTasks

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "传输任务",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (activeCount > 0) "正在传输 $activeCount 个任务" else "查看本批次进度与历史记录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))

        Spacer(Modifier.height(8.dp))
        TabRow(selectedTabIndex = if (historySelected) 1 else 0) {
            Tab(selected = !historySelected, onClick = { historySelected = false }, text = { Text("本批次") })
            Tab(selected = historySelected, onClick = { historySelected = true }, text = { Text("历史累计") })
        }
        Spacer(Modifier.height(10.dp))
        TransferOverview(
            overview = overview,
            historySelected = historySelected,
            busy = busy,
            onClearHistory = { confirmHistoryClear = true }
        )

        if (confirmHistoryClear) {
            AlertDialog(
                onDismissRequest = { if (!busy) confirmHistoryClear = false },
                title = { Text("清理历史记录？") },
                text = {
                    Text("将删除较早批次中已完成、失败或已取消的记录。最新批次、本批次统计以及正在传输或暂停的任务会保留。此操作无法撤销。")
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            confirmHistoryClear = false
                            vm.clearHistory()
                        }
                    ) { Text("清空历史") }
                },
                dismissButton = {
                    TextButton(enabled = !busy, onClick = { confirmHistoryClear = false }) { Text("取消") }
                }
            )
        }

        message?.let { text ->
            Spacer(Modifier.height(10.dp))
            Banner(
                text = text,
                tone = BannerTone.Info,
                actionLabel = "知道了",
                onAction = { vm.consumeMessage() }
            )
        }

        Spacer(Modifier.height(12.dp))

        if (visibleTasks.isEmpty()) {
            EmptyState(
                title = if (historySelected) "还没有传输记录" else "本批次还没有任务",
                hint = if (historySelected) "在电脑网页上传下载，或者在「附近」页给另一台设备发文件，这里都会留下记录。"
                    else "本批次按电脑网页端一次选择的文件或文件夹统计。"
            )
        } else {
            LazyColumn(
                // 用 weight 而不是 fillMaxSize：在 Column 里 fillMaxSize 会拿剩余高度约束，
                // 一旦上方标题换行变高就会把列表压扁，weight 则是稳定的"拿走剩下全部"
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val groups = listOf(
                    "进行中" to visibleTasks.filter { it.status in listOf(TransferStatus.RUNNING.name, TransferStatus.QUEUED.name, TransferStatus.PAUSED.name) },
                    "失败" to visibleTasks.filter { it.status == TransferStatus.FAILED.name || it.status == TransferStatus.CANCELED.name },
                    "已完成" to visibleTasks.filter { it.status == TransferStatus.DONE.name }
                ).filter { it.second.isNotEmpty() }
                groups.forEach { (label, tasks) ->
                    item(key = "group-$label") {
                        Text("$label（${tasks.size}）", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp, bottom = 1.dp))
                    }
                    items(tasks, key = { it.id }) { task ->
                        TransferCard(
                            task = task,
                            statusLabel = vm.statusLabel(task),
                            directionLabel = vm.directionLabel(task),
                            actions = vm.actionsFor(task),
                            busy = busy,
                            onAction = { action ->
                                when (action) {
                                    TransferAction.PAUSE -> vm.pause(task)
                                    TransferAction.RESUME -> vm.resume(task)
                                    TransferAction.CANCEL -> vm.cancel(task)
                                }
                            }
                        )
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
    }
}

/**
 * 概览条。
 *
 * 显示"还剩多少字节没传完"而不是"总共传了多少" —— 前者才是用户此刻在等的东西。
 * 传完之后这个数会归零，用户一看就知道不用再等了。
 */
@Composable
private fun TransferOverview(
    overview: TransfersViewModel.Overview,
    historySelected: Boolean,
    busy: Boolean,
    onClearHistory: () -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (historySelected) "历史累计" else "本批次",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (historySelected) {
                TextButton(enabled = !busy, onClick = onClearHistory) { Text("清空记录") }
            }
            Text(
                text = if (overview.activeBytes > 0) {
                    "还剩 ${Formatters.bytes(overview.activeBytes)} 没传完"
                } else {
                    "没有正在传输的内容"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(10.dp))
        val stats = if (historySelected) overview.history else overview.batch
        if (historySelected) {
            OverviewTotalRow("总任务", stats.total.toString(), "已完成", stats.done.toString(), "失败", (stats.failed + stats.canceled).toString())
        } else {
            OverviewTotalRow("本批次", stats.total.toString(), "已完成", stats.done.toString(), "失败", (stats.failed + stats.canceled).toString(), running = stats.running, showRunning = true)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (historySelected) "历史累计覆盖当前保留的全部传输记录。" else "本批次按电脑网页端一次选择的文件或文件夹统计。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OverviewTotalRow(
    label1: String, value1: String,
    label2: String, value2: String,
    label3: String, value3: String,
    running: Int = 0,
    showRunning: Boolean = false
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SummaryValue(label1, value1, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        SummaryValue(label2, value2, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
        SummaryValue(label3, value3, MaterialTheme.colorScheme.error, Modifier.weight(1f))
        if (showRunning) SummaryValue("进行中", running.toString(), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
    }
}

@Composable
private fun SummaryValue(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier.padding(horizontal = 7.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun TransferCard(
    task: TransferTaskEntity,
    statusLabel: String,
    directionLabel: String,
    actions: List<TransferAction>,
    busy: Boolean,
    onAction: (TransferAction) -> Unit
) {
    val running = task.status == TransferStatus.RUNNING.name
    val paused = task.status == TransferStatus.PAUSED.name
    val failed = task.status == TransferStatus.FAILED.name

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = directionLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusDot(
                color = when {
                    running -> MaterialTheme.colorScheme.primary
                    paused -> MaterialTheme.colorScheme.tertiary
                    failed -> MaterialTheme.colorScheme.error
                    task.status == TransferStatus.DONE.name -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = statusLabel
            )
        }

        if (running || paused) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { task.percent / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(10.dp))
        KeyValueRow(
            label = "进度",
            value = if (task.totalBytes > 0) {
                "${task.percent}% · ${Formatters.bytes(task.transferredBytes)} / ${Formatters.bytes(task.totalBytes)}"
            } else {
                Formatters.bytes(task.transferredBytes)
            },
            valueBold = running
        )
        if (task.lanes > 1) {
            KeyValueRow(label = "并行路数", value = "${task.lanes} 路")
        }
        if (task.peerName.isNotBlank()) {
            KeyValueRow(
                label = "对端设备",
                value = task.peerName + if (task.peerIp.isNotBlank()) "（${task.peerIp}）" else ""
            )
        }
        KeyValueRow(
            label = if (running) "开始于" else "结束于",
            value = Formatters.relative(task.finishedAt ?: task.createdAt)
        )
        task.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error
            )
        }

        /*
         * 一条说明，专门讲"为什么这里没有「开始」按钮"。
         *
         * 断掉的上传/下载没法从手机上重来 —— 数据源（电脑里那个文件、对方设备上
         * 那个文件）不在我们手里，凭空点一个"重试"只能失败。用户最容易在这一刻
         * 得出"这软件不让我重试"的结论，所以这里把该怎么做直接写出来。
         */
        if (actions.isEmpty() && (failed || task.status == TransferStatus.CANCELED.name)) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = retryHint(task.direction),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions.forEach { action ->
                    TextButton(
                        enabled = !busy,
                        onClick = { onAction(action) }
                    ) {
                        Text(
                            text = action.label,
                            color = if (action == TransferAction.CANCEL) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            fontWeight = if (action == TransferAction.CANCEL) FontWeight.Normal
                            else FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/** 这条传输断了之后用户该怎么把它弄完。三种方向三种说法，不能写成一句笼统的"请重试"。 */
private fun retryHint(direction: String): String = when (direction) {
    TransferDirection.UPLOAD.name ->
        "在电脑上把同一个文件再拖进网页一次，会自动接着没传完的地方继续，不用重头来。"

    TransferDirection.PULL.name ->
        "回到「附近」页，对着那台设备再拉一次即可。"

    TransferDirection.PUSH.name ->
        "回到「附近」页重新推送。若对面已经有半截文件，它会在文件列表里，可以自己删掉。"

    TransferDirection.DOWNLOAD.name ->
        "在电脑浏览器里重新点一次下载，会从断点接着下。"

    else -> "重新发起一次即可。"
}
