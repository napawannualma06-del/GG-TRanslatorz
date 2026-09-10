package com.example.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.GET
import retrofit2.http.Streaming

data class ChatMessage(val role: String, val content: String)
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false
)
data class ChatResponse(val choices: List<Choice>)
data class Choice(val message: ChatMessage)

data class ModelsResponse(val data: List<ModelItem>)
data class ModelItem(val id: String)

data class StreamChunk(val choices: List<StreamChoice>? = null)
data class StreamChoice(val delta: StreamDelta? = null)
data class StreamDelta(val content: String? = null)

interface DeepSeekApi {
    @GET("models")
    suspend fun getModels(@Header("Authorization") auth: String): ModelsResponse

    @POST("chat/completions")
    suspend fun translateText(
        @Header("Authorization") auth: String,
        @Body request: ChatRequest
    ): ChatResponse

    @Streaming
    @POST("chat/completions")
    suspend fun streamTranslateText(
        @Header("Authorization") auth: String,
        @Body request: ChatRequest
    ): Response<ResponseBody>
}
