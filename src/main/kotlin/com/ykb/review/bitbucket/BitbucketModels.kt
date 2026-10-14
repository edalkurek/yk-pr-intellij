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
)

/** Satır içi yorum konumu. side: "old" | "new", lineType: ADDED | REMOVED | CONTEXT */
data class InlineCommentLocation(
    val path: String,
    val srcPath: String?,
    val line: Int,
    val side: String,
    val lineType: String,
)

class BitbucketHttpException(val status: Int, message: String) : IOException(message)
