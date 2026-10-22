package com.ykb.review.review

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ykb.review.ai.ReviewFinding
import com.ykb.review.ai.ReviewResult
import com.ykb.review.bitbucket.BitbucketClient
import com.ykb.review.bitbucket.InlineCommentLocation
import com.ykb.review.bitbucket.PullRequestRef
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PublishServiceTest {

    private val diff = """
        diff --git a/src/A.kt b/src/A.kt
        --- a/src/A.kt
        +++ b/src/A.kt
        @@ -1,3 +1,4 @@
         line1
        -old2
        +new2
        +new3
         line3
    """.trimIndent()

    private lateinit var server: MockWebServer
    private lateinit var client: BitbucketClient
    private lateinit var ref: PullRequestRef
    private val comments = mutableListOf<JsonObject>()
    private var changesStatus = 200
    private var rejectEmojiOnce = false
    /** Başka bir ekip arkadaşının PR'da zaten bulunan yorumları (id → metin). */
    private val othersComments = mutableMapOf<Long, String>()
    private val deletedPaths = mutableListOf<String>()
    private var nextId = 100L

    private class MemoryState : PublicationState {
        val keys = mutableSetOf<String>()
        override fun isPublished(key: String) = key in keys
        override fun markPublished(key: String) {
            keys.add(key)
        }
    }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.contains("/changes")) {
                    return if (changesStatus == 200) {
                        MockResponse().setBody("""{"fromHash":"src-tip","toHash":"merge-base","values":[],"isLastPage":true}""")
                    } else {
                        MockResponse().setResponseCode(changesStatus)
                    }
                }
                if (request.method == "POST" && path.endsWith("/comments")) {
                    val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                    // utf8mb4 olmayan sunucuyu taklit eder: emoji içeren ilk yorumu reddeder.
                    if (rejectEmojiOnce && body.get("text").asString.codePoints().anyMatch { it > 0xFFFF }) {
                        rejectEmojiOnce = false
                        return MockResponse().setResponseCode(400)
                    }
                    val id = synchronized(comments) {
                        comments.add(body)
                        nextId++
                    }
                    return MockResponse().setResponseCode(201).setBody("""{"id":$id,"version":0}""")
                }
                if (request.method == "DELETE") {
                    synchronized(deletedPaths) { deletedPaths.add(path) }
                    return MockResponse().setResponseCode(204)
                }
                if (path.contains("/activities")) {
                    val values = com.google.gson.JsonArray()
                    val own = synchronized(comments) { comments.mapIndexed { i, body -> 100L + i to body.get("text").asString } }
                    for ((id, text) in othersComments + own) {
                        values.add(JsonObject().apply {
                            add("comment", JsonObject().apply {
                                addProperty("id", id)
                                addProperty("text", text)
                                addProperty("version", 0)
                            })
                        })
                    }
                    return MockResponse().setBody(JsonObject().apply {
                        add("values", values)
                        addProperty("isLastPage", true)
                    }.toString())
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val baseUrl = server.url("/bitbucket").toString()
        client = BitbucketClient(baseUrl, "token", OkHttpClient())
        ref = PullRequestRef(baseUrl, "PRJ", "repo", 7)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun review(vararg findings: ReviewFinding) =
        ReviewResult(summary = "Özet", riskLevel = "medium", findings = findings.toList(), finalComment = "Son")

    private fun finding(line: Int, side: String? = "new") =
        ReviewFinding("src/A.kt", line, side, "high", "bug", "Sorun $line", "Öneri $line")

    private fun publish(review: ReviewResult, state: PublicationState = MemoryState(), unmatched: MutableList<ReviewFinding>? = null) =
        PublishService.publish(client, ref, review, diff, "k", "<!-- marker -->", state) { unmatched?.add(it) }

    @Test
    fun `diffteki bulgu satir yorumu, disindaki bulgu genel yorum olur`() {
        val unmatched = mutableListOf<ReviewFinding>()

        val result = publish(review(finding(3), finding(99)), unmatched = unmatched)

        assertEquals(1, result.inlineComments)
        assertEquals(1, result.generalOnlyFindings)
        assertEquals(listOf(99), unmatched.map { it.line })
        assertEquals(2, comments.size)
        val anchor = comments[0].getAsJsonObject("anchor")
        assertEquals(3, anchor.get("line").asInt)
        assertEquals("ADDED", anchor.get("lineType").asString)
        assertEquals("TO", anchor.get("fileType").asString)
        assertEquals("src-tip", anchor.get("fromHash").asString)
        assertEquals("merge-base", anchor.get("toHash").asString)
        assertFalse(comments[1].has("anchor"))
        assertTrue(comments[1].get("text").asString.contains("Sorun 99"))
    }

    @Test
    fun `silinen satir eski tarafa baglanir`() {
        publish(review(finding(2, side = "old")))

        val anchor = comments[0].getAsJsonObject("anchor")
        assertEquals(2, anchor.get("line").asInt)
        assertEquals("REMOVED", anchor.get("lineType").asString)
        assertEquals("FROM", anchor.get("fileType").asString)
    }

    @Test
    fun `hash alinamazsa satir yorumu hash'siz gonderilir`() {
        changesStatus = 404

        publish(review(finding(3)))

        val anchor = comments[0].getAsJsonObject("anchor")
        assertFalse(anchor.has("fromHash"))
        assertEquals(3, anchor.get("line").asInt)
    }

    @Test
    fun `400 yanitinda emoji temizlenmis metinle ayni anchor'a tekrar denenir`() {
        rejectEmojiOnce = true

        publish(review(finding(3)))

        val inline = comments.first { it.has("anchor") }
        assertEquals(3, inline.getAsJsonObject("anchor").get("line").asInt)
        assertFalse(inline.get("text").asString.codePoints().anyMatch { it > 0xFFFF })
    }

    @Test
    fun `tekrar yayinda daha once gonderilen yorumlar atlanir`() {
        val state = MemoryState()
        publish(review(finding(3)), state)
        val firstCount = comments.size

        publish(review(finding(3)), state)

        assertEquals(firstCount, comments.size)
    }

    @Test
    fun `satir anahtari VS Code eklentisiyle birebir aynidir`() {
        // Beklenen değerler Node.js ile: sha256(JSON.stringify([path, content])).slice(0, 32)
        fun key(content: String?, line: Int = 7) =
            PublishService.lineCommentKey(InlineCommentLocation("src/A.kt", null, line, "new", "ADDED", content))
        assertEquals("bitbucket-ai-review:line=cc9f54aa8147aae6db9fca27f5453b80", key("new2"))
        assertEquals("bitbucket-ai-review:line=c7833380d3643b6851aa9a8b3f8a5cc9", key("  if (a < b && c == \"x\") { return 'ü'; }  "))
        assertEquals("bitbucket-ai-review:line=0e2bc6f33616e601f11b794fe1205189", key(null))
    }

    @Test
    fun `daha once yorum yazilmis satir baska model veya kisi tarafindan tekrar yorumlanmaz`() {
        publish(review(finding(3)))
        val existing = comments.map { it.get("text").asString }
        comments.clear()

        // Aynı satır, farklı ifade ve bulgu türü.
        val other = ReviewFinding("src/A.kt", 3, "new", "medium", "security", "Başka ifade", "Başka öneri")
        val result = PublishService.publish(client, ref, review(other), diff, "k2", "<!-- marker -->", MemoryState(), existingComments = existing)

        assertEquals(1, result.skippedExisting)
        assertTrue(comments.none { it.has("anchor") })
    }

    @Test
    fun `ayni commit icin once yayimlanan inceleme kalir, sonraki kendi yorumlarini siler`() {
        val key = CommentFormatter.markerKey(7, "abc")
        othersComments[50L] = "Özet\n\n[//]: # (${key}model=baska)"

        val result = PublishService.publish(
            client, ref, review(finding(3)), diff, "k", CommentFormatter.createMarker(7, "abc", "m"), MemoryState(), reviewKey = key,
        )

        assertEquals(50L, result.supersededBy)
        assertEquals(2, deletedPaths.size)
        assertTrue(deletedPaths.all { it.contains("/comments/10") && it.contains("version=0") })
    }

    @Test
    fun `ilk yayimlanan inceleme yorumlarini korur`() {
        val key = CommentFormatter.markerKey(7, "abc")
        othersComments[500L] = "Sonraki inceleme [//]: # (${key}model=baska)"

        val result = PublishService.publish(
            client, ref, review(finding(3)), diff, "k", CommentFormatter.createMarker(7, "abc", "m"), MemoryState(), reviewKey = key,
        )

        assertEquals(null, result.supersededBy)
        assertEquals(1, result.inlineComments)
        assertTrue(deletedPaths.isEmpty())
    }
}
