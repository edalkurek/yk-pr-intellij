package com.ykb.review.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SmartModelRouterTest {

    @Test
    fun `guvenlik ve auth iceren diff parcalari Guclu Modele yonlendirilmeli`() {
        val chunk = DiffChunk(
            index = 1,
            totalChunks = 1,
            fileNames = listOf("src/main/com/ykb/auth/TokenService.java"),
            content = "public String generateToken() { String secret = 'test'; }",
            lineCount = 20,
            isHighComplexity = true
        )

        val decision = SmartModelRouter.decide(chunk)

        assertEquals(ModelTier.COMPLEX_TIER, decision.tier)
        assertTrue(decision.reason.contains("Güvenlik"))
    }

    @Test
    fun `yuksek satir sayili backend dosyalari Guclu Modele yonlendirilmeli`() {
        val chunk = DiffChunk(
            index = 1,
            totalChunks = 1,
            fileNames = listOf("src/main/com/ykb/service/PaymentProcessor.kt"),
            content = "val x = 1\n".repeat(180),
            lineCount = 180,
            isHighComplexity = true
        )

        val decision = SmartModelRouter.decide(chunk)

        assertEquals(ModelTier.COMPLEX_TIER, decision.tier)
        assertTrue(decision.reason.contains("Geniş kapsamlı backend"))
    }

    @Test
    fun `kucuk ve rutin degisiklikler Hizli Modele yonlendirilmeli`() {
        val chunk = DiffChunk(
            index = 1,
            totalChunks = 1,
            fileNames = listOf("src/main/resources/application.properties"),
            content = "app.version=1.0.1",
            lineCount = 5,
            isHighComplexity = false
        )

        val decision = SmartModelRouter.decide(chunk)

        assertEquals(ModelTier.FAST_TIER, decision.tier)
        assertTrue(decision.reason.contains("Rutin"))
    }
}
