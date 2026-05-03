package com.agente.autonomo.api

import android.util.Log
import com.agente.autonomo.api.model.ApiProvider
import com.agente.autonomo.data.dao.ProviderHealthDao
import com.agente.autonomo.data.entity.ProviderHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Repository that manages the persisted health state for every [ApiProvider].
 *
 * ## Backoff Strategy
 * After each consecutive failure the backoff window doubles:
 * ```
 * backoffMs = BASE_BACKOFF_MS * 2^(consecutiveFailures - 1)
 * ```
 * capped at [MAX_BACKOFF_MS] (5 minutes).  The computed deadline is stored
 * in [ProviderHealth.backoffUntil] so that the chain can skip the provider
 * without an additional live ping until that instant passes.
 *
 * ## Healthy threshold
 * A provider whose [ProviderHealth.consecutiveFailures] reaches
 * [MAX_CONSECUTIVE_FAILURES] is marked [ProviderHealth.isHealthy] = false.
 * It will still be offered to the chain once its backoff window expires, so
 * a single success resets it to healthy.
 *
 * ## Initialization
 * [ensureAllProvidersSeeded] must be called once at app startup (or the
 * first time the chain is used) to guarantee a row exists for every
 * provider enum value.
 */
class ProviderHealthRepository(private val dao: ProviderHealthDao) {

    companion object {
        const val TAG = "ProviderHealthRepo"
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val BASE_BACKOFF_MS = 5_000L        // 5 s
        const val MAX_BACKOFF_MS = 300_000L       // 5 min

        /** Ordered failover chain – same order as project context. */
        val PROVIDER_CHAIN: List<ApiProvider> = listOf(
            ApiProvider.GROQ,
            ApiProvider.OPENROUTER,
            ApiProvider.GITHUB,
            ApiProvider.CLOUDFLARE
        )
    }

    // ---------------------------------------------------------------------------
    // Seeding
    // ---------------------------------------------------------------------------

    /**
     * Inserts a default [ProviderHealth] row for every provider in
     * [PROVIDER_CHAIN] if it does not already exist.
     */
    suspend fun ensureAllProvidersSeeded() = withContext(Dispatchers.IO) {
        PROVIDER_CHAIN.forEach { provider ->
            val existing = dao.getProviderHealth(provider.name)
            if (existing == null) {
                dao.upsert(
                    ProviderHealth(
                        provider = provider.name,
                        isHealthy = true,
                        consecutiveFailures = 0,
                        lastChecked = Date(),
                        backoffUntil = 0L
                    )
                )
                Log.d(TAG, "Seeded health record for ${provider.name}")
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Chain ordering
    // ---------------------------------------------------------------------------

    /**
     * Returns providers in the canonical [PROVIDER_CHAIN] order, excluding
     * any whose backoff window has not yet expired.
     *
     * Providers that are unhealthy but whose backoff *has* expired are still
     * included so the chain can attempt a recovery ping.
     */
    suspend fun getOrderedAvailableProviders(): List<ApiProvider> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            // Fetch all persisted rows so we have backoffUntil per provider.
            val healthMap: Map<String, ProviderHealth> = PROVIDER_CHAIN
                .mapNotNull { p -> dao.getProviderHealth(p.name)?.let { p.name to it } }
                .toMap()

            PROVIDER_CHAIN.filter { provider ->
                val health = healthMap[provider.name]
                    ?: return@filter true // no row → treat as available
                health.backoffUntil <= now
            }.also { available ->
                Log.d(TAG, "Available providers: ${available.map { it.name }}")
            }
        }

    // ---------------------------------------------------------------------------
    // Recording outcomes
    // ---------------------------------------------------------------------------

    /**
     * Persists a successful request outcome for [provider], resets consecutive
     * failure count, marks provider healthy, and updates rolling average latency.
     *
     * @param provider  The provider that succeeded.
     * @param latencyMs Round-trip duration in milliseconds.
     */
    suspend fun recordSuccess(provider: ApiProvider, latencyMs: Long) =
        withContext(Dispatchers.IO) {
            dao.recordSuccess(
                providerName = provider.name,
                latencyMs = latencyMs,
                now = System.currentTimeMillis()
            )
            Log.d(TAG, "${provider.name} success recorded (${latencyMs}ms)")
        }

    /**
     * Persists a failure for [provider], increments consecutive failure counter,
     * computes and stores the next exponential backoff deadline.
     *
     * Marks the provider unhealthy once [MAX_CONSECUTIVE_FAILURES] is reached.
     *
     * @param provider The provider that failed.
     * @param reason   Human-readable failure description stored for diagnostics.
     */
    suspend fun recordFailure(provider: ApiProvider, reason: String) =
        withContext(Dispatchers.IO) {
            // Read current state to compute backoff exponent.
            val current = dao.getProviderHealth(provider.name)
            val failures = (current?.consecutiveFailures ?: 0) + 1
            val backoffMs = computeBackoff(failures)
            val backoffUntil = System.currentTimeMillis() + backoffMs

            Log.w(
                TAG,
                "${provider.name} failure #$failures – backoff ${backoffMs}ms, " +
                "reason: $reason"
            )

            dao.recordFailure(
                providerName = provider.name,
                reason = reason,
                backoffUntil = backoffUntil,
                now = System.currentTimeMillis(),
                maxFailures = MAX_CONSECUTIVE_FAILURES
            )
        }

    /**
     * Forces a provider back to healthy state regardless of current failure count.
     * Used by the manual "reset" action in the UI.
     */
    suspend fun resetProvider(provider: ApiProvider) = withContext(Dispatchers.IO) {
        dao.resetProvider(provider.name)
        Log.i(TAG, "${provider.name} manually reset to healthy")
    }

    /**
     * Retrieves the current [ProviderHealth] snapshot for [provider].
     */
    suspend fun getHealth(provider: ApiProvider): ProviderHealth? =
        withContext(Dispatchers.IO) {
            dao.getProviderHealth(provider.name)
        }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Computes exponential backoff: `BASE * 2^(failures-1)` capped at [MAX_BACKOFF_MS].
     */
    internal fun computeBackoff(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return 0L
        val exponent = (consecutiveFailures - 1).coerceAtMost(20) // guard overflow
        val raw = BASE_BACKOFF_MS * (1L shl exponent)
        return raw.coerceAtMost(MAX_BACKOFF_MS)
    }
}
