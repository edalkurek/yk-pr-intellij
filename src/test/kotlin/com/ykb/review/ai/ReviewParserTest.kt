package com.ykb.review.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ReviewParserTest {

    private val valid = """
        Açıklama metni
        ```json
        {
          "summary": "Özet",
          "riskLevel": "medium",
          "findings": [
            {"file": "src/A.kt", "line": 12, "side": "new", "severity": "high", "type": "bug",
             "message": "Null kontrolü yok {", "suggestion": "Kontrol ekleyin"},
            {"file": "src/B.kt", "line": "7", "side": "FROM", "severity": "low", "type": "readability",
             "message": "İsim belirsiz", "suggestion": "Yeniden adlandırın"}
          ],
          "testSuggestions": ["Null girdi testi"],
          "finalComment": "Genel olarak iyi"
        }
        ```
    """.trimIndent()

    @Test
    fun `gecerli yanit kod blogu icinden ayristirilir`() {
        val result = ReviewParser.parse(valid)
        assertEquals("medium", result.riskLevel)
        assertEquals(2, result.findings.size)
        assertEquals(12, result.findings[0].line)
        assertEquals("new", result.findings[0].side)
        assertEquals(7, result.findings[1].line)
        assertEquals("old", result.findings[1].side)
        assertEquals(listOf("Null girdi testi"), result.testSuggestions)
    }

    @Test
    fun `suggestion zorunludur`() {
        val broken = valid.replace(", \"suggestion\": \"Kontrol ekleyin\"", "")
        assertThrows<ReviewParseException> { ReviewParser.parse(broken) }
    }

    @Test
    fun `gecersiz risk seviyesi reddedilir`() {
        assertThrows<ReviewParseException> { ReviewParser.parse(valid.replace("\"medium\"", "\"critical\"")) }
    }

    @Test
    fun `json olmayan yanit reddedilir`() {
        assertThrows<ReviewParseException> { ReviewParser.parse("Maalesef yardımcı olamam.") }
    }

    @Test
    fun `gecersiz satir bilgisi yok sayilir`() {
        val result = ReviewParser.parse(valid.replace("\"line\": 12", "\"line\": -3"))
        assertNull(result.findings[0].line)
        assertNull(result.findings[0].side)
    }
}
