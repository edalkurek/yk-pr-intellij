package com.ykb.review.ai

object DiffFilter {

    private val BINARY_PATTERNS = listOf(
        Regex("Binary files .* differ", RegexOption.IGNORE_CASE),
        Regex("GIT binary patch", RegexOption.IGNORE_CASE),
    )

    private val SECRET_PATTERNS = listOf(
        Regex("""(?i)(bearer\s+)[A-Za-z0-9_\-\.]{20,}"""),
        Regex("""(?i)(api[_-]?key\s*[:=]\s*["']?)[A-Za-z0-9_\-]{20,}(["']?)"""),
        Regex("""(?i)(secret[_-]?key\s*[:=]\s*["']?)[A-Za-z0-9_\-]{20,}(["']?)"""),
        Regex("""(?i)(password\s*[:=]\s*["']?)[^"'\s]{8,}(["']?)"""),
    )

    data class FilterResult(val filteredDiff: String, val excludedFiles: List<String>)

    fun isBinarySection(content: String): Boolean = BINARY_PATTERNS.any { it.containsMatchIn(content) }

    /**
     * VS Code diffFilter.ts ile aynı davranış: yalnızca ikili (binary) diff bölümleri elenir;
     * dosya adı veya uzantısına göre hariç tutma yapılmaz.
     */
    fun filterDiff(diff: String): FilterResult {
        val starts = DiffSections.starts(diff)
        if (starts.isEmpty()) {
            return if (isBinarySection(diff)) FilterResult("", emptyList()) else FilterResult(diff, emptyList())
        }
        val kept = StringBuilder()
        val excluded = linkedSetOf<String>()
        if (starts[0] > 0) {
            val preamble = diff.substring(0, starts[0])
            if (preamble.isNotBlank()) kept.append(preamble)
        }
        starts.forEachIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: diff.length
            val section = diff.substring(start, end)
            if (isBinarySection(section)) {
                DiffSections.fileFromHeader(section)?.let { excluded.add(it) }
            } else {
                kept.append(section)
            }
        }
        return FilterResult(kept.toString(), excluded.toList())
    }

    /** Diff içindeki şifre/token benzeri değerleri maskeler (satır yapısını değiştirmez). */
    fun maskSecrets(diffContent: String): String {
        var masked = diffContent
        for (pattern in SECRET_PATTERNS) {
            masked = pattern.replace(masked) { match ->
                val prefix = match.groupValues[1]
                val suffix = match.groupValues.getOrNull(2).orEmpty()
                "$prefix***MASKED_SECRET***$suffix"
            }
        }
        return masked
    }
}

internal object DiffSections {
    private val START = Regex("^diff --git ", RegexOption.MULTILINE)
    private val HEADER = Regex("^diff --git a/(.+?) b/(.+)$", RegexOption.MULTILINE)

    fun starts(diff: String): List<Int> = START.findAll(diff).map { it.range.first }.toList()

    fun fileFromHeader(section: String): String? = HEADER.find(section)?.groupValues?.get(2)?.trimEnd('\r')
}
