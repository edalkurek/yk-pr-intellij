package com.ykb.review.bitbucket

import com.google.gson.Gson
import com.ykb.review.config.ReviewSettingsState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class BitbucketClient(
    private val baseUrl: String = ReviewSettingsState.instance.bitbucketBaseUrl.trimEnd('/'),
    private val token: String = ReviewSettingsState.instance.bitbucketToken
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun buildRequest(path: String): Request.Builder {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val fullUrl = if (path.startsWith("http")) path else "$baseUrl$cleanPath"
        val builder = Request.Builder()
            .url(fullUrl)
            .header("Accept", "application/json")

        if (token.isNotBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    /**
     * Bitbucket Server global dashboard API'si üzerinden kullanıcının
     * REVIEWER (İnceleyici) olarak atandığı TÜM AÇIK PR'ları çeker.
     * Proje veya Depo adı girmeye gerek kalmaz.
     */
    @Throws(IOException::class)
    fun listOpenPullRequestsForReviewer(): List<PullRequest> {
        val allPrs = mutableListOf<PullRequest>()
        var start = 0
        do {
            val path = "/rest/api/1.0/dashboard/pull-requests?role=REVIEWER&state=OPEN&limit=100&start=$start"
            val request = buildRequest(path).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Reviewer PR listesi alınamadı (HTTP ${response.code}): ${response.message}")
                }
                val body = response.body?.string() ?: return allPrs
                val parsed = gson.fromJson(body, PullRequestResponse::class.java)
                allPrs.addAll(parsed.values)

                if (parsed.isLastPage || parsed.nextPageStart == null || parsed.nextPageStart <= start) {
                    return allPrs
                }
                start = parsed.nextPageStart
            }
        } while (true)
    }

    /**
     * Kullanıcının erişim yetkisi olan tüm depoları listeler (Repo bazlı özel talimatlar için).
     */
    @Throws(IOException::class)
    fun listRepositories(): List<BitbucketRepository> {
        val allRepos = mutableListOf<BitbucketRepository>()
        var start = 0
        do {
            val path = "/rest/api/1.0/repos?limit=100&start=$start"
            val request = buildRequest(path).get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Depolar listelenemedi (HTTP ${response.code}): ${response.message}")
                }
                val body = response.body?.string() ?: return allRepos
                val parsed = gson.fromJson(body, RepositoryResponse::class.java)
                for (ref in parsed.values) {
                    allRepos.add(
                        BitbucketRepository(
                            slug = ref.slug,
                            name = ref.name ?: ref.slug,
                            projectKey = ref.project?.key ?: ""
                        )
                    )
                }

                if (parsed.isLastPage || parsed.nextPageStart == null || parsed.nextPageStart <= start) {
                    return allRepos
                }
                start = parsed.nextPageStart
            }
        } while (true)
    }

    /**
     * Belirli bir proje ve depodaki açık PR listesini döner
     */
    @Throws(IOException::class)
    fun getOpenPullRequests(projectKey: String, repoSlug: String): List<PullRequest> {
        val path = "/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests?state=OPEN&limit=50"
        val request = buildRequest(path).get().build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Bitbucket PR listesi alınamadı (HTTP ${response.code}): ${response.message}")
            }
            val body = response.body?.string() ?: return emptyList()
            val parsed = gson.fromJson(body, PullRequestResponse::class.java)
            return parsed.values
        }
    }

    /**
     * PR Diff metnini çeker (Unified Diff)
     */
    @Throws(IOException::class)
    fun getPullRequestDiff(projectKey: String, repoSlug: String, pullRequestId: Long): String {
        val path = "/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$pullRequestId/diff"
        val request = buildRequest(path)
            .header("Accept", "text/plain")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Bitbucket diff alınamadı (HTTP ${response.code}): ${response.message}")
            }
            return response.body?.string() ?: ""
        }
    }

    /**
     * PR üzerindeki mevcut yorumları tarayarak bu commit için daha önce
     * AI inceleme yorumu atılıp atılmadığını kontrol eder.
     */
    fun hasCommentMarker(projectKey: String, repoSlug: String, pullRequestId: Long, marker: String): Boolean {
        return try {
            val path = "/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$pullRequestId/comments?limit=100"
            val request = buildRequest(path).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body?.string() ?: return false
                body.contains(marker)
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * PR genel yorumu gönderir
     */
    @Throws(IOException::class)
    fun postGeneralComment(projectKey: String, repoSlug: String, pullRequestId: Long, markdownComment: String): Boolean {
        val path = "/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$pullRequestId/comments"
        val payload = BitbucketCommentPayload(text = markdownComment)
        val requestBody = gson.toJson(payload).toRequestBody(jsonMediaType)

        val request = buildRequest(path)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Yorum gönderilemedi (HTTP ${response.code}): ${response.message}")
            }
            return true
        }
    }

    /**
     * PR Diff üzerinde satır içi (inline) yorum gönderir
     */
    @Throws(IOException::class)
    fun postInlineComment(
        projectKey: String,
        repoSlug: String,
        pullRequestId: Long,
        filePath: String,
        lineNumber: Int,
        side: String,
        comment: String
    ): Boolean {
        val path = "/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests/$pullRequestId/comments"
        val lineType = if (side.equals("old", ignoreCase = true)) "REMOVED" else "ADDED"
        val anchor = InlineCommentAnchor(line = lineNumber, lineType = lineType, path = filePath)
        val payload = BitbucketCommentPayload(text = comment, anchor = anchor)

        val requestBody = gson.toJson(payload).toRequestBody(jsonMediaType)
        val request = buildRequest(path)
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }
}
