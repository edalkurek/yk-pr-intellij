package com.ykb.review.ai

import com.intellij.concurrency.SensitiveProgressWrapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.ykb.review.util.Cancellation
import com.ykb.review.util.rethrowIfControlFlow
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    /** Modele aynı anda gönderilebilecek en fazla parça sayısı (1 = sıralı). */
    val maxParallelChunks: Int = 3,
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

        val successful = reviewChunks(client, chunks, context, options)

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

    private sealed interface ChunkOutcome {
        val index: Int
        val modelName: String

        data class Success(override val index: Int, override val modelName: String, val result: ReviewResult) : ChunkOutcome
        data class Failure(override val index: Int, override val modelName: String, val error: Throwable) : ChunkOutcome
    }

    /**
     * Parçaları en fazla [ReviewEngineOptions.maxParallelChunks] eşzamanlı istekle inceler; başarılı sonuçları parça
     * sırasıyla döner. Geri çağrılar (onProgress/onChunkError) ve bekleme sırasındaki iptal kontrolü yalnızca çağıran
     * thread'de yapılır. ProgressIndicator thread-local olduğundan işçi thread'ler çağıranın göstergesini iptal
     * edilebilir bir sarmalayıcı üzerinden devralır; böylece LLMClient/Http içindeki iptal kontrolleri orada da çalışır
     * ve hata/iptal durumunda uçuştaki HTTP istekleri de kesilir.
     */
    private fun reviewChunks(
        client: ChatClient,
        chunks: List<DiffChunk>,
        context: ReviewContext,
        options: ReviewEngineOptions,
    ): List<ReviewResult> {
        Cancellation.check()
        val total = chunks.size
        val parallelism = options.maxParallelChunks.coerceIn(1, total)
        options.onProgress?.invoke(
            if (parallelism > 1) "$total parça inceleniyor (eşzamanlı $parallelism)..." else "$total parça inceleniyor...",
            0.1,
        )

        val parentIndicator = if (ApplicationManager.getApplication() != null) ProgressManager.getGlobalProgressIndicator() else null
        val workerIndicator: ProgressIndicator? = parentIndicator?.let { SensitiveProgressWrapper(it) }
        val stop = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(parallelism, workerThreadFactory())
        val completion = ExecutorCompletionService<ChunkOutcome>(executor)
        val futures = mutableListOf<Future<ChunkOutcome>>()

        try {
            for (chunk in chunks) {
                futures += completion.submit {
                    val decision = SmartModelRouter.decide(chunk, options.fastModel, options.complexModel)
                    var outcome: ChunkOutcome? = null
                    val body = Runnable {
                        outcome = try {
                            if (stop.get()) throw ProcessCanceledException()
                            Cancellation.check()
                            val messages = ReviewPrompt.buildMessages(
                                chunk.content,
                                context.copy(chunkLabel = "${chunk.index + 1}/${chunk.total}", files = chunk.files),
                            )
                            val parsed = ReviewParser.parse(client.chat(decision.modelName, messages))
                            ChunkOutcome.Success(chunk.index, decision.modelName, parsed.copy(modelUsed = decision.modelName))
                        } catch (e: Throwable) {
                            ChunkOutcome.Failure(chunk.index, decision.modelName, e)
                        }
                    }
                    if (workerIndicator != null) {
                        ProgressManager.getInstance().executeProcessUnderProgress(body, workerIndicator)
                    } else {
                        body.run()
                    }
                    outcome ?: ChunkOutcome.Failure(chunk.index, decision.modelName, ProcessCanceledException())
                }
            }

            val outcomes = arrayOfNulls<ChunkOutcome>(total)
            var done = 0
            while (done < total) {
                Cancellation.check()
                val future = completion.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val outcome = try {
                    future.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
                outcomes[outcome.index] = outcome
                done++
                if (outcome is ChunkOutcome.Failure) {
                    val error = outcome.error
                    if (isControlFlow(error)) {
                        Cancellation.check()
                        throw error
                    }
                    if (options.failOnChunkError) throw error
                    try {
                        options.onChunkError?.invoke(error, outcome.index, total)
                    } catch (callbackError: Throwable) {
                        callbackError.rethrowIfControlFlow()
                        // Loglama hatası kalan parçaları durdurmamalı.
                    }
                }
                options.onProgress?.invoke(
                    "Parça ${outcome.index + 1}/$total tamamlandı [${outcome.modelName}] ($done/$total)",
                    0.1 + 0.8 * done.toDouble() / total,
                )
            }
            Cancellation.check()

            val successful = outcomes.filterIsInstance<ChunkOutcome.Success>().map { it.result }
            if (successful.isEmpty()) {
                // Deterministik olsun diye tamamlanma sırasına değil parça sırasına göre ilk hata.
                val firstError = outcomes.filterIsInstance<ChunkOutcome.Failure>().firstOrNull()?.error
                throw firstError ?: IllegalStateException("AI review tamamlanamadı.")
            }
            return successful
        } catch (e: Throwable) {
            // İptal veya failOnChunkError: bekleyen parçalar başlamasın, uçuştakiler kesilsin.
            stop.set(true)
            workerIndicator?.cancel()
            futures.forEach { it.cancel(true) }
            throw e
        } finally {
            executor.shutdownNow()
        }
    }

    private fun isControlFlow(e: Throwable): Boolean = runCatching { e.rethrowIfControlFlow() }.isFailure

    private val workerCounter = AtomicInteger()

    private fun workerThreadFactory() = ThreadFactory { runnable ->
        Thread(runnable, "YKB-Review-Chunk-${workerCounter.incrementAndGet()}").apply { isDaemon = true }
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
