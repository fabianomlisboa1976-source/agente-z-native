package com.agente.autonomo.api

import com.agente.autonomo.api.model.*
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

/**
 * Cliente para comunicação com o servidor de memória do Agente Z
 * Este cliente se conecta ao servidor Next.js com banco de dados SQLite
 */
class MemoryApiClient(private val baseUrl: String) {
    
    companion object {
        const val TAG = "MemoryApiClient"
        const val DEFAULT_TIMEOUT = 120L
        const val DEFAULT_BASE_URL = "https://preview-chat-68144e5f.space.z.ai"
        
        // Singleton para armazenar o ID do usuário
        @Volatile
        private var cachedUserId: String? = null
        
        fun getCachedUserId(): String? = cachedUserId
        fun setCachedUserId(id: String) { cachedUserId = id }
    }
    
    private val gson = Gson()
    
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(baseUrl.ifEmpty { DEFAULT_BASE_URL })
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
    
    private val api = retrofit.create(MemoryApi::class.java)
    
    /**
     * Criar ou obter usuário
     */
    suspend fun getOrCreateUser(email: String, name: String? = null): Result<UserResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.createUser(CreateUserRequest(email, name))
            if (response.isSuccessful && response.body()?.success == true) {
                val user = response.body()?.data
                if (user != null) {
                    cachedUserId = user.id
                }
                Result.success(response.body()!!)
            } else {
                // Tentar buscar usuário existente
                val existingUser = api.getUserByEmail(email)
                if (existingUser.isSuccessful && existingUser.body()?.success == true) {
                    val user = existingUser.body()?.data
                    if (user != null) {
                        cachedUserId = user.id
                    }
                    Result.success(existingUser.body()!!)
                } else {
                    Result.failure(Exception("Falha ao criar usuário: ${response.errorBody()?.string()}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Enviar mensagem de chat com memória
     */
    suspend fun sendChatMessage(
        userId: String,
        message: String,
        conversationId: String? = null
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.chat(ChatRequest(userId, message, conversationId))
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    Result.success(body)
                } else {
                    Result.failure(Exception("Resposta vazia"))
                }
            } else {
                Result.failure(Exception("Erro ${response.code()}: ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Buscar tarefas do usuário
     */
    suspend fun getTasks(userId: String): Result<TasksResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.getTasks(userId)
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Erro ao buscar tarefas"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Criar tarefa
     */
    suspend fun createTask(
        userId: String,
        title: String,
        description: String? = null,
        priority: String = "MEDIUM"
    ): Result<ApiResponse<TaskData>> = withContext(Dispatchers.IO) {
        try {
            val response = api.createTask(CreateTaskRequest(userId, title, description, priority))
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Erro ao criar tarefa"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Buscar memórias do usuário
     */
    suspend fun getMemories(userId: String, query: String? = null): Result<MemoriesResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.getMemories(userId, query)
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Erro ao buscar memórias"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Buscar conversas
     */
    suspend fun getConversations(userId: String): Result<ConversationsResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.getConversations(userId)
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Erro ao buscar conversas"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Verificar saúde da API
     */
    suspend fun checkHealth(): Result<HealthResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.health()
            if (response.isSuccessful) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("API indisponível"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Testar conexão
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = api.health()
            if (response.isSuccessful && response.body()?.status == "healthy") {
                Result.success("Conectado! DB: ${response.body()?.database?.counts}")
            } else {
                Result.failure(Exception("Falha na conexão"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Falha na conexão: ${e.message}"))
        }
    }
    
    // ==================== DATA CLASSES ====================
    
    data class CreateUserRequest(
        val email: String,
        val name: String?
    )
    
    data class ChatRequest(
        val userId: String,
        val message: String,
        val conversationId: String?
    )
    
    data class CreateTaskRequest(
        val userId: String,
        val title: String,
        val description: String?,
        val priority: String
    )
    
    data class ApiResponse<T>(
        val success: Boolean,
        val data: T?,
        val error: String?,
        val message: String?
    )
    
    data class UserResponse(
        val success: Boolean,
        val data: UserData?,
        val message: String?
    )
    
    data class UserData(
        val id: String,
        val email: String,
        val name: String?,
        val createdAt: String
    )
    
    data class ChatResponse(
        val conversationId: String,
        val messageId: String,
        val response: String,
        val memories: List<MemoryData>?,
        val tasks: List<TaskData>?
    )
    
    data class TasksResponse(
        val success: Boolean,
        val data: List<TaskData>?,
        val pagination: Pagination?
    )
    
    data class TaskData(
        val id: String,
        val userId: String,
        val title: String,
        val description: String?,
        val status: String,
        val priority: String,
        val category: String?,
        val dueDate: String?,
        val createdAt: String
    )
    
    data class MemoriesResponse(
        val success: Boolean,
        val data: List<MemoryData>?,
        val pagination: Pagination?
    )
    
    data class MemoryData(
        val id: String,
        val userId: String,
        val type: String,
        val key: String?,
        val content: String,
        val importance: Int,
        val createdAt: String
    )
    
    data class ConversationsResponse(
        val success: Boolean,
        val data: List<ConversationData>?,
        val pagination: Pagination?
    )
    
    data class ConversationData(
        val id: String,
        val userId: String,
        val title: String?,
        val summary: String?,
        val createdAt: String
    )
    
    data class Pagination(
        val total: Int,
        val page: Int,
        val limit: Int,
        val totalPages: Int
    )
    
    data class HealthResponse(
        val success: Boolean,
        val status: String,
        val timestamp: String,
        val database: DatabaseInfo?
    )
    
    data class DatabaseInfo(
        val connected: Boolean,
        val counts: Map<String, Int>?
    )
    
    // ==================== API INTERFACE ====================
    
    interface MemoryApi {
        @POST("api/users")
        suspend fun createUser(@Body request: CreateUserRequest): Response<UserResponse>
        
        @GET("api/users")
        suspend fun getUserByEmail(@Query("email") email: String): Response<UserResponse>
        
        @POST("api/chat")
        suspend fun chat(@Body request: ChatRequest): Response<ChatResponse>
        
        @GET("api/tasks")
        suspend fun getTasks(@Query("userId") userId: String): Response<TasksResponse>
        
        @POST("api/tasks")
        suspend fun createTask(@Body request: CreateTaskRequest): Response<ApiResponse<TaskData>>
        
        @GET("api/memory")
        suspend fun getMemories(
            @Query("userId") userId: String,
            @Query("query") query: String?
        ): Response<MemoriesResponse>
        
        @GET("api/conversations")
        suspend fun getConversations(@Query("userId") userId: String): Response<ConversationsResponse>
        
        @GET("api/health")
        suspend fun health(): Response<HealthResponse>
    }
}
