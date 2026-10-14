package com.ykb.review.review

import com.intellij.openapi.diagnostic.Logger
import com.ykb.review.ai.ChatClient
import com.ykb.review.ai.DiffFilter
import com.ykb.review.ai.LLMClient
import com.ykb.review.ai.ReviewContext
import com.ykb.review.ai.ReviewEngine
import com.ykb.review.ai.ReviewEngineOptions
import com.ykb.review.ai.ReviewResult
import com.ykb.review.bitbucket.BitbucketClient
import com.ykb.review.bitbucket.BitbucketRepository
import com.ykb.review.bitbucket.PullRequest
import com.ykb.review.bitbucket.PullRequestRef
import com.ykb.review.config.ConfigValidation
import com.ykb.review.config.ReviewSettingsState
import com.ykb.review.util.Cancellation
import com.ykb.review.util.safeMessage

data class ReviewOutcome(
    val review: ReviewResult,
    val pullRequest: PullRequest,
    val diff: String,
    val commitHash: String,
    val published: Boolean,
    val skippedDuplicate: Boolean,
    val publication: PublicationResult? = null,
)

/** VS Code reviewCoordinator.ts karşılığı: PR inceleme ve Bitbucket'a yayın akışının tek giriş noktası. */
class ReviewCoordinator(private val settings: ReviewSettingsState = ReviewSettingsState.instance) {

    private val log = Logger.getInstance(ReviewCoordinator::class.java)

    private val publicationState = object : PublicationState {
        override fun isPublished(key: String) = settings.isPublished(key)
        override fun markPublished(key: String) = settings.markPublished(key)
    }

    fun listPullRequests(): List<PullRequest> = BitbucketClient.fromSettings().listOpenPullRequestsForReviewer()

    fun listRepositories(): List<BitbucketRepository> = BitbucketClient.fromSettings().listRepositories()

    /** Bu commit için (herhangi bir IDE/model ile) zaten yorum atılmış mı? */
    fun hasReviewFor(pr: PullRequest, client: BitbucketClient = BitbucketClient.fromSettings()): Boolean {
        val commit = pr.latestCommitHash
        if (commit.isBlank()) return false
        if (settings.isPublished(reviewKey(pr.ref, commit))) return true
        if (settings.legacyReviewedCommit(pr.ref) == commit) return true
        return client.hasComment(pr.ref) { CommentFormatter.matchesExistingReview(it, pr.ref.pullRequestId, commit) }
    }

    fun review(
        ref: PullRequestRef,
        pullRequest: PullRequest?,
        publish: Boolean,
        onProgress: ((String, Double) -> Unit)? = null,
    ): ReviewOutcome {
        Cancellation.check()
        val fastModel = settings.fastModelName.trim()
        val complexModel = ConfigValidation.modelName(settings.complexModelName, "Güçlü model (Tier 2) adı")
        val bitbucket = BitbucketClient.fromSettings()
        val llm = LLMClient.fromSettings()

        onProgress?.invoke("PR bilgileri alınıyor...", 0.02)
        val pr = pullRequest ?: bitbucket.getPullRequest(ref)
        val commitHash = pr.latestCommitHash.ifBlank { bitbucket.getLatestCommitHash(ref) }
        if (commitHash.isBlank()) throw IllegalStateException("Pull request son commit bilgisi alınamadı.")

        Cancellation.check()
        onProgress?.invoke("Bitbucket'tan diff çekiliyor...", 0.04)
        val diff = bitbucket.getPullRequestDiff(ref)
        if (diff.isBlank()) throw IllegalStateException("Pull request diff içeriği boş.")

        val instructions = settings.effectiveInstructions(settings.repoInstructionFor(ref))
        val reviewInput = if (settings.maskSecretsInDiff) DiffFilter.maskSecrets(diff) else diff

        val review = ReviewEngine.review(
            ChatClient { model, messages -> llm.chat(model, messages) },
            reviewInput,
            ReviewContext(
                title = pr.title,
                description = pr.description,
                sourceBranch = pr.sourceBranch,
                targetBranch = pr.destinationBranch,
                extraInstructions = instructions,
            ),
            ReviewEngineOptions(
                fastModel = fastModel,
                complexModel = complexModel,
                maxChunkChars = settings.maxChunkChars.coerceAtLeast(4_000),
                failOnChunkError = false,
                minSeverity = settings.minSeverity,
                onChunkError = { e, index, total ->
                    log.warn("PR #${ref.pullRequestId}: AI review parçası ${index + 1}/$total başarısız: ${e.safeMessage()}")
                },
                onProgress = onProgress,
            ),
        )
        Cancellation.check()

        val outcome = ReviewOutcome(review, pr, diff, commitHash, published = false, skippedDuplicate = false)
        return if (publish) publish(outcome, bitbucket) else outcome
    }

    /** Daha önce yalnızca IDE'de gösterilmiş bir sonucu Bitbucket'a yayınlar. */
    fun publish(outcome: ReviewOutcome, client: BitbucketClient = BitbucketClient.fromSettings()): ReviewOutcome {
        val ref = outcome.pullRequest.ref
        val key = reviewKey(ref, outcome.commitHash)
        if (settings.isPublished(key) ||
            client.hasComment(ref) { CommentFormatter.matchesExistingReview(it, ref.pullRequestId, outcome.commitHash) }
        ) {
            log.info("PR #${ref.pullRequestId}: aynı commit için yorum zaten mevcut.")
            return outcome.copy(skippedDuplicate = true)
        }
        if (client.getLatestCommitHash(ref) != outcome.commitHash) {
            throw IllegalStateException("PR inceleme sırasında değişti. Güncel commit için yeniden inceleme başlatın.")
        }
        val marker = CommentFormatter.createMarker(
            ref.pullRequestId,
            outcome.commitHash,
            outcome.review.modelUsed ?: settings.complexModelName,
        )
        val publication = PublishService.publish(client, ref, outcome.review, outcome.diff, key, marker, publicationState) { finding ->
            val reason = when {
                finding.line == null -> "Model geçerli satır konumu döndürmedi."
                finding.side == null -> "Taraf bilgisi eksik; konum diff üzerinde tek bir satırla eşleştirilemedi."
                else -> "Dosya, satır ve taraf bilgisi diff üzerinde bulunamadı."
            }
            log.info("PR #${ref.pullRequestId}: satır yorumu genel yoruma alındı (${finding.file}:${finding.line}:${finding.side}). $reason")
        }
        settings.markPublished(key)
        log.info("PR #${ref.pullRequestId}: ${publication.inlineComments} satır yorumu ve 1 genel yorum yayımlandı.")
        return outcome.copy(published = true, publication = publication)
    }

    private fun reviewKey(ref: PullRequestRef, commitHash: String): String =
        "publishedReview:${ReviewSettingsState.repoKey(ref.baseUrl, ref.projectKey, ref.repositorySlug)}#${ref.pullRequestId}@$commitHash"
}
