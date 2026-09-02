package dev.mindmax.v4.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

@Entity(
    tableName = "messages",
    indices = [
        Index("conversation_id"),
        Index("timestamp"),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "sender_type") val senderType: SenderType,
    @ColumnInfo(name = "agent_id") val agentId: String? = null,
    @ColumnInfo(name = "agent_name") val agentName: String? = null,
    val content: String,
    val timestamp: Date,
    @ColumnInfo(name = "is_synced") val isSynced: Boolean = false,
    val metadata: String? = null,
    @ColumnInfo(name = "tokens_used") val tokensUsed: Int? = null,
)

enum class SenderType { USER, AGENT, SYSTEM }
