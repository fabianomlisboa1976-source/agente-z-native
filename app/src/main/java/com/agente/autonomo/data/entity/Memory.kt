package com.agente.autonomo.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.Date

@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "type")
    val type: String = "FACT",
    
    @ColumnInfo(name = "key")
    val key: String,
    
    @ColumnInfo(name = "value")
    val value: String,
    
    @ColumnInfo(name = "category")
    val category: String? = null,
    
    @ColumnInfo(name = "importance")
    val importance: Int = 5,
    
    @ColumnInfo(name = "sourceAgent")
    val sourceAgent: String? = null,
    
    @ColumnInfo(name = "conversationId")
    val conversationId: String? = null,
    
    @ColumnInfo(name = "createdAt")
    val createdAt: Date = Date(),
    
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Date = Date(),
    
    @ColumnInfo(name = "lastAccessed")
    val lastAccessed: Date? = null,
    
    @ColumnInfo(name = "accessCount")
    val accessCount: Int = 0,
    
    @ColumnInfo(name = "expiresAt")
    val expiresAt: Date? = null,
    
    @ColumnInfo(name = "isArchived")
    val isArchived: Boolean = false,
    
    @ColumnInfo(name = "metadata")
    val metadata: String? = null
)
