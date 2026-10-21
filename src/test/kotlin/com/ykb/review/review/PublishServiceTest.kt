package com.ykb.review.review

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ykb.review.ai.ReviewFinding
import com.ykb.review.ai.ReviewResult
import com.ykb.review.bitbucket.BitbucketClient
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
                    synchronized(comments) { comments.add(body) }
                    return MockResponse().setResponseCode(201).setBody("{}")
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
}
