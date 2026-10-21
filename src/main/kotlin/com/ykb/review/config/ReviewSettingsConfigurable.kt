package com.ykb.review.config

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.ykb.review.review.AutomaticReviewService
import javax.swing.JComponent

/**
 * Uygulama seviyesinde ayar sayfası (Settings → Tools → YKB AI Code Review).
 * Ayarlar uygulama geneli olduğu için proje parametresi almaz; bildirimlerden proje olmadan da açılabilir.
 */
class ReviewSettingsConfigurable : Configurable {

    private var ui: ReviewSettingsComponent? = null

    override fun getDisplayName(): String = "YKB AI Code Review"

    override fun createComponent(): JComponent {
        val component = ReviewSettingsComponent()
        ui = component
        reset()
        return component.mainPanel
    }

    override fun isModified(): Boolean {
        val s = ReviewSettingsState.instance
        val ui = ui ?: return false
        return ui.bitbucketUrlField.text.trim() != s.bitbucketBaseUrl ||
            ui.typedBitbucketToken.isNotEmpty() ||
            ui.typedApiKey.isNotEmpty() ||
            ui.autoReviewEnabledCheck.isSelected != s.automaticReviewEnabled ||
            ui.interval != s.automaticReviewIntervalMinutes ||
            ui.aiBaseUrlField.text.trim() != s.aiBaseUrl ||
            ui.fastModel != s.fastModelName ||
            ui.complexModel != s.complexModelName ||
            ui.minSeverityCombo.selectedItem != s.minSeverity ||
            ui.maxChunkChars != s.maxChunkChars ||
            ui.extraInstructionsArea.text.trim() != s.extraInstructions ||
            ui.maskSecretsCheck.isSelected != s.maskSecretsInDiff ||
            ui.disableTlsCheck.isSelected != s.disableTlsVerification ||
            ui.publicationModeCombo.selectedItem != s.publicationMode ||
            ui.openDiffAfterReviewCheck.isSelected != s.openDiffAfterReview ||
            ui.showInlaysCheck.isSelected != s.showFindingInlays ||
            ui.repoInstructions() != s.repoInstructions.filterValues { it.isNotBlank() } ||
            ui.sharedRulesRepoField.text.trim() != s.sharedRulesRepo ||
            ui.sharedRulesBranchField.text.trim().ifEmpty { "master" } != s.sharedRulesBranch
    }

    override fun apply() {
        val s = ReviewSettingsState.instance
        val ui = ui ?: return

        val bitbucketUrl: String
        val aiUrl: String
        val fast: String
        val complex: String
        try {
            bitbucketUrl = ConfigValidation.bitbucketBaseUrl(ui.bitbucketUrlField.text)
            aiUrl = ConfigValidation.modelBaseUrl(ui.aiBaseUrlField.text)
            fast = ConfigValidation.modelName(ui.fastModel, "Hızlı model")
            complex = ConfigValidation.modelName(ui.complexModel, "Güçlü model")
            ConfigValidation.interval(ui.interval)
            val sharedRepo = ui.sharedRulesRepoField.text.trim()
            if (sharedRepo.isNotEmpty() && SharedRulesRepo.parse(sharedRepo, ui.sharedRulesBranchField.text) == null) {
                throw ConfigurationError("Ortak talimat reposu PROJE/slug biçiminde olmalıdır (ör. SDLC/ai-review-rules).")
            }
        } catch (e: ConfigurationError) {
            throw ConfigurationException(e.message, "YKB AI Code Review")
        }

        val tokenAvailable = ui.typedBitbucketToken.isNotEmpty() || s.bitbucketTokenStored || s.bitbucketToken.isNotBlank()
        if (ui.autoReviewEnabledCheck.isSelected && !tokenAvailable) {
            throw ConfigurationException("Auto PR Review için önce Bitbucket access token girilmelidir.", "YKB AI Code Review")
        }

        s.bitbucketBaseUrl = bitbucketUrl
        s.aiBaseUrl = aiUrl
        s.fastModelName = fast
        s.complexModelName = complex
        s.automaticReviewEnabled = ui.autoReviewEnabledCheck.isSelected
        s.automaticReviewIntervalMinutes = ui.interval
        s.minSeverity = (ui.minSeverityCombo.selectedItem as? String) ?: "low"
        s.maxChunkChars = ui.maxChunkChars
        s.extraInstructions = ui.extraInstructionsArea.text.trim()
        s.maskSecretsInDiff = ui.maskSecretsCheck.isSelected
        s.disableTlsVerification = ui.disableTlsCheck.isSelected
        s.publicationMode = (ui.publicationModeCombo.selectedItem as? String) ?: ReviewSettingsState.PUBLICATION_ASK
        s.openDiffAfterReview = ui.openDiffAfterReviewCheck.isSelected
        s.showFindingInlays = ui.showInlaysCheck.isSelected
        s.repoInstructions = ui.repoInstructions().toMutableMap()
        s.sharedRulesRepo = ui.sharedRulesRepoField.text.trim()
        s.sharedRulesBranch = ui.sharedRulesBranchField.text.trim().ifEmpty { "master" }
        SharedInstructions.instance.invalidate()

        // Gizli bilgiler yalnızca yeni değer yazıldıysa güncellenir.
        if (ui.typedBitbucketToken.isNotEmpty()) SecretStore.set(SecretStore.Key.BITBUCKET_TOKEN, ui.typedBitbucketToken)
        if (ui.typedApiKey.isNotEmpty()) SecretStore.set(SecretStore.Key.MODEL_API_KEY, ui.typedApiKey)
        ui.updateSecretHints(s)

        AutomaticReviewService.getInstance().restartTimer()
    }

    override fun reset() {
        val s = ReviewSettingsState.instance
        val ui = ui ?: return
        ui.bitbucketUrlField.text = s.bitbucketBaseUrl
        ui.aiBaseUrlField.text = s.aiBaseUrl
        ui.fastModelCombo.selectedItem = s.fastModelName
        ui.complexModelCombo.selectedItem = s.complexModelName
        ui.autoReviewEnabledCheck.isSelected = s.automaticReviewEnabled
        ui.autoReviewIntervalSpinner.value = s.automaticReviewIntervalMinutes.coerceIn(1, 1440)
        ui.minSeverityCombo.selectedItem = s.minSeverity
        ui.maxChunkCharsSpinner.value = s.maxChunkChars.coerceIn(5_000, 500_000)
        ui.extraInstructionsArea.text = s.extraInstructions
        ui.maskSecretsCheck.isSelected = s.maskSecretsInDiff
        ui.disableTlsCheck.isSelected = s.disableTlsVerification
        ui.publicationModeCombo.selectedItem = s.publicationMode
        ui.openDiffAfterReviewCheck.isSelected = s.openDiffAfterReview
        ui.showInlaysCheck.isSelected = s.showFindingInlays
        ui.setRepoInstructions(s.repoInstructions.filterValues { it.isNotBlank() })
        ui.sharedRulesRepoField.text = s.sharedRulesRepo
        ui.sharedRulesBranchField.text = s.sharedRulesBranch
        ui.updateSecretHints(s)
        ui.syncDependentState()
    }

    override fun disposeUIResources() {
        ui = null
    }
}
