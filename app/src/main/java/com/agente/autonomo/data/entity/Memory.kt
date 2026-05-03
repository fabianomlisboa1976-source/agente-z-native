package com.agente.autonomo.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

/**
 * Long-term memory entity persisted in Room.
 *
 * ## Embedding column
 * [embedding] stores the L2-normalised sentence embedding produced by
 * [com.agente.autonomo.memory.EmbeddingEngine] for the concatenation of
 * [key] + " " + [value].  It is serialised as a BLOB (little-endian IEEE-754
 * float array, 384 × 4 = 1 536 bytes for all-MiniLM-L6-v2).
 *
 * A `null` embedding means the row was inserted before the embedding engine
 * was available (legacy row) or that embedding inference failed.  Such rows
 * are still matched by keyword search as a fallback but are excluded from
 * vector similarity ranking in
 * [com.agente.autonomo.memory.MemorySearchEngine].
 */
@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "type")
    val type: MemoryType,

    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String,

    @ColumnInfo(name = "category")
    val category: String? = null,

    @ColumnInfo(name = "importance")
    val importance: Int = 5, // 1-10

    @ColumnInfo(name = "source_agent")
    val sourceAgent: String? = null,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Date = Date(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Date = Date(),

    @ColumnInfo(name = "last_accessed")
    val lastAccessed: Date? = null,

    @ColumnInfo(name = "access_count")
    val accessCount: Int = 0,

    @ColumnInfo(name = "expires_at")
    val expiresAt: Date? = null,

    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,

    @ColumnInfo(name = "metadata")
    val metadata: String? = null, // JSON with additional metadata

    /**
     * L2-normalised sentence embedding for semantic similarity search.
     * Serialised as little-endian IEEE-754 BLOB (384 floats = 1 536 bytes).
     * Null for legacy rows or when embedding inference failed.
     *
     * Use [com.agente.autonomo.memory.EmbeddingEngine.bytesToFloatArray] to
     * deserialise and [com.agente.autonomo.memory.EmbeddingEngine.cosineSimilarity]
     * to compare.
     */
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray? = null
) {
    enum class MemoryType {
        FACT,           // Known fact
        PREFERENCE,     // User preference
        CONTEXT,        // Conversation context
        TASK_RESULT,    // Task result
        LEARNED,        // System learning
        USER_PROFILE,   // User profile
        SYSTEM_STATE    // System state
    }

    /**
     * Two [Memory] instances are considered equal if their [id] fields match.
     * The [embedding] BLOB is intentionally excluded from equality checks
     * because [ByteArray.equals] uses reference equality by default.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Memory) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
