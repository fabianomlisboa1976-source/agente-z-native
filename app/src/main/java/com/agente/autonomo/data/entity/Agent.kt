package com.agente.autonomo.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.Date

@Entity(tableName = "agents")
data class Agent(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "name")
    val name: String,
    
    @ColumnInfo(name = "description")
    val description: String,
    
    @ColumnInfo(name = "type")
    val type: String = "CUSTOM",
    
    @ColumnInfo(name = "systemPrompt")
    val systemPrompt: String,
    
    @ColumnInfo(name = "isActive")
    val isActive: Boolean = true,
    
    @ColumnInfo(name = "priority")
    val priority: Int = 0,
    
    @ColumnInfo(name = "capabilities")
    val capabilities: String = "",
    
    @ColumnInfo(name = "maxTokens")
    val maxTokens: Int = 2048,
    
    @ColumnInfo(name = "temperature")
    val temperature: Float = 0.7f,
    
    @ColumnInfo(name = "createdAt")
    val createdAt: Date = Date(),
    
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Date = Date(),
    
    @ColumnInfo(name = "lastUsed")
    val lastUsed: Date? = null,
    
    @ColumnInfo(name = "usageCount")
    val usageCount: Int = 0,
    
    @ColumnInfo(name = "color")
    val color: String = "#6366F1"
)
