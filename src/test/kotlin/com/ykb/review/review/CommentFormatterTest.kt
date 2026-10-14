package com.ykb.review.review

import com.ykb.review.ai.ReviewFinding
import com.ykb.review.ai.ReviewResult
import com.ykb.review.bitbucket.PullRequestUrlParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CommentFormatterTest {

    @Test
    fun `marker model bagimsiz olarak eslesir`() {
        val marker = CommentFormatter.createMarker(42, "abc123", "openai/gpt-oss-120b")
        assertEquals("[//]: # (bitbucket-ai-review:pr=42:commit=abc123:model=openai%2Fgpt-oss-120b)", marker)
        assertTrue(CommentFormatter.matchesExistingReview("yorum\n\n$marker", 42, "abc123"))
        assertFalse(CommentFormatter.matchesExistingReview(marker, 42, "def456"))
        assertTrue(CommentFormatter.matchesExistingReview("YKB_AI_REVIEW: prId=42 commitHash=abc123", 42, "abc123"))
    }

    @Test
    fun `sunucu icin emoji temizlenir`() {
        val review = ReviewResult(
            summary = "Özet",
            riskLevel = "high",
            findings = listOf(ReviewFinding("a.kt", 1, "new", "high", "bug", "Sorun", "Öneri")),
            finalComment = "Son",
        )
        val sanitized = CommentFormatter.sanitizeCommentForServer(CommentFormatter.formatReviewComment(review))
        assertTrue(sanitized.contains("[AI] AI PR Review"))
        assertTrue(sanitized.contains("[Yüksek]"))
        assertFalse(sanitized.contains("🤖"))
    }

    @Test
    fun `pr url ayristirilir`() {
        val ref = PullRequestUrlParser.parse("https://sdlc.yapikredi.com.tr/bitbucket/projects/PROJ/repos/my-repo/pull-requests/123/overview")
        assertEquals("https://sdlc.yapikredi.com.tr/bitbucket", ref.baseUrl)
        assertEquals("PROJ", ref.projectKey)
        assertEquals("my-repo", ref.repositorySlug)
        assertEquals(123L, ref.pullRequestId)
    }

    @Test
    fun `bitbucket cloud url reddedilir`() {
        assertThrows<IllegalArgumentException> {
            PullRequestUrlParser.parse("https://bitbucket.org/team/repo/pull-requests/1")
        }
    }
}
