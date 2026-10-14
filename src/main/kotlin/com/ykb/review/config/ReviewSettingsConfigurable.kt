package com.ykb.review.config

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class ReviewSettingsConfigurable : Configurable {

    private var settingsComponent: ReviewSettingsComponent? = null

    override fun getDisplayName(): String = "YKB AI Code Review"

    override fun createComponent(): JComponent {
        val component = ReviewSettingsComponent()
        settingsComponent = component
        reset()
        return component.mainPanel
    }

    override fun isModified(): Boolean {
        val state = ReviewSettingsState.instance
        val ui = settingsComponent ?: return false

        return ui.bitbucketUrlField.text != state.bitbucketBaseUrl ||
                String(ui.bitbucketTokenField.password) != state.bitbucketToken ||
                ui.projectKeyField.text != state.defaultProjectKey ||
                ui.repoSlugField.text != state.defaultRepoSlug ||
                ui.aiBaseUrlField.text != state.aiBaseUrl ||
                String(ui.aiApiKeyField.password) != state.aiApiKey ||
                ui.fastModelField.text != state.fastModelName ||
                ui.complexModelField.text != state.complexModelName ||
                ui.minSeverityCombo.selectedItem != state.minSeverity ||
                ui.maxChunkLinesField.text != state.maxChunkLines.toString() ||
                ui.extraInstructionsArea.text != state.extraInstructions ||
                ui.autoPublishCheck.isSelected != state.autoPublishComments ||
                ui.maskSecretsCheck.isSelected != state.maskSecretsInDiff
    }

    override fun apply() {
        val state = ReviewSettingsState.instance
        val ui = settingsComponent ?: return

        state.bitbucketBaseUrl = ui.bitbucketUrlField.text.trim()
        state.bitbucketToken = String(ui.bitbucketTokenField.password).trim()
        state.defaultProjectKey = ui.projectKeyField.text.trim()
        state.defaultRepoSlug = ui.repoSlugField.text.trim()

        state.aiBaseUrl = ui.aiBaseUrlField.text.trim()
        state.aiApiKey = String(ui.aiApiKeyField.password).trim()
        state.fastModelName = ui.fastModelField.text.trim()
        state.complexModelName = ui.complexModelField.text.trim()

        state.minSeverity = (ui.minSeverityCombo.selectedItem as? String) ?: "low"
        state.maxChunkLines = ui.maxChunkLinesField.text.toIntOrNull() ?: 450
        state.extraInstructions = ui.extraInstructionsArea.text.trim()
        state.autoPublishComments = ui.autoPublishCheck.isSelected
        state.maskSecretsInDiff = ui.maskSecretsCheck.isSelected
    }

    override fun reset() {
        val state = ReviewSettingsState.instance
        val ui = settingsComponent ?: return

        ui.bitbucketUrlField.text = state.bitbucketBaseUrl
        ui.bitbucketTokenField.text = state.bitbucketToken
        ui.projectKeyField.text = state.defaultProjectKey
        ui.repoSlugField.text = state.defaultRepoSlug

        ui.aiBaseUrlField.text = state.aiBaseUrl
        ui.aiApiKeyField.text = state.aiApiKey
        ui.fastModelField.text = state.fastModelName
        ui.complexModelField.text = state.complexModelName

        ui.minSeverityCombo.selectedItem = state.minSeverity
        ui.maxChunkLinesField.text = state.maxChunkLines.toString()
        ui.extraInstructionsArea.text = state.extraInstructions
        ui.autoPublishCheck.isSelected = state.autoPublishComments
        ui.maskSecretsCheck.isSelected = state.maskSecretsInDiff
    }

    override fun disposeUIResources() {
        settingsComponent = null
    }
}
