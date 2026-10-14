package com.ykb.review.bitbucket

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ykb.review.config.ConfigValidation
import com.ykb.review.config.ConfigurationError
import com.ykb.review.config.ReviewSettingsState
import com.ykb.review.config.SecretStore
import com.ykb.review.net.Http
import com.ykb.review.util.Cancellation
import com.ykb.review.util.arr
import com.ykb.review.util.bool
import com.ykb.review.util.long
import com.ykb.review.util.obj
import com.ykb.review.util.str
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Bitbucket Server / Data Center REST istemcisi (VS Code client.ts BitbucketServerClient karşılığı).
 */
class BitbucketClient(
    baseUrl: String,
    private val token: String,
    private val http: OkHttpClient = Http.client(30, 60, 30, ReviewSettingsState.instance.disableTlsVerification),
) {
    val webBaseUrl: String
    private val apiBaseUrl: String
    private val allowedOrigin: String

    init {
        if (token.isBlank()) throw ConfigurationError("Bitbucket kimlik bilgisi ayarlanmamış.")
        val base = ConfigValidation.bitbucketBaseUrl(baseUrl)
        webBaseUrl = base.replace(API_SUFFIX, "")
        apiBaseUrl = if (API_SUFFIX.containsMatchIn(base)) base else "$base/rest/api/1.0"
        allowedOrigin = Http.origin(apiBaseUrl)
    }

    /** Kimlik bilgisini doğrular (ayarlar ekranındaki bağlantı testi). Erişilebilen PR sayısı (ilk sayfa) döner. */
    fun verifyAccess(): Int {
        val page = JsonParser.parseString(send("$apiBaseUrl/dashboard/pull-requests?role=REVIEWER&state=OPEN&limit=25")).obj()
        return page.get("values").arr().size()
    }

    fun listRepositories(): List<BitbucketRepository> {
        val result = mutableListOf<BitbucketRepository>()
        paginate("$apiBaseUrl/repos?limit=100") { item ->
            val slug = item.str("slug")
            if (slug.isNotEmpty()) {
                result.add(
                    BitbucketRepository(
                        baseUrl = webBaseUrl,
                        projectKey = item.get("project").obj().str("key"),
                        slug = slug,
                        name = item.str("name").ifEmpty { slug },
                    )
                )
            }
            false
        }
        return result
    }

    /** Kullanıcının REVIEWER olduğu tüm açık PR'lar (dashboard API'si, proje/repo bilgisi gerekmez). */
    fun listOpenPullRequestsForReviewer(): List<PullRequest> {
        val result = mutableListOf<PullRequest>()
        paginate("$apiBaseUrl/dashboard/pull-requests?role=REVIEWER&state=OPEN&limit=100") { item ->
            result.add(toPullRequest(item))
            false
        }
        return result
    }

    fun getPullRequest(ref: PullRequestRef): PullRequest =
        toPullRequest(JsonParser.parseString(send(pullRequestUrl(ref))).obj())

    /** Ham unified diff (`.diff` uç noktası; `/diff` JSON döndürdüğü için kullanılmaz). */
    fun getPullRequestDiff(ref: PullRequestRef): String {
        val text = send("${pullRequestUrl(ref)}.diff", accept = "text/plain")
        if (!text.contains("diff --git") && text.trimStart().startsWith("{")) {
            throw IllegalStateException("Bitbucket ham diff yerine JSON döndürdü; Bitbucket sürümü .diff uç noktasını desteklemiyor olabilir.")
        }
        return text
    }

    fun getLatestCommitHash(ref: PullRequestRef): String =
        JsonParser.parseString(send(pullRequestUrl(ref))).obj().get("fromRef").obj().str("latestCommit")

    /**
     * PR'daki tüm yorumları (yanıtlar dahil) gezer. Server'ın GET /comments uç noktası dosya yolu istediği için
     * PR geneli yorumlar /activities üzerinden okunur.
     */
    fun hasComment(ref: PullRequestRef, predicate: (String) -> Boolean): Boolean {
        val seen = HashSet<Long>()
        fun visitThread(comment: JsonObject): Boolean {
            val id = comment.long("id") ?: 0L
            if (seen.add(id) && !comment.bool("deleted") && predicate(comment.str("text"))) return true
            return comment.get("comments").arr().any { visitThread(it.obj()) }
        }
        return paginate("${pullRequestUrl(ref)}/activities?limit=100") { activity ->
            val comment = activity.get("comment").obj()
            comment.size() > 0 && visitThread(comment)
        }
    }

    fun addGeneralComment(ref: PullRequestRef, text: String) {
        require(text.isNotBlank()) { "Yorum metni boş olamaz." }
        val body = JsonObject().apply { addProperty("text", text) }
        send("${pullRequestUrl(ref)}/comments", jsonBody = body.toString())
    }

    fun addInlineComment(ref: PullRequestRef, text: String, location: InlineCommentLocation) {
        require(text.isNotBlank()) { "Yorum metni boş olamaz." }
        val anchor = JsonObject().apply {
            addProperty("diffType", "EFFECTIVE")
            addProperty("path", location.path)
            addProperty("srcPath", location.srcPath ?: location.path)
            addProperty("line", location.line)
            addProperty("lineType", location.lineType)
            addProperty("fileType", if (location.side == "old") "FROM" else "TO")
        }
        val body = JsonObject().apply {
            addProperty("text", text)
            add("anchor", anchor)
        }
        send("${pullRequestUrl(ref)}/comments", jsonBody = body.toString())
    }

    // ---------------------------------------------------------------- internals

    private fun pullRequestUrl(ref: PullRequestRef): String =
        "$apiBaseUrl/projects/${encode(ref.projectKey)}/repos/${encode(ref.repositorySlug)}/pull-requests/${ref.pullRequestId}"

    /** Sayfalı uç noktayı gezer; visitor true dönerse durur ve true döner. */
    private fun paginate(firstUrl: String, visitor: (JsonObject) -> Boolean): Boolean {
        var start = 0L
        val separator = if (firstUrl.contains('?')) "&" else "?"
        while (true) {
            val page = JsonParser.parseString(send("$firstUrl${separator}start=$start")).obj()
            for (value in page.get("values").arr()) {
                if (visitor(value.obj())) return true
            }
            if (page.bool("isLastPage")) return false
            val next = page.long("nextPageStart") ?: return false
            if (next <= start) return false
            start = next
        }
    }

    private fun send(url: String, accept: String = "application/json", jsonBody: String? = null): String {
        if (Http.origin(url) != allowedOrigin) {
            throw IllegalStateException("Bitbucket kimlik bilgisi beklenmeyen bir adrese gönderilmeyecek.")
        }
        var attempt = 0
        while (true) {
            Cancellation.check()
            val builder = Request.Builder()
                .url(url)
                .header("Accept", accept)
                .header("Authorization", "Bearer $token")
                .header("X-Atlassian-Token", "no-check")
            if (jsonBody != null) builder.post(jsonBody.toRequestBody(Http.JSON)) else builder.get()

            val (code, text, retryAfter) = Http.execute(http, builder.build()).use {
                Triple(it.code, it.body?.string().orEmpty(), it.header("Retry-After"))
            }
            if (code in 200..299) return text
            if (Http.isRetryableStatus(code) && attempt < Http.MAX_RETRY_ATTEMPTS) {
                Cancellation.sleep(Http.backoffDelay(attempt, Http.parseRetryAfter(retryAfter)))
                attempt++
                continue
            }
            val server = serverMessage(text)
            throw BitbucketHttpException(
                code,
                "Bitbucket isteği başarısız (HTTP $code)${if (server != null) ": $server" else ""}",
            )
        }
    }

    private fun serverMessage(body: String): String? {
        val message = try {
            val parsed = JsonParser.parseString(body).obj()
            val errors = parsed.get("errors").arr().map { it.obj().str("message") }.filter { it.isNotBlank() }
            if (errors.isNotEmpty()) errors.joinToString("; ") else parsed.str("message").ifBlank { null }
        } catch (_: Exception) {
            Regex("<title>([^<]+)</title>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim()
                ?: body.trim().takeIf { it.isNotEmpty() && it.length < 200 && !it.contains('<') }
        }
        return message?.let { Http.redact(it) }
    }

    private fun toPullRequest(item: JsonObject): PullRequest {
        val fromRef = item.get("fromRef").obj()
        val toRef = item.get("toRef").obj()
        val repository = toRef.get("repository").obj()
        val project = repository.get("project").obj()
        val author = item.get("author").obj().get("user").obj()
        val self = item.get("links").obj().get("self").arr().firstOrNull().obj()
        return PullRequest(
            ref = PullRequestRef(
                baseUrl = webBaseUrl,
                projectKey = project.str("key"),
                repositorySlug = repository.str("slug"),
                pullRequestId = item.long("id") ?: 0L,
            ),
            title = item.str("title"),
            description = item.str("description").ifBlank { null },
            state = item.str("state"),
            url = self.str("href"),
            authorName = author.str("displayName").ifEmpty { author.str("name") }.ifEmpty { "Bilinmiyor" },
            sourceBranch = fromRef.str("displayId").ifEmpty { fromRef.str("id") },
            destinationBranch = toRef.str("displayId").ifEmpty { toRef.str("id") },
            latestCommitHash = fromRef.str("latestCommit"),
            updatedDate = item.long("updatedDate"),
        )
    }

    companion object {
        private val API_SUFFIX = Regex("/rest/api/(?:latest|\\d+(?:\\.\\d+)*)$", RegexOption.IGNORE_CASE)

        fun fromSettings(): BitbucketClient {
            val settings = ReviewSettingsState.instance
            val token = SecretStore.get(SecretStore.Key.BITBUCKET_TOKEN)
                ?: throw ConfigurationError("Bitbucket kimlik bilgisi ayarlanmamış. Ayarlar → Tools → YKB AI Code Review.")
            return BitbucketClient(settings.bitbucketBaseUrl, token)
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
