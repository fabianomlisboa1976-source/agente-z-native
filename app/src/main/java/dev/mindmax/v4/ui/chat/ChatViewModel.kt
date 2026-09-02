package dev.mindmax.v4.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mindmax.v4.agent.AgentEvent
import dev.mindmax.v4.agent.AgentRuntime
import dev.mindmax.v4.core.di.ServiceLocator
import dev.mindmax.v4.data.entity.MessageEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the chat-screen state: the in-memory list of messages, the input text,
 * the "thinking" indicator flag, and an error string when the LLM fails.
 *
 * The screen is for a single conversation — the [conversationId] defaults to
 * `"default"`; a future multi-conversation flow will swap this out.
 *
 * **SSE only runs while the chat surface is in foreground.** The streaming
 * `Flow<String>` from [LlmClient.stream] is collected on `viewModelScope`,
 * which Android cancels when [onCleared] fires (i.e. when the ViewModel is
 * destroyed, typically because the user navigated away from the chat tab).
 * When that happens the underlying `EventSource` is closed via `awaitClose`.
 * This is intentional: Android (especially 14+) is quick to throttle
 * background LLM traffic. The optional foreground service
 * [dev.mindmax.v4.service.MindMaxForegroundService] keeps the process alive
 * but doesn't itself drive streaming — only this VM does.
 */
class ChatViewModel(
    private val conversationId: String = "default",
    private val runtime: AgentRuntime = AgentRuntime(),
) : ViewModel() {

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    init {
        observeMessages()
    }

    private fun observeMessages() {
        viewModelScope.launch {
            ServiceLocator.chatRepository.observeConversation(conversationId)
                .collect { messages ->
                    _state.update { it.copy(messages = messages) }
                }
        }
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text) }
    }

    /**
     * Clears the chat error banner after the user dismisses it. The persisted
     * message is unaffected — only the transient [ChatState.error] field is
     * zeroed, so the next failure can set a new one without leaking the old
     * value across the screen.
     */
    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty()) return
        _state.update { it.copy(input = "", isThinking = true, error = null) }
        viewModelScope.launch {
            try {
                runtime.handle(text, conversationId).collect { event ->
                    handle(event)
                }
            } catch (error: Throwable) {
                _state.update { it.copy(error = error.message ?: "Erro", isThinking = false) }
            } finally {
                _state.update { it.copy(isThinking = false) }
            }
        }
    }

    private fun handle(event: AgentEvent) {
        when (event) {
            is AgentEvent.UserSaved -> Unit // persistência já cobriu.
            is AgentEvent.PlanStarted -> _state.update {
                it.copy(thinkingPhase = "Coordenador está escolhendo os agentes…")
            }
            is AgentEvent.AgentSpoke -> _state.update {
                it.copy(thinkingPhase = null)
            }
            is AgentEvent.AgentDone -> Unit
            is AgentEvent.AgentError -> _state.update {
                it.copy(error = event.message, thinkingPhase = null)
            }
            is AgentEvent.AgentComplete -> _state.update {
                it.copy(thinkingPhase = null)
            }
            is AgentEvent.StreamDelta -> Unit
        }
    }
}

data class ChatState(
    val messages: List<MessageEntity> = emptyList(),
    val input: String = "",
    val isThinking: Boolean = false,
    val thinkingPhase: String? = null,
    val error: String? = null,
)
