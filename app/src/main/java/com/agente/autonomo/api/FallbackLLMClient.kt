package com.agente.autonomo.api

import android.util.Log
import com.agente.autonomo.api.model.ApiProvider
import com.agente.autonomo.api.model.ChatCompletionResponse
import com.agente.autonomo.api.model.Message
import com.agente.autonomo.data.entity.Settings
import kotlinx.coroutines.delay

/**
 * Drop-in replacement / wrapper around [LLMClient] that implements an
 * **automatic provider failover chain**.
 *
 * ## Failover pipeline (per request)
 * ```
 * 1. Ask ProviderHealthRepository for ordered available providers
 *    (skips any whose backoffUntil has not yet passed).
 * 2. For each candidate provider:
 *    a. If the cached health record is stale, issue a lightweight ping
 *       via ProviderHealthChecker.ping().
 *       - Ping fails → recordFailure(), advance to next provider.
 *       - Ping succeeds → continue to actual request.
 *    b. Build a per-provider LLMClient and call sendMessage().
 *    c. On success  → recordSuccess(), return result.
 *    d. On failure  → recordFailure() (updates backoff), apply intra-
 *       provider exponential backoff delay, try next provider.
 * 3. If every provider fails → invoke onAllProvidersFailed callback and
 *    return Result.failure with a descriptive exception.
 * ```
 *
 * ## Backoff inside a single provider
 * Between consecutive attempts at the *same* provider (up to [maxRetriesPerProvider]
 * retries) the coroutine sleeps for `BASE_RETRY_DELAY_MS * 2^attempt`.
 * This is separate from the cross-request backoff stored in [ProviderHealth].
 *
 * ## Provider-specific LLMClient instances
 * A fresh [LLMClient] is constructed for each provider attempt using a
 * synthesised [Settings] copy that overrides `apiProvider` and `apiModel`.
 * The user's [apiKey] is shared across all providers (the project design
 * uses a single key field).
 *
 * @param baseSettings            The persisted [Settings] from the database.
 * @param healthRepository        Repository that reads/writes [ProviderHealth].
 * @param healthChecker           Issues lightweight ping requests.
 * @param onAllProvidersFailed    Callback invoked (on the calling coroutine)
 *                                when every provider in the chain is
 *                                exhausted.  Use it to post a notification.
 * @param maxRetriesPerProvider   How many times to retry a single provider
 *                                before advancing to the next.
 */
