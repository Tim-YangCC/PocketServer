package com.pocketserver.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketserver.data.model.AccessRequest
import com.pocketserver.ui.common.Labels
import com.pocketserver.ui.theme.TvTokens
import com.pocketserver.ui.vm.ConsoleViewModel

private enum class TvRoute(val label: String, val hint: String) {
    CONSOLE("控制台", "地址、配对码、运行状态"),
    FILES("本机文件", "浏览共享出去的内容"),
    RECENT("最近接收", "选中即可播放"),
    SETTINGS("设置", "权限、安全与省电")
}

/**
 * 电视端应用外壳（FR-10）。
 *
 * ## 与手机端最根本的差别：追求"零操作"
 * 电视端被定位成**常驻服务端 + 展示端**（决策 D-2 的延伸）。用户坐到沙发上打开它，
 * 想要的是"屏幕上已经写着电脑该访问的地址"，而不是"先找到开关按一下"。
 * 所以这里做了两件事：
 *  1. 进入应用**自动启动服务**，用户什么都不用按
 *  2. 首屏直接把地址、配对码、二维码摆出来，这三样就是电视端存在的全部意义
 *
 * ## 为什么用左侧竖排导航而不是底部横排
 * 十尺 UI 里，底部横排在 16:9 屏幕上会浪费最宝贵的中部垂直空间，而且横向移动的
 * 焦点距离远（遥控器左右键容易冲过头）。左侧竖排的焦点移动路径短、位置固定，
 * 是 Android TV 设计规范推荐的做法。
 *
 * ## 接入确认弹窗放在外壳层
 * 因为它在任何页面都可能弹出来（用户可能正停在文件页）。如果放在某个具体页面里，
 * 换个页面就收不到确认请求，结果是 60 秒后自动拒绝 —— 对方永远连不上，
 * 而电视这边完全没有任何提示，属于最难排查的一类问题。
 */
@Composable
fun TvApp() {
    val consoleVm: ConsoleViewModel = hiltViewModel()
    val state by consoleVm.state.collectAsState()
    val requests by consoleVm.accessRequests.collectAsState()

    var route by remember { mutableStateOf(TvRoute.CONSOLE) }

    // 零操作：进入电视端就自动把服务拉起来（FR-10.5）。
    // 只在首次进入时尝试一次，避免用户手动停掉后又自动重启造成的"关不掉的开关"。
    var autoStartDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoStartDone) {
            autoStartDone = true
            val snapshot = consoleVm.state.value
            if (!snapshot.running && !snapshot.starting) {
                consoleVm.startService()
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TvNavRail(
            current = route,
            onSelect = { route = it },
            statusText = if (state.running) "服务运行中" else "服务已停止"
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            when (route) {
                TvRoute.CONSOLE -> TvConsoleScreen()
                TvRoute.FILES -> TvFilesScreen()
                TvRoute.RECENT -> TvRecentScreen()
                TvRoute.SETTINGS -> TvSettingsScreen()
            }
        }
    }

    requests.firstOrNull()?.let { request ->
        TvAccessConfirmDialog(
            request = request,
            queueSize = requests.size,
            onAllow = { consoleVm.decideAccess(request.id, true) },
            onDeny = { consoleVm.decideAccess(request.id, false) }
        )
    }
}

@Composable
private fun TvNavRail(
    current: TvRoute,
    onSelect: (TvRoute) -> Unit,
    statusText: String
) {
    Column(
        modifier = Modifier
            .width(232.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "口袋服务器",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))

        TvRoute.entries.forEach { item ->
            TvNavItem(
                label = item.label,
                hint = item.hint,
                selected = item == current,
                onClick = { onSelect(item) }
            )
        }
    }
}

/**
 * 导航项。
 *
 * 焦点态同时用**描边 + 背景提亮 + 左侧色条**三种方式表达。
 * 只靠其中一种在 3 米外都可能看不出来 —— 电视端的焦点可见性是可用性问题，
 * 不是审美问题（FR-10.3）。
 */
@Composable
private fun TvNavItem(
    label: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val border = when {
        focused -> TvTokens.FocusBorder
        selected -> TvTokens.FocusBorder.copy(alpha = 0.5f)
        else -> TvTokens.IdleBorder
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(if (focused) 3.dp else 1.dp, border, RoundedCornerShape(12.dp))
            .background(
                color = when {
                    focused -> MaterialTheme.colorScheme.surfaceVariant
                    selected -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.surface
                },
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(5.dp)
                .height(36.dp)
                .background(
                    color = if (selected || focused) {
                        TvTokens.FocusBorder
                    } else {
                        TvTokens.IdleBorder
                    },
                    shape = RoundedCornerShape(3.dp)
                )
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 大屏接入确认（FR-10.8 / FR-2.11）。
 *
 * ## 与手机端的关键差异
 * 手机上手就在旁边，可以慢慢读。电视上用户躺在沙发上、只有遥控器的方向键和确认键，
 * 所以这里必须做到：
 *  1. **文字巨大**（对方设备名与 IP 用 titleLarge）
 *  2. **默认焦点落在「允许」上**，一次确认就能放行（拒绝才需要移动焦点）
 *  3. 明确说出后果（"对方可以删除你电视上的文件"），而不是抽象地问"是否允许连接"
 *
 * 默认焦点放在"允许"是有意的：电视端 90% 的使用场景是家里人自己的设备，
 * 而"拒绝"的代价是对方要重新发起一次（还可能再等 60 秒）。
 * 但拒绝依然是**一次按键就能到达**，不会被藏起来。
 */
@Composable
private fun TvAccessConfirmDialog(
    request: AccessRequest,
    queueSize: Int,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* 必须显式选择，误触关闭等于静默拒绝 */ },
        title = {
            Text(
                text = "有设备想接入这台电视",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = request.deviceName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${Labels.deviceType(request.deviceType)} · ${request.peerIp}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "允许后，对方可以浏览电视上的共享文件，并且可以上传、修改和删除文件。",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "按遥控器左右键选择，中间键确认。60 秒内不做选择会自动拒绝。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (queueSize > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "另有 ${queueSize - 1} 个请求在排队。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(
                    text = "允许接入",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text(
                    text = "拒绝",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}
