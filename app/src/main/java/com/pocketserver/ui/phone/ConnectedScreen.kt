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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketserver.net.PairingManager
import com.pocketserver.ui.common.rememberNowTick
import com.pocketserver.ui.components.Banner
import com.pocketserver.ui.components.BannerTone
import com.pocketserver.ui.components.EmptyState
import com.pocketserver.ui.components.KeyValueRow
import com.pocketserver.ui.components.SectionCard
import com.pocketserver.ui.components.StatusDot
import com.pocketserver.ui.vm.ConsoleViewModel
import com.pocketserver.util.Formatters

/**
 * 已连接设备（FR-2.10）。
 *
 * ## 这一页解决的是"我关不掉它"的焦虑
 * 长效令牌（"记住这台设备"）虽然方便，但用户会本能地担心："我上次在别人电脑上勾了记住，
 * 现在还能进我的手机吗？" 如果找不到关掉它的地方，用户的选择就是**永远不勾记住**，
 * 于是每次都要输 6 位码 —— 体验和安全同时受损。
 *
 * 所以这一页必须做到两件事：**列得出来**、**一键断掉**。断掉的语义也要说清楚：
 * 是真断了（令牌作废、需要重新配对），而不是"暂时不显示"。
 */
@Composable
fun ConnectedScreen() {
    val vm: ConsoleViewModel = hiltViewModel()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // 每秒刷新一次列表：会话本身变化很慢，但"还剩多久过期"要一直在走
    val now = rememberNowTick()
    val sessions = remember(now) { vm.connectedSessions() }

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
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "已连接的设备",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "这些设备现在可以直接访问本机文件，不需要再输配对码",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            if (sessions.isEmpty()) {
                EmptyState(
                    title = "当前没有已授权的设备",
                    hint = "任何设备想接入，都必须先输配对码（或在对方屏幕上确认）。" +
                        "接入成功后会出现在这里，你可以随时把它断开。"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Banner(
                            text = "「免确认」的意思是：下次它连过来时，这台手机上不会再弹确认框。" +
                                "如果你不再信任某台设备，点「断开」即可 —— 它会立刻失效，" +
                                "下次必须重新输配对码。",
                            tone = BannerTone.Info
                        )
                    }
                    items(sessions, key = { it.token }) { session ->
                        SessionCard(
                            session = session,
                            remainingMillis = (session.expiresAt - now).coerceAtLeast(0),
                            onDisconnect = { vm.disconnect(session.token, session.deviceName) }
                        )
                    }
                    item { Spacer(Modifier.height(4.dp)) }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
        )
    }
}

@Composable
private fun SessionCard(
    session: PairingManager.Session,
    remainingMillis: Long,
    onDisconnect: () -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                StatusDot(
                    color = if (session.trusted) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    label = if (session.trusted) "免确认设备" else "本次会话"
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onDisconnect) {
                Text("断开", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(8.dp))
        KeyValueRow(label = "来源地址", value = session.peerIp)
        KeyValueRow(label = "接入时间", value = Formatters.relative(session.createdAt))
        KeyValueRow(
            label = "剩余有效",
            value = if (remainingMillis > 0) Formatters.duration(remainingMillis) else "已过期",
            valueBold = true
        )
        if (session.trusted) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "免确认设备默认保留 90 天。如果这台设备已经不用了（比如换电脑了），" +
                    "建议现在就断开。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
