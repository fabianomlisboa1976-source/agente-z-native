package com.agente.autonomo.api

import com.agente.autonomo.api.model.*
import com.agente.autonomo.data.entity.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Cliente para comunicação com APIs de LLM
 */
class LLMClient(private val settings: Settings) {
    
    companion object {
        const val TAG = "LLMClient"
        const val DEFAULT_TIMEOUT = 60L // segundos
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 1000L
    }
    
    private val apiService: LLMApiService by lazy {
        createApiService()
    }
    
    private fun createApiService(): LLMApiService {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (settings.apiProvider == "debug") {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        
        val baseUrl = getBaseUrl()
        
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LLMApiService::class.java)
    }
    
    private fun getBaseUrl(): String {
        return when (settings.apiProvider.lowercase()) {
            "groq" -> ApiProvider.GROQ.baseUrl
            "openrouter" -> ApiProvider.OPENROUTER.baseUrl
            "cloudflare" -> settings.apiBaseUrl ?: ApiProvider.CLOUDFLARE.baseUrl
            "github" -> ApiProvider.GITHUB.baseUrl
            else -> settings.apiBaseUrl ?: ApiProvider.GROQ.baseUrl
        }
    }
    
    private fun getAuthHeader(): String {
        return "Bearer ${settings.apiKey}"
    }
    
    /**
     * Envia uma mensagem para o LLM e retorna a resposta
     */
    suspend fun sendMessage(
        messages: List<Message>,
        model: String? = null,
        temperature: Float? = null,
        maxTokens: Int? = null
    ): Result<ChatCompletionResponse> = withContext(Dispatchers.IO) {
        try {
            val request = ChatCompletionRequest(
                model = model ?: settings.apiModel,
                messages = messages,
                temperature = temperature ?: settings.temperature,
                max_tokens = maxTokens ?: settings.maxTokens,
                top_p = settings.topP,
                stream = false
            )
            
            val response = apiService.createChatCompletion(getAuthHeader(), request)
            
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    Result.success(body)
                } else {
                    Result.failure(Exception("Resposta vazia da API"))
                }
            } else {
                val errorBody = response.errorBody()?.string()
                Result.failure(Exception("Erro ${response.code()}: $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Envia uma mensagem simples (sistema + usuário)
     */
    suspend fun sendSimpleMessage(
        systemPrompt: String,
        userMessage: String,
        model: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val messages = listOf(
            Message(role = "system", content = systemPrompt),
            Message(role = "user", content = userMessage)
        )
        
        sendMessage(messages, model).map { response ->
            response.choices.firstOrNull()?.message?.content 
                ?: throw Exception("Resposta vazia do modelo")
        }
    }
    
    /**
     * Streaming de resposta (para respostas longas)
     */
    fun streamMessage(
        messages: List<Message>,
        model: String? = null,
        temperature: Float? = null,
        maxTokens: Int? = null
    ): Flow<String> = flow {
        val request = ChatCompletionRequest(
            model = model ?: settings.apiModel,
            messages = messages,
            temperature = temperature ?: settings.temperature,
            max_tokens = maxTokens ?: settings.maxTokens,
            top_p = settings.topP,
            stream = true
        )
        
        try {
            val response = apiService.createChatCompletionStream(getAuthHeader(), request)
            
            if (response.isSuccessful) {
                val source = response.body()?.source()
                
                source?.use { src ->
                    while (!src.exhausted()) {
                        val line = src.readUtf8Line() ?: continue
                        
                        if (line.startsWith("data: ")) {
                            val data = line.substring(6)
                            
                            if (data == "[DONE]") break
                            
                            try {
                                // Parse do chunk de streaming
                                val chunk = parseStreamChunk(data)
                                val content = chunk?.choices?.firstOrNull()?.delta?.content
                                if (!content.isNullOrBlank()) {
                                    emit(content)
                                }
                            } catch (e: Exception) {
                                // Ignora chunks malformados
                            }
                        }
                    }
                }
            } else {
                throw Exception("Erro no streaming: ${response.code()}")
            }
        } catch (e: Exception) {
            throw e
        }
    }.flowOn(Dispatchers.IO)
    
    private fun parseStreamChunk(data: String): StreamChunk? {
        // Implementação simplificada - em produção usar Gson
        return try {
            com.google.gson.Gson().fromJson(data, StreamChunk::class.java)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Envia mensagem com retry automático
     */
    suspend fun sendMessageWithRetry(
        messages: List<Message>,
        model: String? = null,
        maxRetries: Int = MAX_RETRIES
    ): Result<ChatCompletionResponse> {
        var lastException: Exception? = null
        
        repeat(maxRetries) { attempt ->
            val result = sendMessage(messages, model)
            
            if (result.isSuccess) {
                return result
            }
            
            lastException = result.exceptionOrNull() as? Exception
            
            if (attempt < maxRetries - 1) {
                kotlinx.coroutines.delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        
        return Result.failure(lastException ?: Exception("Falha após $maxRetries tentativas"))
    }
    
    /**
     * Testa a conexão com a API
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val messages = listOf(
                Message(role = "user", content = "Olá! Responda apenas 'OK' para confirmar que está funcionando.")
            )
            
            val result = sendMessage(messages, maxTokens = 10)
            
            result.map { response ->
                "Conectado com sucesso! Modelo: ${response.model}"
            }
        } catch (e: Exception) {
            Result.failure(Exception("Falha na conexão: ${e.message}"))
        }
    }
    
    /**
     * Verifica se a API key está configurada
     */
    fun isConfigured(): Boolean {
        return settings.apiKey.isNotBlank() && settings.apiModel.isNotBlank()
    }
}
