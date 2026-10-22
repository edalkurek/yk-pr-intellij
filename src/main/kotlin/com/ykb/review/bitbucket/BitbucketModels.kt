package com.ykb.review.bitbucket

import java.io.IOException

/** Bitbucket Server/Data Center PR kimliği. [baseUrl] web (context path dahil) adresidir. */
data class PullRequestRef(
    val baseUrl: String,
    val projectKey: String,
    val repositorySlug: String,
    val pullRequestId: Long,
)

data class PullRequest(
    val ref: PullRequestRef,
    val title: String,
    val description: String?,
    val state: String,
    val url: String,
    val authorName: String,
    val sourceBranch: String,
    val destinationBranch: String,
    val latestCommitHash: String,
    val updatedDate: Long?,
)

data class BitbucketRepository(
    val baseUrl: String,
    val projectKey: String,
    val slug: String,
    val name: String,
)

data class PullRequestComment(
    val id: Long,
    val text: String,
    val deleted: Boolean,
    /** Bitbucket Server'ın iyimser kilit sürümü; yorumu silmek için gerekir. */
    val version: Int = 0,
)

/** Satır içi yorum konumu. side: "old" | "new", lineType: ADDED | REMOVED | CONTEXT */
data class InlineCommentLocation(
    val path: String,
    val srcPath: String?,
    val line: Int,
    val side: String,
    val lineType: String,
    /** Satırın kodu (diff işareti olmadan); yalnızca yerelde tekrar anahtarı için kullanılır, Bitbucket'a gönderilmez. */
    val content: String? = null,
)

/** PR'ın etkin (EFFECTIVE) diff'ini tanımlayan commit'ler; satır yorumu anchor'ı bu diff'e bağlanır. */
data class DiffHashes(val fromHash: String, val toHash: String)

class BitbucketHttpException(val status: Int, message: String) : IOException(message)

class FileTooLargeException(val path: String, val limitBytes: Int) :
    IOException("Dosya çok büyük (> ${limitBytes / (1024 * 1024)} MB): $path")

/** PR'daki tek dosya değişikliği. type: ADD | MODIFY | DELETE | MOVE | COPY | UNKNOWN */
data class PullRequestFileChange(
    val path: String,
    val srcPath: String?,
    val type: String,
)

/**
 * PR değişiklik özeti. [toHash] Bitbucket'ın karşılaştırma tabanı (hedef dalla ortak ata), [fromHash] PR'ın son commit'i.
 * Fork PR'larında kaynak dosyalar [sourceProjectKey]/[sourceRepositorySlug] deposundan okunabilir.
 */
data class PullRequestChanges(
    val fromHash: String,
    val toHash: String,
    val sourceProjectKey: String,
    val sourceRepositorySlug: String,
    val files: List<PullRequestFileChange>,
    val truncated: Boolean,
)
