package com.ykb.review.ai

object ReviewConstants {
    val RISK_LEVELS = listOf("low", "medium", "high")
    val FINDING_TYPES = listOf("bug", "security", "performance", "readability", "test", "architecture")
    const val PR_SUMMARY_LIMIT = 1000
    const val FINAL_COMMENT_LIMIT = 1000

    fun riskScore(level: String): Int = when (level) {
        "high" -> 2
        "medium" -> 1
        else -> 0
    }
}

data class ReviewFinding(
    val file: String,
    val line: Int? = null,
    val side: String? = null, // "new" | "old" | null
    val severity: String,     // "low" | "medium" | "high"
    val type: String,         // ReviewConstants.FINDING_TYPES
    val message: String,
    val suggestion: String,
)

data class ReviewResult(
    val summary: String,
    val riskLevel: String,
    val findings: List<ReviewFinding> = emptyList(),
    val testSuggestions: List<String> = emptyList(),
    val finalComment: String,
    /** Hangi model(ler)in kullanıldığı (yalnızca IDE içinde gösterilir). */
    val modelUsed: String? = null,
    /** Uygulanan talimat kaynakları (yalnızca IDE içinde gösterilir), örn. "Ortak genel + IDE repo". */
    val instructionSources: String? = null,
)

data class ReviewContext(
    val title: String? = null,
    val description: String? = null,
    val sourceBranch: String? = null,
    val targetBranch: String? = null,
    val chunkLabel: String? = null,
    val files: List<String> = emptyList(),
    val extraInstructions: String? = null,
)

data class ChatMessage(val role: String, val content: String)
