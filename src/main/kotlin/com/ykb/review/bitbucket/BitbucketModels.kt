package com.ykb.review.bitbucket

import com.google.gson.annotations.SerializedName

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
)

data class PullRequestAuthor(
    val user: BitbucketUser? = null
)

data class BitbucketUser(
    val name: String,
    val displayName: String? = null,
    val emailAddress: String? = null
)

data class BranchRef(
    val id: String,
    val displayId: String,
    val latestCommit: String? = null
)

data class PullRequestResponse(
    val size: Int = 0,
    val limit: Int = 25,
    val isLastPage: Boolean = true,
    val values: List<PullRequest> = emptyList(),
    val start: Int = 0
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
