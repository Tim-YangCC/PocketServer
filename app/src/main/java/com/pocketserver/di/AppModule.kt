package com.pocketserver.di

import android.content.Context
import com.pocketserver.data.db.AccessLogDao
import com.pocketserver.data.db.AppDatabase
import com.pocketserver.data.db.PairedDeviceDao
import com.pocketserver.data.db.TransferDao
import com.pocketserver.data.prefs.SettingsStore
import com.pocketserver.data.storage.StorageAccessManager
import com.pocketserver.net.AccessGate
import com.pocketserver.net.PairingManager
import com.pocketserver.net.ServerStats
import com.pocketserver.perf.BufferPool
import com.pocketserver.perf.SpeedMeter
import com.pocketserver.perf.WifiLinkInspector
import com.pocketserver.perf.ZeroCopySender
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * 依赖装配。
 *
 * 全部按 Singleton 提供，理由：这些都是**有状态且必须全局唯一**的组件。
 * 尤其 [BufferPool] 与 [PairingManager] —— 多实例会造成
 * "缓冲被池化两份导致内存翻倍"和"两个配对码同时有效"这类静默故障，
 * 属于最难查的一类 bug，所以在装配层就用 Singleton 把它堵死。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context

    // ==================== 数据层 ====================

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase = AppDatabase.build(context)

    @Provides
    fun provideTransferDao(db: AppDatabase): TransferDao = db.transferDao()

    @Provides
    fun provideAccessLogDao(db: AppDatabase): AccessLogDao = db.accessLogDao()

    @Provides
    fun providePairedDeviceDao(db: AppDatabase): PairedDeviceDao = db.pairedDeviceDao()

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore = SettingsStore(context)

    @Provides
    @Singleton
    fun provideStorageAccess(@ApplicationContext context: Context): StorageAccessManager =
        StorageAccessManager(context)

    // ==================== 网络与安全 ====================

    /**
     * 配对管理器全局唯一。
     * 多实例会让"手机屏幕上显示的配对码"和"服务端校验的配对码"不是同一个，
     * 表现为用户明明输对了却一直提示错误。
     */
    @Provides
    @Singleton
    fun providePairingManager(): PairingManager = PairingManager()

    @Provides
    @Singleton
    fun provideAccessGate(): AccessGate = AccessGate()

    // ==================== 性能层 ====================

    @Provides
    @Singleton
    fun provideSpeedMeter(): SpeedMeter = SpeedMeter()

    @Provides
    @Singleton
    fun provideServerStats(speedMeter: SpeedMeter): ServerStats = ServerStats(speedMeter)

    /**
     * 缓冲池全局唯一。
     * 池化的意义就是"复用同一批直接内存"；若存在两个池，
     * 常驻直接内存会翻倍，在电视盒子上会明显加大部分场景的 OOM 概率。
     */
    @Provides
    @Singleton
    fun provideBufferPool(): BufferPool = BufferPool()

    @Provides
    @Singleton
    fun provideZeroCopySender(bufferPool: BufferPool): ZeroCopySender = ZeroCopySender(bufferPool)

    @Provides
    @Singleton
    fun provideWifiLinkInspector(@ApplicationContext context: Context): WifiLinkInspector =
        WifiLinkInspector(context)

    // ==================== 协程 ====================

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * 应用级作用域：承载"比任何界面活得久"的任务 ——
     * 服务端 accept 循环、状态 ticker、日志批量写入、mDNS 应答循环。
     * 用 SupervisorJob 保证其中一个子任务崩溃不会连坐整条服务链路。
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
