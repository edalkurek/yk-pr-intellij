package com.ykb.review.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReviewParserTest {

    @Test
    fun `markdown bloklari icindeki JSON basariyla temizlenip parse edilmeli`() {
        val markdownJson = """
            ```json
            {
              "summary": "Kimlik doğrulama güncellemesi",
              "riskLevel": "high",
              "findings": [
                {
                  "file": "src/Auth.java",
                  "line": 42,
                  "side": "new",
                  "severity": "high",
                  "type": "security",
                  "message": "Token süresi kontrol edilmiyor",
                  "suggestion": "Expiration validator ekleyin"
                }
              ],
              "testSuggestions": ["Süresi dolmuş token senaryosu ekleyin"],
              "finalComment": "Kritik güvenlik açığı giderilmeli"
            }
            ```
        """.trimIndent()

        val parsed = ReviewParser.parse(markdownJson, "gpt-4o")

        assertEquals("high", parsed.riskLevel)
        assertEquals(1, parsed.findings.size)
        assertEquals("src/Auth.java", parsed.findings[0].file)
        assertEquals(42, parsed.findings[0].line)
        assertEquals("gpt-4o", parsed.modelUsed)
    }
}
