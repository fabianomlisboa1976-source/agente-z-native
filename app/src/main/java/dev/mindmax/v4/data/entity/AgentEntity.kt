package dev.mindmax.v4.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "agents",
    indices = [Index("type"), Index("is_active")],
)
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val type: AgentType,
    @ColumnInfo(name = "system_prompt") val systemPrompt: String,
    @ColumnInfo(name = "is_active", defaultValue = "1") val isActive: Boolean = true,
    @ColumnInfo(defaultValue = "0") val priority: Int = 0,
    val capabilities: List<String> = emptyList(),
    @ColumnInfo(name = "max_tokens", defaultValue = "1024") val maxTokens: Int = 1024,
    @ColumnInfo(defaultValue = "0.7") val temperature: Float = 0.7f,
    @ColumnInfo(name = "created_at") val createdAt: Date,
    @ColumnInfo(name = "updated_at") val updatedAt: Date,
    @ColumnInfo(name = "last_used") val lastUsed: Date? = null,
    @ColumnInfo(name = "usage_count", defaultValue = "0") val usageCount: Int = 0,
    val color: String,
)

enum class AgentType {
    COORDINATOR, PLANNER, RESEARCHER, EXECUTOR, AUDITOR, MEMORY, COMMUNICATION, CUSTOM,
}
