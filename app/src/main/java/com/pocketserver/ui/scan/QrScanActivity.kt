package com.pocketserver.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.pocketserver.core.PocketLog
import com.pocketserver.net.QrCodec
import com.pocketserver.ui.theme.PocketServerTheme
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫码接入（FR-2.7）。
 *
 * ## 扫码之后到底做什么
 * 二维码是**服务端**（另一台手机或电视）展示的，里面装着它的主机名、端口和当前配对码。
 * 扫码方要做的就是"拿着这三样东西去访问对方"。而访问方式有两种：
 *  1. 本应用内建立对等会话（双向传输入口，FR-11）
 *  2. 用浏览器打开对方的网页版（单向浏览/上传下载）
 *
 * 这里选了**方案 2**，理由是它零成本且立刻可用：扫码方不需要也装一个口袋服务器。
 * 而如果对方正好也装了本应用，网页版同样完整可用（上传、下载、预览都能做），
 * 不会因为"没走对等通道"而少任何功能 —— 少掉的只是"从本机主动推文件过去"，
 * 那件事在「附近设备」页用 mDNS 自动发现做，比扫码更顺手。
 *
 * ## 现场扫码优先用 IP 而不是主机名
 * 二维码的载荷里主机名在前、IP 在后（主机名不会随 DHCP 变，适合截图长期保存）。
 * 但**现场扫码**的场景下 IP 才是更可靠的那个：部分 Android 版本和不少第三方浏览器
 * 不解析 `.local`。二维码是刚刚对着屏幕扫的，不存在过期问题。
 */
class QrScanActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PocketServerTheme(isTv = false) {
                QrScanScreen(onClose = { finish() })
            }
        }
    }
}

@Composable
private fun QrScanScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    // 直接用 Activity 当 LifecycleOwner：QrScanActivity 本身就是 ComponentActivity，
    // 这样就不必依赖 LocalLifecycleOwner（它在 Compose 1.7 之后挪到了另一个 artifact，
    // 引用错包会编译失败）
    val activity = context as? ComponentActivity

    var payload by remember { mutableStateOf<QrCodec.PairPayload?>(null) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) PocketLog.w(TAG, "用户拒绝了相机权限，扫码不可用")
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 分析线程与"已识别"标记：识别成功一次就够了，反复回调会不停重建对话框
    val analyzer = remember { Executors.newSingleThreadExecutor() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val reader = remember { MultiFormatReader() }
    val alreadyFound = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            analyzer.shutdownNow()
            runCatching { reader.reset() }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission && activity != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        runCatching {
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val analysis = ImageAnalysis.Builder()
                                // 只保留最新一帧：扫码不需要排队处理历史帧，
                                // 否则手机扫到码之后还会继续解几秒前的画面
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysis.setAnalyzer(analyzer) { proxy ->
                                if (alreadyFound.get()) {
                                    proxy.close()
                                    return@setAnalyzer
                                }
                                val text = decode(proxy, reader)
                                proxy.close()
                                if (text == null) return@setAnalyzer
                                val parsed = QrCodec.parse(text) ?: return@setAnalyzer
                                if (alreadyFound.compareAndSet(false, true)) {
                                    handler.post { payload = parsed }
                                }
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                activity,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                        }.onFailure {
                            PocketLog.w(TAG, "相机初始化失败：${it.message}")
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )

            ScanOverlay()
        } else if (!hasPermission) {
            PermissionHint(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onClose = onClose
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        // 底部提示与取消
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "把另一台设备屏幕上的二维码放到框里",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onClose) {
                Text("取消", color = Color.White)
            }
        }
    }

    payload?.let { found ->
        val targetHost = found.ipFallback.ifBlank { found.host }
        val pairUrl = "http://$targetHost:${found.port}/pair?c=${found.code}"
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("接入「${found.name}」") },
            text = {
                Column {
                    Text(
                        text = "$targetHost:${found.port}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "确认后会用浏览器打开对方的页面，并自动带上配对码，不需要手输。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "如果浏览器打不开，请确认两台设备连的是同一个 Wi-Fi。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val opened = runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(pairUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.isSuccess
                    // 打不开浏览器时不静默退出：用户需要知道"刚才那一下为什么没反应"
                    if (!opened) PocketLog.w(TAG, "无法打开浏览器，地址：$pairUrl")
                    onClose()
                }) { Text("用浏览器接入") }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text("取消") }
            }
        )
    }
}

/** 取景框：四角与中间横线。扫码页没有取景提示会让人不知道该把码放多大。 */
@Composable
private fun ScanOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val frame = 260.dp
        Box(
            Modifier
                .size(frame)
                .border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(20.dp))
        )
        Row(
            modifier = Modifier
                .width(frame - 36.dp)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary)
        ) { }
    }
}

@Composable
private fun PermissionHint(onRequest: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "需要相机权限才能扫码",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "也可以不用扫码：在电脑或手机的浏览器里直接输入对方屏幕上的地址，" +
                "再手输一遍 6 位配对码就能接入。",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRequest) { Text("授予权限") }
            OutlinedButton(onClick = onClose) { Text("取消", color = Color.White) }
        }
    }
}

/**
 * 用 ZXing 解一帧。
 *
 * 取 `planes[0]` 直接当灰度图用：相机输出的 YUV_420_888 里，第一个平面就是完整的
 * 亮度（Y）通道，分辨率与整帧一致。这样省掉了 YUV→RGB→灰度 两次转换 ——
 * 在低端机上这两次转换正是扫码"一卡一卡"的元凶。
 *
 * 不做旋转：QR 码的检测器靠三个定位图案定方向，任意角度都能识别，
 * 多一次旋转只会多一次内存拷贝。
 */
private fun decode(image: ImageProxy, reader: MultiFormatReader): String? {
    return runCatching {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val width = image.width
        val height = image.height
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull().also {
        // NotFoundException 是每一帧没找到码时的正常返回，不值得记日志；
        // 其它异常同样按"没找到"处理，避免一次畸形帧把扫码协程打死
        runCatching { reader.reset() }
    }
}

private const val TAG = "QrScan"
