package dev.mindmax.v4.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for OpenAI-compatible Chat Completions. Field names mirror the
 * official spec so providers that match the spec (Groq, OpenRouter, GitHub
 * Models, custom endpoints, …) accept the payload unchanged.
 *
 * We intentionally stay minimal: stream, temperature, top_p, max_tokens.
 * Don't add fields the LLM layer doesn't yet consume.
 */

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
data class ChatMessage(
    val role: String, // "system" | "user" | "assistant" | "tool"
    val content: String,
    val name: String? = null,
)

@Serializable
data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null,
    val error: ChatError? = null,
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
    val delta: ChatDeltaPayload? = null,
)

@Serializable
data class ChatDeltaPayload(
    val role: String? = null,
    val content: String? = null,
)

@Serializable
data class ChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
data class ChatError(
    val message: String,
    val type: String? = null,
    val code: String? = null,
)

/** Parsed non-streaming summary returned by [LlmClient.chat]. */
data class ChatSummary(
    val content: String,
    val model: String?,
    val totalTokens: Int?,
)
