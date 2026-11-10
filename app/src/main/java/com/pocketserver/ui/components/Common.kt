package com.pocketserver.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketserver.ui.theme.TvTokens

/** 通用区块卡片。手机端与电视端共用，靠 theme 的 typography 自动适配字号。 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

/** 产品信息统一入口，供手机端和电视端设置页共用。 */
@Composable
fun AboutCard(extraContent: (@Composable () -> Unit)? = null) {
    SectionCard(title = "关于") {
        Text(
            text = "口袋服务器 PocketServer",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        KeyValueRow(label = "作者", value = "杨凯")
        KeyValueRow(label = "邮箱", value = "yangkaicim@foxmail.com")
        KeyValueRow(
            label = "版本",
            value = com.pocketserver.BuildConfig.VERSION_NAME,
            valueColor = MaterialTheme.colorScheme.primary,
            valueBold = true
        )
        KeyValueRow(label = "发布时间", value = "2026年9月30日")
        if (extraContent != null) {
            Spacer(Modifier.height(8.dp))
            extraContent()
        }
    }
}

/** 一行「标签 : 值」。 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    valueBold: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (valueBold) FontWeight.SemiBold else FontWeight.Normal,
            color = valueColor,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) trailing()
    }
}

/** 状态圆点 + 文案。 */
@Composable
fun StatusDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 大号配对码。手机端是主角，电视端是"3 米外可读"的硬指标（FR-10.4）。
 *
 * 用等宽字体 + 字距：数字 0/6/8 在比例字体里辨识度低，
 * 而用户要在电脑上把这 6 位敲进去，读错一位就要重来。
 */
@Composable
fun PairingCodeText(
    code: String,
    isTv: Boolean,
    modifier: Modifier = Modifier
) {
    // 字号是硬指标，不是审美：
    //  - 电视端要满足 FR-10.4「3 米外可读」。55 吋屏幕、3 米视距下，48sp 约合 0.55° 视角，
    //    刚过人眼舒适阅读下限，所以取 56sp 留一点余量。
    //  - 手机端取 32sp：用户要一边看手机一边在电脑上敲这 6 位数字，字号低于正文水平
    //    （14sp）会直接导致读错重来。
    val spacing = if (isTv) 56.sp else 32.sp
    val letterSpacing = if (isTv) 18.sp else 10.sp
    Text(
        text = code.ifBlank { "------" }.toCharArray().joinToString(" "),
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = spacing,
        letterSpacing = letterSpacing,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        lineHeight = spacing * 1.2f
    )
}

/**
 * 电视端可聚焦卡片（FR-10.3 焦点态必须有明显描边）。
 *
 * 为什么焦点态不能用"轻微变色"表示：坐在 3 米外，细微的颜色差异根本看不出来，
 * 用户会迷路。所以这里用 3dp 亮蓝描边 + 轻微背景提亮，是弱视用户也能分辨的差异。
 */
@Composable
fun TvFocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = TvTokens.FOCUS_BORDER_DP.dp,
                color = if (focused) TvTokens.FocusBorder else TvTokens.IdleBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .focusable()
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(14.dp),
        color = if (focused) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Box(Modifier.padding(18.dp)) { content() }
    }
}

/** 提示横幅。用于权限缺失、频段建议、端口占用这类"可操作的提示"。 */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val (bg, fg) = when (tone) {
        BannerTone.Info -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        BannerTone.Warn -> Color(0xFFFFF3E0) to Color(0xFF8A5300)
        BannerTone.Error -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f) to MaterialTheme.colorScheme.error
        BannerTone.Success -> Color(0xFFE7F7EE) to Color(0xFF116039)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
            modifier = Modifier.weight(1f)
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

enum class BannerTone { Info, Warn, Error, Success }

/** 空状态占位：一句话说清"为什么空"和"该做什么"，而不是只写"暂无数据"。 */
@Composable
fun EmptyState(title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
