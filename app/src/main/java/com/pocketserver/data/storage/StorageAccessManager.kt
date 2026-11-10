package com.pocketserver.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.pocketserver.core.PocketLog
import com.pocketserver.data.model.StorageMode
import com.pocketserver.data.model.StorageShare
import com.pocketserver.data.prefs.AppSettings
import java.io.File

/**
 * 存储通道选择器。
 *
 * ## 这个类存在的唯一理由：性能（O-4）
 * 手机上有两条访问文件的通路，速度差 15% 以上：
 *  - **直连**（真实路径 + FileChannel）：可零拷贝、可随机写、可预分配 → 目标 55MB/s
 *  - **SAF**（系统文件框架）：每次 open 都要跨进程 IPC，拿不到 FileChannel → 约 -15%，且分块并行失效
 *
 * 所以策略是：**尽力拿直连，拿不到才退 SAF，并且把退化原因如实告诉用户**。
 * 不告诉用户的后果是：用户觉得"这软件就是慢"，然后在应用商店打一星。
 */
class StorageAccessManager(private val context: Context) {

    /** 是否已获得全文件访问（或旧版本上的读写外置存储权限）。 */
    fun hasAllFilesAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        else -> true
    }

    /** 跳转系统设置让用户授予全文件访问。返回 null 表示当前系统版本不需要。 */
    fun allFilesAccessIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * 当前应使用的存储通道。
     *
     * 优先级：用户指定目录(直连) > 全文件访问(直连) > SAF 授权目录 > 应用私有目录。
     * 最后一档是"保底可用"：应用私有目录永远能读写，但用户在系统文件管理器里看不到，
     * 因此 UI 会明确提示"当前只能共享应用内置空间"。
     */
    fun describe(settings: AppSettings): StorageMode = shareInfo(settings).mode

    /**
     * 存储通道的"指纹"。用于判断**权限变化后共享根有没有变**。
     *
     * 场景：用户先把服务打开了（此时还没授权，共享根是应用私有目录），
     * 然后去系统设置里授予「所有文件访问权限」，再切回应用 —— 什么都没触发重建，
     * 服务端仍在共享那个空目录，用户会以为"授权了也没用"。
     * 有了指纹，回到前台时比对一次就能发现并重建。
     */
    fun rootKey(settings: AppSettings): String {
        resolveDirectRoot(settings)?.let { return "direct:" + it.absolutePath }
        if (settings.safTreeUri.isNotBlank()) return "saf:" + settings.safTreeUri
        return "private:" + (context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath)
    }

    fun build(settings: AppSettings): StorageProvider {
        resolveDirectRoot(settings)?.let { root ->
            PocketLog.i(TAG, "存储通道：直连（${root.absolutePath}）→ 可走零拷贝")
            return DirectStorageProvider(root)
        }
        val treeUri = settings.safTreeUri
        if (treeUri.isNotBlank()) {
            PocketLog.w(TAG, "存储通道：SAF（用户授权目录）→ 吞吐预计下降约 15%")
            return SafStorageProvider(context, Uri.parse(treeUri))
        }
        val privateDir = context.getExternalFilesDir(null) ?: context.filesDir
        PocketLog.w(TAG, "存储通道：应用私有目录（未获得全文件访问）→ 仅应用可访问")
        return DirectStorageProvider(privateDir)
    }

    /**
     * 当前共享根的完整描述（FR-6）。
     *
     * 这是修「电脑上看不到手机里的文件」这个反馈的关键一步：退到应用私有目录时
     * 目录**存在、可读、可写、列目录成功**，只是空的。上层拿不到任何异常，
     * 只能看到一个空列表，于是把结论说成"空文件夹"。用户真正需要知道的是
     * 「我共享错了地方」——这件事只有这里知道，就必须由这里说出来。
     */
    fun shareInfo(settings: AppSettings): StorageShare {
        val direct = resolveDirectRoot(settings)
        if (direct != null) {
            val external = Environment.getExternalStorageDirectory()
            val isFull = external != null && samePath(direct, external)
            val privateDir = context.getExternalFilesDir(null)
            val isPrivate = privateDir != null && samePath(direct, privateDir)
            return StorageShare(
                mode = StorageMode.DIRECT,
                rootLabel = when {
                    isFull -> "手机内部存储"
                    isPrivate -> "应用内置空间"
                    else -> direct.name.ifBlank { "共享目录" }
                },
                rootPath = direct.absolutePath,
                fullStorage = isFull,
                notice = when {
                    isFull -> null
                    isPrivate -> "当前只共享了应用自己的空间（" + direct.absolutePath + "），" +
                        "手机里的照片、下载、微信文件都在别的位置，所以电脑上看不到。" +
                        "请在手机上授予「所有文件访问权限」，然后重新打开这个页面。"
                    else -> "当前共享的是自定义目录（" + direct.absolutePath + "），" +
                        "手机其它位置的文件不会出现在这里。"
                }
            )
        }

        val treeUri = settings.safTreeUri
        if (treeUri.isNotBlank()) {
            return StorageShare(
                mode = StorageMode.SAF,
                rootLabel = "已授权目录",
                rootPath = "",
                fullStorage = false,
                notice = "当前只共享了你在手机上单独授权的那一个目录，看不到手机里的其它文件，" +
                    "传输速度也会比直连慢约 15%。建议改为授予「所有文件访问权限」。"
            )
        }

        val privateDir = context.getExternalFilesDir(null) ?: context.filesDir
        return StorageShare(
            mode = StorageMode.DIRECT,
            rootLabel = "应用内置空间",
            rootPath = privateDir.absolutePath,
            fullStorage = false,
            notice = "还没有拿到存储访问权限，现在只能共享应用内置空间，" +
                "所以电脑上看不到手机里的文件。请在手机「设置」中授予「所有文件访问权限」。"
        )
    }

    /** 两个路径是否指向同一个位置。canonicalPath 在个别 ROM 上会抛异常，此时按字符串判等。 */
    private fun samePath(a: File, b: File): Boolean = runCatching {
        a.canonicalPath == b.canonicalPath
    }.getOrDefault(a.absolutePath == b.absolutePath)

    /** 供设置页展示的一行说明。 */
    fun describeHint(settings: AppSettings): String = when {
        resolveDirectRoot(settings) != null -> "全文件访问已开启，传输走最优通路"
        settings.safTreeUri.isNotBlank() -> "仅授权了单个目录，传输速度会下降约 15%，建议改为授予「所有文件访问权限」"
        else -> "未获得存储权限，当前只能共享应用内置空间。请在设置中授予「所有文件访问权限」"
    }

    private fun resolveDirectRoot(settings: AppSettings): File? {
        if (settings.shareRootPath.isNotBlank()) {
            val custom = File(settings.shareRootPath)
            if (custom.exists() && custom.isDirectory && custom.canRead()) return custom
        }
        if (!hasAllFilesAccess()) return null
        val external = Environment.getExternalStorageDirectory()
        return if (external != null && external.exists() && external.canRead()) external else null
    }

    /** 默认共享根（新增共享目录时的起点）。 */
    fun suggestedRoot(settings: AppSettings): String =
        resolveDirectRoot(settings)?.absolutePath ?: (context.getExternalFilesDir(null)?.absolutePath ?: "")

    private companion object {
        const val TAG = "StorageAccess"
    }
}
