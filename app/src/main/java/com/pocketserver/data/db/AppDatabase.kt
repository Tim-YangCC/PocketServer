package com.pocketserver.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本机数据库。
 *
 * 传输记录用于历史统计，升级数据库时必须保留，所以结构变化使用显式迁移。
 */
@Database(
    entities = [TransferTaskEntity::class, AccessLogEntity::class, PairedDeviceEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transferDao(): TransferDao
    abstract fun accessLogDao(): AccessLogDao
    abstract fun pairedDeviceDao(): PairedDeviceDao

    companion object {
        const val NAME = "pocketserver.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transfer_tasks ADD COLUMN batchId TEXT NOT NULL DEFAULT ''")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
