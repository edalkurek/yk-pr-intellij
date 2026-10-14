package com.ykb.review.ai

object DiffFilter {

    private val IGNORED_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "ico", "svg", "webp", "pdf", "zip", "tar", "gz",
        "jar", "war", "class", "lock", "map", "woff", "woff2", "ttf", "eot"
    )

    private val IGNORED_FILENAMES = setOf(
        "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "composer.lock",
        "Cargo.lock", "Gemfile.lock", "gradle-wrapper.jar"
    )

    private val IGNORED_DIRECTORIES = listOf(
        "/dist/", "/build/", "/out/", "/target/", "/node_modules/", "/.idea/", "/.gradle/", "/coverage/"
    )

    private val SECRET_PATTERNS = listOf(
        Regex("""(?i)(bearer\s+)[A-Za-z0-9_\-\.]{20,}"""),
        Regex("""(?i)(api[_-]?key\s*[:=]\s*["']?)[A-Za-z0-9_\-]{20,}(["']?)"""),
        Regex("""(?i)(secret[_-]?key\s*[:=]\s*["']?)[A-Za-z0-9_\-]{20,}(["']?)"""),
        Regex("""(?i)(password\s*[:=]\s*["']?)[^"'\s]{8,}(["']?)""")
    )

    fun shouldIgnoreFile(filePath: String): Boolean {
        val normalized = filePath.replace("\\", "/").lowercase()
        val fileName = normalized.substringAfterLast('/')

        if (IGNORED_FILENAMES.contains(fileName)) return true

        val extension = fileName.substringAfterLast('.', "")
        if (IGNORED_EXTENSIONS.contains(extension)) return true

        if (IGNORED_DIRECTORIES.any { normalized.contains(it) }) return true

        return false
    }

    fun maskSecrets(diffContent: String): String {
        var masked = diffContent
        for (pattern in SECRET_PATTERNS) {
            masked = pattern.replace(masked) { matchResult ->
                val prefix = matchResult.groupValues[1]
                "$prefix***MASKED_SECRET***"
            }
        }
        return masked
    }
}
