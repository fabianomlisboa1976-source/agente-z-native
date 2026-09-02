package dev.mindmax.v4.agent

/**
 * Local-first commands the user can type that short-circuit the LLM call.
 * Keeps the agent-on-mobile experience snappy: a slash command answers in
 * microseconds, never going to the network.
 *
 * Matching is command-name exact (`/help`, `/agents`, …) — we never partially
 * match to avoid surprise. [tryProgramCommand] returns null when no command
 * matched, signalling the runtime to use the normal coordinator path.
 */
class ConversationProgrammer {

    fun tryProgramCommand(text: String): ProgrammedReply? {
        val trimmed = text.trim()
        if (!trimmed.startsWith('/')) return null

        val (verb, _) = trimmed.split(' ', limit = 2).let { parts ->
            parts[0] to parts.getOrNull(1).orEmpty().trim()
        }

        return when (verb.lowercase()) {
            "/help", "/?" -> ProgrammedReply(
                agentId = ProgrammedReply.System,
                content = HELP_TEXT,
            )

            "/agents" -> ProgrammedReply(
                agentId = ProgrammedReply.System,
                content = AGENTS_TEXT,
            )

            "/audit" -> ProgrammedReply(
                agentId = ProgrammedReply.System,
                content = AUDIT_TEXT,
            )

            "/reset" -> ProgrammedReply(
                agentId = ProgrammedReply.System,
                content = RESET_TEXT,
            )

            else -> null
        }
    }

    /** Hard-coded reply; content doesn't depend on any IO. */
    data class ProgrammedReply(val agentId: String, val content: String) {
        companion object {
            const val System: String = "system"
        }
    }

    private companion object {
        const val HELP_TEXT = "Comandos disponíveis:\n" +
            "  /help      — esta ajuda\n" +
            "  /agents    — lista os agentes default\n" +
            "  /audit     — aponta para a aba Auditoria\n" +
            "  /reset     — instrução para limpar dados\n" +
            "\nTudo o que não começa com '/' vai para o Coordenador."

        const val AGENTS_TEXT = "Agentes default (7):\n" +
            "- Coordenador: decide quais agentes usar\n" +
            "- Planejador: decompõe pedidos\n" +
            "- Pesquisador: reúne contexto\n" +
            "- Executor: produz a resposta\n" +
            "- Auditor: revisa consistência e segurança\n" +
            "- Memória: persistência de longo prazo\n" +
            "- Comunicação: formata para o usuário"

        const val AUDIT_TEXT =
            "A aba Auditoria mostra as últimas entradas (REQUEST, RESPONSE, " +
                "AGENT_DECISION, ERROR, SECURITY). Retenção padrão: 30 dias " +
                "(configurável em Configurações)."

        const val RESET_TEXT =
            "Para limpar dados, abra Configurações → Danger Zone → Limpar dados."
    }
}
