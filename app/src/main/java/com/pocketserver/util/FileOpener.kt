package com.pocketserver.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.pocketserver.core.PocketLog
import java.io.File

/**
 * 把共享根里的文件交给系统播放器/图片查看器打开（电视端「选卡即播」，FR-10.7）。
 *
 * ## 为什么必须走 FileProvider
 * Android 10（API 29）起，把 `file://` 路径直接放进 Intent 会触发
 * `FileUriExposedException` —— 系统不希望一个应用把文件系统路径泄露给另一个应用。
 * 正确做法是用 `content://` URI，并且只对**本次点击的目标应用**做临时读取授权。
 *
 * ## 为什么这里不接收 StorageProvider
 * 早期版本按存储通道（直连 / SAF）分别处理，后来发现没必要：SAF 模式下共享根
 * 本来就不是真实文件路径，`File(externalStorageDirectory, relPath)` 自然读不到，
 * 于是"读不到"就等价于"打不开"。让文件系统自己回答这个问题，
 * 比让调用方先去问存储通道再决定要不要尝试更简单，也少一条可能走错的分支。
 *
 * 界面拿到 [Result.SafNotSupported] 或 [Result.NotFound] 时应该说的是同一句话：
 * **开启「所有文件访问权限」后就能直接在电视上播放** —— 这同时也是给用户的升级动机。
 */
object FileOpener {

    /** 打开结果。用密封类而不是 Boolean，是因为失败原因不同、界面该说的话也不同。 */
    sealed class Result {
        /** 已成功抛出 Intent。 */
        data object Launched : Result()

        /** 没有可用的真实文件路径（通常是未获得全文件访问，共享根落在应用私有目录）。 */
        data object NoRealPath : Result()

        /** 文件不存在、不可读，或本身不是可预览类型。 */
        data object NotFound : Result()

        /** 系统里没有能打开这种类型的应用（电视盒子经常被裁剪掉图片查看器）。 */
        data object NoAppAvailable : Result()
    }

    /** 只有这些类型值得交给外部应用打开；其它类型直接说"不支持"比抛一个失败更清楚。 */
    fun canOpen(relPath: String): Boolean =
        PathUtil.isPreviewable(relPath) || PathUtil.isPlayableMedia(relPath)

    fun open(context: Context, relPath: String): Result {
        if (!canOpen(relPath)) return Result.NotFound

        val root = sharedRootOf(context) ?: return Result.NoRealPath
        val file = File(root, relPath)
        if (!file.exists() || !file.isFile || !file.canRead()) return Result.NotFound

        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.onFailure {
            // 最常见的原因：文件实际路径不在 file_paths.xml 的白名单内。
            // 这种情况必须留日志，否则线上表现就是"按了没反应"，无从排查。
            PocketLog.w(TAG, "构造 content:// 失败（可能不在白名单内）：${file.absolutePath}")
        }.getOrNull() ?: return Result.NotFound

        val mime = PathUtil.mimeType(relPath).ifBlank { "*/*" }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return Result.NoAppAvailable
        }
        return runCatching {
            context.startActivity(intent)
            Result.Launched
        }.getOrElse {
            PocketLog.w(TAG, "打开文件失败：${it.message}")
            Result.NoAppAvailable
        }
    }

    /**
     * 取共享根的绝对路径。
     *
     * 必须与 `StorageAccessManager.resolveDirectRoot` 用**同一套判定顺序**，
     * 否则会出现"文件传得进去却打不开"这种自相矛盾的现象：
     * 写入侧用的是应用私有目录，读取侧却在找内部存储根。
     */
    private fun sharedRootOf(context: Context): String? {
        val external = android.os.Environment.getExternalStorageDirectory()
        if (external != null && external.exists() && external.canRead()) {
            return external.absolutePath
        }
        val appExternal = context.getExternalFilesDir(null)
        if (appExternal != null && appExternal.exists()) return appExternal.absolutePath
        return context.filesDir?.takeIf { it.exists() }?.absolutePath
    }

    private const val TAG = "FileOpener"
}
