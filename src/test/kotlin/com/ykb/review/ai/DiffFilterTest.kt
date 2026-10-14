package com.ykb.review.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiffFilterTest {

    private val textSection = """
        diff --git a/src/App.java b/src/App.java
        --- a/src/App.java
        +++ b/src/App.java
        @@ -1,1 +1,2 @@
         class App {}
        +// yeni
    """.trimIndent() + "\n"

    private val binarySection = """
        diff --git a/img/logo.png b/img/logo.png
        Binary files a/img/logo.png and b/img/logo.png differ
    """.trimIndent() + "\n"

    @Test
    fun `yalnizca binary bolumler elenir`() {
        val result = DiffFilter.filterDiff(textSection + binarySection)
        assertTrue(result.filteredDiff.contains("src/App.java"))
        assertFalse(result.filteredDiff.contains("logo.png"))
        assertEquals(listOf("img/logo.png"), result.excludedFiles)
    }

    @Test
    fun `uzantiya gore filtreleme yapilmaz`() {
        val lockFile = textSection.replace("src/App.java", "package-lock.json")
        assertEquals(lockFile, DiffFilter.filterDiff(lockFile).filteredDiff)
    }

    @Test
    fun `gizli degerler maskelenir`() {
        val masked = DiffFilter.maskSecrets("+password = \"SuperGizli123\"\n+Authorization: Bearer abcdefghijklmnopqrstuvwxyz123456")
        assertFalse(masked.contains("SuperGizli123"))
        assertFalse(masked.contains("abcdefghijklmnopqrstuvwxyz123456"))
        assertTrue(masked.contains("***MASKED_SECRET***"))
    }
}
