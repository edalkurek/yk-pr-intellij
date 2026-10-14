package com.ykb.review.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ykb.review.config.ConfigValidation
import com.ykb.review.config.ReviewSettingsState
import com.ykb.review.config.SecretStore
import com.ykb.review.net.Http
import com.ykb.review.util.Cancellation
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

class AIClientException(message: String, val status: Int? = null, cause: Throwable? = null) : IOException(message, cause)

/** OpenAI uyumlu `/chat/completions` istemcisi (VS Code openAIClient.ts karşılığı). */
class LLMClient(
    baseUrl: String,
    private val apiKey: String?,
    private val http: OkHttpClient = Http.client(45, 180, 60, ReviewSettingsState.instance.disableTlsVerification),
) {
    private val endpoint: String

    init {
        val normalized = ConfigValidation.modelBaseUrl(baseUrl)
        endpoint = if (normalized.endsWith("/chat/completions")) normalized else "$normalized/chat/completions"
    }

    fun chat(model: String, messages: List<ChatMessage>, temperature: Double = 0.1): String {
        if (model.isBlank()) throw AIClientException("Model adı yapılandırılmamış.")
        require(messages.isNotEmpty()) { "Model mesaj listesi boş olamaz." }

        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", JsonArray().apply {
                messages.forEach { m ->
                    add(JsonObject().apply {
                        addProperty("role", m.role)
                        addProperty("content", m.content)
                    })
                }
            })
            addProperty("temperature", temperature)
        }.toString()

        var attempt = 0
        while (true) {
            Cancellation.check()
            val builder = Request.Builder().url(endpoint).post(body.toRequestBody(Http.JSON))
            apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.header("Authorization", "Bearer $it") }

            val (code, text, retryAfter) = try {
                Http.execute(http, builder.build()).use { Triple(it.code, it.body?.string().orEmpty(), it.header("Retry-After")) }
            } catch (e: SocketTimeoutException) {
                throw AIClientException("Model API isteği zaman aşımına uğradı.", cause = e)
            } catch (e: InterruptedIOException) {
                throw AIClientException("Model API isteği zaman aşımına uğradı.", cause = e)
            } catch (e: IOException) {
                // Yalnızca taşıma katmanı hatası (TLS/DNS/bağlantı); yanıt gövdesi içermez.
                throw AIClientException("Model API isteği tamamlanamadı (${e.message ?: e.javaClass.simpleName}).", cause = e)
            }

            if (code !in 200..299) {
                if (Http.isRetryableStatus(code) && attempt < Http.MAX_RETRY_ATTEMPTS) {
                    Cancellation.sleep(Http.backoffDelay(attempt, Http.parseRetryAfter(retryAfter)))
                    attempt++
                    continue
                }
                // Yanıt gövdesi bilinçli olarak eklenmez: gateway'ler prompt/kimlik bilgisi yansıtabilir.
                throw AIClientException("Model API isteği başarısız oldu (HTTP $code).", code)
            }
            return responseContent(text)
        }
    }

    private fun responseContent(text: String): String {
        val payload = try {
            JsonParser.parseString(text)
        } catch (_: Exception) {
            throw AIClientException("Model API yanıtı geçerli JSON değil.")
        }
        if (!payload.isJsonObject) throw AIClientException("Model API geçersiz bir yanıt döndürdü.")
        val choices = payload.asJsonObject.get("choices")
        if (choices == null || !choices.isJsonArray || choices.asJsonArray.size() == 0) {
            throw AIClientException("Model API yanıtında choices bulunamadı.")
        }
        val first = choices.asJsonArray[0]
        val message = if (first.isJsonObject) first.asJsonObject.get("message") else null
        if (message == null || !message.isJsonObject) throw AIClientException("Model API yanıtında message bulunamadı.")
        val content = message.asJsonObject.get("content")
        val value = if (content != null && content.isJsonPrimitive && content.asJsonPrimitive.isString) content.asString else ""
        if (value.isBlank()) throw AIClientException("Model API boş içerik döndürdü.")
        return value
    }

    companion object {
        fun fromSettings(): LLMClient {
            val settings = ReviewSettingsState.instance
            return LLMClient(settings.aiBaseUrl, SecretStore.get(SecretStore.Key.MODEL_API_KEY))
        }
    }
}
