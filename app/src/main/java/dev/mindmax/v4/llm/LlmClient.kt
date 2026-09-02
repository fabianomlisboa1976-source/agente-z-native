package dev.mindmax.v4.llm

import dev.mindmax.v4.core.di.ServiceLocator
import dev.mindmax.v4.data.entity.SettingsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSources
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The unified entry point for every LLM call the app makes.
 *
 * Two surfaces:
 *   - [chat] / [chatOnce] — non-streaming; useful for the planner/auditor
 *     agents that need the full answer before continuing.
 *   - [stream] — SSE token-by-token; powers the live chat experience.
 *
 * The client is constructed lazily by [ServiceLocator.llmClient] once we know
 * the persisted provider. We re-build it cheaply when the user switches
 * providers so we don't carry a stale base URL or auth scheme.
 *
 * All public methods accept a [RequestSpec] so callers can carry temp /
 * maxTokens / stop sequences without having to materialise the full ChatRequest
 * themselves. The spec is merged with provider defaults inside this class.
 */
class LlmClient(
    private val provider: Provider,
    private val accountIdProvider: () -> String? = { null },
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
    }

    /** Single source of OkHttp. The SSE factory wraps this same client. */
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(
            AuthInterceptor(
                secureKeyStore = ServiceLocator.secureKeyStore,
                provider = provider,
                cloudflareAccountId = accountIdProvider,
            ),
        )
        .build()

    /** Retrofit facade for the non-stream path. */
    private val api: LlmApi = Retrofit.Builder()
        .baseUrl(provider.resolvedBaseUrl(accountIdProvider()) + "/")
        .client(okHttp)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(LlmApi::class.java)

    /** Resolves a model using settings overrides. */
    fun modelFromSettings(settings: SettingsEntity?): String =
        ProviderRegistry.modelFor(provider, settings)

    /** Performs a single non-streamed completion and returns the textual answer. */
    suspend fun chat(spec: RequestSpec): ChatSummary = withContext(Dispatchers.IO) {
        val req = spec.toChatRequest(stream = false)
        val response = api.chatCompletions(req)
        response.error?.let { throw LlmException(it.message) }
        val content = response.choices.firstOrNull()?.message?.content
            ?: throw LlmException("Provider returned no message choices.")
        ChatSummary(
            content = content,
            model = response.model ?: spec.model,
            totalTokens = response.usage?.totalTokens,
        )
    }

    /** Sugar for ad-hoc prompts — used by ConversationProgrammer and the Auditor. */
    suspend fun chatOnce(
        systemPrompt: String,
        userMessage: String,
        spec: RequestSpec = RequestSpec(),
    ): ChatSummary {
        val effective = spec.copy(
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userMessage),
            ),
        )
        return chat(effective)
    }

    /**
     * Streams the answer back as a Flow of `data:` payloads. Each emitted string
     * is the delta content — never the entire cumulative message — so the UI
     * can render tokens in order. The Flow terminates when the provider sends
     * `[DONE]` or the underlying transport fails.
     */
    fun stream(spec: RequestSpec): Flow<String> = flow {
        val req = spec.toChatRequest(stream = true)
        val body = json.encodeToString(ChatRequest.serializer(), req)
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(provider.resolvedBaseUrl(accountIdProvider()) + "/chat/completions")
            .post(body)
            .build()

        emitAll(streamFromOkHttp(request))
    }
        .flowOn(Dispatchers.IO)
        .retryWhen { cause, _ ->
            // Retry on transient IO errors so a momentary network blip doesn't
            // kill the user's turn. Auth/4xx errors don't surface as IOException
            // so they're left to propagate without retry.
            cause is IOException
        }

    private suspend fun streamFromOkHttp(request: Request): Flow<String> = callbackFlow {
        val factory = EventSources.createFactory(okHttp)
        val listener = SseChatListener(
            onDelta = { trySend(it).isSuccess },
            onError = { close(it) },
            onClosed = { close() },
        )
        val source: EventSource = factory.newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    /** Suspended factory for non-Flow callers. */
    suspend fun chatOnceOrNull(
        systemPrompt: String,
        userMessage: String,
        spec: RequestSpec = RequestSpec(),
    ): String? = try {
        chatOnce(systemPrompt, userMessage, spec).content
    } catch (error: Throwable) {
        null
    }

    /** Reusable inputs to a chat or stream call. */
    data class RequestSpec(
        val model: String = provider.defaultModel,
        val messages: List<ChatMessage> = emptyList(),
        val temperature: Float? = null,
        val topP: Float? = null,
        val maxTokens: Int? = null,
    ) {
        fun toChatRequest(stream: Boolean): ChatRequest = ChatRequest(
            model = model,
            messages = messages,
            stream = stream,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
        )
    }

    class LlmException(message: String) : RuntimeException(message)
}
