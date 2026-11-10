package com.pocketserver.net

import com.pocketserver.data.model.SharedPermission

/**
 * 共享策略（FR-6）。服务端所有写操作的唯一裁决点。
 *
 * 为什么把策略做成不可变数据类而不是散落在各处理器里的 if：
 * HTTP、FTP、WebDAV、对等传输四条协议栈都要做同样的权限判断，
 * 一旦各自实现，必然出现"网页端不让删但 FTP 能删"这类漏洞 —— 而且极难被发现。
 * 集中到一处，任何协议进来的写请求都过这里，漏判就不可能发生。
 */
data class SharingPolicy(
    /** 默认读写（决策 D-3：双向对等）。 */
    val permission: SharedPermission = SharedPermission.READ_WRITE,
    val allowUpload: Boolean = true,
    val allowDelete: Boolean = true,
    val allowRename: Boolean = true,
    val allowMkdir: Boolean = true,
    /** 每次接入是否必须输配对码。关闭后仅对已白名单设备免码。 */
    val requirePairing: Boolean = true,
    /** 白名单设备是否免码直连。 */
    val trustedBypass: Boolean = true,
    /** 网页端是否显示 `.` 开头的隐藏文件。 */
    val showHiddenFiles: Boolean = false,
    /**
     * 电视端是否允许重命名/新建文件夹。
     * FR-10.6 明确裁剪：遥控器输入文本的体验会毁掉产品口碑。
     */
    val allowTextOperations: Boolean = true
) {

    val canRead: Boolean get() = true

    val canWrite: Boolean
        get() = permission == SharedPermission.READ_WRITE && allowUpload

    val canDeleteAllowed: Boolean
        get() = permission == SharedPermission.READ_WRITE && allowDelete

    val canRenameAllowed: Boolean
        get() = permission == SharedPermission.READ_WRITE && allowRename && allowTextOperations

    /**
     * 新建文件夹。
     *
     * 注意这里**多带了一个 [allowRename] 条件**，是单向依赖而非重复开关：
     * "改名"是这类操作的总闸 —— 关掉重命名就意味着"不许用户在这台设备的共享根里
     * 改动名称结构"，此时还允许新建文件夹会留下一个半开的口子（能建不能改）。
     * 反向不成立：单独关掉新建文件夹不影响重命名，两者不是同一个开关。
     */
    val canMkdirAllowed: Boolean
        get() = permission == SharedPermission.READ_WRITE &&
            allowMkdir && allowRename && allowTextOperations

    /** 只读模式下的统一拒绝文案，四条协议复用，保证用户看到的说法一致。 */
    val readOnlyMessage: String get() = "当前为只读模式，如需写入请在手机上切换为读写"

    fun describe(): String = when (permission) {
        SharedPermission.READ_WRITE -> "读写"
        SharedPermission.READ_ONLY -> "只读"
    }
}
