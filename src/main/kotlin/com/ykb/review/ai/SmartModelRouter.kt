package com.ykb.review.ai

enum class ModelTier {
    FAST_TIER,       // Hızlı / Triage (Tier 1)
    COMPLEX_TIER     // Güçlü / Derin Analiz (Tier 2)
}

data class RoutingDecision(
    val tier: ModelTier,
    val modelName: String,
    val reason: String
)

object SmartModelRouter {

    /** Dosya yolunda geçtiğinde parçayı kritik sayan kelimeler. */
    private val CRITICAL_PATH_KEYWORDS = setOf(
        "security", "auth", "token", "password", "crypto", "ssl", "cert",
        "payment", "hesap", "bakiye", "transfer", "kredi", "transaction",
        "permission", "role", "oauth", "jwt", "secret"
    )

    /**
     * Yalnızca değişen (+/-) satırlarda aranan, daha dar kapsamlı içerik sinyalleri.
     * ("token", "role", "sql" gibi genel kelimeler neredeyse her kurumsal diff'te geçtiği için içerikte aranmaz.)
     */
    private val CRITICAL_CONTENT_PATTERNS = listOf(
        Regex("password|passwd|secret|private[_ ]?key|api[_-]?key", RegexOption.IGNORE_CASE),
        Regex("\\bjwt\\b|oauth|authoriz|authenticat|@PreAuthorize|@Secured", RegexOption.IGNORE_CASE),
        Regex("cipher|encrypt|decrypt|MessageDigest|SecureRandom|TrustManager|HostnameVerifier", RegexOption.IGNORE_CASE),
        Regex("createStatement|executeQuery\\s*\\(\\s*\"[^\"]*\"\\s*\\+|Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder", RegexOption.IGNORE_CASE),
    )

    private val CORE_BACKEND_EXTENSIONS = setOf(
        "java", "kt", "scala", "go", "sql", "py", "rs", "c", "cpp", "cs", "ts", "js"
    )

    /**
     * Diff içeriğini, dosya yollarını ve büyüklüğünü analiz ederek işi en uygun modele (Tier 1 vs Tier 2) yönlendirir.
     */
    fun decide(chunk: DiffChunk, fastModel: String, complexModel: String): RoutingDecision {
        if (fastModel.isBlank() || fastModel == complexModel) {
            return RoutingDecision(ModelTier.COMPLEX_TIER, complexModel, "Tek model yapılandırılmış")
        }
        val files = chunk.files.map { it.lowercase() }
        val changedLines = chunk.content.lineSequence()
            .filter { (it.startsWith("+") || it.startsWith("-")) && !it.startsWith("+++") && !it.startsWith("---") }
            .joinToString("\n")

        val criticalPath = files.any { path -> CRITICAL_PATH_KEYWORDS.any { path.contains(it) } }
        val criticalContent = CRITICAL_CONTENT_PATTERNS.any { it.containsMatchIn(changedLines) }
        if (criticalPath || criticalContent) {
            return RoutingDecision(
                ModelTier.COMPLEX_TIER, complexModel,
                "Güvenlik, yetkilendirme veya kritik iş mantığı tespit edildi (Kural: Tier 2 Güçlü Model)"
            )
        }

        val hasBackendCode = files.any { CORE_BACKEND_EXTENSIONS.contains(it.substringAfterLast('.', "")) }
        val changedLineCount = if (changedLines.isEmpty()) 0 else changedLines.count { it == '\n' } + 1
        if (hasBackendCode && changedLineCount > 150) {
            return RoutingDecision(
                ModelTier.COMPLEX_TIER, complexModel,
                "Geniş kapsamlı backend iş mantığı değişikliği ($changedLineCount satır) (Kural: Tier 2 Güçlü Model)"
            )
        }

        if (chunk.files.size >= 4) {
            return RoutingDecision(
                ModelTier.COMPLEX_TIER, complexModel,
                "Çoklu dosya çapraz bağımlılık analizi (Kural: Tier 2 Güçlü Model)"
            )
        }

        return RoutingDecision(
            ModelTier.FAST_TIER, fastModel,
            "Rutin / orta seviye değişiklik analizi (Kural: Tier 1 Hızlı Model)"
        )
    }
}
