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

    // Bitbucket Entegrasyon Ayarları
    var bitbucketBaseUrl: String = "https://bitbucket.yapikredi.com.tr"
    var bitbucketToken: String = ""
    var defaultProjectKey: String = ""
    var defaultRepoSlug: String = ""

    // AI Servis Ayarları
    var aiBaseUrl: String = "https://api.openai.com/v1"
    var aiApiKey: String = ""
    
    // Model Katmanları (Tiered Engine)
    // Hızlı / Triage Modeli:
    var fastModelName: String = "gpt-4o-mini"
    // Güçlü / Karmaşık İş Mantığı & Güvenlik Modeli:
    var complexModelName: String = "claude-3-5-sonnet-20241022"
    
    // İnceleme Parametreleri
    var minSeverity: String = "low" // low, medium, high
    var maxChunkLines: Int = 450
    var extraInstructions: String = ""
    var autoPublishComments: Boolean = false
    var maskSecretsInDiff: Boolean = true

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
