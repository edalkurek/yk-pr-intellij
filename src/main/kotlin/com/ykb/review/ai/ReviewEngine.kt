package com.ykb.review.ai

import com.ykb.review.util.Cancellation
import com.ykb.review.util.rethrowIfControlFlow

/** Modele sohbet isteği gönderebilen soyutlama (testlerde sahte istemci kullanılabilsin diye). */
fun interface ChatClient {
    fun chat(model: String, messages: List<ChatMessage>): String
}

data class ReviewEngineOptions(
    val fastModel: String,
    val complexModel: String,
    val maxChunkChars: Int = 60_000,
    /** Gate (yerel) incelemelerde kısmen incelenmiş bir diff asla onaylanmamalı. */
    val failOnChunkError: Boolean = false,
    val minSeverity: String = "low",
    val onChunkError: ((Throwable, Int, Int) -> Unit)? = null,
    val onProgress: ((String, Double) -> Unit)? = null,
)

/** VS Code reviewService.ts (reviewDiffInChunks) karşılığı + IntelliJ'e özgü katmanlı model yönlendirme. */
object ReviewEngine {

    fun review(client: ChatClient, diff: String, context: ReviewContext, options: ReviewEngineOptions): ReviewResult {
        options.onProgress?.invoke("Diff analiz ediliyor ve parçalanıyor...", 0.05)
        val chunks = DiffChunker.chunkDiff(diff, options.maxChunkChars, includeLineLocations = true)
        if (chunks.isEmpty()) {
            if (diff.isNotBlank()) {
                return ReviewResult(
                    summary = "Değişikliklerin tamamı ikili (binary) dosyalar kapsamında olduğu için incelenecek kod metni bulunamadı.",
                    riskLevel = "low",
                    finalComment = "Yalnızca ikili (binary) dosyalar içerdiğinden AI incelemesi yapılmadı.",
                )
            }
            throw IllegalArgumentException("Review için diff boş olamaz.")
        }

        val successful = mutableListOf<ReviewResult>()
        var firstError: Throwable? = null
        for (chunk in chunks) {
            Cancellation.check()
            val decision = SmartModelRouter.decide(chunk, options.fastModel, options.complexModel)
            options.onProgress?.invoke(
                "Parça ${chunk.index + 1}/${chunk.total} inceleniyor [${decision.modelName}]",
                0.1 + 0.8 * chunk.index.toDouble() / chunks.size,
            )
            try {
                val messages = ReviewPrompt.buildMessages(
                    chunk.content,
                    context.copy(chunkLabel = "${chunk.index + 1}/${chunk.total}", files = chunk.files),
                )
                val parsed = ReviewParser.parse(client.chat(decision.modelName, messages))
                successful.add(parsed.copy(modelUsed = decision.modelName))
            } catch (e: Throwable) {
                e.rethrowIfControlFlow()
                if (options.failOnChunkError) throw e
                if (firstError == null) firstError = e
                try {
                    options.onChunkError?.invoke(e, chunk.index, chunk.total)
                } catch (callbackError: Throwable) {
                    callbackError.rethrowIfControlFlow()
                    // Loglama hatası kalan parçaları durdurmamalı.
                }
            }
        }
        Cancellation.check()
        if (successful.isEmpty()) {
            throw firstError ?: IllegalStateException("AI review tamamlanamadı.")
        }

        options.onProgress?.invoke("Bulgular birleştiriliyor...", 0.92)
        var merged = ReviewMerger.merge(successful)
        val shortenModel = options.fastModel.ifBlank { options.complexModel }
        if (merged.summary.length > ReviewConstants.PR_SUMMARY_LIMIT) {
            merged = merged.copy(summary = shorten(client, shortenModel, merged.summary, ReviewConstants.PR_SUMMARY_LIMIT))
        }
        if (merged.finalComment.length > ReviewConstants.FINAL_COMMENT_LIMIT) {
            merged = merged.copy(finalComment = shorten(client, shortenModel, merged.finalComment, ReviewConstants.FINAL_COMMENT_LIMIT))
        }

        val minScore = ReviewConstants.riskScore(options.minSeverity.lowercase())
        if (minScore > 0) {
            merged = merged.copy(findings = merged.findings.filter { ReviewConstants.riskScore(it.severity) >= minScore })
        }
        options.onProgress?.invoke("İnceleme tamamlandı.", 1.0)
        return merged
    }

    private fun shorten(client: ChatClient, model: String, text: String, maxLength: Int): String {
        return try {
            val prompt = "Aşağıdaki metin belirlenen karakter sınırını ($maxLength) aşıyor. Lütfen bu metni en fazla $maxLength karakter olacak şekilde, anlam bütünlüğünü koruyarak ve tüm cümleleri (veya maddeleri) tamamlayarak yeniden özetle. Yarım kalan cümle veya eksik madde olmamalıdır.\n\nMetin:\n$text"
            client.chat(model, listOf(ChatMessage("user", prompt))).trim().ifEmpty { text }
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            text
        }
    }
}
