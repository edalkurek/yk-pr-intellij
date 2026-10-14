package com.ykb.review.ai

import com.google.gson.Gson
import com.google.gson.JsonParser

object ReviewParser {

    private val gson = Gson()

    fun parse(rawResponse: String, modelName: String): ReviewResult {
        val cleaned = cleanJsonString(rawResponse)
        return try {
            val result = gson.fromJson(cleaned, ReviewResult::class.java)
            // Model bilgisini ve eksik alan kontrollerini tamamla
            result.copy(
                modelUsed = modelName,
                riskLevel = result.riskLevel.lowercase(),
                findings = result.findings.map { f ->
                    f.copy(
                        severity = f.severity.lowercase(),
                        type = f.type.lowercase()
                    )
                }
            )
        } catch (e: Exception) {
            // Basit fallback: Eğer model JSON dışında metin dönerse
            ReviewResult(
                summary = "JSON parse hatası: ${e.message}",
                riskLevel = "medium",
                findings = emptyList(),
                testSuggestions = emptyList(),
                finalComment = "Model çıktısı doğrudan JSON şemasına uymadı:\n$rawResponse",
                modelUsed = modelName
            )
        }
    }

    private fun cleanJsonString(text: String): String {
        var str = text.trim()
        if (str.startsWith("```json")) {
            str = str.removePrefix("```json")
        } else if (str.startsWith("```")) {
            str = str.removePrefix("```")
        }
        if (str.endsWith("```")) {
            str = str.removeSuffix("```")
        }
        return str.trim()
    }
}
