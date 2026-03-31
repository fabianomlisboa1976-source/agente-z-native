package com.agente.autonomo.api.model

/**
 * Modelos de dados para comunicação com APIs de LLM
 */

// ==================== REQUEST MODELS ====================

data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Float? = 0.7f,
    val max_tokens: Int? = 2048,
    val top_p: Float? = 0.9f,
    val stream: Boolean? = false,
    val stop: List<String>? = null
)

data class Message(
    val role: String, // "system", "user", "assistant"
    val content: String
)

// ==================== RESPONSE MODELS ====================

data class ChatCompletionResponse(
    val id: String,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage?,
    val created: Long?,
    val error: ErrorResponse? = null
)

data class Choice(
    val index: Int,
    val message: Message?,
    val delta: Delta?, // Para streaming
    val finish_reason: String?
)

data class Delta(
    val role: String? = null,
    val content: String? = null
)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

data class ErrorResponse(
    val message: String,
    val type: String?,
    val code: String?
)

// ==================== STREAMING MODELS ====================

data class StreamChunk(
    val id: String?,
    val model: String?,
    val choices: List<StreamChoice>?
)

data class StreamChoice(
    val index: Int,
    val delta: Delta,
    val finish_reason: String?
)

// ==================== PROVIDER CONFIGS ====================

enum class ApiProvider(val baseUrl: String, val defaultModel: String) {
    GROQ(
        baseUrl = "https://api.groq.com/openai/v1/",
        defaultModel = "llama-3.1-8b-instant"
    ),
    OPENROUTER(
        baseUrl = "https://openrouter.ai/api/v1/",
        defaultModel = "meta-llama/llama-3.1-8b-instruct"
    ),
    CLOUDFLARE(
        baseUrl = "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/run/",
        defaultModel = "@cf/meta/llama-3.1-8b-instruct"
    ),
    GITHUB(
        baseUrl = "https://models.inference.ai.azure.com/",
        defaultModel = "Meta-Llama-3.1-8B-Instruct"
    ),
    CUSTOM(
        baseUrl = "",
        defaultModel = ""
    )
}

// ==================== AVAILABLE MODELS ====================

object AvailableModels {
    
    data class ModelInfo(
        val id: String,
        val name: String,
        val provider: ApiProvider,
        val contextWindow: Int,
        val description: String
    )
    
    val GROQ_MODELS = listOf(
        ModelInfo(
            id = "llama-3.1-8b-instant",
            name = "Llama 3.1 8B Instant",
            provider = ApiProvider.GROQ,
            contextWindow = 128000,
            description = "Rápido e eficiente para tarefas gerais"
        ),
        ModelInfo(
            id = "llama-3.1-70b-versatile",
            name = "Llama 3.1 70B Versatile",
            provider = ApiProvider.GROQ,
            contextWindow = 128000,
            description = "Maior capacidade de raciocínio"
        ),
        ModelInfo(
            id = "mixtral-8x7b-32768",
            name = "Mixtral 8x7B",
            provider = ApiProvider.GROQ,
            contextWindow = 32768,
            description = "Bom equilíbrio performance/custo"
        ),
        ModelInfo(
            id = "gemma2-9b-it",
            name = "Gemma 2 9B IT",
            provider = ApiProvider.GROQ,
            contextWindow = 8192,
            description = "Modelo Google eficiente"
        )
    )
    
    val OPENROUTER_MODELS = listOf(
        ModelInfo(
            id = "meta-llama/llama-3.1-8b-instruct",
            name = "Llama 3.1 8B Instruct",
            provider = ApiProvider.OPENROUTER,
            contextWindow = 128000,
            description = "Versátil e rápido"
        ),
        ModelInfo(
            id = "meta-llama/llama-3.1-70b-instruct",
            name = "Llama 3.1 70B Instruct",
            provider = ApiProvider.OPENROUTER,
            contextWindow = 128000,
            description = "Alta performance"
        ),
        ModelInfo(
            id = "mistralai/mistral-7b-instruct",
            name = "Mistral 7B Instruct",
            provider = ApiProvider.OPENROUTER,
            contextWindow = 32768,
            description = "Excelente para instruções"
        ),
        ModelInfo(
            id = "microsoft/phi-3-mini-128k-instruct",
            name = "Phi-3 Mini 128K",
            provider = ApiProvider.OPENROUTER,
            contextWindow = 128000,
            description = "Muito eficiente, contexto grande"
        ),
        ModelInfo(
            id = "google/gemma-2-9b-it",
            name = "Gemma 2 9B IT",
            provider = ApiProvider.OPENROUTER,
            contextWindow = 8192,
            description = "Modelo Google"
        ),
        ModelInfo(
            id = "qwen/qwen-2.5-7b-instruct",
            name = "Qwen 2.5 7B Instruct",
            provider = ApiProvider.OPENROUTER,
            contextWindow = 32768,
            description = "Bom para múltiplos idiomas"
        )
    )
    
    val GITHUB_MODELS = listOf(
        ModelInfo(
            id = "Meta-Llama-3.1-8B-Instruct",
            name = "Llama 3.1 8B Instruct",
            provider = ApiProvider.GITHUB,
            contextWindow = 128000,
            description = "Versátil"
        ),
        ModelInfo(
            id = "Phi-3-mini-4k-instruct",
            name = "Phi-3 Mini 4K",
            provider = ApiProvider.GITHUB,
            contextWindow = 4096,
            description = "Compacto e eficiente"
        ),
        ModelInfo(
            id = "Mistral-small",
            name = "Mistral Small",
            provider = ApiProvider.GITHUB,
            contextWindow = 32768,
            description = "Bom para tarefas diversas"
        )
    )
    
    fun getAllModels(): List<ModelInfo> {
        return GROQ_MODELS + OPENROUTER_MODELS + GITHUB_MODELS
    }
    
    fun getModelsByProvider(provider: ApiProvider): List<ModelInfo> {
        return when (provider) {
            ApiProvider.GROQ -> GROQ_MODELS
            ApiProvider.OPENROUTER -> OPENROUTER_MODELS
            ApiProvider.GITHUB -> GITHUB_MODELS
            else -> emptyList()
        }
    }
    
    fun getModelById(id: String): ModelInfo? {
        return getAllModels().find { it.id == id }
    }
}
