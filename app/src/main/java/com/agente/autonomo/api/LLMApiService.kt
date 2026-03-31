package com.agente.autonomo.api

import com.agente.autonomo.api.model.ChatCompletionRequest
import com.agente.autonomo.api.model.ChatCompletionResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming
import okhttp3.ResponseBody
import retrofit2.http.Headers

/**
 * Interface Retrofit para APIs de LLM compatíveis com OpenAI
 */
interface LLMApiService {
    
    @POST("chat/completions")
    @Headers("Content-Type: application/json")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest
    ): Response<ChatCompletionResponse>
    
    @POST("chat/completions")
    @Headers("Content-Type: application/json")
    @Streaming
    suspend fun createChatCompletionStream(
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest
    ): Response<ResponseBody>
}

/**
 * Interface específica para OpenRouter (requer header adicional)
 */
interface OpenRouterApiService : LLMApiService {
    
    @POST("chat/completions")
    @Headers("Content-Type: application/json")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Header("HTTP-Referer") referer: String = "https://agente-autonomo.app",
        @Header("X-Title") title: String = "Agente Autonomo",
        @Body request: ChatCompletionRequest
    ): Response<ChatCompletionResponse>
}
