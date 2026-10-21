package com.ykb.review.review

import com.ykb.review.ai.DiffLocations
import com.ykb.review.ai.ReviewFinding
import com.ykb.review.ai.ReviewResult
import com.ykb.review.bitbucket.BitbucketClient
import com.ykb.review.bitbucket.BitbucketHttpException
import com.ykb.review.bitbucket.InlineCommentLocation
import com.ykb.review.bitbucket.PullRequestRef
import com.ykb.review.util.rethrowIfControlFlow
import com.ykb.review.util.safeMessage
import java.security.MessageDigest

/** Yayın durumu deposu (tekrar denemede başarılı yorumları atlamak için). */
interface PublicationState {
    fun isPublished(key: String): Boolean
    fun markPublished(key: String)
}

data class PublicationResult(val inlineComments: Int, val generalOnlyFindings: Int)

/** VS Code publishReview.ts karşılığı. */
object PublishService {

    fun publish(
        client: BitbucketClient,
        ref: PullRequestRef,
        review: ReviewResult,
        diff: String,
        key: String,
        marker: String,
        state: PublicationState,
        onUnmatchedFinding: ((ReviewFinding) -> Unit)? = null,
    ): PublicationResult {
        fun once(id: String, text: String, send: (String) -> Unit) {
            val itemKey = "$key:$id"
            if (state.isPublished(itemKey)) return
            try {
                send(text)
            } catch (e: BitbucketHttpException) {
                if (e.status != 400) throw e
                val safe = CommentFormatter.sanitizeCommentForServer(text)
                if (safe == text) throw e
                send(safe)
            }
            state.markPublished(itemKey)
        }

        val locations = DiffLocations.diffLineLocations(diff)
        // Bulguları diff'teki kesin konumlara normalize et.
        val normalized = review.copy(findings = review.findings.map { finding ->
            val location = DiffLocations.findInlineLocation(finding, locations)
            if (location == null) finding else finding.copy(
                file = if (location.side == "old") location.srcPath ?: location.path else location.path,
                line = location.line,
                side = location.side,
            )
        })

        data class Group(val location: InlineCommentLocation, val comments: LinkedHashSet<String>, val findings: MutableList<ReviewFinding>)

        val inlineFindings = CommentFormatter.newIdentitySet()
        val groups = LinkedHashMap<String, Group>()
        for (finding in normalized.findings) {
            val location = DiffLocations.findInlineLocation(finding, locations)
            if (location == null) {
                onUnmatchedFinding?.invoke(finding)
                continue
            }
            val id = sha256("${location.path}\u0000${location.side}\u0000${location.line}")
            val group = groups.getOrPut(id) { Group(location, LinkedHashSet(), mutableListOf()) }
            group.comments.add(CommentFormatter.formatInlineComment(finding))
            group.findings.add(finding)
        }

        val hashes = if (groups.isEmpty()) null else try {
            client.getEffectiveDiffHashes(ref)
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            null
        }
        val failures = mutableListOf<Throwable>()
        for ((id, group) in groups) {
            val text = group.comments.joinToString("\n\n---\n\n")
            try {
                once(id, text) { body -> client.addInlineComment(ref, body, group.location, hashes) }
                inlineFindings.addAll(group.findings)
            } catch (e: Throwable) {
                e.rethrowIfControlFlow()
                failures.add(e)
            }
        }
        val general = CommentFormatter.formatReviewComment(normalized, inlineFindings) + "\n\n" + marker
        once("general", general) { body -> client.addGeneralComment(ref, body) }

        if (failures.isNotEmpty()) {
            throw IllegalStateException(
                "${failures.size} satır yorumu gönderilemedi. Genel yorum yayımlandı; gönderilemeyen bulgular genel yorumda korundu. " +
                    "Yeniden denemede başarılı yorumlar atlanır. ${failures.first().safeMessage()}"
            )
        }
        return PublicationResult(groups.size, normalized.findings.size - inlineFindings.size)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
