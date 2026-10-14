package com.ykb.review.ai

import com.ykb.review.config.ReviewSettingsState

class ReviewCoordinator(
    private val llmClient: LLMClient = LLMClient()
) {

    fun executeReview(
        prTitle: String,
        prDescription: String?,
        fromBranch: String?,
        toBranch: String?,
        rawDiff: String,
        onProgress: ((step: String, percent: Double) -> Unit)? = null
    ): ReviewResult {
        val state = ReviewSettingsState.instance

        onProgress?.invoke("Diff analiz ediliyor ve filtreleniyor...", 0.1)

        val processedDiff = if (state.maskSecretsInDiff) {
            DiffFilter.maskSecrets(rawDiff)
        } else {
            rawDiff
        }

        val chunks = DiffChunker.chunkDiff(processedDiff, state.maxChunkLines)
        if (chunks.isEmpty()) {
            return ReviewResult(
                summary = "İncelenecek uygun kod değişikliği bulunamadı (Tüm dosyalar filtrelendi veya diff boş).",
                riskLevel = "low"
            )
        }

        val chunkResults = mutableListOf<ReviewResult>()

        chunks.forEachIndexed { index, chunk ->
            // Smart Model Router ile model katmanını seç (Karmaşık işler -> Güçlü Model)
            val routingDecision = SmartModelRouter.decide(chunk)
            val stepMsg = "Parça ${chunk.index}/${chunk.totalChunks} inceleniyor [${routingDecision.tier} -> ${routingDecision.modelName}]"
            val progressPercent = 0.2 + (0.7 * (index.toDouble() / chunks.size))
            onProgress?.invoke(stepMsg, progressPercent)

            val userPrompt = ReviewPrompt.buildUserPrompt(
                prTitle = prTitle,
                prDescription = prDescription,
                fromBranch = fromBranch,
                toBranch = toBranch,
                chunkIndex = chunk.index,
                totalChunks = chunk.totalChunks,
                fileNames = chunk.fileNames,
                extraInstructions = state.extraInstructions,
                diffContent = chunk.content
            )

            try {
                val rawResponse = llmClient.callChatCompletion(
                    modelName = routingDecision.modelName,
                    systemPrompt = ReviewPrompt.SYSTEM_PROMPT,
                    userPrompt = userPrompt
                )
                val parsed = ReviewParser.parse(rawResponse, routingDecision.modelName)
                chunkResults.add(parsed)
            } catch (e: Exception) {
                // Hata durumunda fallback
                System.err.println("Parça ${chunk.index} incelenirken hata: ${e.message}")
                chunkResults.add(
                    ReviewResult(
                        summary = "Parça ${chunk.index} için analiz başarısız: ${e.message}",
                        riskLevel = "medium",
                        modelUsed = routingDecision.modelName
                    )
                )
            }
        }

        onProgress?.invoke("Bulgular birleştiriliyor ve önceliklendiriliyor...", 0.95)
        val merged = ReviewMerger.merge(chunkResults)

        // Minimum severity filtresi uygula
        val minSeverity = state.minSeverity.lowercase()
        val filteredFindings = merged.findings.filter { finding ->
            when (minSeverity) {
                "high" -> finding.severity == "high"
                "medium" -> finding.severity == "high" || finding.severity == "medium"
                else -> true
            }
        }

        onProgress?.invoke("İnceleme tamamlandı.", 1.0)
        return merged.copy(findings = filteredFindings)
    }
}
