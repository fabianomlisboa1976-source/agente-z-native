package dev.mindmax.v4.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

/**
 * Singleton row (id = 1). The `apiKey` column stores ONLY a sentinel
 * (`__ENC__:<migrated>` or `__ENC__:<present>`); the real key is in
 * [dev.mindmax.v4.core.prefs.SecureKeyStore] backed by EncryptedSharedPreferences.
 */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "api_provider", defaultValue = "groq") val apiProvider: String = "groq",
    @ColumnInfo(name = "api_model", defaultValue = "llama-3.3-70b-versatile") val apiModel: String = "llama-3.3-70b-versatile",
    @ColumnInfo(name = "api_base_url") val apiBaseUrl: String? = null,
    @ColumnInfo(name = "api_key", defaultValue = "") val apiKey: String = "",
    @ColumnInfo(name = "max_tokens", defaultValue = "1024") val maxTokens: Int = 1024,
    @ColumnInfo(defaultValue = "0.7") val temperature: Float = 0.7f,
    @ColumnInfo(name = "top_p", defaultValue = "1.0") val topP: Float = 1.0f,
    @ColumnInfo(name = "auto_start", defaultValue = "0") val autoStart: Boolean = false,
    @ColumnInfo(name = "service_enabled", defaultValue = "0") val serviceEnabled: Boolean = false,
    @ColumnInfo(name = "notification_enabled", defaultValue = "1") val notificationEnabled: Boolean = true,
    @ColumnInfo(name = "audit_enabled", defaultValue = "1") val auditEnabled: Boolean = true,
    @ColumnInfo(name = "audit_retention_days", defaultValue = "30") val auditRetentionDays: Int = 30,
    @ColumnInfo(name = "default_agent", defaultValue = "coordinator") val defaultAgent: String = "coordinator",
    @ColumnInfo(name = "multi_agent_enabled", defaultValue = "1") val multiAgentEnabled: Boolean = true,
    @ColumnInfo(name = "cross_audit_enabled", defaultValue = "0") val crossAuditEnabled: Boolean = false,
    @ColumnInfo(name = "context_window_size", defaultValue = "10") val contextWindowSize: Int = 10,
    @ColumnInfo(name = "memory_enabled", defaultValue = "1") val memoryEnabled: Boolean = true,
    @ColumnInfo(name = "max_retries", defaultValue = "3") val maxRetries: Int = 3,
    @ColumnInfo(name = "retry_delay_ms", defaultValue = "1500") val retryDelayMs: Long = 1500L,
    @ColumnInfo(name = "created_at") val createdAt: Date,
    @ColumnInfo(name = "updated_at") val updatedAt: Date,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() || apiKey.startsWith(ENC_PREFIX)
    val setupInstructions: String
        get() = "Selecione um provedor de IA e informe sua chave em Configurações."

    companion object {
        const val ENC_PREFIX = "__ENC__:"

        /** A safe default that satisfies Room `NOT NULL` constraints before the user customizes anything. */
        fun default(now: Date) = SettingsEntity(
            createdAt = now,
            updatedAt = now,
        )
    }
}
