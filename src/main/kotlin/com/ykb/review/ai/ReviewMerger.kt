package com.ykb.review.ai

import java.util.Locale

/** VS Code reviewMerger.ts karşılığı. */
object ReviewMerger {

    private val TR = Locale.forLanguageTag("tr-TR")

    private fun uniqueNonEmpty(values: List<String>): List<String> {
        val seen = HashSet<String>()
        return values.filter { value ->
            val key = value.trim().lowercase(TR)
            key.isNotEmpty() && seen.add(key)
        }
    }

    private fun findingKey(f: ReviewFinding): String =
        listOf(f.file, f.side ?: "", f.line?.toString() ?: "", f.type, f.message)
            .joinToString("\u0000").trim().lowercase(TR)

    fun merge(results: List<ReviewResult>): ReviewResult {
        require(results.isNotEmpty()) { "Birleştirilecek AI review sonucu bulunamadı." }

        val findingMap = LinkedHashMap<String, ReviewFinding>()
        for (result in results) {
            for (finding in result.findings) {
                val key = findingKey(finding)
                val existing = findingMap[key]
                if (existing == null || ReviewConstants.riskScore(finding.severity) > ReviewConstants.riskScore(existing.severity)) {
                    findingMap[key] = finding
                }
            }
        }
        val findings = findingMap.values.sortedByDescending { ReviewConstants.riskScore(it.severity) }
        val declaredRisk = results.maxByOrNull { ReviewConstants.riskScore(it.riskLevel) }?.riskLevel ?: "low"
        val findingRisk = findings.maxByOrNull { ReviewConstants.riskScore(it.severity) }?.severity ?: "low"
        val models = results.mapNotNull { it.modelUsed }.flatMap { it.split(", ") }.filter { it.isNotBlank() }.distinct()

        return ReviewResult(
            summary = uniqueNonEmpty(results.map { it.summary }).joinToString(" "),
            riskLevel = if (ReviewConstants.riskScore(findingRisk) > ReviewConstants.riskScore(declaredRisk)) findingRisk else declaredRisk,
            findings = findings,
            testSuggestions = uniqueNonEmpty(results.flatMap { it.testSuggestions }),
            finalComment = uniqueNonEmpty(results.map { it.finalComment }).joinToString(" "),
            modelUsed = models.joinToString(", ").ifEmpty { null },
        )
    }
}
