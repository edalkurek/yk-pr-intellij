package com.ykb.review.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class ReviewEngineTest {

    private val fileCount = 6
    private fun fileDiff(i: Int): String {
        val body = (1..8).joinToString("\n") { "+val satir$it = \"dosya $i satır $it\"" }
        return """
        |diff --git a/src/F$i.kt b/src/F$i.kt
        |--- a/src/F$i.kt
        |+++ b/src/F$i.kt
        |@@ -0,0 +1,8 @@
        |$body
        |""".trimMargin()
    }

    private val diff = (0 until fileCount).joinToString("") { fileDiff(it) }

    /** Her dosya bölümü tek bir parçaya sığar, iki bölüm birlikte sığmaz. */
    private val maxChunkChars = DiffLocations.annotateDiffLines(fileDiff(0)).length * 3 / 2

    private fun chunkIndexOf(messages: List<ChatMessage>): Int {
        val text = messages.joinToString("\n") { it.content }
        return Regex("""src/F(\d+)\.kt""").find(text)!!.groupValues[1].toInt()
    }

    private fun response(i: Int) = """
        {"summary": "S$i", "riskLevel": "low",
         "findings": [{"file": "src/F$i.kt", "line": 1, "side": "new", "severity": "low", "type": "bug",
                       "message": "Bulgu $i", "suggestion": "Öneri $i"}],
         "testSuggestions": [], "finalComment": "C$i"}
    """.trimIndent()

    private fun options(
        failOnChunkError: Boolean = false,
        parallel: Int = 3,
        onChunkError: ((Throwable, Int, Int) -> Unit)? = null,
        onProgress: ((String, Double) -> Unit)? = null,
    ) = ReviewEngineOptions(
        fastModel = "fast",
        complexModel = "complex",
        maxChunkChars = maxChunkChars,
        failOnChunkError = failOnChunkError,
        onChunkError = onChunkError,
        onProgress = onProgress,
        maxParallelChunks = parallel,
    )

    @Test
    fun `diff her dosya icin ayri parcaya bolunur`() {
        assertEquals(fileCount, DiffChunker.chunkDiff(diff, maxChunkChars, includeLineLocations = true).size)
    }

    @Test
    fun `parcalar sinirli paralellikle islenir ve sonuc parca sirasini korur`() {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val progress = Collections.synchronizedList(mutableListOf<Double>())
        val client = ChatClient { _, messages ->
            val i = chunkIndexOf(messages)
            val now = active.incrementAndGet()
            maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                // Önceki parçalar daha geç bitsin: tamamlanma sırası parça sırasından farklı olsun.
                Thread.sleep(50L * (fileCount - i))
            } finally {
                active.decrementAndGet()
            }
            response(i)
        }

        val result = ReviewEngine.review(client, diff, ReviewContext(), options(onProgress = { _, f -> progress.add(f) }))

        assertEquals(3, maxActive.get())
        assertEquals((0 until fileCount).joinToString(" ") { "S$it" }, result.summary)
        assertEquals((0 until fileCount).map { "src/F$it.kt" }, result.findings.map { it.file })
        assertEquals(progress.sorted(), progress, "İlerleme monoton artmalı")
        assertEquals(1.0, progress.last())
    }

    @Test
    fun `tek parcalik paralellik siralidir`() {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val client = ChatClient { _, messages ->
            maxActive.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
            Thread.sleep(10)
            active.decrementAndGet()
            response(chunkIndexOf(messages))
        }
        ReviewEngine.review(client, diff, ReviewContext(), options(parallel = 1))
        assertEquals(1, maxActive.get())
    }

    @Test
    fun `hata toleransli modda basarisiz parca atlanir ve bildirilir`() {
        val errors = Collections.synchronizedList(mutableListOf<Int>())
        val client = ChatClient { _, messages ->
            val i = chunkIndexOf(messages)
            if (i == 2) throw IllegalStateException("parça $i bozuk")
            response(i)
        }
        val result = ReviewEngine.review(client, diff, ReviewContext(), options(onChunkError = { _, idx, _ -> errors.add(idx) }))
        assertEquals(listOf(2), errors)
        assertEquals(listOf(0, 1, 3, 4, 5).joinToString(" ") { "S$it" }, result.summary)
    }

    @Test
    fun `failOnChunkError ilk hatada kalan isleri durdurup hatayi firlatir`() {
        val started = AtomicInteger()
        val client = ChatClient { _, messages ->
            val i = chunkIndexOf(messages)
            started.incrementAndGet()
            if (i == 0) throw IllegalStateException("parça 0 bozuk")
            Thread.sleep(300)
            response(i)
        }
        val error = assertThrows<IllegalStateException> {
            ReviewEngine.review(client, diff, ReviewContext(), options(failOnChunkError = true))
        }
        assertEquals("parça 0 bozuk", error.message)
        Thread.sleep(400)
        assertTrue(started.get() < fileCount, "Bekleyen parçalar başlatılmamalı (başlayan: ${started.get()})")
    }

    @Test
    fun `tum parcalar basarisizsa parca sirasindaki ilk hata firlatilir`() {
        val client = ChatClient { _, messages ->
            val i = chunkIndexOf(messages)
            Thread.sleep(20L * (fileCount - i))
            throw IllegalStateException("hata $i")
        }
        val error = assertThrows<IllegalStateException> {
            ReviewEngine.review(client, diff, ReviewContext(), options())
        }
        assertEquals("hata 0", error.message)
    }
}
