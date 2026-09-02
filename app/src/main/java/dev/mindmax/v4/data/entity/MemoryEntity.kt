package dev.mindmax.v4.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "memories",
    indices = [
        Index("type"),
        Index("category"),
        Index("is_archived"),
        Index("conversation_id"),
    ],
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val type: MemoryType,
    val key: String,
    val value: String,
    val category: String? = null,
    @ColumnInfo(defaultValue = "5") val importance: Int = 5,
    @ColumnInfo(name = "source_agent") val sourceAgent: String? = null,
    @ColumnInfo(name = "conversation_id") val conversationId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Date,
    @ColumnInfo(name = "updated_at") val updatedAt: Date,
    @ColumnInfo(name = "last_accessed") val lastAccessed: Date? = null,
    @ColumnInfo(name = "access_count", defaultValue = "0") val accessCount: Int = 0,
    @ColumnInfo(name = "expires_at") val expiresAt: Date? = null,
    @ColumnInfo(name = "is_archived", defaultValue = "0") val isArchived: Boolean = false,
    val metadata: String? = null,
)

enum class MemoryType {
    FACT, PREFERENCE, CONTEXT, TASK_RESULT, LEARNED, USER_PROFILE, SYSTEM_STATE,
}
