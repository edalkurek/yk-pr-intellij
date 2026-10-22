package com.ykb.review.bitbucket

import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BitbucketClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BitbucketClient
    private lateinit var ref: PullRequestRef

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/bitbucket").toString()
        client = BitbucketClient(baseUrl, "token", OkHttpClient())
        ref = PullRequestRef(baseUrl, "PRJ", "repo", 7)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun postedAnchor() =
        JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject.getAsJsonObject("anchor")

    @Test
    fun `satir yorumu anchor'i diff hash'lerini icerir ve yeniden adlandirma yoksa srcPath gondermez`() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

        client.addInlineComment(ref, "yorum", InlineCommentLocation("src/A.kt", null, 12, "new", "ADDED"), DiffHashes("from1", "to1"))

        val anchor = postedAnchor()
        assertEquals("EFFECTIVE", anchor.get("diffType").asString)
        assertEquals("from1", anchor.get("fromHash").asString)
        assertEquals("to1", anchor.get("toHash").asString)
        assertEquals("src/A.kt", anchor.get("path").asString)
        assertFalse(anchor.has("srcPath"))
        assertEquals(12, anchor.get("line").asInt)
        assertEquals("ADDED", anchor.get("lineType").asString)
        assertEquals("TO", anchor.get("fileType").asString)
    }

    @Test
    fun `yeniden adlandirilan dosyada silinen satir eski tarafa ve srcPath'e baglanir`() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

        client.addInlineComment(ref, "yorum", InlineCommentLocation("new.kt", "old.kt", 5, "old", "REMOVED"))

        val anchor = postedAnchor()
        assertEquals("new.kt", anchor.get("path").asString)
        assertEquals("old.kt", anchor.get("srcPath").asString)
        assertEquals("REMOVED", anchor.get("lineType").asString)
        assertEquals("FROM", anchor.get("fileType").asString)
        assertFalse(anchor.has("fromHash"))
    }

    @Test
    fun `etkin diff hash'leri changes uc noktasindan okunur`() {
        server.enqueue(MockResponse().setBody("""{"fromHash":"abc","toHash":"def","values":[],"isLastPage":true}"""))

        assertEquals(DiffHashes("abc", "def"), client.getEffectiveDiffHashes(ref))
        assertEquals(
            "/bitbucket/rest/api/1.0/projects/PRJ/repos/repo/pull-requests/7/changes?limit=1",
            server.takeRequest().path,
        )
    }

    @Test
    fun `hash'ler eksikse null doner`() {
        server.enqueue(MockResponse().setBody("""{"values":[],"isLastPage":true}"""))

        assertNull(client.getEffectiveDiffHashes(ref))
    }

    private fun pullRequestPage(vararg ids: Int): MockResponse {
        val values = ids.joinToString(",") { id ->
            """{"id":$id,"title":"PR $id","state":"OPEN","fromRef":{"displayId":"f","latestCommit":"c"},""" +
                """"toRef":{"displayId":"main","repository":{"slug":"repo","project":{"key":"PRJ"}}}}"""
        }
        return MockResponse().setBody("""{"values":[$values],"isLastPage":true}""")
    }

    @Test
    fun `kendi PR'larim istenirse AUTHOR rolu de tekrarsiz listelenir`() {
        server.enqueue(pullRequestPage(1, 2))
        assertEquals(listOf(1L, 2L), client.listOpenPullRequestsForReviewer().map { it.ref.pullRequestId })
        assertEquals(true, server.takeRequest().path!!.contains("role=REVIEWER"))

        server.enqueue(pullRequestPage(1, 2))
        server.enqueue(pullRequestPage(2, 3))
        assertEquals(listOf(1L, 2L, 3L), client.listOpenPullRequestsForReviewer(includeAuthored = true).map { it.ref.pullRequestId })
        server.takeRequest()
        assertEquals(true, server.takeRequest().path!!.contains("role=AUTHOR&state=OPEN"))
    }
}
