package com.agente.autonomo.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.Date

/**
 * Entidade que representa uma mensagem no chat
 */
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "conversation_id")
    val conversationId: String = "default",
    
    @ColumnInfo(name = "sender_type")
    val senderType: SenderType,
    
    @ColumnInfo(name = "agent_id")
    val agentId: String? = null,
    
    @ColumnInfo(name = "agent_name")
    val agentName: String? = null,
    
    @ColumnInfo(name = "content")
    val content: String,
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Date = Date(),
    
    @ColumnInfo(name = "is_synced")
    val isSynced: Boolean = false,
    
    @ColumnInfo(name = "metadata")
    val metadata: String? = null,
    
    @ColumnInfo(name = "tokens_used")
    val tokensUsed: Int? = null
) {
    enum class SenderType {
        USER,
        AGENT,
        SYSTEM
    }
}
