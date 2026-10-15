package com.ykb.review.bitbucket

/** Yerel git remote URL'sinin bir Bitbucket deposuna ait olup olmadığını belirler (HTTPS `/scm/PROJ/repo.git`, SSH `:7999/proj/repo.git`). */
object RemoteMatcher {

    fun matches(remoteUrl: String, projectKey: String, repositorySlug: String): Boolean {
        val url = remoteUrl.trim().lowercase().removeSuffix("/").removeSuffix(".git")
        val suffix = "${projectKey.lowercase()}/${repositorySlug.lowercase()}"
        return url.endsWith("/$suffix") || url.endsWith(":$suffix")
    }
}
