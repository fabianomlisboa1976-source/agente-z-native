package dev.mindmax.v4.llm

import kotlinx.serialization.json.Json
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import dev.mindmax.v4.llm.LlmClient.LlmException

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
 *
 * Error handling: OkHttp fires [onFailure] for *both* a transport exception
 * (`t != null`) and an HTTP non-2xx (`response != null`, `t == null`). The
 * previous implementation only covered the transport branch — when the
 * provider returned 401 (e.g. missing API key) or 429 (rate limit), the
 * response body was silently dropped and the chat UI saw an empty stream.
 * We now read the response body, surface it as a real exception, and tag
 * the status code so the chat layer can show something more useful.
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
        when {
            t != null -> onError(t)
            response != null -> onError(asHttpFailure(response))
            else -> onError(IllegalStateException("SSE stream closed without a cause."))
        }
    }

    override fun onClosed(eventSource: EventSource) {
        onClosed()
    }

    private fun asHttpFailure(response: okhttp3.Response): Throwable {
        val code = response.code
        val bodyText = try {
            response.body?.string().orEmpty().take(MAX_BODY_CHARS)
        } catch (_: Throwable) {
            ""
        }
        val summary = when (code) {
            401, 403 -> "Provedor recusou a chave (HTTP $code). Verifique Configurações → Chave de API."
            404 -> "Endpoint não encontrado (HTTP 404). Confira o provider/modelo selecionado."
            429 -> "Limite de requisições do provedor (HTTP 429). Aguarde alguns segundos."
            in 500..599 -> "Provedor instável (HTTP $code). Tente novamente em instantes."
            else -> "Falha no streaming (HTTP $code)."
        }
        val message = if (bodyText.isNotBlank()) "$summary\n$bodyText" else summary
        return LlmClient.LlmException(message)
    }

    private companion object {
        const val MAX_BODY_CHARS = 400
    }
}
