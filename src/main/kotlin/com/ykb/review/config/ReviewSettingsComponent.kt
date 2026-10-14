package com.ykb.review.config

import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

class ReviewSettingsComponent {
    val mainPanel: JPanel

    val bitbucketUrlField = JBTextField()
    val bitbucketTokenField = JBPasswordField()
    val projectKeyField = JBTextField()
    val repoSlugField = JBTextField()

    val aiBaseUrlField = JBTextField()
    val aiApiKeyField = JBPasswordField()
    
    // Model Katmanları (Tier 1 vs Tier 2)
    val fastModelField = JBTextField()
    val complexModelField = JBTextField()

    val minSeverityCombo = JComboBox(arrayOf("low", "medium", "high"))
    val maxChunkLinesField = JBTextField()
    val extraInstructionsArea = JBTextArea(5, 40)
    val autoPublishCheck = JBCheckBox("Bulguları otomatik olarak Bitbucket PR yorumu olarak yayınla")
    val maskSecretsCheck = JBCheckBox("Diff içerisindeki şifre/token/secret verilerini maskele")

    init {
        extraInstructionsArea.lineWrap = true
        extraInstructionsArea.wrapStyleWord = true

        mainPanel = FormBuilder.createFormBuilder()
            .addSeparator()
            .addComponent(JBLabel("<html><b>Bitbucket Entegrasyonu</b></html>"))
            .addLabeledComponent("Bitbucket URL:", bitbucketUrlField, 1, false)
            .addLabeledComponent("Access Token:", bitbucketTokenField, 1, false)
            .addLabeledComponent("Varsayılan Proje Kodu (KEY):", projectKeyField, 1, false)
            .addLabeledComponent("Varsayılan Depo (Repo Slug):", repoSlugField, 1, false)
            
            .addSeparator()
            .addComponent(JBLabel("<html><b>AI Servis ve Çok Katmanlı Model Mimarisi</b></html>"))
            .addLabeledComponent("AI API Base URL:", aiBaseUrlField, 1, false)
            .addLabeledComponent("API Key:", aiApiKeyField, 1, false)
            .addLabeledComponent("Hızlı / Triage Modeli (Tier 1):", fastModelField, 1, false)
            .addLabeledComponent("Güçlü / Derin Analiz Modeli (Tier 2):", complexModelField, 1, false)

            .addSeparator()
            .addComponent(JBLabel("<html><b>İnceleme & Güvenlik Kriterleri</b></html>"))
            .addLabeledComponent("Minimum Önem Seviyesi:", minSeverityCombo, 1, false)
            .addLabeledComponent("Maksimum Chunk Satır Sayısı:", maxChunkLinesField, 1, false)
            .addLabeledComponent("Proje Özel İnceleme Kuralları:", JScrollPane(extraInstructionsArea), 1, true)
            .addComponent(maskSecretsCheck)
            .addComponent(autoPublishCheck)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }
}