class FallbackLLMClient(
    private val baseSettings: Settings,
    private val healthRepository: ProviderHealthRepository,
    private val healthChecker: ProviderHealthChecker,
    private val onAllProvidersFailed: suspend () -> Unit = {},
    private val maxRetriesPerProvider: Int = 2
) {

    companion object {
        const val TAG = "FallbackLLMClient"
        const val BASE_RETRY_DELAY_MS = 1_000L
    }

    // ---------------------------------------------------------------------------
    // Public API (mirrors LLMClient surface)
    // ---------------------------------------------------------------------------

    /**
     * Sends [messages] to the first healthy provider in the failover chain.
     *
     * The chain order is Groq → OpenRouter → GitHub Models → Cloudflare.
     * Providers in backoff or with exhausted retries are skipped until their
     * backoff window expires.
     *
     * @param messages     The conversation turns to send.
     * @param model        Optional model override; falls back to provider default.
     * @param temperature  Optional temperature override.
     * @param maxTokens    Optional max-tokens override.
     * @return [Result.success] with the first successful [ChatCompletionResponse],
     *         or [Result.failure] if the entire chain is exhausted.
     */
    suspend fun sendMessage(
        messages: List<Message>,
        model: String? = null,
        temperature: Float? = null,
        maxTokens: Int? = null
    ): Result<ChatCompletionResponse> {
        val availableProviders = healthRepository.getOrderedAvailableProviders()

        if (availableProviders.isEmpty()) {
            Log.e(TAG, "All providers are in backoff – no candidates available")
            onAllProvidersFailed()
            return Result.failure(
                AllProvidersExhaustedException("All LLM providers are in backoff / unavailable")
            )
        }

        val attemptErrors = mutableListOf<String>()

        for (provider in availableProviders) {
            Log.d(TAG, "Trying provider: ${provider.name}")

            // ----------------------------------------------------------------
            // Step 2a – optional health-check ping
            // ----------------------------------------------------------------
            val healthRecord = healthRepository.getHealth(provider)
            val lastCheckedMs = healthRecord?.lastChecked?.time ?: 0L

            if (!healthRecord?.isHealthy.let { it == false } || // unhealthy → always re-ping
                healthChecker.shouldRePing(lastCheckedMs)
            ) {
                val pingOk = healthChecker.ping(provider, baseSettings.apiKey, baseSettings)
                if (!pingOk) {
                    val reason = "Health-check ping failed"
                    Log.w(TAG, "${provider.name}: $reason – skipping")
                    healthRepository.recordFailure(provider, reason)
                    attemptErrors += "${provider.name}: $reason"
                    continue
                }
            }

            // ----------------------------------------------------------------
            // Step 2b–2d – attempt the actual request with per-provider retries
            // ----------------------------------------------------------------
            val result = attemptWithRetries(
                provider = provider,
                messages = messages,
                model = model,
                temperature = temperature,
                maxTokens = maxTokens
            )

            when {
                result.isSuccess -> {
                    Log.i(TAG, "${provider.name} succeeded")
                    return result
                }
                else -> {
                    val errMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                    Log.w(TAG, "${provider.name} exhausted retries: $errMsg")
                    attemptErrors += "${provider.name}: $errMsg"
                }
            }
        }

        // All providers failed
        val summary = attemptErrors.joinToString(" | ")
        Log.e(TAG, "All providers failed: $summary")
        onAllProvidersFailed()
        return Result.failure(
            AllProvidersExhaustedException(
                "All LLM providers exhausted. Attempts: $summary"
            )
        )
    }

    /**
     * Convenience wrapper that returns just the response text string.
     *
     * @param systemPrompt System-role prompt.
     * @param userMessage  User-role message.
     * @param model        Optional model override.
     * @return [Result.success] with the first choice content, or
     *         [Result.failure] if the chain is exhausted.
     */
    suspend fun sendSimpleMessage(
        systemPrompt: String,
        userMessage: String,
        model: String? = null
    ): Result<String> {
        val messages = listOf(
            Message(role = "system", content = systemPrompt),
            Message(role = "user", content = userMessage)
        )
        return sendMessage(messages, model).map { response ->
            response.choices.firstOrNull()?.message?.content
                ?: throw Exception("Empty response from model")
        }
    }

    // ---------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------

    /**
     * Retries [provider] up to [maxRetriesPerProvider] times with exponential
     * backoff between attempts.  Records success/failure in [healthRepository]
     * after each outcome.
     */
    private suspend fun attemptWithRetries(
        provider: ApiProvider,
        messages: List<Message>,
        model: String?,
        temperature: Float?,
        maxTokens: Int?
    ): Result<ChatCompletionResponse> {
        var lastResult: Result<ChatCompletionResponse> =
            Result.failure(Exception("No attempt made"))

        repeat(maxRetriesPerProvider) { attempt ->
            val client = buildClientForProvider(provider)
            val startMs = System.currentTimeMillis()

            val result = client.sendMessage(
                messages = messages,
                model = model ?: provider.defaultModel,
                temperature = temperature,
                maxTokens = maxTokens
            )

            val latencyMs = System.currentTimeMillis() - startMs

            if (result.isSuccess) {
                healthRepository.recordSuccess(provider, latencyMs)
                lastResult = result
                return result // early exit
            } else {
                val reason = result.exceptionOrNull()?.message ?: "Unknown"
                healthRepository.recordFailure(provider, reason)
                lastResult = result
                Log.w(TAG, "${provider.name} attempt ${attempt + 1} failed: $reason")

                // Intra-provider exponential backoff before next retry
                if (attempt < maxRetriesPerProvider - 1) {
                    val delayMs = BASE_RETRY_DELAY_MS * (1L shl attempt)
                    Log.d(TAG, "${provider.name}: waiting ${delayMs}ms before retry")
                    delay(delayMs)
                }
            }
        }

        return lastResult
    }

    /**
     * Builds a [LLMClient] configured for [provider] by merging the provider's
     * base URL and default model into a copy of [baseSettings].
     */
    private fun buildClientForProvider(provider: ApiProvider): LLMClient {
        val providerSettings = baseSettings.copy(
            apiProvider = provider.name.lowercase(),
            apiModel = provider.defaultModel,
            apiBaseUrl = when (provider) {
                ApiProvider.CLOUDFLARE -> baseSettings.apiBaseUrl ?: provider.baseUrl
                else -> provider.baseUrl
            }
        )
        return LLMClient(providerSettings)
    }
}

/**
 * Thrown by [FallbackLLMClient] when every provider in the failover chain
 * has been attempted and all have failed.
 */
class AllProvidersExhaustedException(message: String) : Exception(message)
