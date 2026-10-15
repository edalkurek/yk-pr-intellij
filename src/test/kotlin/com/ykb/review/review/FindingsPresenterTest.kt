package com.ykb.review.review

import com.ykb.review.ai.ReviewFinding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FindingsPresenterTest {

    private fun f(file: String, severity: String, line: Int? = null, type: String = "bug", message: String = "m") =
        ReviewFinding(file, line, "new", severity, type, message, "s")

    @Test
    fun `dosyalar en yuksek onem ve bulgular onem-satir sirasina gore gruplanir`() {
        val groups = FindingsPresenter.group(
            listOf(
                f("b/src/Low.kt", "low", 5),
                f("src/High.kt", "medium", 30),
                f("src/High.kt", "high", 40),
                f("src/High.kt", "high", 10),
            )
        )
        assertEquals(listOf("src/High.kt", "src/Low.kt"), groups.map { it.path })
        assertEquals(listOf(10, 40, 30), groups[0].findings.map { it.line })
        assertEquals("High.kt", groups[0].fileName)
        assertEquals("src", groups[0].directory)
    }

    @Test
    fun `filtre onem ve ture gore calisir`() {
        val all = listOf(f("a", "high", type = "security"), f("a", "low"), f("b", "medium"))
        assertEquals(1, FindingsPresenter.filter(all, setOf("high", "medium"), "security").size)
        assertEquals(2, FindingsPresenter.filter(all, setOf("high", "medium"), null).size)
    }

    @Test
    fun `baslik ilk cumleyi ve kod isaretleri olmadan doner`() {
        val headline = FindingsPresenter.headline("`user` null olabilir ve NPE oluşur. Ayrıca log seviyesi yanlış.")
        assertEquals("user null olabilir ve NPE oluşur.", headline)
    }

    @Test
    fun `html kacisi yapilir ve markdown donusturulur`() {
        val html = FindingsPresenter.textToHtml("<script>x</script> **kalın** `kod`\n```java\nif (a < b) {}\n```")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("<b>kalın</b>"))
        assertTrue(html.contains(">kod</code>"))
        assertTrue(html.contains("if (a &lt; b) {}</pre>"))
    }

    @Test
    fun `markdown kopyasi konum icerir`() {
        val md = FindingsPresenter.toMarkdown(f("./src/A.kt", "high", 12))
        assertTrue(md.startsWith("**Yüksek · Hata** — `src/A.kt:12 (yeni)`"))
    }
}
