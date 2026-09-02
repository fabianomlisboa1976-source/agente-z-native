package dev.mindmax.v4.agent

/**
 * The streaming output from [AgentRuntime.handle]. The chat layer collects
 * these events and renders them. Plain data only — no callbacks — so the
 * pipeline is testable end-to-end.
 */
sealed class AgentEvent {
    data class UserSaved(val messageId: Long) : AgentEvent()
    data class PlanStarted(val correlationId: String, val participants: List<String>) : AgentEvent()
    data class AgentSpoke(
        val agentId: String,
        val agentName: String,
        val content: String,
        val correlationId: String,
    ) : AgentEvent()
    data class StreamDelta(val agentId: String, val delta: String, val correlationId: String) : AgentEvent()
    data class AgentDone(val agentId: String, val correlationId: String) : AgentEvent()
    data class AgentError(val agentId: String, val correlationId: String, val message: String) : AgentEvent()
    data class AgentComplete(
        val correlationId: String,
        val finalMessage: String,
        val participants: List<String>,
    ) : AgentEvent()
}
