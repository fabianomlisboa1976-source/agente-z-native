package com.agente.autonomo.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.agente.autonomo.api.model.ApiProvider
import java.util.Date

/**
 * Represents the persisted health state of an LLM API provider.
 *
 * A provider is considered "healthy" when [isHealthy] is true and its
 * [consecutiveFailures] count is below the threshold defined in
 * [ProviderHealthRepository.MAX_CONSECUTIVE_FAILURES].
 *
 * [backoffUntil] is set to a future [Date] after each failure so that
 * the failover chain can skip the provider without issuing a live
 * ping until the backoff window has elapsed.
 */
@Entity(tableName = "provider_health")
data class ProviderHealth(
    @PrimaryKey
    val provider: String, // ApiProvider.name()
    val isHealthy: Boolean = true,
    val consecutiveFailures: Int = 0,
    val lastChecked: Date = Date(),
    val lastFailureReason: String? = null,
    /** Epoch millis after which this provider may be retried. */
    val backoffUntil: Long = 0L,
    val totalRequests: Long = 0L,
    val totalFailures: Long = 0L,
    val averageLatencyMs: Long = 0L
)
