package com.pocketserver.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * 每秒自增一次的"当前时间"。
 *
 * ## 为什么需要一个共享的 ticker
 * 这个应用有三处需要"倒计时/已运行时长"这类按秒变化的时间：控制台的配对码倒计时、
 * 二维码页的剩余有效期、已连接设备页的令牌剩余时间。各自实现一遍的结果是
 * **三处刷新节奏不一致**（一个 1000ms、一个 800ms），用户会觉得整个界面在细微地抖。
 *
 * ## 为什么不把 tick 放在 ViewModel 里
 * 秒级心跳是纯粹的展示需求 —— 后台没有任何东西需要它。放在 ViewModel 里会导致
 * 应用退到后台时依然在推状态，白白耗电。放在 Composable 里，界面一销毁就自动停。
 */
@Composable
fun rememberNowTick(intervalMs: Long = 1000L): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            delay(intervalMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}
