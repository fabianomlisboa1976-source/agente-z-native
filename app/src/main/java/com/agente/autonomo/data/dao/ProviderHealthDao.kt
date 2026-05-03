package com.agente.autonomo.data.dao

import androidx.room.*
import com.agente.autonomo.data.entity.ProviderHealth
import kotlinx.coroutines.flow.Flow

/**
 * DAO for persisting and querying [ProviderHealth] records.
 *
 * Each row corresponds to one [com.agente.autonomo.api.model.ApiProvider];
 * the primary key is the provider's name string.
 */
@Dao
interface ProviderHealthDao {

    @Query("SELECT * FROM provider_health")
    fun getAllProviderHealth(): Flow<List<ProviderHealth>>

    @Query("SELECT * FROM provider_health WHERE provider = :providerName")
    suspend fun getProviderHealth(providerName: String): ProviderHealth?

    @Query("SELECT * FROM provider_health WHERE isHealthy = 1 AND backoffUntil < :now")
    suspend fun getHealthyProviders(now: Long): List<ProviderHealth>

    @Query("SELECT * FROM provider_health WHERE backoffUntil < :now ORDER BY consecutiveFailures ASC")
    suspend fun getAvailableProviders(now: Long): List<ProviderHealth>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(providerHealth: ProviderHealth)

    @Update
    suspend fun update(providerHealth: ProviderHealth)

    @Query(
        "UPDATE provider_health SET " +
        "consecutiveFailures = consecutiveFailures + 1, " +
        "isHealthy = CASE WHEN consecutiveFailures + 1 >= :maxFailures THEN 0 ELSE isHealthy END, " +
        "lastChecked = :now, " +
        "lastFailureReason = :reason, " +
        "backoffUntil = :backoffUntil, " +
        "totalFailures = totalFailures + 1, " +
        "totalRequests = totalRequests + 1 " +
        "WHERE provider = :providerName"
    )
    suspend fun recordFailure(
        providerName: String,
        reason: String,
        backoffUntil: Long,
        now: Long,
        maxFailures: Int
    )

    @Query(
        "UPDATE provider_health SET " +
        "consecutiveFailures = 0, " +
        "isHealthy = 1, " +
        "lastChecked = :now, " +
        "lastFailureReason = NULL, " +
        "backoffUntil = 0, " +
        "averageLatencyMs = (averageLatencyMs * totalRequests + :latencyMs) / (totalRequests + 1), " +
        "totalRequests = totalRequests + 1 " +
        "WHERE provider = :providerName"
    )
    suspend fun recordSuccess(providerName: String, latencyMs: Long, now: Long)

    @Query("DELETE FROM provider_health")
    suspend fun clearAll()

    @Query(
        "UPDATE provider_health SET " +
        "isHealthy = 1, consecutiveFailures = 0, backoffUntil = 0, lastFailureReason = NULL " +
        "WHERE provider = :providerName"
    )
    suspend fun resetProvider(providerName: String)
}
