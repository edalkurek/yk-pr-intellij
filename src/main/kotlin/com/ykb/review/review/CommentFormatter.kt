package com.ykb.review.review

import com.ykb.review.ai.ReviewConstants
import com.ykb.review.ai.ReviewFinding
import com.ykb.review.ai.ReviewResult
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap

/** VS Code commentFormatter.ts karşılığı (aynı başlıklar, etiketler ve marker biçimi). */
object CommentFormatter {

    private val RISK_LABELS = mapOf("low" to "Düşük", "medium" to "Orta", "high" to "Yüksek")
    private val RISK_ICONS = mapOf("low" to "🟢", "medium" to "🟡", "high" to "🔴")
    private val FINDING_LABELS = mapOf(
        "bug" to "Hata",
        "security" to "Güvenlik",
        "performance" to "Performans",
        "readability" to "Okunabilirlik",
        "test" to "Test",
        "architecture" to "Mimari",
    )

    private fun risk(level: String) = RISK_LABELS[level] ?: level
    private fun icon(level: String) = RISK_ICONS[level] ?: ""

    fun compactText(value: String, maxLength: Int): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        val codePoints = normalized.codePoints().toArray()
        if (codePoints.size <= maxLength) return normalized
        return String(codePoints, 0, maxLength - 1).trimEnd() + "…"
    }

    /** JavaScript encodeURIComponent ile aynı çıktı (marker'ların iki eklentide birebir aynı olması için). */
    private fun encodeURIComponent(value: String): String =
        URLEncoder.encode(value.trim(), StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~")

    /** Model bağımsız tekrar anahtarı: aynı commit, hangi IDE/model ile incelenmiş olursa olsun tanınır. */
    fun markerKey(prId: Long, commitHash: String): String =
        "bitbucket-ai-review:pr=${encodeURIComponent(prId.toString())}:commit=${encodeURIComponent(commitHash)}:"

    /**
     * Görünmez markdown yorumu olarak marker. VS Code'un `<!-- ... -->` biçimi Bitbucket Server'da metin olarak
     * görünebildiği için, VS Code'un 400 hatası sonrası kullandığı `[//]: # (...)` biçimi doğrudan kullanılır.
     */
    fun createMarker(prId: Long, commitHash: String, modelName: String): String =
        "[//]: # (${markerKey(prId, commitHash)}model=${encodeURIComponent(modelName)})"

    /** Bu PR + commit için daha önce atılmış bir AI review yorumunu tanır (yeni ve eski IntelliJ biçimleri). */
    fun matchesExistingReview(text: String, prId: Long, commitHash: String): Boolean =
        text.contains(markerKey(prId, commitHash)) ||
            text.contains("YKB_AI_REVIEW: prId=$prId commitHash=$commitHash")

    private fun inlineCode(value: String): String = "`" + compactText(value, 120).replace("`", "\\`") + "`"

    private fun formatFinding(f: ReviewFinding): String =
        "- **${risk(f.severity)} · ${f.type} · ${inlineCode(f.file)}:** ${compactText(f.message, 240)} Öneri: ${compactText(f.suggestion, 200)}"

    fun formatInlineComment(f: ReviewFinding): String =
        "### ${icon(f.severity)} ${FINDING_LABELS[f.type] ?: f.type} · ${risk(f.severity)} önem\n\n" +
            "**🔎 Sorun**\n\n${f.message}\n\n" +
            "**💡 Düzeltme önerisi**\n\n${f.suggestion}"

    fun newIdentitySet(): MutableSet<ReviewFinding> = Collections.newSetFromMap(IdentityHashMap())

    fun formatReviewComment(review: ReviewResult, inlineFindings: Set<ReviewFinding> = emptySet()): String {
        val findings = if (review.findings.isNotEmpty()) {
            review.findings.map { f ->
                if (inlineFindings.contains(f)) {
                    "- **${risk(f.severity)} · ${f.type} · ${inlineCode(f.file)}:${f.line} (${if (f.side == "old") "eski" else "yeni"}):** Sorun ve düzeltme önerisi satır içi yorumda."
                } else {
                    formatFinding(f)
                }
            }.distinct().joinToString("\n")
        } else {
            "- Ciddi veya doğrudan aksiyon gerektiren bir bulgu tespit edilmedi."
        }
        val tests = if (review.testSuggestions.isNotEmpty()) {
            review.testSuggestions.joinToString("\n") { "- ${compactText(it, 200)}" }
        } else {
            "- Ek bir test önerisi bulunmuyor."
        }
        return """## 🤖 AI PR Review

**Risk seviyesi:** ${icon(review.riskLevel)} ${risk(review.riskLevel)}

### PR özeti
${compactText(review.summary, ReviewConstants.PR_SUMMARY_LIMIT)}

### 🔎 Öne çıkan bulgular
$findings

### 🧪 Test önerileri
$tests

### 📝 Genel değerlendirme
${compactText(review.finalComment.ifBlank { review.summary }, ReviewConstants.FINAL_COMMENT_LIMIT)}"""
    }

    /** Emoji desteklemeyen (utf8mb4 olmayan) Bitbucket veritabanları için güvenli metin. */
    fun sanitizeCommentForServer(text: String): String = text
        .replace(Regex("<!--\\s*(bitbucket-ai-review:[^>]+?)\\s*-->"), "[//]: # ($1)")
        .replace("🤖", "[AI]")
        .replace("🟢", "[Düşük]")
        .replace("🟡", "[Orta]")
        .replace("🔴", "[Yüksek]")
        .replace("🔎", "•")
        .replace("🧪", "•")
        .replace("📝", "•")
        .replace("💡", "•")
        .replace(Regex("[\\x{10000}-\\x{10FFFF}]"), "")
}
