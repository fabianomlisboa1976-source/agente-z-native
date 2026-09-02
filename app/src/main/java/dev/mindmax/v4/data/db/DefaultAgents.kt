package dev.mindmax.v4.data.db

import dev.mindmax.v4.data.entity.AgentEntity
import dev.mindmax.v4.data.entity.AgentType
import java.util.Date

/**
 * The seven default agents that ship with MindMax V4. IDs are stable strings
 * so cross-references in messages, audit logs and tasks keep working across
 * fresh installs.
 *
 * System prompts are written in pt-BR to match the agent's UX language.
 */
object DefaultAgents {

    fun all(now: Date): List<AgentEntity> = listOf(
        AgentEntity(
            id = "coordinator",
            name = "Coordenador",
            description = "Decide quais agentes usar e orquestra a resposta final.",
            type = AgentType.COORDINATOR,
            systemPrompt = COORDINATOR_PROMPT,
            priority = 100,
            capabilities = listOf("routing", "synthesis"),
            color = "#8B5CF6",
            createdAt = now,
            updatedAt = now,
        ),
        AgentEntity(
            id = "planner",
            name = "Planejador",
            description = "Decompõe pedidos complexos em passos executáveis.",
            type = AgentType.PLANNER,
            systemPrompt = PLANNER_PROMPT,
            priority = 80,
            capabilities = listOf("decomposition", "task_breakdown"),
            color = "#3B82F6",
            createdAt = now,
            updatedAt = now,
        ),
        AgentEntity(
            id = "researcher",
            name = "Pesquisador",
            description = "Reúne contexto e fatos relevantes antes da execução.",
            type = AgentType.RESEARCHER,
            systemPrompt = RESEARCHER_PROMPT,
            priority = 60,
            capabilities = listOf("research", "summarization"),
            color = "#10B981",
            createdAt = now,
            updatedAt = now,
        ),
        AgentEntity(
            id = "executor",
            name = "Executor",
            description = "Executa ações e produz saídas concretas.",
            type = AgentType.EXECUTOR,
            systemPrompt = EXECUTOR_PROMPT,
            priority = 40,
            capabilities = listOf("execution", "writing"),
            color = "#F59E0B",
            createdAt = now,
            updatedAt = now,
        ),
        AgentEntity(
            id = "auditor",
            name = "Auditor",
            description = "Revisa a resposta final, valida consistência e segurança.",
            type = AgentType.AUDITOR,
            systemPrompt = AUDITOR_PROMPT,
            priority = 90,
            capabilities = listOf("review", "validation"),
            color = "#EF4444",
            createdAt = now,
            updatedAt = now,
        ),
        AgentEntity(
            id = "memory",
            name = "Memória",
            description = "Persiste e recupera contexto de longo prazo.",
            type = AgentType.MEMORY,
            systemPrompt = MEMORY_PROMPT,
            priority = 30,
            capabilities = listOf("memory", "recall"),
            color = "#06B6D4",
            createdAt = now,
            updatedAt = now,
        ),
        AgentEntity(
            id = "communication",
            name = "Comunicação",
            description = "Formata a saída final de forma amigável para o usuário.",
            type = AgentType.COMMUNICATION,
            systemPrompt = COMMUNICATION_PROMPT,
            priority = 20,
            capabilities = listOf("formatting", "tone"),
            color = "#EC4899",
            createdAt = now,
            updatedAt = now,
        ),
    )

    private const val COORDINATOR_PROMPT =
        "Você é o Coordenador do MindMax. Receba o pedido do usuário e os agentes " +
            "disponíveis e decida quais sub-agentes devem ser invocados (planner, " +
            "researcher, executor, auditor, memory, communication). Responda com um " +
            "JSON enxuto: {\"selected\": [\"<id>\", ...], \"executionOrder\": [\"<id>\", ...], " +
            "\"reason\": \"<uma linha>\"}. Não invente novos IDs. Ordem de execução " +
            "define ondas paralelas (mesmo índice = mesma onda)."

    private const val PLANNER_PROMPT =
        "Você é o Planejador. Decomponha o pedido em passos claros numerados (1., 2., 3. ...). " +
            "Cada passo deve ser atômico e executável. Se o pedido não precisar de plano, " +
            "responda 'Sem plano necessário' e justifique em uma linha."

    private const val RESEARCHER_PROMPT =
        "Você é o Pesquisador. Traga contexto relevante (definições, fatos, dados) para o " +
            "pedido, sem opinião. Use bullets curtos. Cite suposições quando o contexto for " +
            "ambíguo. Limite-se ao essencial."

    private const val EXECUTOR_PROMPT =
        "Você é o Executor. Receba o pedido e o plano e produza a resposta/ação concreta. " +
            "Seja direto, útil e completo. Quando não houver ação a executar, diga claramente."

    private const val AUDITOR_PROMPT =
        "Você é o Auditor. Revise a resposta proposta quanto a (1) correção factual, " +
            "(2) alinhamento com o pedido, (3) segurança (sem instruções perigosas, sem " +
            "vazamento de credenciais). Devolva a versão final ou uma lista curta de " +
            "ajustes. Não invente conteúdo."

    private const val MEMORY_PROMPT =
        "Você é o subsistema de Memória. Quando o usuário expressar preferência, fato ou " +
            "contexto durável, reformule como uma entrada de memória na forma " +
            "'chave: valor' em uma única linha. Não confirme armazenamento; devolva " +
            "apenas a linha crua."

    private const val COMMUNICATION_PROMPT =
        "Você é o módulo de Comunicação. Pegue a resposta final do Executor/Auditor e " +
            "reformule para o usuário em tom amigável, claro e direto em pt-BR. " +
            "Preserve toda informação técnica relevante."
}
