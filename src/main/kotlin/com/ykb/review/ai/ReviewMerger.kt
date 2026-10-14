package com.ykb.review.ai

object ReviewMerger {

    fun merge(results: List<ReviewResult>): ReviewResult {
        if (results.isEmpty()) {
            return ReviewResult(
                summary = "İncelenecek değişiklik veya bulgu bulunamadı.",
                riskLevel = "low"
            )
        }

        if (results.size == 1) {
            return results.first()
        }

        val allFindings = mutableListOf<ReviewFinding>()
        val allTestSuggestions = mutableListOf<String>()
        val summaries = mutableListOf<String>()
        val modelsUsed = mutableSetOf<String>()

        for (res in results) {
            if (res.summary.isNotBlank()) summaries.add(res.summary)
            allFindings.addAll(res.findings)
            allTestSuggestions.addAll(res.testSuggestions)
            res.modelUsed?.let { modelsUsed.add(it) }
        }

        // Bulguları tekilleştir (Aynı dosya, satır ve mesajı tekrar etme)
        val deduplicatedFindings = allFindings.distinctBy {
            "${it.file}:${it.line}:${it.message.take(40)}"
        }.sortedWith(compareByDescending { severityScore(it.severity) })

        val highestRisk = when {
            deduplicatedFindings.any { it.severity == "high" } -> "high"
            deduplicatedFindings.any { it.severity == "medium" } -> "medium"
            else -> "low"
        }

        val mergedSummary = summaries.joinToString("\n\n")
        val mergedFinalComment = "Toplam ${deduplicatedFindings.size} bulgu tespit edildi. Genel risk seviyesi: $highestRisk."

        return ReviewResult(
            summary = mergedSummary,
            riskLevel = highestRisk,
            findings = deduplicatedFindings,
            testSuggestions = allTestSuggestions.distinct(),
            finalComment = mergedFinalComment,
            modelUsed = modelsUsed.joinToString(", ")
        )
    }

    private fun severityScore(severity: String): Int = when (severity.lowercase()) {
        "high" -> 3
        "medium" -> 2
        "low" -> 1
        else -> 0
    }
}
