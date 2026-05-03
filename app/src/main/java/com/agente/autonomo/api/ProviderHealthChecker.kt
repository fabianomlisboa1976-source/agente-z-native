package com.agente.autonomo.api

import android.util.Log
import com.agente.autonomo.api.model.ApiProvider
import com.agente.autonomo.api.model.ChatCompletionRequest
import com.agente.autonomo.api.model.Message
import com.agente.autonomo.data.entity.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Performs lightweight health-check pings against LLM provider endpoints
 * **before** the main request is attempted.
 *
 * ## Ping strategy
 * A ping is a real `chat/completions` call with:
 * - `max_tokens = 1` to minimise cost and latency
 * - A 10-second hard timeout (via [withTimeoutOrNull])
 * - No retry – a single failure is enough to mark the provider suspect
 *   for the current cycle
 *
 * The caller ([FallbackLLMClient]) decides whether to trust the cached
 * [ProviderHealth] or re-ping based on how recently the last check was
 * performed.
 */
class ProviderHealthChecker {

    companion object {
        const val TAG = "ProviderHealthChecker"
        const val PING_TIMEOUT_MS = 10_000L
        const val PING_MAX_TOKENS = 1
        const val HEALTH_CHECK_INTERVAL_MS = 60_000L // re-ping at most once per minute
    }

    // One shared lightweight OkHttp client for pings (short timeouts).
    private val pingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Pings [provider] with a minimal 1-token request using [apiKey].
     *
     * @param provider The provider to ping.
     * @param apiKey   The API key for authentication.
     * @param settings Current [Settings] used to derive base URL overrides
     *                 (e.g. Cloudflare account-specific URLs).
     *
     * @return `true` if the provider responded with HTTP 2xx within
     *         [PING_TIMEOUT_MS], `false` otherwise.
     */
    suspend fun ping(provider: ApiProvider, apiKey: String, settings: Settings): Boolean =
        withContext(Dispatchers.IO) {
            val baseUrl = resolveBaseUrl(provider, settings)
            Log.d(TAG, "Pinging ${provider.name} at $baseUrl")

            val result = withTimeoutOrNull(PING_TIMEOUT_MS) {
                try {
                    val service = buildService(baseUrl)
                    val request = ChatCompletionRequest(
                        model = provider.defaultModel,
                        messages = listOf(Message(role = "user", content = "ping")),
                        max_tokens = PING_MAX_TOKENS,
                        temperature = 0f,
                        stream = false
                    )
                    val response = service.createChatCompletion(
                        authorization = "Bearer $apiKey",
                        request = request
                    )
                    val ok = response.isSuccessful
                    Log.d(TAG, "${provider.name} ping → HTTP ${response.code()}, ok=$ok")
                    ok
                } catch (e: Exception) {
                    Log.w(TAG, "${provider.name} ping exception: ${e.message}")
                    false
                }
            }

            result ?: run {
                Log.w(TAG, "${provider.name} ping timed out")
                false
            }
        }

    /**
     * Returns whether [lastCheckedMs] is old enough to warrant a fresh ping,
     * based on [HEALTH_CHECK_INTERVAL_MS].
     */
    fun shouldRePing(lastCheckedMs: Long): Boolean {
        return System.currentTimeMillis() - lastCheckedMs >= HEALTH_CHECK_INTERVAL_MS
    }

    // ---------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------

    private fun resolveBaseUrl(provider: ApiProvider, settings: Settings): String {
        return when (provider) {
            ApiProvider.CLOUDFLARE -> settings.apiBaseUrl?.takeIf { it.isNotBlank() }
                ?: provider.baseUrl
            else -> provider.baseUrl
        }
    }

    private fun buildService(baseUrl: String): LLMApiService {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(pingClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LLMApiService::class.java)
    }
}
