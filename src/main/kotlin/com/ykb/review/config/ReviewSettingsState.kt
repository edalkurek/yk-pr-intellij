package com.ykb.review.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "com.ykb.review.config.ReviewSettingsState",
    storages = [Storage("YkbAiReviewSettings.xml")]
)
class ReviewSettingsState : PersistentStateComponent<ReviewSettingsState> {

    // Bitbucket Entegrasyon Ayarları (YKB Varsayılanları)
    var bitbucketBaseUrl: String = "https://sdlc.yapikredi.com.tr/bitbucket"
    var bitbucketToken: String = ""
    var defaultProjectKey: String = ""
    var defaultRepoSlug: String = ""

    // AI Servis Ayarları (YKB Bifrost Gateway & Yerel Modeller)
    var aiBaseUrl: String = "https://bifrost-proxy-server-dsp-ddi.apps.lan-dev-dc1-ocp.yapikredi.com.tr/v1"
    var aiApiKey: String = ""
    
    // Model Katmanları (Tiered Engine)
    // Hızlı / Triage Modeli:
    var fastModelName: String = "cyankiwi/Qwen3.6-35B-A3B-AWQ-4bit"
    // Güçlü / Derin Analiz Modeli:
    var complexModelName: String = "openai/gpt-oss-120b"
    
    // Otomatik Arka Plan Review (Batch / Scheduler)
    var automaticReviewEnabled: Boolean = false
    var automaticReviewIntervalMinutes: Int = 5

    // İnceleme Parametreleri & Repo Bazlı Talimatlar
    var minSeverity: String = "low" // low, medium, high
    var maxChunkLines: Int = 450
    var extraInstructions: String = ""
    var repoInstructions: MutableMap<String, String> = mutableMapOf()
    
    // Zaten incelenen PR ve commit'lerin kaydı (Tekrar yorum atmamak için)
    var lastReviewedCommits: MutableMap<String, String> = mutableMapOf()

    var autoPublishComments: Boolean = true
    var maskSecretsInDiff: Boolean = true

    fun getEffectiveInstructions(repoSlug: String): String {
        val repoSpecific = repoInstructions[repoSlug]?.trim() ?: ""
        val global = extraInstructions.trim()
        return when {
            repoSpecific.isNotBlank() && global.isNotBlank() -> "$global\n\n[Repo Özel Talimatı - $repoSlug]:\n$repoSpecific"
            repoSpecific.isNotBlank() -> "[Repo Özel Talimatı - $repoSlug]:\n$repoSpecific"
            else -> global
        }
    }

    override fun getState(): ReviewSettingsState {
        return this
    }

    override fun loadState(state: ReviewSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        val instance: ReviewSettingsState
            get() = ApplicationManager.getApplication().getService(ReviewSettingsState::class.java)
    }
}
