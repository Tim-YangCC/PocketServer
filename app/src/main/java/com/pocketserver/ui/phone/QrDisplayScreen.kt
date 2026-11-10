package com.pocketserver.ui.phone

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import com.pocketserver.net.QrCodec
import com.pocketserver.ui.components.Banner
import com.pocketserver.ui.components.BannerTone
import com.pocketserver.ui.components.KeyValueRow
import com.pocketserver.ui.components.PairingCodeText
import com.pocketserver.ui.components.SectionCard
import com.pocketserver.ui.vm.ConsoleViewModel
import com.pocketserver.ui.vm.SettingsViewModel
import com.pocketserver.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 二维码展示页（FR-2.7 扫码接入）。
 *
 * ## 二维码优先连接当前 IP
 * 现场扫码时使用当前 IP，减少 `.local` 解析失败和代理干扰；同时保留主机名，
 * 作为设备名地址备用。
 *
 * ## 为什么手机上也要有这个页面
 * 手机才是"让别人接进来"的那一端。电脑上打开 `pocketserver.local` 后需要配对码；
 * 有了这个页面，手边有另一台手机/平板时直接扫一下就能进，不用念 6 位数字。
 */
@Composable
fun QrDisplayScreen() {
    val consoleVm: ConsoleViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()

    val state by consoleVm.state.collectAsState()
    val settings by settingsVm.settings.collectAsState()

    val payload = remember(
        state.mdnsHost,
        state.httpPort,
        state.pairingCode,
        settings.deviceName,
        state.addresses
    ) {
        QrCodec.payload(
            alias = state.mdnsHost,
            port = state.httpPort,
            code = state.pairingCode,
            deviceName = settings.deviceName,
            ipFallback = state.addresses.firstOrNull() ?: ""
        )
    }

    // 生成二维码是纯粹的 CPU 计算，放到 Default 线程，避免在首帧时卡一下
    @Suppress("ProduceStateDoesNotAssignValue")
    val bitmap by produceState<Bitmap?>(initialValue = null, payload) {
        if (payload.isBlank()) {
            value = null
        } else {
            value = withContext(Dispatchers.Default) { QrCodec.bitmap(payload, QR_SIZE_PX) }
        }
    }

    val now = System.currentTimeMillis()
    val remaining = (state.pairingCodeExpiresAt - now).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "扫这个码直接接入",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "用另一台手机的相机或浏览器扫一下，会自动带着地址和配对码进来，不用手输。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!state.running) {
            Banner(
                text = "服务还没开启，二维码暂时无效。请先回到控制台开启服务。",
                tone = BannerTone.Warn
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color.White, RoundedCornerShape(18.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            val image = bitmap
            if (image != null) {
                // 二维码必须"黑模块 + 白底"，无论手机当前是浅色还是深色主题，
                // 所以这里刻意用白底容器而不是主题色 —— 反色会让很多扫码器读不出来
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "接入二维码",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator()
            }
        }

        SectionCard(title = "手输也一样可以") {
            PairingCodeText(code = state.pairingCode, isTv = false)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (remaining > 0) {
                    "配对码还剩 ${Formatters.countdown(remaining)} 有效期"
                } else {
                    "配对码已过期，稍后会自动更新"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            KeyValueRow(label = "电脑地址", value = state.primaryUrl)
            state.fallbackUrl?.let { KeyValueRow(label = "设备名备用地址", value = it) }
        }

        Banner(
            text = "提醒：二维码里含有当前配对码，等同于一次性的开门钥匙。截图转发给别人前请先想一下，或者用完在控制台点「立即换一个」。",
            tone = BannerTone.Info
        )

        Spacer(Modifier.height(6.dp))
    }
}

/** 二维码位图边长。720px 足够在大屏手机与平板上清晰显示，又不至于生成过慢。 */
private const val QR_SIZE_PX = 720
