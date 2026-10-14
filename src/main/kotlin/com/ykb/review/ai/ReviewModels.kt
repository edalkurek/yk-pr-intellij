package com.ykb.review.ai

import com.google.gson.annotations.SerializedName

data class ReviewFinding(
    val file: String,
    val line: Int? = null,
    val side: String? = "new", // "new" veya "old"
    val severity: String = "medium", // "high", "medium", "low"
    val type: String = "bug", // "bug", "security", "performance", "readability", "test", "architecture"
    val message: String,
    val suggestion: String? = null
)

data class ReviewResult(
    val summary: String = "",
    val riskLevel: String = "low", // "low", "medium", "high"
    val findings: List<ReviewFinding> = emptyList(),
    val testSuggestions: List<String> = emptyList(),
    val finalComment: String = "",
    val modelUsed: String? = null // Hangi model katmanının çalıştırıldığını loglamak için
)
