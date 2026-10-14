package com.ykb.review.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiffLocationsTest {

    private val diff = """
        diff --git a/src/A.kt b/src/A.kt
        --- a/src/A.kt
        +++ b/src/A.kt
        @@ -10,3 +10,3 @@
         val a = 1
        -val b = 2
        +val b = 3
         val c = 4
    """.trimIndent()

    @Test
    fun `degisen satirlarin koordinatlari dogru hesaplanir`() {
        val changed = DiffLocations.changedLineLocations(diff)
        assertEquals(2, changed.size)
        val removed = changed.first { it.lineType == "REMOVED" }
        assertEquals(11, removed.line)
        assertEquals("old", removed.side)
        val added = changed.first { it.lineType == "ADDED" }
        assertEquals(11, added.line)
        assertEquals("new", added.side)
        assertEquals("src/A.kt", added.path)
    }

    @Test
    fun `bulgu diffteki konuma eslenir`() {
        val locations = DiffLocations.diffLineLocations(diff)
        val finding = ReviewFinding("src/A.kt", 11, "new", "high", "bug", "m", "s")
        val location = DiffLocations.findInlineLocation(finding, locations)
        assertNotNull(location)
        assertEquals("ADDED", location!!.lineType)
    }

    @Test
    fun `taraf belirtilmezse belirsiz konum eslenmez`() {
        val locations = DiffLocations.diffLineLocations(diff)
        val finding = ReviewFinding("src/A.kt", 11, null, "high", "bug", "m", "s")
        assertNull(DiffLocations.findInlineLocation(finding, locations))
    }

    @Test
    fun `annotate satirlara koordinat ekler`() {
        val annotated = DiffLocations.annotateDiffLines(diff)
        assertTrue(annotated.contains("+[review-location {\"file\":\"src/A.kt\",\"line\":11,\"side\":\"new\"}] val b = 3"))
    }
}
