package com.ykb.review.ai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ykb.review.config.ReviewSettingsState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class LLMClient(
    private val baseUrl: String = ReviewSettingsState.instance.aiBaseUrl.trimEnd('/'),
    private val apiKey: String = ReviewSettingsState.instance.aiApiKey
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // Güçlü modellerin derin analizi için yeterli süre
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Belirtilen model (Fast veya Complex) ile LLM API'sine chat completion isteği atar.
     */
    @Throws(IOException::class)
    fun callChatCompletion(modelName: String, systemPrompt: String, userPrompt: String): String {
        val endpoint = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"

        val requestJson = JsonObject().apply {
            addProperty("model", modelName)
            addProperty("temperature", 0.1)

            val messagesArray = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemPrompt)
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", userPrompt)
                })
            }
            add("messages", messagesArray)

            // Destekleyen modeller için JSON modunu etkinleştir
            val responseFormat = JsonObject().apply {
                addProperty("type", "json_object")
            }
            add("response_format", responseFormat)
        }

        val requestBody = requestJson.toString().toRequestBody(jsonMediaType)
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(requestBody)

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("LLM API Hatası ($modelName) [HTTP ${response.code}]: $responseBody")
            }

            try {
                val parsed = JsonParser.parseString(responseBody).asJsonObject
                val choices = parsed.getAsJsonArray("choices")
                if (choices != null && choices.size() > 0) {
                    val messageObj = choices[0].asJsonObject.getAsJsonObject("message")
                    return messageObj.get("content").asString
                }
            } catch (e: Exception) {
                throw IOException("LLM yanıtı parse edilemedi: ${e.message}\nHam Yanıt: $responseBody", e)
            }

            throw IOException("LLM API boş veya geçersiz format döndürdü: $responseBody")
        }
    }
}
