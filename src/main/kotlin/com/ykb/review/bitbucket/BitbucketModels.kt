package com.ykb.review.bitbucket

import com.google.gson.annotations.SerializedName

data class BitbucketRepository(
    val slug: String,
    val name: String = slug,
    val projectKey: String = ""
)

data class BitbucketProject(
    val key: String,
    val name: String? = null
)

data class RepositoryRef(
    val slug: String,
    val name: String? = null,
    val project: BitbucketProject? = null
)

data class BranchRef(
    val id: String,
    val displayId: String,
    val latestCommit: String? = null,
    val repository: RepositoryRef? = null
)

data class PullRequestAuthor(
    val user: BitbucketUser? = null
)

data class BitbucketUser(
    val name: String,
    val displayName: String? = null,
    val emailAddress: String? = null
)

data class PullRequest(
    val id: Long,
    val title: String,
    val description: String? = null,
    val state: String = "OPEN",
    val author: PullRequestAuthor? = null,
    val fromRef: BranchRef? = null,
    val toRef: BranchRef? = null,
    val createdDate: Long? = null,
    val updatedDate: Long? = null
) {
    val repositorySlug: String
        get() = toRef?.repository?.slug ?: fromRef?.repository?.slug ?: ""

    val projectKey: String
        get() = toRef?.repository?.project?.key ?: fromRef?.repository?.project?.key ?: ""

    val latestCommitHash: String
        get() = fromRef?.latestCommit ?: ""
}

data class PullRequestResponse(
    val size: Int = 0,
    val limit: Int = 25,
    val isLastPage: Boolean = true,
    val values: List<PullRequest> = emptyList(),
    val start: Int = 0,
    val nextPageStart: Int? = null
)

data class RepositoryResponse(
    val size: Int = 0,
    val limit: Int = 100,
    val isLastPage: Boolean = true,
    val values: List<RepositoryRef> = emptyList(),
    val start: Int = 0,
    val nextPageStart: Int? = null
)

data class InlineCommentAnchor(
    val line: Int,
    val lineType: String = "ADDED", // ADDED or REMOVED
    val path: String,
    val diffType: String = "EFFECTIVE"
)

data class BitbucketCommentPayload(
    val text: String,
    val anchor: InlineCommentAnchor? = null
)

data class CommentItem(
    val id: Long = 0,
    val text: String = ""
)

data class CommentResponse(
    val values: List<CommentItem> = emptyList()
)
