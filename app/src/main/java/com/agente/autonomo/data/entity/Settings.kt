package com.agente.autonomo.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.Date

/**
 * Entidade para configurações do aplicativo
 */
@Entity(tableName = "settings")
data class Settings(
    @PrimaryKey
    val id: Int = 1, // Sempre 1, apenas uma linha
    
    // Configurações de API - Valores padrão otimizados
    @ColumnInfo(name = "api_provider")
    val apiProvider: String = "groq",
    
    @ColumnInfo(name = "api_key")
    val apiKey: String = "", // Usuário deve configurar sua própria key
    
    @ColumnInfo(name = "api_model")
    val apiModel: String = "llama-3.3-70b-versatile",
    
    @ColumnInfo(name = "api_base_url")
    val apiBaseUrl: String? = null,
    
    // Configurações de geração
    @ColumnInfo(name = "max_tokens")
    val maxTokens: Int = 4096,
    
    @ColumnInfo(name = "temperature")
    val temperature: Float = 0.7f,
    
    @ColumnInfo(name = "top_p")
    val topP: Float = 0.9f,
    
    // Configurações do serviço
    @ColumnInfo(name = "auto_start")
    val autoStart: Boolean = true,
    
    @ColumnInfo(name = "service_enabled")
    val serviceEnabled: Boolean = true,
    
    @ColumnInfo(name = "notification_enabled")
    val notificationEnabled: Boolean = true,
    
    // Configurações de auditoria
    @ColumnInfo(name = "audit_enabled")
    val auditEnabled: Boolean = true,
    
    @ColumnInfo(name = "audit_retention_days")
    val auditRetentionDays: Int = 30,
    
    // Configurações de agentes
    @ColumnInfo(name = "default_agent")
    val defaultAgent: String = "coordinator",
    
    @ColumnInfo(name = "multi_agent_enabled")
    val multiAgentEnabled: Boolean = true,
    
    @ColumnInfo(name = "cross_audit_enabled")
    val crossAuditEnabled: Boolean = true,
    
    // Configurações de memória
    @ColumnInfo(name = "context_window_size")
    val contextWindowSize: Int = 20,
    
    @ColumnInfo(name = "memory_enabled")
    val memoryEnabled: Boolean = true,
    
    // Configurações de retry
    @ColumnInfo(name = "max_retries")
    val maxRetries: Int = 3,
    
    @ColumnInfo(name = "retry_delay_ms")
    val retryDelayMs: Long = 1000,
    
    // Timestamps
    @ColumnInfo(name = "created_at")
    val createdAt: Date = Date(),
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Date = Date()
) {
    /**
     * Retorna true se as configurações básicas estão prontas para uso
     */
    fun isConfigured(): Boolean {
        return apiKey.isNotBlank()
    }
    
    /**
     * Retorna instruções de configuração
     */
    fun getSetupInstructions(): String {
        return """
            Para usar o Agente Z, configure sua API Key:
            
            1. Acesse https://console.groq.com/keys
            2. Crie uma chave de API gratuita
            3. Cole a chave nas Configurações do app
            
            Você também pode usar:
            - OpenRouter (https://openrouter.ai)
            - Cloudflare Workers AI
            - Qualquer API compatível com OpenAI
        """.trimIndent()
    }
}
