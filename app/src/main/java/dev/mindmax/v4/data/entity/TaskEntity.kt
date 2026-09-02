package dev.mindmax.v4.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "tasks",
    indices = [
        Index("status"),
        Index("type"),
        Index("scheduled_at"),
        Index("parent_task_id"),
    ],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String? = null,
    val type: TaskType,
    val status: TaskStatus,
    val priority: TaskPriority,
    @ColumnInfo(name = "assigned_agent") val assignedAgent: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Date,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Date? = null,
    @ColumnInfo(name = "started_at") val startedAt: Date? = null,
    @ColumnInfo(name = "completed_at") val completedAt: Date? = null,
    @ColumnInfo(name = "due_date") val dueDate: Date? = null,
    val parameters: String? = null,
    val result: String? = null,
    val error: String? = null,
    @ColumnInfo(name = "retry_count", defaultValue = "0") val retryCount: Int = 0,
    @ColumnInfo(name = "max_retries", defaultValue = "3") val maxRetries: Int = 3,
    @ColumnInfo(name = "parent_task_id") val parentTaskId: String? = null,
    val tags: List<String> = emptyList(),
    @ColumnInfo(name = "is_recurring", defaultValue = "0") val isRecurring: Boolean = false,
    @ColumnInfo(name = "recurrence_rule") val recurrenceRule: String? = null,
)

enum class TaskType {
    API_CALL, DATA_PROCESSING, NOTIFICATION, REMINDER, RESEARCH, COMMUNICATION, FILE_OPERATION, CUSTOM,
}

enum class TaskStatus {
    PENDING, SCHEDULED, RUNNING, COMPLETED, FAILED, CANCELLED, RETRYING,
}

enum class TaskPriority { LOW, MEDIUM, HIGH, CRITICAL }
