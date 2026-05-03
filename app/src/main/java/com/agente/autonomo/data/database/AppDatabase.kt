package com.agente.autonomo.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agente.autonomo.data.dao.AgentDao
import com.agente.autonomo.data.dao.AuditLogDao
import com.agente.autonomo.data.dao.MemoryDao
import com.agente.autonomo.data.dao.MessageDao
import com.agente.autonomo.data.dao.ProviderHealthDao
import com.agente.autonomo.data.dao.SettingsDao
import com.agente.autonomo.data.dao.TaskDao
import com.agente.autonomo.data.entity.Agent
import com.agente.autonomo.data.entity.AuditLog
import com.agente.autonomo.data.entity.Memory
import com.agente.autonomo.data.entity.Message
import com.agente.autonomo.data.entity.ProviderHealth
import com.agente.autonomo.data.entity.Settings
import com.agente.autonomo.data.entity.Task
import com.agente.autonomo.utils.DateConverter

/**
 * Room database singleton.
 *
 * ## Schema version history
 * | Version | Change |
 * |---------|--------|
 * | 1       | Initial schema |
 * | 2       | Add `ProviderHealth` table |
 * | 3       | Add `embedding` BLOB column to `memories` table |
 *
 * Migration 2→3 adds the `embedding` column with a default of NULL so all
 * existing rows are treated as legacy rows pending back-fill by
 * [com.agente.autonomo.memory.EmbeddingBackfillWorker].
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
    version = 3,
    exportSchema = true
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
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // ------------------------------------------------------------------
        // Migrations
        // ------------------------------------------------------------------

        /**
         * Migration 2 → 3: add nullable `embedding` BLOB column to `memories`.
         * All existing rows keep `embedding = NULL` and will be back-filled
         * asynchronously by [com.agente.autonomo.memory.EmbeddingBackfillWorker].
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE memories ADD COLUMN embedding BLOB DEFAULT NULL"
                )
            }
        }

        // ------------------------------------------------------------------
        // Singleton accessor
        // ------------------------------------------------------------------

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agente_autonomo_database"
                )
                    .addMigrations(MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
