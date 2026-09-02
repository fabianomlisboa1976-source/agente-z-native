package dev.mindmax.v4.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.mindmax.v4.data.dao.AgentDao
import dev.mindmax.v4.data.dao.AuditDao
import dev.mindmax.v4.data.dao.MemoryDao
import dev.mindmax.v4.data.dao.MessageDao
import dev.mindmax.v4.data.dao.SettingsDao
import dev.mindmax.v4.data.dao.TaskDao
import dev.mindmax.v4.data.entity.AgentEntity
import dev.mindmax.v4.data.entity.AuditLogEntity
import dev.mindmax.v4.data.entity.MemoryEntity
import dev.mindmax.v4.data.entity.MessageEntity
import dev.mindmax.v4.data.entity.SettingsEntity
import dev.mindmax.v4.data.entity.TaskEntity

@Database(
    entities = [
        MessageEntity::class,
        AgentEntity::class,
        AuditLogEntity::class,
        SettingsEntity::class,
        TaskEntity::class,
        MemoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DateConverter::class, Converters::class)
abstract class MindMaxDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun agentDao(): AgentDao
    abstract fun auditLogDao(): AuditDao
    abstract fun settingsDao(): SettingsDao
    abstract fun taskDao(): TaskDao
    abstract fun memoryDao(): MemoryDao
}
