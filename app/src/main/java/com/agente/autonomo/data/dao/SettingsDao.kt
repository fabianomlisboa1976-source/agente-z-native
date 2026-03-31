package com.agente.autonomo.data.dao

import androidx.room.*
import com.agente.autonomo.data.entity.Settings
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface SettingsDao {
    
    @Query("SELECT * FROM settings WHERE id = 1 LIMIT 1")
    fun getSettings(): Flow<Settings?>
    
    @Query("SELECT * FROM settings WHERE id = 1 LIMIT 1")
    suspend fun getSettingsSync(): Settings?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: Settings)
    
    @Update
    suspend fun updateSettings(settings: Settings)
    
    @Query("UPDATE settings SET api_key = :apiKey WHERE id = 1")
    suspend fun updateApiKey(apiKey: String)
    
    @Query("UPDATE settings SET api_provider = :provider, api_model = :model WHERE id = 1")
    suspend fun updateApiConfig(provider: String, model: String)
    
    @Query("UPDATE settings SET service_enabled = :enabled WHERE id = 1")
    suspend fun setServiceEnabled(enabled: Boolean)
    
    @Query("UPDATE settings SET auto_start = :enabled WHERE id = 1")
    suspend fun setAutoStart(enabled: Boolean)
    
    @Query("UPDATE settings SET audit_enabled = :enabled WHERE id = 1")
    suspend fun setAuditEnabled(enabled: Boolean)
    
    @Query("UPDATE settings SET updated_at = :timestamp WHERE id = 1")
    suspend fun updateTimestamp(timestamp: Date = Date())
    
    @Query("SELECT api_key FROM settings WHERE id = 1")
    suspend fun getApiKey(): String?
    
    @Query("SELECT api_provider FROM settings WHERE id = 1")
    suspend fun getApiProvider(): String?
    
    @Query("SELECT api_model FROM settings WHERE id = 1")
    suspend fun getApiModel(): String?
    
    @Query("SELECT service_enabled FROM settings WHERE id = 1")
    suspend fun isServiceEnabled(): Boolean?
    
    @Query("SELECT auto_start FROM settings WHERE id = 1")
    suspend fun isAutoStart(): Boolean?
    
    @Query("SELECT audit_enabled FROM settings WHERE id = 1")
    suspend fun isAuditEnabled(): Boolean?
    
    @Query("DELETE FROM settings WHERE id = 1")
    suspend fun deleteSettings()
    
    @Query("SELECT EXISTS(SELECT 1 FROM settings WHERE id = 1)")
    suspend fun hasSettings(): Boolean
}
