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

    @Test
    fun `Bitbucket Server src ve dst onekleri yoldan atilir`() {
        val serverDiff = """
            diff --git src://dml/T1/a.sql dst://dml/T1/a.sql
            new file mode 100644
            --- /dev/null
            +++ dst://dml/T1/a.sql
            @@ -0,0 +1,2 @@
            +INSERT INTO X VALUES (1);
            +INSERT INTO X VALUES (2);
        """.trimIndent()
        val locations = DiffLocations.diffLineLocations(serverDiff)
        assertEquals(listOf("dml/T1/a.sql"), locations.map { it.path }.distinct())
        assertNull(locations.first().srcPath)
        val finding = ReviewFinding("dst://dml/T1/a.sql", 2, "new", "low", "bug", "m", "s")
        assertEquals(2, DiffLocations.findInlineLocation(finding, locations)?.line)
        assertEquals("dml/T1/a.sql", DiffSections.fileFromHeader(serverDiff))
    }
}
