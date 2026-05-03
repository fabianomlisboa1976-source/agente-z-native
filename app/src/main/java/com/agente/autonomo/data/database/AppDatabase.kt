package com.agente.autonomo.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agente.autonomo.data.dao.*
import com.agente.autonomo.data.entity.*
import com.agente.autonomo.utils.DateConverter

/**
 * Room database singleton for the Agente Autônomo app.
 *
 * ## Schema version history
 * | Version | Change |
 * |---------|--------|
 * | 1       | Initial schema (agents, messages, memories, audit_logs, tasks, settings) |
 * | 2       | Added `provider_health` table for offline-first LLM failover chain |
 *
 * ## Migration
 * [MIGRATION_1_2] creates the `provider_health` table so existing installs
 * are upgraded without data loss.
 */
@Database(
    entities = [
        Agent::class,
        Message::class,
        Memory::class,
        AuditLog::class,
        Task::class,
        Settings::class,
        ProviderHealth::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(DateConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun agentDao(): AgentDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun taskDao(): TaskDao
    abstract fun settingsDao(): SettingsDao
    abstract fun providerHealthDao(): ProviderHealthDao

    companion object {
        const val DATABASE_NAME = "agente_autonomo_db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `provider_health` (
                        `provider` TEXT NOT NULL,
                        `isHealthy` INTEGER NOT NULL DEFAULT 1,
                        `consecutiveFailures` INTEGER NOT NULL DEFAULT 0,
                        `lastChecked` INTEGER NOT NULL DEFAULT 0,
                        `lastFailureReason` TEXT,
                        `backoffUntil` INTEGER NOT NULL DEFAULT 0,
                        `totalRequests` INTEGER NOT NULL DEFAULT 0,
                        `totalFailures` INTEGER NOT NULL DEFAULT 0,
                        `averageLatencyMs` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`provider`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
