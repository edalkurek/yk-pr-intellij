package com.ykb.review.review

import com.ykb.review.ai.ReviewConstants
import com.ykb.review.ai.ReviewFinding

/** Bulguları panelde göstermek için gruplama, sıralama ve metin biçimlendirme (UI bağımsız). */
object FindingsPresenter {

    val SEVERITY_LABELS = mapOf("high" to "Yüksek", "medium" to "Orta", "low" to "Düşük")
    val TYPE_LABELS = mapOf(
        "bug" to "Hata",
        "security" to "Güvenlik",
        "performance" to "Performans",
        "readability" to "Okunabilirlik",
        "test" to "Test",
        "architecture" to "Mimari",
    )

    fun severityLabel(severity: String) = SEVERITY_LABELS[severity] ?: severity
    fun typeLabel(type: String) = TYPE_LABELS[type] ?: type

    data class FileGroup(val path: String, val findings: List<ReviewFinding>) {
        val fileName: String get() = path.substringAfterLast('/')
        val directory: String get() = path.substringBeforeLast('/', "")
        val maxSeverity: Int get() = findings.maxOfOrNull { ReviewConstants.riskScore(it.severity) } ?: 0
    }

    fun normalizePath(path: String): String = path.replace('\\', '/').removePrefix("./").replace(Regex("^[ab]/"), "")

    /** Dosyaya göre gruplar; önce en yüksek önemli dosyalar, dosya içinde önem (azalan) ve satır (artan) sırası. */
    fun group(findings: List<ReviewFinding>): List<FileGroup> =
        findings.groupBy { normalizePath(it.file) }
            .map { (path, items) ->
                FileGroup(
                    path,
                    items.sortedWith(compareByDescending<ReviewFinding> { ReviewConstants.riskScore(it.severity) }.thenBy { it.line ?: Int.MAX_VALUE }),
                )
            }
            .sortedWith(compareByDescending<FileGroup> { it.maxSeverity }.thenByDescending { it.findings.size }.thenBy { it.path })

    fun filter(findings: List<ReviewFinding>, severities: Set<String>, type: String?): List<ReviewFinding> =
        findings.filter { it.severity in severities && (type == null || it.type == type) }

    /** Bulgular ağacının gruplama kipi. */
    enum class GroupBy(val label: String) { FILE("Dosya"), SEVERITY("Önem"), TYPE("Tür") }

    /** Dosya dışı gruplama: anahtar = önem veya tür; grup içinde dosya ve satıra göre sıralı. */
    data class KeyGroup(val key: String, val findings: List<ReviewFinding>)

    private val byFileAndLine = compareBy<ReviewFinding>({ normalizePath(it.file) }, { it.line ?: Int.MAX_VALUE })

    fun groupBySeverity(findings: List<ReviewFinding>): List<KeyGroup> =
        findings.groupBy { it.severity }
            .map { (severity, items) -> KeyGroup(severity, items.sortedWith(byFileAndLine)) }
            .sortedByDescending { ReviewConstants.riskScore(it.key) }

    fun groupByType(findings: List<ReviewFinding>): List<KeyGroup> =
        findings.groupBy { it.type }
            .map { (type, items) ->
                KeyGroup(type, items.sortedWith(compareByDescending<ReviewFinding> { ReviewConstants.riskScore(it.severity) }.then(byFileAndLine)))
            }
            .sortedBy { ReviewConstants.FINDING_TYPES.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }

    /** high → medium → low sırasıyla sayılar (sıfırlar dahil). */
    fun severityCounts(findings: List<ReviewFinding>): LinkedHashMap<String, Int> =
        linkedMapOf("high" to 0, "medium" to 0, "low" to 0).apply {
            findings.forEach { f -> this[f.severity] = (this[f.severity] ?: 0) + 1 }
        }

    /** "az önce", "5 dk önce", "3 saat önce", "2 gün önce" ... */
    fun relativeTime(epochMillis: Long?, now: Long = System.currentTimeMillis()): String {
        if (epochMillis == null || epochMillis <= 0) return ""
        val minutes = ((now - epochMillis) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "az önce"
            minutes < 60 -> "$minutes dk önce"
            minutes < 60 * 24 -> "${minutes / 60} saat önce"
            minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)} gün önce"
            minutes < 60 * 24 * 365 -> "${minutes / (60 * 24 * 30)} ay önce"
            else -> "${minutes / (60 * 24 * 365)} yıl önce"
        }
    }

    /** Birden fazla bulguyu tek Markdown belgesi olarak biçimlendirir. */
    fun toMarkdown(findings: List<ReviewFinding>, title: String? = null): String = buildString {
        if (!title.isNullOrBlank()) append("### ").append(title).append("\n\n")
        findings.forEachIndexed { i, f ->
            if (i > 0) append("\n\n---\n\n")
            append(toMarkdown(f))
        }
    }

    /** Ağaçta tek satırlık özet için ilk cümle / satır. */
    fun headline(message: String, maxLength: Int = 140): String {
        val firstLine = message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("```") }.orEmpty()
        val sentenceEnd = Regex("(?<=[.!?])\\s").find(firstLine)?.range?.first
        val sentence = if (sentenceEnd != null && sentenceEnd in 20..maxLength) firstLine.substring(0, sentenceEnd) else firstLine
        val plain = sentence.replace("`", "").replace("**", "")
        return if (plain.length <= maxLength) plain else plain.take(maxLength - 1).trimEnd() + "…"
    }

    fun location(f: ReviewFinding): String = buildString {
        append(normalizePath(f.file))
        f.line?.let { append(':').append(it) }
        if (f.line != null && f.side != null) append(if (f.side == "old") " (eski)" else " (yeni)")
    }

    /** Bulguyu Bitbucket / sohbet için Markdown olarak biçimlendirir. */
    fun toMarkdown(f: ReviewFinding): String =
        "**${severityLabel(f.severity)} · ${typeLabel(f.type)}** — `${location(f)}`\n\n" +
            "**Sorun:** ${f.message}\n\n**Düzeltme önerisi:** ${f.suggestion}"

    /**
     * Model metnindeki basit Markdown'ı (kod bloğu, satır içi kod, kalın) güvenli HTML'e çevirir.
     * Önce HTML kaçışı yapılır; yalnızca bilinen kalıplar etikete dönüştürülür.
     */
    fun textToHtml(text: String, codeStyle: String = ""): String {
        val out = StringBuilder()
        val fence = Regex("```[^\\n]*\\n?([\\s\\S]*?)```")
        var cursor = 0
        for (match in fence.findAll(text)) {
            out.append(inlineToHtml(text.substring(cursor, match.range.first), codeStyle))
            out.append("<pre style='$codeStyle'>").append(escape(match.groupValues[1].trimEnd())).append("</pre>")
            cursor = match.range.last + 1
        }
        out.append(inlineToHtml(text.substring(cursor), codeStyle))
        return out.toString()
    }

    private fun inlineToHtml(text: String, codeStyle: String): String {
        if (text.isEmpty()) return ""
        var html = escape(text.trim('\n'))
        html = html.replace(Regex("`([^`\\n]+)`")) { "<code style='$codeStyle'>${it.groupValues[1]}</code>" }
        html = html.replace(Regex("\\*\\*([^*\\n]+)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
        return html.replace("\n", "<br>")
    }

    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
