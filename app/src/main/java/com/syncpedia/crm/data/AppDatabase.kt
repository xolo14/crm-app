package com.syncpedia.crm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class SyncStatusConverters {
    @TypeConverter
    fun toStatus(value: String?): SyncStatus =
        runCatching { SyncStatus.valueOf(value ?: SyncStatus.PENDING.name) }
            .getOrDefault(SyncStatus.PENDING)

    @TypeConverter
    fun fromStatus(status: SyncStatus?): String = (status ?: SyncStatus.PENDING).name
}

@Database(
    entities = [CallRecordEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(SyncStatusConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callRecordDao(): CallRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(ctx: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "syncpedia_crm.db"
                ).fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
