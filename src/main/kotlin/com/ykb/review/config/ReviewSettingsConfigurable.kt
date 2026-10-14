package com.ykb.review.config

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.ykb.review.review.AutomaticReviewService
import javax.swing.JComponent

class ReviewSettingsConfigurable(private val project: Project) : Configurable {

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

        val currentRepoInstructions = mutableMapOf<String, String>()
        for (i in 0 until ui.repoInstructionsModel.rowCount) {
            val key = ui.repoInstructionsModel.getValueAt(i, 0) as String
            val value = ui.repoInstructionsModel.getValueAt(i, 1) as String
            currentRepoInstructions[key] = value
        }

        return ui.bitbucketUrlField.text != state.bitbucketBaseUrl ||
                String(ui.bitbucketTokenField.password) != state.bitbucketToken ||
                ui.autoReviewEnabledCheck.isSelected != state.automaticReviewEnabled ||
                ui.autoReviewIntervalField.text != state.automaticReviewIntervalMinutes.toString() ||
                ui.aiBaseUrlField.text != state.aiBaseUrl ||
                String(ui.aiApiKeyField.password) != state.aiApiKey ||
                ui.fastModelField.text != state.fastModelName ||
                ui.complexModelField.text != state.complexModelName ||
                ui.minSeverityCombo.selectedItem != state.minSeverity ||
                ui.maxChunkLinesField.text != state.maxChunkLines.toString() ||
                ui.extraInstructionsArea.text != state.extraInstructions ||
                ui.maskSecretsCheck.isSelected != state.maskSecretsInDiff ||
                currentRepoInstructions != state.repoInstructions
    }

    override fun apply() {
        val state = ReviewSettingsState.instance
        val ui = settingsComponent ?: return

        state.bitbucketBaseUrl = ui.bitbucketUrlField.text.trim()
        state.bitbucketToken = String(ui.bitbucketTokenField.password).trim()

        val oldEnabled = state.automaticReviewEnabled
        val oldInterval = state.automaticReviewIntervalMinutes

        state.automaticReviewEnabled = ui.autoReviewEnabledCheck.isSelected
        state.automaticReviewIntervalMinutes = ui.autoReviewIntervalField.text.toIntOrNull()?.coerceIn(1, 1440) ?: 5

        state.aiBaseUrl = ui.aiBaseUrlField.text.trim()
        state.aiApiKey = String(ui.aiApiKeyField.password).trim()
        state.fastModelName = ui.fastModelField.text.trim()
        state.complexModelName = ui.complexModelField.text.trim()

        state.minSeverity = (ui.minSeverityCombo.selectedItem as? String) ?: "low"
        state.maxChunkLines = ui.maxChunkLinesField.text.toIntOrNull() ?: 450
        state.extraInstructions = ui.extraInstructionsArea.text.trim()
        state.maskSecretsInDiff = ui.maskSecretsCheck.isSelected

        state.repoInstructions.clear()
        for (i in 0 until ui.repoInstructionsModel.rowCount) {
            val key = ui.repoInstructionsModel.getValueAt(i, 0) as String
            val value = ui.repoInstructionsModel.getValueAt(i, 1) as String
            state.repoInstructions[key] = value
        }

        // Zamanlayıcıyı yeniden başlat
        if (oldEnabled != state.automaticReviewEnabled || oldInterval != state.automaticReviewIntervalMinutes) {
            AutomaticReviewService.getInstance(project).restartTimer()
        }
    }

    override fun reset() {
        val state = ReviewSettingsState.instance
        val ui = settingsComponent ?: return

        ui.bitbucketUrlField.text = state.bitbucketBaseUrl
        ui.bitbucketTokenField.text = state.bitbucketToken
        ui.autoReviewEnabledCheck.isSelected = state.automaticReviewEnabled
        ui.autoReviewIntervalField.text = state.automaticReviewIntervalMinutes.toString()

        ui.aiBaseUrlField.text = state.aiBaseUrl
        ui.aiApiKeyField.text = state.aiApiKey
        ui.fastModelField.text = state.fastModelName
        ui.complexModelField.text = state.complexModelName

        ui.minSeverityCombo.selectedItem = state.minSeverity
        ui.maxChunkLinesField.text = state.maxChunkLines.toString()
        ui.extraInstructionsArea.text = state.extraInstructions
        ui.maskSecretsCheck.isSelected = state.maskSecretsInDiff

        ui.repoInstructionsModel.rowCount = 0
        for ((key, value) in state.repoInstructions) {
            ui.repoInstructionsModel.addRow(arrayOf(key, value))
        }
    }

    override fun disposeUIResources() {
        settingsComponent = null
    }
}
