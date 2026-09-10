package com.example.api

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.GET

data class ChatMessage(val role: String, val content: String)
data class ChatRequest(val model: String, val messages: List<ChatMessage>)
data class ChatResponse(val choices: List<Choice>)
data class Choice(val message: ChatMessage)

data class ModelsResponse(val data: List<ModelItem>)
data class ModelItem(val id: String)

interface DeepSeekApi {
    @GET("models")
    suspend fun getModels(@Header("Authorization") auth: String): ModelsResponse

    @POST("chat/completions")
    suspend fun translateText(
        @Header("Authorization") auth: String,
        @Body request: ChatRequest
    ): ChatResponse
}
