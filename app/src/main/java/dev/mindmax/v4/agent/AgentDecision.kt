package dev.mindmax.v4.agent

import kotlinx.serialization.Serializable

/**
 * The Coordinator returns this when it decides which agents to invoke for a
 * user turn. Keeping it as a strict DTO makes the JSON shape testable and the
 * parser tolerant: unknown fields are dropped, missing `selected` defaults to
 * `[]`, and a bad parse renders as [Fallback].
 */
@Serializable
data class AgentDecision(
    val selected: List<String> = emptyList(),
    val executionOrder: List<String> = emptyList(),
    val reason: String = "",
) {
    /** Safe parser that survives a malformed Coordinator reply. */
    companion object {
        fun parseOrNull(raw: String): AgentDecision? = try {
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
            }.decodeFromString(serializer(), raw.firstJsonObject())
        } catch (_: Throwable) {
            null
        }

        /**
         * The Coordinator reply is sometimes wrapped in prose or fenced code
         * blocks. We pull the first balanced `{...}` JSON object out of the
         * string before parsing.
         */
        private fun String.firstJsonObject(): String {
            val open = indexOf('{')
            if (open < 0) return this
            var depth = 0
            var inString = false
            var escape = false
            for (i in open until length) {
                val c = this[i]
                if (escape) { escape = false; continue }
                if (c == '\\') { escape = true; continue }
                if (c == '"') { inString = !inString; continue }
                if (!inString) {
                    when (c) {
                        '{' -> depth++
                        '}' -> { depth--; if (depth == 0) return substring(open, i + 1) }
                    }
                }
            }
            return this
        }

        /** Use this when parsing fails — never throws and keeps the pipeline running. */
        fun fallback(): AgentDecision = AgentDecision(
            selected = listOf("executor"),
            executionOrder = listOf("executor"),
            reason = "fallback (coordinator reply unparsable)",
        )
    }
}
