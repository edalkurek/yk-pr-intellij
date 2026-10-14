package com.ykb.review.review

import com.ykb.review.ai.ReviewFinding
import com.ykb.review.ai.ReviewResult

object CommentFormatter {

    fun formatGeneralComment(result: ReviewResult): String {
        val riskEmoji = when (result.riskLevel.lowercase()) {
            "high" -> "🔴 **YÜKSEK RİSK**"
            "medium" -> "🟡 **ORTA RİSK**"
            else -> "🟢 **DÜŞÜK RİSK**"
        }

        val sb = StringBuilder()
        sb.append("### 🤖 YKB Bitbucket AI Code Review Özeti\n\n")
        sb.append("**Genel Risk Durumu:** $riskEmoji\n")
        if (!result.modelUsed.isNullOrBlank()) {
            sb.append("**Kullanılan Model Mimarisi:** `${result.modelUsed}`\n\n")
        }

        sb.append("#### 📋 Özet Değerlendirme\n")
        sb.append("${result.summary}\n\n")

        if (result.findings.isNotEmpty()) {
            sb.append("#### ⚠️ Tespit Edilen Bulgular (${result.findings.size})\n\n")
            sb.append("| Seviye | Tür | Dosya | Satır | Açıklama |\n")
            sb.append("| :--- | :--- | :--- | :--- | :--- |\n")
            for (f in result.findings) {
                val sevIcon = when (f.severity.lowercase()) {
                    "high" -> "🔴 High"
                    "medium" -> "🟡 Med"
                    else -> "ℹ️ Low"
                }
                val lineStr = f.line?.toString() ?: "-"
                val safeMsg = f.message.replace("\n", " ").replace("|", "\\|")
                sb.append("| $sevIcon | `${f.type}` | `${f.file}` | $lineStr | $safeMsg |\n")
            }
            sb.append("\n")
        } else {
            sb.append("✅ *Herhangi bir kritik veya aksiyon gerektiren bulgu tespit edilmedi.*\n\n")
        }

        if (result.testSuggestions.isNotEmpty()) {
            sb.append("#### 🧪 Test Senaryosu Önerileri\n")
            for (t in result.testSuggestions) {
                sb.append("- $t\n")
            }
            sb.append("\n")
        }

        if (result.finalComment.isNotBlank()) {
            sb.append("---\n*${result.finalComment}*\n")
        }

        return sb.toString()
    }

    fun formatInlineComment(finding: ReviewFinding): String {
        val sevBadge = when (finding.severity.lowercase()) {
            "high" -> "🔴 [HIGH - ${finding.type.uppercase()}]"
            "medium" -> "🟡 [MEDIUM - ${finding.type.uppercase()}]"
            else -> "ℹ️ [LOW - ${finding.type.uppercase()}]"
        }

        val sb = StringBuilder()
        sb.append("🤖 **YKB AI Review:** $sevBadge\n\n")
        sb.append("${finding.message}\n\n")

        if (!finding.suggestion.isNullOrBlank()) {
            sb.append("**💡 Öneri:**\n")
            sb.append("```\n${finding.suggestion}\n```\n")
        }
        return sb.toString()
    }
}
