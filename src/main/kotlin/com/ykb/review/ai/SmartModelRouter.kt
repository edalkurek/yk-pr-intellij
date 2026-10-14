package com.ykb.review.ai

import com.ykb.review.config.ReviewSettingsState

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

    private val CRITICAL_KEYWORDS = setOf(
        "security", "auth", "token", "password", "crypto", "ssl", "cert",
        "payment", "hesap", "bakiye", "transfer", "kredi", "transaction",
        "sql", "injection", "permission", "role", "oauth", "jwt", "secret"
    )

    private val CORE_BACKEND_EXTENSIONS = setOf(
        "java", "kt", "scala", "go", "sql", "py", "rs", "c", "cpp"
    )

    /**
     * Diff içeriğini, dosya yollarını ve büyüklüğünü analiz ederek
     * işi en uygun modele (Tier 1 vs Tier 2) yönlendirir.
     */
    fun decide(chunk: DiffChunk): RoutingDecision {
        val state = ReviewSettingsState.instance
        val files = chunk.fileNames.map { it.lowercase() }

        // 1. Kritik anahtar kelimeler içeriyor mu? (Güvenlik / Finans / Auth)
        val hasCriticalKeyword = files.any { path ->
            CRITICAL_KEYWORDS.any { keyword -> path.contains(keyword) }
        } || CRITICAL_KEYWORDS.any { keyword -> chunk.content.contains(keyword, ignoreCase = true) }

        if (hasCriticalKeyword) {
            return RoutingDecision(
                tier = ModelTier.COMPLEX_TIER,
                modelName = state.complexModelName,
                reason = "Güvenlik, yetkilendirme veya kritik iş mantığı tespit edildi (Kural: Tier 2 Güçlü Model)"
            )
        }

        // 2. Çekirdek backend kodu ve yüksek diff satırı var mı?
        val hasBackendCode = files.any { path ->
            val ext = path.substringAfterLast('.', "")
            CORE_BACKEND_EXTENSIONS.contains(ext)
        }

        if (hasBackendCode && chunk.lineCount > 150) {
            return RoutingDecision(
                tier = ModelTier.COMPLEX_TIER,
                modelName = state.complexModelName,
                reason = "Geniş kapsamlı backend iş mantığı değişikliği (${chunk.lineCount} satır) (Kural: Tier 2 Güçlü Model)"
            )
        }

        // 3. Çoklu dosya etkileşimi yüksek karmaşıklıkta mı?
        if (chunk.fileNames.size >= 4) {
            return RoutingDecision(
                tier = ModelTier.COMPLEX_TIER,
                modelName = state.complexModelName,
                reason = "Çoklu dosya çapraz bağımlılık analizi (Kural: Tier 2 Güçlü Model)"
            )
        }

        // 4. Standart / rutin değişiklikler -> Tier 1 Hızlı Model
        return RoutingDecision(
            tier = ModelTier.FAST_TIER,
            modelName = state.fastModelName,
            reason = "Rutin / orta seviye değişiklik analizi (Kural: Tier 1 Hızlı Model)"
        )
    }
}
