package com.pocketserver.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketserver.ui.theme.TvTokens

/**
 * 电视端专用的小组件集。
 *
 * ## 为什么不直接用 Material3 的 Button / Switch
 * 不是为了好看，是为了**在 3 米外看得见**。Material3 的组件是按手机视距设计的：
 *  - Button 的默认高度 40dp，在电视上只有约 0.7° 视角，遥控器选中哪一个是猜的
 *  - Switch 的焦点态只是一圈很细的描边，深色背景上几乎不可见
 *
 * 所以这里所有可聚焦元素统一做到：**最小高度不低于 56dp、焦点态用 3dp 亮色描边**。
 * 这两个数字来自十尺 UI 的经验值（FR-10.3）。
 */

/** 电视端主操作按钮。 */
@Composable
fun TvAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    var focused by remember { mutableStateOf(false) }
    val accent = TvTokens.FocusBorder
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        focused -> accent.copy(alpha = 0.22f)
        primary -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        modifier = modifier
            .height(58.dp)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) accent else TvTokens.IdleBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .background(bg, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .focusable(enabled = enabled)
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (primary || focused) FontWeight.Bold else FontWeight.Normal,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                primary -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

/**
 * 电视端开关行。
 *
 * 整行都是热区（而不是只有右侧的小滑块），因为遥控器的焦点导航是按元素走的，
 * 让用户先横向移到小滑块上再按确认，等于平白多一次无效操作。
 *
 * 右侧用「开 / 关」文字而不是 Switch 组件：文字在深色背景上的可读性远高于
 * 一个 40×24dp 的图形，而且不存在"颜色差异看不清"的问题。
 */
@Composable
fun TvToggleRow(
    label: String,
    hint: String,
    checked: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) TvTokens.FocusBorder else TvTokens.IdleBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .background(
                color = if (focused) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled, onClick = onToggle)
            .focusable(enabled = enabled)
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (enabled) hint else "$hint（当前不可用）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = if (checked) "已开启" else "已关闭",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                checked -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
