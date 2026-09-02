package dev.mindmax.v4.llm

import kotlinx.serialization.json.Json
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

/**
 * Minimal Server-Sent Events parser hand-rolled for our LLM stream so we can
 * flow-control on Backpressure without dragging in a third-party SSE library.
 *
 * OpenAI-compatible streaming sends each payload as a single `data: {...}` line
 * terminated by `[DONE]`:
 *
 *   data: {"choices":[{"delta":{"content":"hi"}}]}
 *
 *   data: [DONE]
 *
 * Some providers (Cloudflare) emit a heartbeat comment line `: heartbeat` —
 * we ignore anything that doesn't start with `data:`.
 */
object SseParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** Decodes one parsed delta from a single SSE `data:` payload. */
    fun decodeDelta(payload: String): ChatDeltaPayload? = try {
        // Wrapping in `ChatResponse` lets us reuse the same decoder for both
        // stream and non-stream modes; we look only at the delta field here.
        val response = json.decodeFromString(ChatResponse.serializer(), payload)
        response.choices.firstOrNull()?.delta
    } catch (error: Throwable) {
        null
    }

    /** Returns true when the payload is the OpenAI-style stream terminator. */
    fun isDone(payload: String?): Boolean = payload?.trim() == "[DONE]"
}

/**
 * Pass-through EventSourceListener that forwards each delta into a callback.
 * Lives here so the streaming code path stays compact.
 */
class SseChatListener(
    private val onDelta: (String) -> Unit,
    private val onError: (Throwable) -> Unit = {},
    private val onClosed: () -> Unit = {},
) : EventSourceListener() {

    override fun onEvent(
        eventSource: EventSource,
        id: String?,
        type: String?,
        data: String,
    ) {
        if (SseParser.isDone(data)) {
            eventSource.cancel()
            onClosed()
            return
        }
        val delta = SseParser.decodeDelta(data)?.content ?: return
        if (delta.isNotEmpty()) onDelta(delta)
    }

    override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
        if (t != null) onError(t)
    }

    override fun onClosed(eventSource: EventSource) {
        onClosed()
    }
}
