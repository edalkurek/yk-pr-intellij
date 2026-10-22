package com.ykb.review.review

import com.google.gson.GsonBuilder
import com.ykb.review.ai.DiffLocations
import com.ykb.review.ai.ReviewFinding
import com.ykb.review.ai.ReviewResult
import com.ykb.review.bitbucket.BitbucketClient
import com.ykb.review.bitbucket.BitbucketHttpException
import com.ykb.review.bitbucket.InlineCommentLocation
import com.ykb.review.bitbucket.PullRequestComment
import com.ykb.review.bitbucket.PullRequestRef
import com.ykb.review.util.rethrowIfControlFlow
import com.ykb.review.util.safeMessage
import java.security.MessageDigest

/** Yayın durumu deposu (tekrar denemede başarılı yorumları atlamak için). */
interface PublicationState {
    fun isPublished(key: String): Boolean
    fun markPublished(key: String)
}

/**
 * @property skippedExisting Daha önceki bir incelemenin (başka model, IDE veya ekip arkadaşı) zaten yorum yazdığı satırlardaki bulgular.
 * @property supersededBy Aynı commit için bir ekip arkadaşının incelemesi önce yayımlandıysa onun yorum id'si; bu incelemenin yorumları geri alınmıştır.
 */
data class PublicationResult(
    val inlineComments: Int,
    val generalOnlyFindings: Int,
    val skippedExisting: Int = 0,
    val supersededBy: Long? = null,
)

/** VS Code publishReview.ts karşılığı. */
object PublishService {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /**
     * Yorum yazılan kod satırını commit'ler, modeller ve ekip arkadaşları arasında tanır. Satır numarası yerine kod
     * içeriğine bağlıdır, satırlar kaysa da değişmez. VS Code eklentisi aynı anahtarı üretir (`JSON.stringify` ile aynı JSON).
     */
    fun lineCommentKey(location: InlineCommentLocation): String {
        val parts: List<Any> = listOf(location.path, location.content?.trim() ?: location.line)
        return "bitbucket-ai-review:line=${sha256(gson.toJson(parts)).take(32)}"
    }

    /** VS Code 0.3.x'in yazdığı, bulgu türüne de bağlı anahtar. */
    private fun legacyFindingKey(location: InlineCommentLocation, finding: ReviewFinding): String {
        val parts: List<Any> = listOf(location.path, location.content?.trim() ?: location.line, finding.type)
        return "bitbucket-ai-review:finding=${sha256(gson.toJson(parts)).take(32)}"
    }

    /**
     * @param existingComments PR'daki mevcut yorum metinleri; daha önce yorum yazılmış satırlar tekrarlanmaz.
     * @param reviewKey Bu commit incelemesinin model bağımsız anahtarı ([CommentFormatter.markerKey]). Aynı commit için bir ekip
     * arkadaşının incelemesi önce yayımlandıysa bu inceleme kendi yorumlarını siler.
     */
    fun publish(
        client: BitbucketClient,
        ref: PullRequestRef,
        review: ReviewResult,
        diff: String,
        key: String,
        marker: String,
        state: PublicationState,
        existingComments: List<String> = emptyList(),
        reviewKey: String? = null,
        onUnmatchedFinding: ((ReviewFinding) -> Unit)? = null,
    ): PublicationResult {
        /** Yayın anahtarı başına bir kez gönderir; önceki denemede gönderildiyse null döner. */
        fun once(id: String, text: String, send: (String) -> PullRequestComment): PullRequestComment? {
            val itemKey = "$key:$id"
            if (state.isPublished(itemKey)) return null
            val comment = try {
                send(text)
            } catch (e: BitbucketHttpException) {
                if (e.status != 400) throw e
                val safe = CommentFormatter.sanitizeCommentForServer(text)
                if (safe == text) throw e
                send(safe)
            }
            state.markPublished(itemKey)
            return comment
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
        var skippedExisting = 0
        val groups = LinkedHashMap<String, Group>()
        for (finding in normalized.findings) {
            val location = DiffLocations.findInlineLocation(finding, locations)
            if (location == null) {
                onUnmatchedFinding?.invoke(finding)
                continue
            }
            val keys = listOf(lineCommentKey(location), legacyFindingKey(location, finding))
            if (existingComments.any { text -> keys.any { text.contains(it) } }) {
                inlineFindings.add(finding)
                skippedExisting++
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
        val posted = mutableListOf<PullRequestComment>()
        for ((id, group) in groups) {
            val text = group.comments.joinToString("\n\n---\n\n") + "\n\n" + hiddenMarker(lineCommentKey(group.location))
            try {
                once(id, text) { body -> client.addInlineComment(ref, body, group.location, hashes) }?.let { posted.add(it) }
                inlineFindings.addAll(group.findings)
            } catch (e: Throwable) {
                e.rethrowIfControlFlow()
                failures.add(e)
            }
        }
        val general = CommentFormatter.formatReviewComment(normalized, inlineFindings) + "\n\n" + marker
        val generalComment = once("general", general) { body -> client.addGeneralComment(ref, body) }

        val supersededBy = if (generalComment != null && reviewKey != null) earlierReview(client, ref, reviewKey, generalComment) else null
        if (supersededBy != null) {
            // Bitbucket yorum id'leri yalnızca artar; eşzamanlı yayımlayan herkes hangi incelemenin önce geldiğinde hemfikirdir.
            for (comment in posted + generalComment!!) {
                try {
                    client.deleteComment(ref, comment)
                } catch (e: Throwable) {
                    e.rethrowIfControlFlow()
                }
            }
            return PublicationResult(0, 0, skippedExisting, supersededBy)
        }

        if (failures.isNotEmpty()) {
            throw IllegalStateException(
                "${failures.size} satır yorumu gönderilemedi. Genel yorum yayımlandı; gönderilemeyen bulgular genel yorumda korundu. " +
                    "Yeniden denemede başarılı yorumlar atlanır. ${failures.first().safeMessage()}"
            )
        }
        return PublicationResult(groups.size, normalized.findings.size - inlineFindings.size, skippedExisting)
    }

    /** Bu incelemeden önce yayımlanmış, aynı commit'e ait bir incelemenin yorum id'si. */
    private fun earlierReview(client: BitbucketClient, ref: PullRequestRef, reviewKey: String, own: PullRequestComment): Long? =
        try {
            client.listComments(ref).firstOrNull { !it.deleted && it.id < own.id && it.text.contains(reviewKey) }?.id
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            // Tek kopyayı silme riskine girmektense incelemeyi koru.
            null
        }

    /** Görünmez markdown referans tanımı (VS Code `hiddenMarker` ile aynı biçim). */
    private fun hiddenMarker(key: String): String = "[//]: # ($key)"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
