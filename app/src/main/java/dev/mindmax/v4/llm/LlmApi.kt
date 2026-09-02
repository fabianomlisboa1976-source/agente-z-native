package dev.mindmax.v4.llm

import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Single-method Retrofit interface used by [LlmClient.chat] for the non-stream
 * path. The streaming path uses OkHttp's [okhttp3.sse.EventSource] directly so
 * we can backpressure via Flow without drag in extra machinery.
 *
 * The route is intentionally `chat/completions` — every OpenAI-compatible
 * provider exposes that exact path, which is why the LlmClient only needs to
 * swap the baseUrl to switch providers.
 */
interface LlmApi {
    @POST("chat/completions")
    suspend fun chatCompletions(@Body request: ChatRequest): ChatResponse
}
