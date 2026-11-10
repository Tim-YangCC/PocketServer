package com.pocketserver.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.pocketserver.data.db.TransferTaskEntity
import com.pocketserver.data.model.TransferDirection
import com.pocketserver.data.model.TransferStatus
import com.pocketserver.ui.components.EmptyState
import com.pocketserver.ui.components.TvFocusableCard
import com.pocketserver.ui.vm.TransfersViewModel
import com.pocketserver.util.FileOpener
import com.pocketserver.util.Formatters

/**
 * 最近接收（FR-10.7）。
 *
 * ## 为什么用"传输记录"而不是"最近修改的文件"
 * 按修改时间扫全盘在电视盒子上要遍历几万个 inode，冷启动时会明显卡住，
 * 而用户真正关心的是"刚刚传进来的那几部片子"。传输记录恰好是这个问题的精确答案：
 * 它只包含**通过网络进来的文件**，而且天然按时间倒序。
 *
 * ## 数据来源的取舍
 * 只取 `UPLOAD`（电脑上传）与 `PULL`（本机从别的设备拉取）两种方向 ——
 * 这两种才是"进到这台电视上"的文件。`DOWNLOAD`（电脑下载）与 `PUSH`（推出去）
 * 都跟本机存储无关，混进来只会让用户困惑"我明明没收到这个文件"。
 */
@Composable
fun TvRecentScreen() {
    val vm: TransfersViewModel = hiltViewModel()
    val transfers by vm.transfers.collectAsState()
    val context = LocalContext.current

    var notice by remember { mutableStateOf<String?>(null) }

    val received = remember(transfers) {
        transfers.filter {
            it.status == TransferStatus.DONE.name &&
                (it.direction == TransferDirection.UPLOAD.name ||
                    it.direction == TransferDirection.PULL.name)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 22.dp)
    ) {
        Text(
            text = "最近接收",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "从电脑或其他设备传进来的文件。选中卡片即可直接播放。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        if (received.isEmpty()) {
            EmptyState(
                title = "还没有接收过文件",
                hint = "在电脑浏览器里上传文件，或者用另一台手机推送过来，这里就会出现。"
            )
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(received, key = { it.id }) { task ->
                ReceivedCard(
                    task = task,
                    onOpen = {
                        notice = openNotice(FileOpener.open(context, task.relPath), task.name)
                    }
                )
            }
        }
    }

    notice?.let { text ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = {
                Text(
                    text = "打不开这个文件",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = { Text(text = text, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = { notice = null }) {
                    Text(text = "知道了", style = MaterialTheme.typography.titleMedium)
                }
            }
        )
    }
}

@Composable
private fun ReceivedCard(task: TransferTaskEntity, onOpen: () -> Unit) {
    TvFocusableCard(onClick = onOpen) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = task.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${Formatters.bytes(task.totalBytes)} · " +
                    Formatters.relative(task.finishedAt ?: task.createdAt),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "来自 ${task.peerName.ifBlank { "电脑" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (FileOpener.canOpen(task.relPath)) "按中间键播放" else "该格式电视无法播放",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun openNotice(result: FileOpener.Result, name: String): String? = when (result) {
    FileOpener.Result.Launched -> null

    FileOpener.Result.NoRealPath ->
        "这台电视还没有获得「所有文件访问权限」，拿不到文件的真实路径。" +
            "在手机上开启该权限并重启服务后，就能直接在这里播放了。"

    FileOpener.Result.NotFound ->
        "「$name」不存在、已被移动，或者电视上没有能打开它的应用。" +
            "可以回到电脑网页版下载查看。"

    FileOpener.Result.NoAppAvailable ->
        "这台电视上没有安装能播放「$name」的应用。可以回到电脑网页版播放。"
}
