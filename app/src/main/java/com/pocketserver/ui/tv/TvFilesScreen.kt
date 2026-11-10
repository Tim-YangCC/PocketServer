package com.pocketserver.ui.tv

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.pocketserver.data.storage.StorageEntry
import com.pocketserver.ui.components.EmptyState
import com.pocketserver.ui.components.KeyValueRow
import com.pocketserver.ui.components.TvFocusableCard
import com.pocketserver.ui.vm.FilesViewModel
import com.pocketserver.util.FileOpener
import com.pocketserver.util.Formatters

/**
 * 电视端文件浏览（FR-10.6 / FR-10.7）。
 *
 * ## 功能裁剪是刻意的，而且裁剪线画在"输入"上
 * 需求 FR-10.6 明确：电视端只保留**浏览 / 播放 / 删除**三项。被砍掉的
 * 重命名与新建文件夹，共同点是**都需要输入文字**——遥控器输入一个文件名
 * 要按几十次方向键，这件事的体验下限低到会直接毁掉产品口碑。
 *
 * 删除却被保留了：删除不需要输入，而且"把不要的片子删掉腾空间"是电视端很自然的诉求。
 * 所以裁剪的依据不是"危险程度"，而是"是否需要敲字"。
 *
 * ## 焦点态为什么做得这么重
 * 这一页是纯列表，用户在 3 米外靠焦点框知道自己选的是哪一行。焦点态一旦不够明显，
 * 用户就会连续按错方向键、删掉不该删的文件（删除是不可撤销的）。所以这里
 * 选中行除了描边，还会多显示一行"按中间键打开 / 删除"的操作提示。
 */
@Composable
fun TvFilesScreen() {
    val vm: FilesViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    var actionTarget by remember { mutableStateOf<StorageEntry?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedForDelete by remember { mutableStateOf<StorageEntry?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 22.dp)
    ) {
        Text(
            text = "本机文件",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (state.path.isBlank()) "共享根目录" else "/${state.path}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvAction(
                label = "上一级",
                enabled = state.path.isNotBlank(),
                onClick = { vm.up() },
                modifier = Modifier.width(160.dp)
            )
            TvAction(
                label = "刷新",
                onClick = { vm.open(state.path) },
                modifier = Modifier.width(160.dp)
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = state.storageMode.ifBlank { "读取中…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "剩余 ${Formatters.bytes(state.freeSpace)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(14.dp))

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
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            state.entries.isEmpty() -> EmptyState(
                title = "这个目录是空的",
                hint = "在电脑上打开网页版把文件传上来，这里就能看到了。"
            )

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.entries, key = { it.relPath }) { entry ->
                    TvFocusableCard(
                        onClick = {
                            if (entry.isDirectory) {
                                vm.open(entry.relPath)
                            } else {
                                actionTarget = entry
                            }
                        }
                    ) {
                        Column {
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (entry.isDirectory) {
                                    if (entry.childCount >= 0) {
                                        "文件夹 · ${entry.childCount} 项"
                                    } else {
                                        "文件夹"
                                    }
                                } else {
                                    "${Formatters.bytes(entry.size)} · " +
                                        Formatters.relative(entry.lastModified)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (entry.isDirectory) {
                                    "按中间键进入"
                                } else {
                                    "按中间键查看操作"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }
            }
        }
    }

    // 文件操作：只给"打开 / 删除"两项（FR-10.6）
    actionTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    KeyValueRow(label = "大小", value = Formatters.bytes(entry.size))
                    KeyValueRow(label = "修改时间", value = Formatters.dateTime(entry.lastModified))
                    KeyValueRow(
                        label = "类型",
                        value = entry.mimeType.ifBlank { "未知" }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (FileOpener.canOpen(entry.relPath)) {
                            "「打开」会把文件交给系统播放器。"
                        } else {
                            "这种类型的文件电视上打不开，可以在电脑网页版里下载。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = entry
                    actionTarget = null
                    notice = openNotice(FileOpener.open(context, target.relPath))
                }) {
                    Text(
                        text = "打开",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        selectedForDelete = entry
                        actionTarget = null
                    }) {
                        Text(
                            text = "删除",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    TextButton(onClick = { actionTarget = null }) {
                        Text(text = "关闭", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        )
    }

    // 删除是不可撤销的，必须二次确认（电视端尤其如此：焦点一旦跑偏就容易连按）
    selectedForDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { selectedForDelete = null },
            title = {
                Text(
                    text = "确定删除？",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "「${entry.name}」将被永久删除，无法从回收站恢复。",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = entry
                    selectedForDelete = null
                    vm.delete(target)
                }) {
                    Text(
                        text = "确认删除",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedForDelete = null }) {
                    Text(text = "取消", style = MaterialTheme.typography.titleMedium)
                }
            }
        )
    }

    notice?.let { text ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text(text = "打不开这个文件", style = MaterialTheme.typography.titleLarge) },
            text = { Text(text = text, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = { notice = null }) {
                    Text(text = "知道了", style = MaterialTheme.typography.titleMedium)
                }
            }
        )
    }
}

/**
 * 把打开结果翻译成用户能照着做的一句话。
 *
 * 关键在于**不要出现"不支持"这种死路**：每一种失败都要给出一个明确的下一步，
 * 否则用户在电视前只能干瞪眼，然后去应用商店打一星。
 */
private fun openNotice(result: FileOpener.Result): String? = when (result) {
    FileOpener.Result.Launched -> null
    FileOpener.Result.NoRealPath ->
        "这台电视还没有获得「所有文件访问权限」，所以拿不到文件的真实路径。" +
            "请在手机的设置页开启该权限后重启服务，就能直接在电视上播放了。"

    FileOpener.Result.NotFound ->
        "文件不存在、已被移动，或者电视上没有能打开它的应用。" +
            "可以回到电脑网页版下载查看。"

    FileOpener.Result.NoAppAvailable ->
        "这台电视上没有安装能播放这种格式的应用。可以回到电脑网页版播放。"
}
