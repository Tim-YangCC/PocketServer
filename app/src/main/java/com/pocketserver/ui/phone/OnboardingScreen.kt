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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketserver.data.storage.StorageAccessManager
import com.pocketserver.ui.components.Banner
import com.pocketserver.ui.components.BannerTone
import com.pocketserver.ui.components.SectionCard

/**
 * 首次启动引导（FR-9.1）。
 *
 * ## 为什么只做三步而不是做一长串
 * 首启引导的实际阅读率极低 —— 用户装完就想去试。所以这里只保留三件
 * **不看他一定会卡住**的事：地址怎么给电脑、配对码怎么用、为什么必须授权存储。
 * 其余设置项全部留给设置页，需要时再查。
 *
 * ## 为什么"存储授权"要单独占一步
 * 授权与否决定的是**两倍以上的速度差**（直连 + 零拷贝 vs 系统框架），
 * 而且没授权时共享出去的是应用私有目录，用户在系统文件管理器里根本看不到自己的文件，
 * 会觉得"这软件是个空壳"。这件事必须在第一次打开时就讲清楚、点到。
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val storage = remember(context) { StorageAccessManager(context) }
    var step by remember { mutableIntStateOf(0) }
    var hasAccess by remember { mutableStateOf(storage.hasAllFilesAccess()) }

    val steps = remember { onboardingSteps() }
    val current = steps[step]
    val lastStep = step == steps.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            if (!lastStep) {
                TextButton(onClick = onFinish) { Text("跳过") }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 步骤指示点：让用户知道"还剩几下"，避免一上来就想跳过
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.indices.forEach { i ->
                Box(
                    Modifier
                        .size(width = if (i == step) 22.dp else 8.dp, height = 8.dp)
                        .background(
                            color = if (i <= step) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape
                        )
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = current.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = current.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        // 第三步才展示授权状态：前两步谈授权会打断理解主线
        if (step == 2) {
            SectionCard(title = "当前存储权限状态") {
                if (hasAccess) {
                    Banner(
                        text = "已获得「所有文件访问权限」，传输会走最快的通道，并且共享的是你真实的文件。",
                        tone = BannerTone.Success
                    )
                } else {
                    Banner(
                        text = "尚未获得权限。现在也能用，但只能共享应用内置空间，且传输速度会明显偏低。",
                        tone = BannerTone.Warn
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            storage.allFilesAccessIntent()?.let { intent ->
                                runCatching { context.startActivity(intent) }
                            }
                        }) { Text("去授权") }
                        OutlinedButton(onClick = { hasAccess = storage.hasAllFilesAccess() }) {
                            Text("我已授权")
                        }
                    }
                }
            }
        } else {
            current.highlight?.let { highlight ->
                SectionCard(title = highlight.title) {
                    Text(
                        text = highlight.value,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = highlight.hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step > 0) {
                OutlinedButton(
                    onClick = { step-- },
                    modifier = Modifier.width(96.dp)
                ) { Text("上一步") }
            }
            Button(
                onClick = { if (lastStep) onFinish() else step++ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (lastStep) "开始使用" else "下一步", textAlign = TextAlign.Center)
            }
        }
    }
}

private data class Highlight(val title: String, val value: String, val hint: String)

private data class OnboardingStep(
    val title: String,
    val body: String,
    val highlight: Highlight? = null
)

private fun onboardingSteps(): List<OnboardingStep> = listOf(
    OnboardingStep(
        title = "把这部手机变成服务器",
        body = "开启之后，这部手机就是一个局域网里的小型文件服务器。" +
            "家里的电脑、平板、电视都能直接连上来读写文件，" +
            "文件不经过任何第三方服务器，全在你自己家里。",
        highlight = Highlight(
            title = "第一步不用设置什么",
            value = "同一个 Wi-Fi 就行",
            hint = "手机和电脑连在同一个路由器上（同一个 Wi-Fi 名称），后面的事都可以自动完成。"
        )
    ),
    OnboardingStep(
        title = "电脑端不用装任何东西",
        body = "在电脑浏览器的地址栏里输入下面这个地址，就能看到手机里的文件，" +
            "像用网盘一样上传下载。第一次连的时候需要输一次配对码，之后就不用再输了。",
        highlight = Highlight(
            title = "电脑浏览器打开这个地址",
            value = "pocketserver.local:8080",
            hint = "地址是固定的、不用记 IP。端口默认 8080，可在设置里改。"
        )
    ),
    OnboardingStep(
        title = "给它一个存放文件的地方",
        body = "授予「所有文件访问权限」后，共享出去的就是你自己的文件夹（比如相册、下载目录），" +
            "你在系统文件管理器里能看到它们。不授权也能用，但只能共享应用内部空间，" +
            "而且在电脑上看不到想找的文件。",
        highlight = null
    )
)
