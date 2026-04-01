package com.agente.autonomo.agent

import com.agente.autonomo.data.database.AppDatabase
import com.agente.autonomo.data.entity.Agent
import com.agente.autonomo.data.entity.Task
import com.agente.autonomo.utils.AuditLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Sistema de programação de agentes via conversa
 * Permite criar, modificar e gerenciar agentes através de comandos em linguagem natural
 */
class ConversationAgentProgrammer(
    private val database: AppDatabase,
    private val agentManager: AgentManager,
    private val auditLogger: AuditLogger
) {
    
    companion object {
        const val TAG = "ConversationAgentProgrammer"
        
        // Comandos reconhecidos
        val CREATE_AGENT_PATTERNS = listOf(
            "criar agente", "crie um agente", "novo agente", "adicionar agente",
            "create agent", "new agent"
        )
        
        val MODIFY_AGENT_PATTERNS = listOf(
            "modificar agente", "alterar agente", "editar agente", "atualizar agente",
            "modify agent", "edit agent", "update agent"
        )
        
        val DELETE_AGENT_PATTERNS = listOf(
            "excluir agente", "remover agente", "deletar agente", "apagar agente",
            "delete agent", "remove agent"
        )
        
        val LIST_AGENTS_PATTERNS = listOf(
            "listar agentes", "mostrar agentes", "ver agentes", "quais agentes",
            "list agents", "show agents"
        )
        
        val CREATE_TASK_PATTERNS = listOf(
            "criar tarefa", "nova tarefa", "adicionar tarefa", "crie uma tarefa",
            "create task", "new task"
        )
        
        val SCHEDULE_PATTERNS = listOf(
            "agendar", "programar", "schedule", "lembrar", "lembre-me",
            "avisar", "notificar"
        )
    }
    
    /**
     * Processa uma mensagem para detectar comandos de programação de agentes
     * Retorna um ProcessResult indicando se um comando foi detectado e executado
     */
    suspend fun processMessage(message: String): ProcessResult = withContext(Dispatchers.IO) {
        val lowerMessage = message.lowercase().trim()
        
        // Detectar tipo de comando
        return@withContext when {
            matchesPattern(lowerMessage, CREATE_AGENT_PATTERNS) -> {
                processCreateAgent(message)
            }
            matchesPattern(lowerMessage, MODIFY_AGENT_PATTERNS) -> {
                processModifyAgent(message)
            }
            matchesPattern(lowerMessage, DELETE_AGENT_PATTERNS) -> {
                processDeleteAgent(message)
            }
            matchesPattern(lowerMessage, LIST_AGENTS_PATTERNS) -> {
                processListAgents()
            }
            matchesPattern(lowerMessage, CREATE_TASK_PATTERNS) -> {
                processCreateTask(message)
            }
            matchesPattern(lowerMessage, SCHEDULE_PATTERNS) -> {
                processSchedule(message)
            }
            else -> {
                ProcessResult(
                    isCommand = false,
                    message = ""
                )
            }
        }
    }
    
    /**
     * Verifica se a mensagem corresponde a algum padrão
     */
    private fun matchesPattern(message: String, patterns: List<String>): Boolean {
        return patterns.any { message.contains(it) }
    }
    
    /**
     * Processa comando de criação de agente
     * Exemplo: "Crie um agente chamado Tradutor que traduza textos para inglês"
     */
    private suspend fun processCreateAgent(message: String): ProcessResult {
        try {
            // Extrair informações do comando usando parsing simples
            val agentConfig = parseAgentCreation(message)
            
            if (agentConfig == null) {
                return ProcessResult(
                    isCommand = true,
                    message = "Não consegui entender as especificações do agente. " +
                            "Tente: 'Crie um agente chamado [nome] que [descrição das funções]'"
                )
            }
            
            // Criar o agente
            val result = agentManager.createAgent(
                name = agentConfig.name,
                description = agentConfig.description,
                systemPrompt = agentConfig.systemPrompt,
                type = Agent.AgentType.CUSTOM,
                capabilities = agentConfig.capabilities,
                color = agentConfig.color
            )
            
            return if (result.isSuccess) {
                val agent = result.getOrThrow()
                auditLogger.logAction(
                    action = "Agente criado via conversa",
                    agentId = agent.id,
                    agentName = agent.name,
                    details = "Capacidades: ${agentConfig.capabilities.joinToString()}"
                )
                
                ProcessResult(
                    isCommand = true,
                    message = "✅ Agente **${agent.name}** criado com sucesso!\n\n" +
                            "**Descrição:** ${agent.description}\n" +
                            "**Capacidades:** ${agentConfig.capabilities.joinToString(", ")}\n\n" +
                            "O agente está ativo e pronto para usar. Você pode ativá-lo conversando com ele diretamente."
                )
            } else {
                ProcessResult(
                    isCommand = true,
                    message = "❌ Erro ao criar agente: ${result.exceptionOrNull()?.message}"
                )
            }
        } catch (e: Exception) {
            auditLogger.logError("Criar agente via conversa", e.message ?: "Erro desconhecido")
            return ProcessResult(
                isCommand = true,
                message = "❌ Erro ao processar comando: ${e.message}"
            )
        }
    }
    
    /**
     * Processa comando de modificação de agente
     */
    private suspend fun processModifyAgent(message: String): ProcessResult {
        // Extrair nome e modificações
        val parts = message.split(" ", limit = 4)
        if (parts.size < 3) {
            return ProcessResult(
                isCommand = true,
                message = "Para modificar um agente, diga: 'Modifique o agente [nome] para [nova descrição]'"
            )
        }
        
        // Buscar agente pelo nome
        val agents = agentManager.getAllAgents().first()
        val agentName = extractAgentName(message)
        val agent = agents.find { it.name.equals(agentName, ignoreCase = true) }
        
        if (agent == null) {
            return ProcessResult(
                isCommand = true,
                message = "❌ Agente '$agentName' não encontrado. Use 'listar agentes' para ver os disponíveis."
            )
        }
        
        // Extrair novas informações
        val newDescription = extractDescription(message)
        val updatedAgent = agent.copy(
            description = newDescription,
            systemPrompt = generateSystemPrompt(agent.name, newDescription)
        )
        
        val result = agentManager.updateAgent(updatedAgent)
        
        return if (result.isSuccess) {
            ProcessResult(
                isCommand = true,
                message = "✅ Agente **${agent.name}** atualizado com sucesso!\n\nNova descrição: $newDescription"
            )
        } else {
            ProcessResult(
                isCommand = true,
                message = "❌ Erro ao atualizar agente: ${result.exceptionOrNull()?.message}"
            )
        }
    }
    
    /**
     * Processa comando de exclusão de agente
     */
    private suspend fun processDeleteAgent(message: String): ProcessResult {
        val agentName = extractAgentName(message)
        
        if (agentName.isNullOrEmpty()) {
            return ProcessResult(
                isCommand = true,
                message = "Para excluir um agente, diga: 'Exclua o agente [nome]'"
            )
        }
        
        // Buscar agente
        val agents = agentManager.getAllAgents().first()
        val agent = agents.find { it.name.equals(agentName, ignoreCase = true) }
        
        if (agent == null) {
            return ProcessResult(
                isCommand = true,
                message = "❌ Agente '$agentName' não encontrado."
            )
        }
        
        // Não permitir excluir agentes do sistema
        if (agent.type != Agent.AgentType.CUSTOM) {
            return ProcessResult(
                isCommand = true,
                message = "❌ Não é possível excluir agentes do sistema. Apenas agentes personalizados podem ser removidos."
            )
        }
        
        val result = agentManager.deleteAgent(agent.id)
        
        return if (result.isSuccess) {
            ProcessResult(
                isCommand = true,
                message = "✅ Agente **${agentName}** excluído com sucesso."
            )
        } else {
            ProcessResult(
                isCommand = true,
                message = "❌ Erro ao excluir agente: ${result.exceptionOrNull()?.message}"
            )
        }
    }
    
    /**
     * Processa comando de listagem de agentes
     */
    private suspend fun processListAgents(): ProcessResult {
        val agents = agentManager.getAllAgents().first()
        
        if (agents.isEmpty()) {
            return ProcessResult(
                isCommand = true,
                message = "📋 Nenhum agente encontrado."
            )
        }
        
        val sb = StringBuilder("📋 **Agentes Disponíveis:**\n\n")
        
        agents.sortedByDescending { it.priority }.forEach { agent ->
            val status = if (agent.isActive) "✅ Ativo" else "⏸️ Inativo"
            val typeEmoji = when (agent.type) {
                Agent.AgentType.COORDINATOR -> "🎯"
                Agent.AgentType.PLANNER -> "📝"
                Agent.AgentType.RESEARCHER -> "🔍"
                Agent.AgentType.EXECUTOR -> "⚡"
                Agent.AgentType.AUDITOR -> "🔎"
                Agent.AgentType.MEMORY -> "🧠"
                Agent.AgentType.COMMUNICATION -> "💬"
                Agent.AgentType.CUSTOM -> "🤖"
            }
            
            sb.append("$typeEmoji **${agent.name}** $status\n")
            sb.append("   └ ${agent.description}\n\n")
        }
        
        sb.append("\n_Dica: Converse com um agente específico mencionando seu nome._")
        
        return ProcessResult(
            isCommand = true,
            message = sb.toString()
        )
    }
    
    /**
     * Processa comando de criação de tarefa
     */
    private suspend fun processCreateTask(message: String): ProcessResult {
        val taskConfig = parseTaskCreation(message)
        
        if (taskConfig == null) {
            return ProcessResult(
                isCommand = true,
                message = "Para criar uma tarefa, diga: 'Crie uma tarefa [descrição]' ou 'Nova tarefa: [descrição]'"
            )
        }
        
        val task = Task(
            id = UUID.randomUUID().toString(),
            title = taskConfig.title,
            description = taskConfig.description,
            type = Task.TaskType.CUSTOM,
            status = Task.TaskStatus.PENDING,
            priority = taskConfig.priority,
            assignedAgent = taskConfig.assignedAgent
        )
        
        database.taskDao().insertTask(task)
        
        auditLogger.logAction(
            action = "Tarefa criada via conversa",
            details = "Título: ${task.title}"
        )
        
        return ProcessResult(
            isCommand = true,
            message = "✅ Tarefa **${task.title}** criada com sucesso!\n\n" +
                    "Prioridade: ${task.priority.name}\n" +
                    "Status: Pendente\n\n" +
                    "A tarefa será processada automaticamente pelo sistema."
        )
    }
    
    /**
     * Processa comando de agendamento
     */
    private suspend fun processSchedule(message: String): ProcessResult {
        val scheduleConfig = parseSchedule(message)
        
        if (scheduleConfig == null) {
            return ProcessResult(
                isCommand = true,
                message = "Para agendar, diga: 'Agende [descrição] para [data/hora]'\n" +
                        "Exemplo: 'Agende reunião para amanhã às 10h' ou 'Lembre-me de tomar remédio às 8h'"
            )
        }
        
        val task = Task(
            id = UUID.randomUUID().toString(),
            title = scheduleConfig.title,
            description = scheduleConfig.description,
            type = Task.TaskType.REMINDER,
            status = Task.TaskStatus.SCHEDULED,
            priority = Task.TaskPriority.MEDIUM,
            scheduledAt = scheduleConfig.scheduledAt
        )
        
        database.taskDao().insertTask(task)
        
        val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale("pt", "BR"))
        
        return ProcessResult(
            isCommand = true,
            message = "✅ **Agendamento confirmado!**\n\n" +
                    "📅 **${scheduleConfig.title}**\n" +
                    "🕐 ${dateFormat.format(scheduleConfig.scheduledAt)}\n\n" +
                    "Você receberá uma notificação no horário agendado."
        )
    }
    
    // ============== Parsers ==============
    
    private data class AgentConfig(
        val name: String,
        val description: String,
        val systemPrompt: String,
        val capabilities: List<String>,
        val color: String = "#6366F1"
    )
    
    private data class TaskConfig(
        val title: String,
        val description: String,
        val priority: Task.TaskPriority,
        val assignedAgent: String? = null
    )
    
    private data class ScheduleConfig(
        val title: String,
        val description: String,
        val scheduledAt: java.util.Date
    )
    
    private fun parseAgentCreation(message: String): AgentConfig? {
        // Padrão: "crie um agente chamado X que Y"
        val namePatterns = listOf(
            "chamado (\\w+)", "nomeado (\\w+)", "de nome (\\w+)",
            "named (\\w+)", "called (\\w+)"
        )
        
        var name: String? = null
        for (pattern in namePatterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val match = regex.find(message)
            if (match != null) {
                name = match.groupValues[1].replaceFirstChar { it.uppercase() }
                break
            }
        }
        
        // Se não encontrou nome, gerar um
        if (name == null) {
            name = "Agente_${System.currentTimeMillis() % 10000}"
        }
        
        // Extrair descrição/função
        val descPatterns = listOf(
            "que (.+)$", "para (.+)$", "com funcao de (.+)$",
            "that (.+)$", "to (.+)$", "for (.+)$"
        )
        
        var description = ""
        for (pattern in descPatterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val match = regex.find(message)
            if (match != null) {
                description = match.groupValues[1].trim()
                break
            }
        }
        
        if (description.isEmpty()) {
            description = "Agente personalizado criado via conversa"
        }
        
        // Gerar capacidades baseadas na descrição
        val capabilities = extractCapabilities(description)
        
        // Gerar system prompt
        val systemPrompt = generateSystemPrompt(name, description)
        
        // Gerar cor baseada no nome
        val color = generateColor(name)
        
        return AgentConfig(
            name = name,
            description = description,
            systemPrompt = systemPrompt,
            capabilities = capabilities,
            color = color
        )
    }
    
    private fun extractCapabilities(description: String): List<String> {
        val capabilities = mutableListOf<String>()
        val lowerDesc = description.lowercase()
        
        // Detectar capacidades comuns
        if (lowerDesc.contains("traduz") || lowerDesc.contains("translate")) {
            capabilities.add("traducao")
        }
        if (lowerDesc.contains("pesquis") || lowerDesc.contains("search") || lowerDesc.contains("research")) {
            capabilities.add("pesquisa")
        }
        if (lowerDesc.contains("analis") || lowerDesc.contains("analyze")) {
            capabilities.add("analise")
        }
        if (lowerDesc.contains("codigo") || lowerDesc.contains("code") || lowerDesc.contains("program")) {
            capabilities.add("programacao")
        }
        if (lowerDesc.contains("escrev") || lowerDesc.contains("writ")) {
            capabilities.add("escrita")
        }
        if (lowerDesc.contains("calcul") || lowerDesc.contains("math")) {
            capabilities.add("calculo")
        }
        if (lowerDesc.contains("organi") || lowerDesc.contains("organize")) {
            capabilities.add("organizacao")
        }
        if (lowerDesc.contains("comunic") || lowerDesc.contains("communicate")) {
            capabilities.add("comunicacao")
        }
        
        // Se não detectou nada, adicionar capacidade genérica
        if (capabilities.isEmpty()) {
            capabilities.add("geral")
        }
        
        return capabilities
    }
    
    private fun generateSystemPrompt(name: String, description: String): String {
        return """Você é o $name, um agente especializado.

Sua função principal: $description

Diretrizes:
1. Seja preciso e útil em suas respostas
2. Mantenha foco na sua especialidade
3. Se não souber algo, admita honestamente
4. Ofereça soluções práticas e acionáveis
5. Comunique-se de forma clara e objetiva

Você foi criado personalmente pelo usuário para atender às necessidades específicas."""
    }
    
    private fun generateColor(name: String): String {
        val colors = listOf(
            "#6366F1", "#8B5CF6", "#EC4899", "#EF4444",
            "#F59E0B", "#10B981", "#3B82F6", "#06B6D4"
        )
        return colors[name.hashCode() % colors.size]
    }
    
    private fun extractAgentName(message: String): String? {
        val patterns = listOf(
            "agente (\\w+)", "do agente (\\w+)", "o agente (\\w+)",
            "agent (\\w+)"
        )
        
        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val match = regex.find(message)
            if (match != null) {
                return match.groupValues[1].replaceFirstChar { it.uppercase() }
            }
        }
        return null
    }
    
    private fun extractDescription(message: String): String {
        val patterns = listOf(
            "para (.+)$", "que (.+)$", "com descricao (.+)$"
        )
        
        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val match = regex.find(message)
            if (match != null) {
                return match.groupValues[1].trim()
            }
        }
        return "Agente personalizado"
    }
    
    private fun parseTaskCreation(message: String): TaskConfig? {
        val patterns = listOf(
            "tarefa[:\\s]+(.+)$", "crie uma tarefa (?:para|de)?\\s*(.+)$",
            "nova tarefa[:\\s]+(.+)$"
        )
        
        var title = ""
        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val match = regex.find(message)
            if (match != null) {
                title = match.groupValues[1].trim()
                break
            }
        }
        
        if (title.isEmpty()) return null
        
        // Detectar prioridade
        val priority = when {
            message.contains("urgente", ignoreCase = true) -> Task.TaskPriority.URGENT
            message.contains("importante", ignoreCase = true) -> Task.TaskPriority.HIGH
            message.contains("baixa", ignoreCase = true) -> Task.TaskPriority.LOW
            else -> Task.TaskPriority.MEDIUM
        }
        
        return TaskConfig(
            title = title.take(100),
            description = title,
            priority = priority
        )
    }
    
    private fun parseSchedule(message: String): ScheduleConfig? {
        // Extrair título
        val titlePatterns = listOf(
            "agendar? (.+?) para", "programar (.+?) para",
            "lembre-?me (?:de )?(.+?) (?:as|às|para)", "avisar (.+?) (?:as|às)"
        )
        
        var title = ""
        for (pattern in titlePatterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val match = regex.find(message)
            if (match != null) {
                title = match.groupValues[1].trim()
                break
            }
        }
        
        if (title.isEmpty()) return null
        
        // Extrair data/hora
        val scheduledAt = parseDateTime(message) ?: return null
        
        return ScheduleConfig(
            title = title.take(100),
            description = title,
            scheduledAt = scheduledAt
        )
    }
    
    private fun parseDateTime(message: String): java.util.Date? {
        val calendar = java.util.Calendar.getInstance()
        val now = calendar.time
        
        // Padrões de tempo
        when {
            message.contains("agora", ignoreCase = true) -> {
                return now
            }
            message.contains("em (\\d+) minutos", ignoreCase = true) -> {
                val regex = Regex("em (\\d+) minutos", RegexOption.IGNORE_CASE)
                val match = regex.find(message)
                if (match != null) {
                    val minutes = match.groupValues[1].toIntOrNull() ?: return null
                    calendar.add(java.util.Calendar.MINUTE, minutes)
                    return calendar.time
                }
            }
            message.contains("em (\\d+) horas?", ignoreCase = true) -> {
                val regex = Regex("em (\\d+) horas?", RegexOption.IGNORE_CASE)
                val match = regex.find(message)
                if (match != null) {
                    val hours = match.groupValues[1].toIntOrNull() ?: return null
                    calendar.add(java.util.Calendar.HOUR, hours)
                    return calendar.time
                }
            }
            message.contains("amanha", ignoreCase = true) -> {
                calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
                // Tentar extrair hora
                val hourRegex = Regex("(\\d{1,2})(?::(\\d{2}))?(?:h|:|$)")
                val hourMatch = hourRegex.find(message)
                if (hourMatch != null) {
                    val hour = hourMatch.groupValues[1].toIntOrNull() ?: 9
                    val minute = hourMatch.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                    calendar.set(java.util.Calendar.HOUR_OF_DAY, hour)
                    calendar.set(java.util.Calendar.MINUTE, minute)
                } else {
                    calendar.set(java.util.Calendar.HOUR_OF_DAY, 9)
                    calendar.set(java.util.Calendar.MINUTE, 0)
                }
                return calendar.time
            }
            // Padrão HH:mm
            else -> {
                val timeRegex = Regex("(\\d{1,2}):(\\d{2})")
                val timeMatch = timeRegex.find(message)
                if (timeMatch != null) {
                    val hour = timeMatch.groupValues[1].toIntOrNull() ?: return null
                    val minute = timeMatch.groupValues[2].toIntOrNull() ?: return null
                    calendar.set(java.util.Calendar.HOUR_OF_DAY, hour)
                    calendar.set(java.util.Calendar.MINUTE, minute)
                    if (calendar.time.before(now)) {
                        calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
                    }
                    return calendar.time
                }
            }
        }
        
        return null
    }
    
    // Coroutines flow import
    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.first(): T {
        var result: T? = null
        kotlinx.coroutines.flow.first { result = it; true }
        return result!!
    }
    
    /**
     * Resultado do processamento de um comando
     */
    data class ProcessResult(
        val isCommand: Boolean,
        val message: String
    )
}
