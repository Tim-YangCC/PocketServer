package com.pocketserver.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketserver.data.model.SharedPermission
import com.pocketserver.ui.components.Banner
import com.pocketserver.ui.components.BannerTone
import com.pocketserver.ui.components.AboutCard
import com.pocketserver.ui.components.SectionCard
import com.pocketserver.ui.vm.ConsoleViewModel
import com.pocketserver.ui.vm.SettingsViewModel

/**
 * 电视端设置（FR-10.6 的精简版）。
 *
 * ## 只保留"能用方向键搞定"的设置项
 * 电视上没有键盘。所以这一页只有两种控件：**开关行**和**按钮**，一个文本输入都没有。
 * 被砍掉的典型是"HTTP 端口"——它本来是个数字输入框，在电视上要么做成左右键调节
 * （0~65535 要按几万次），要么做成数字键盘（更难用）。与其做一个没人能用的控件，
 * 不如直接引导用户去手机上改（电视和手机往往就是同一个人的两台设备）。
 *
 * ## 这一页为什么仍然必要
 * 电视是 7×24 常驻的那一端，用户对它最需要控制的两件事是：
 * **权限模式**（我现在想让别人只读还是可写）和**清除授权**（我不信任某台设备了）。
 * 这两件事必须能在大屏上直接做 —— 否则用户为了关掉一个权限还得跑去拿手机。
 */
@Composable
fun TvSettingsScreen() {
    val vm: SettingsViewModel = hiltViewModel()
    val consoleVm: ConsoleViewModel = hiltViewModel()

    val settings by vm.settings.collectAsState()
    val serverRunning = consoleVm.observeRunning()
    var confirmClear by remember { mutableStateOf(false) }

    val storageHint = remember(serverRunning) { vm.storageHint() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "电视上的设置项只用方向键和中间键操作。端口等需要输入的内容请在手机上修改。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ==================== 接入与安全 ====================

        SectionCard(title = "谁能进来") {
            TvToggleRow(
                label = "只读模式",
                hint = "开启后别人只能浏览和下载，任何写入都会被拒绝。临时给别人拷资料时很有用。",
                checked = settings.permission == SharedPermission.READ_ONLY,
                onToggle = {
                    vm.setPermission(
                        if (settings.permission == SharedPermission.READ_ONLY) {
                            SharedPermission.READ_WRITE
                        } else {
                            SharedPermission.READ_ONLY
                        }
                    )
                }
            )
            Spacer(Modifier.height(12.dp))
            TvToggleRow(
                label = "每次接入都需要配对码",
                hint = "关闭后同一 Wi-Fi 下的设备可以直接进来；新设备首次连接仍会弹窗确认。",
                checked = settings.requirePairing,
                onToggle = { vm.setRequirePairing(!settings.requirePairing) }
            )
            Spacer(Modifier.height(12.dp))
            TvToggleRow(
                label = "允许免确认设备直连",
                hint = "已经在白名单里的设备可以跳过弹窗直接接入。",
                checked = settings.trustedBypass,
                onToggle = { vm.setTrustedBypass(!settings.trustedBypass) }
            )
            Spacer(Modifier.height(12.dp))
            TvToggleRow(
                label = "显示隐藏文件",
                hint = "以点开头的文件（如 .nomedia）默认不显示。",
                checked = settings.showHiddenFiles,
                onToggle = { vm.setShowHidden(!settings.showHiddenFiles) }
            )
        }

        // ==================== 别人能做什么 ====================

        SectionCard(title = "别人能做什么") {
            TvToggleRow(
                label = "允许上传文件",
                hint = "对方可以往电视的存储里写新文件。",
                checked = settings.allowUpload,
                enabled = settings.permission == SharedPermission.READ_WRITE,
                onToggle = { vm.setAllowUpload(!settings.allowUpload) }
            )
            Spacer(Modifier.height(12.dp))
            TvToggleRow(
                label = "允许删除文件",
                hint = "对方可以删除你电视上的文件。不确定时建议关掉。",
                checked = settings.allowDelete,
                enabled = settings.permission == SharedPermission.READ_WRITE,
                onToggle = { vm.setAllowDelete(!settings.allowDelete) }
            )
            Spacer(Modifier.height(12.dp))
            // 如实展示"已关闭"而不是隐藏：隐藏会让用户以为功能丢了（FR-10.6）
            TvToggleRow(
                label = "允许重命名与新建文件夹",
                hint = "电视端始终不提供这一项：遥控器输入文字太难受。请用手机或电脑操作。",
                checked = false,
                enabled = false,
                onToggle = { }
            )
        }

        // ==================== 省电与常驻 ====================

        SectionCard(title = "省电与常驻") {
            TvToggleRow(
                label = "开机自动启动服务",
                hint = "电视重启后自动把服务拉起来，不用再手动打开一次。",
                checked = settings.autoStartOnBoot,
                onToggle = { vm.setAutoStart(!settings.autoStartOnBoot) }
            )
            Spacer(Modifier.height(12.dp))
            TvToggleRow(
                label = "长时间没人连就自动停止",
                hint = if (settings.idleStopMinutes > 0) {
                    "当前：无活动 ${settings.idleStopMinutes} 分钟后自动停止服务。"
                } else {
                    "当前：常驻模式，服务一直运行（推荐，半夜也能连上）。"
                },
                checked = settings.idleStopMinutes > 0,
                onToggle = {
                    // 只在"常驻"和"30 分钟"之间切换：电视端给一堆档位只会让用户来回试
                    vm.setIdleStopMinutes(if (settings.idleStopMinutes > 0) 0 else 30)
                }
            )
        }

        // ==================== 存储 ====================

        SectionCard(title = "共享的存储范围") {
            Text(
                text = storageHint,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            Banner(
                text = "电视端建议在首次安装时就授予「所有文件访问权限」：这是电视能直接播放" +
                    "接收到的文件的前提，也是传输速度最快的那条通路。",
                tone = BannerTone.Info
            )
        }

        // ==================== 维护 ====================

        SectionCard(title = "维护") {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvAction(
                    label = if (serverRunning) "重启服务" else "启动服务",
                    onClick = { vm.restartService() },
                    modifier = Modifier.weight(1f)
                )
                TvAction(
                    label = "清除所有设备授权",
                    onClick = { confirmClear = true },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "清除授权后，所有已连接和免确认的设备都会失效，下次必须重新输配对码。" +
                    "不会删除任何文件。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AboutCard {
            Text(
                text = "局域网文件传输与管理工具。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(10.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = {
                Text(
                    text = "清除所有设备授权？",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "所有已连接的设备会立刻失效，免确认名单也会清空。" +
                        "下次连接必须重新输入配对码。这个操作不会删除任何文件。",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearPresence()
                }) {
                    Text(
                        text = "确认清除",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(text = "取消", style = MaterialTheme.typography.titleMedium)
                }
            }
        )
    }
}

/**
 * 复用控制台的运行状态：设置页需要它来决定按钮写"重启服务"还是"启动服务"。
 *
 * 单独包成一个函数而不是在调用处写 `state.collectAsState()`，是为了避免
 * `val state by state.collectAsState()` 这种局部变量与属性同名的写法 —— 它能编译，
 * 但读起来要愣一下才知道 `state` 指的是哪一个。
 */
@Composable
private fun ConsoleViewModel.observeRunning(): Boolean {
    val snapshot by state.collectAsState()
    return snapshot.running
}
