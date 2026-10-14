package com.ykb.review.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartModelRouterTest {

    private fun chunk(files: List<String>, content: String) = DiffChunk(0, 1, files, content)

    @Test
    fun `guvenlik iceren yol guclu modele gider`() {
        val d = SmartModelRouter.decide(chunk(listOf("src/auth/LoginService.java"), "+int a = 1;"), "fast", "complex")
        assertEquals(ModelTier.COMPLEX_TIER, d.tier)
        assertEquals("complex", d.modelName)
        assertTrue(d.reason.contains("Güvenlik"))
    }

    @Test
    fun `genis backend degisikligi guclu modele gider`() {
        val body = (1..160).joinToString("\n") { "+int v$it = $it;" }
        val d = SmartModelRouter.decide(chunk(listOf("src/Calc.java"), body), "fast", "complex")
        assertEquals(ModelTier.COMPLEX_TIER, d.tier)
        assertTrue(d.reason.contains("Geniş kapsamlı backend"))
    }

    @Test
    fun `rutin degisiklik hizli modele gider`() {
        val d = SmartModelRouter.decide(chunk(listOf("README.md"), "+Yeni satır"), "fast", "complex")
        assertEquals(ModelTier.FAST_TIER, d.tier)
        assertEquals("fast", d.modelName)
        assertTrue(d.reason.contains("Rutin"))
    }

    @Test
    fun `tek model yapilandirildiysa o kullanilir`() {
        val d = SmartModelRouter.decide(chunk(listOf("README.md"), "+x"), "same", "same")
        assertEquals("same", d.modelName)
    }
}
