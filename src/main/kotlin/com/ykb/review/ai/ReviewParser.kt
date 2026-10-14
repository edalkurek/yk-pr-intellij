package com.ykb.review.ai

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class ReviewParseException(message: String) : Exception(message)

/** VS Code reviewParser.ts karşılığı: model çıktısını sıkı şemaya göre doğrular; geçersizse istisna fırlatır. */
object ReviewParser {

    private const val MAX_TEXT_LENGTH = 20_000
    private const val MAX_FINDINGS = 200
    private const val MAX_TEST_SUGGESTIONS = 100

    fun parse(raw: String): ReviewResult {
        if (raw.isBlank()) throw ReviewParseException("AI boş bir yanıt döndürdü.")
        val jsonText = extractFirstJsonObject(raw.trim())
        val parsed = try {
            JsonParser.parseString(jsonText)
        } catch (_: Exception) {
            throw ReviewParseException("AI yanıtı geçerli JSON değil.")
        }
        return validate(parsed)
    }

    fun validate(value: JsonElement): ReviewResult {
        if (!value.isJsonObject) throw ReviewParseException("AI review yanıtı bir JSON nesnesi olmalıdır.")
        val obj = value.asJsonObject
        val findings = obj.get("findings")
        if (findings == null || !findings.isJsonArray) throw ReviewParseException("findings bir dizi olmalıdır.")
        if (findings.asJsonArray.size() > MAX_FINDINGS) throw ReviewParseException("findings izin verilen öğe sayısını aşıyor.")
        val tests = obj.get("testSuggestions")
        if (tests == null || !tests.isJsonArray) throw ReviewParseException("testSuggestions bir dizi olmalıdır.")
        if (tests.asJsonArray.size() > MAX_TEST_SUGGESTIONS) throw ReviewParseException("testSuggestions izin verilen öğe sayısını aşıyor.")

        return ReviewResult(
            summary = requiredString(obj.get("summary"), "summary"),
            riskLevel = enumValue(obj.get("riskLevel"), ReviewConstants.RISK_LEVELS, "riskLevel"),
            findings = findings.asJsonArray.mapIndexed { index, item -> validateFinding(item, index) },
            testSuggestions = tests.asJsonArray.mapIndexed { index, item -> requiredString(item, "testSuggestions[$index]") },
            finalComment = requiredString(obj.get("finalComment"), "finalComment"),
        )
    }

    private fun validateFinding(value: JsonElement, index: Int): ReviewFinding {
        if (!value.isJsonObject) throw ReviewParseException("findings[$index] nesne olmalıdır.")
        val obj: JsonObject = value.asJsonObject

        val line: Int? = obj.get("line")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let { p ->
            when {
                p.isNumber -> p.asDouble.takeIf { it == Math.floor(it) && it > 0 && it <= Int.MAX_VALUE }?.toInt()
                p.isString -> p.asString.trim().takeIf { it.matches(Regex("\\d+")) }?.toIntOrNull()?.takeIf { it > 0 }
                else -> null
            }
        }
        val rawSide = obj.get("side")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.trim()?.lowercase()
        // Bazı modeller Bitbucket'ın fileType sözlüğünü kullanır.
        val side = when (rawSide) {
            "from" -> "old"
            "to" -> "new"
            else -> rawSide
        }
        val sideValid = side == null || side == "old" || side == "new"

        return ReviewFinding(
            file = requiredString(obj.get("file"), "findings[$index].file"),
            line = if (line != null && sideValid) line else null,
            side = if (line != null && (side == "old" || side == "new")) side else null,
            severity = enumValue(obj.get("severity"), ReviewConstants.RISK_LEVELS, "findings[$index].severity"),
            type = enumValue(obj.get("type"), ReviewConstants.FINDING_TYPES, "findings[$index].type"),
            message = requiredString(obj.get("message"), "findings[$index].message"),
            suggestion = requiredString(obj.get("suggestion"), "findings[$index].suggestion"),
        )
    }

    private fun requiredString(value: JsonElement?, path: String): String {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw ReviewParseException("$path metin olmalıdır.")
        }
        val normalized = value.asString.trim()
        if (normalized.isEmpty()) throw ReviewParseException("$path boş olamaz.")
        if (normalized.length > MAX_TEXT_LENGTH) throw ReviewParseException("$path izin verilen uzunluğu aşıyor.")
        return normalized
    }

    private fun enumValue(value: JsonElement?, allowed: List<String>, path: String): String {
        val text = if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
        if (text == null || text !in allowed) throw ReviewParseException("$path geçerli bir değer değil.")
        return text
    }

    private fun extractFirstJsonObject(text: String): String {
        val firstBrace = text.indexOf('{')
        if (firstBrace < 0) throw ReviewParseException("AI yanıtında JSON nesnesi bulunamadı.")
        var depth = 0
        var inString = false
        var escaped = false
        for (index in firstBrace until text.length) {
            val c = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(firstBrace, index + 1)
                }
            }
        }
        throw ReviewParseException("AI yanıtındaki JSON nesnesi tamamlanmamış.")
    }
}
