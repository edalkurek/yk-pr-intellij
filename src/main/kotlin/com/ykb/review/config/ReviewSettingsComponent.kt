package com.ykb.review.config

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.ykb.review.bitbucket.BitbucketClient
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel

class ReviewSettingsComponent {
    val mainPanel: JPanel

    val bitbucketUrlField = JBTextField()
    val bitbucketTokenField = JBPasswordField()

    // Otomatik Arka Plan Tarama (Batch Review)
    val autoReviewEnabledCheck = JBCheckBox("Otomatik PR Review'u Etkinleştir (Reviewer olduğum PR'ları arka planda otomatik incele)")
    val autoReviewIntervalField = JBTextField("5")

    val aiBaseUrlField = JBTextField()
    val aiApiKeyField = JBPasswordField()
    val fastModelField = JBTextField()
    val complexModelField = JBTextField()

    val minSeverityCombo = JComboBox(arrayOf("low", "medium", "high"))
    val maxChunkLinesField = JBTextField("450")
    val extraInstructionsArea = JBTextArea(4, 40)
    val maskSecretsCheck = JBCheckBox("Diff içerisindeki şifre/token/secret verilerini maskele")

    // Repo Bazlı Özel Talimatlar
    val repoInstructionsModel = DefaultTableModel(arrayOf("Repo (Slug)", "Özel Talimat"), 0)
    val repoInstructionsTable = JBTable(repoInstructionsModel)
    val fetchReposBtn = JButton("Yetkili Repolarımı Çek")
    val addRepoRuleBtn = JButton("Repo Kuralı Ekle / Düzenle")
    val removeRepoRuleBtn = JButton("Kuralı Sil")

    init {
        extraInstructionsArea.lineWrap = true
        extraInstructionsArea.wrapStyleWord = true

        val repoButtonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            add(fetchReposBtn)
            add(addRepoRuleBtn)
            add(removeRepoRuleBtn)
        }

        val repoTableScrollPane = JBScrollPane(repoInstructionsTable).apply {
            preferredSize = Dimension(500, 140)
        }

        val repoSectionPanel = JPanel(BorderLayout(0, 5)).apply {
            border = BorderFactory.createTitledBorder("Repo Bazlı Özel İnceleme Talimatları")
            add(repoButtonsPanel, BorderLayout.NORTH)
            add(repoTableScrollPane, BorderLayout.CENTER)
        }

        // Yetkili repoları çekip kullanıcıya kural ekletme
        fetchReposBtn.addActionListener {
            val url = bitbucketUrlField.text.trim()
            val token = String(bitbucketTokenField.password).trim()
            if (token.isBlank()) {
                Messages.showWarningDialog("Lütfen önce Bitbucket Access Token giriniz.", "Yetki Hatası")
                return@addActionListener
            }

            ProgressManager.getInstance().run(object : Task.Modal(null, "Bitbucket Repoları Çekiliyor", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        val client = BitbucketClient(baseUrl = url, token = token)
                        val repos = client.listRepositories()
                        SwingUtilities.invokeLater {
                            if (repos.isEmpty()) {
                                Messages.showInfoMessage("Erişim yetkiniz olan bir repo bulunamadı.", "Bitbucket Repoları")
                                return@invokeLater
                            }
                            val repoSlugs = repos.map { it.slug }.distinct().sorted().toTypedArray()
                            val selectedRepo = Messages.showEditableChooseDialog(
                                "Özel talimat eklemek istediğiniz repoyu seçin:",
                                "Repo Seçimi (${repos.size} Depo)",
                                Messages.getQuestionIcon(),
                                repoSlugs,
                                repoSlugs.firstOrNull() ?: "",
                                null
                            )
                            if (!selectedRepo.isNullOrBlank()) {
                                promptForRepoInstruction(selectedRepo)
                            }
                        }
                    } catch (e: Exception) {
                        SwingUtilities.invokeLater {
                            Messages.showErrorDialog("Repolar listelenemedi: ${e.message}", "Bağlantı Hatası")
                        }
                    }
                }
            })
        }

        addRepoRuleBtn.addActionListener {
            val repoSlug = Messages.showInputDialog("Repo Adı (Slug):", "Repo Kuralı", Messages.getQuestionIcon())
            if (!repoSlug.isNullOrBlank()) {
                promptForRepoInstruction(repoSlug.trim())
            }
        }

        removeRepoRuleBtn.addActionListener {
            val row = repoInstructionsTable.selectedRow
            if (row >= 0) {
                repoInstructionsModel.removeRow(row)
            }
        }

        mainPanel = FormBuilder.createFormBuilder()
            .addSeparator()
            .addComponent(JBLabel("<html><b>Bitbucket Bağlantısı</b></html>"))
            .addLabeledComponent("Bitbucket URL:", bitbucketUrlField, 1, false)
            .addLabeledComponent("Access Token:", bitbucketTokenField, 1, false)

            .addSeparator()
            .addComponent(JBLabel("<html><b>Otomatik İnceleme & Zamanlayıcı (Auto PR Review)</b></html>"))
            .addComponent(autoReviewEnabledCheck)
            .addLabeledComponent("Tarama Sıklığı (Dakika):", autoReviewIntervalField, 1, false)

            .addSeparator()
            .addComponent(JBLabel("<html><b>AI Model ve Servis Ayarları (Bifrost Gateway / Yerel LLM)</b></html>"))
            .addLabeledComponent("AI API Base URL:", aiBaseUrlField, 1, false)
            .addLabeledComponent("API Key:", aiApiKeyField, 1, false)
            .addLabeledComponent("Hızlı Model (Tier 1):", fastModelField, 1, false)
            .addLabeledComponent("Güçlü Model (Tier 2):", complexModelField, 1, false)

            .addSeparator()
            .addComponent(JBLabel("<html><b>İnceleme & Güvenlik Kriterleri</b></html>"))
            .addLabeledComponent("Minimum Önem Seviyesi:", minSeverityCombo, 1, false)
            .addLabeledComponent("Maksimum Chunk Satır Sayısı:", maxChunkLinesField, 1, false)
            .addLabeledComponent("Genel Özel İnceleme Kuralları:", JBScrollPane(extraInstructionsArea), 1, true)
            .addComponent(maskSecretsCheck)

            .addSeparator()
            .addComponent(repoSectionPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    private fun promptForRepoInstruction(repoSlug: String) {
        // Mevcut kural var mı bul
        var existingInstruction = ""
        var existingRow = -1
        for (i in 0 until repoInstructionsModel.rowCount) {
            if (repoInstructionsModel.getValueAt(i, 0) == repoSlug) {
                existingInstruction = repoInstructionsModel.getValueAt(i, 1) as String
                existingRow = i
                break
            }
        }

        val area = JBTextArea(existingInstruction, 5, 30)
        val panel = JPanel(BorderLayout(0, 5)).apply {
            add(JBLabel("'$repoSlug' reposu için özel review talimatlarını giriniz:"), BorderLayout.NORTH)
            add(JBScrollPane(area), BorderLayout.CENTER)
        }

        val res = JOptionPane.showConfirmDialog(
            mainPanel,
            panel,
            "Repo Talimatı: $repoSlug",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        )

        if (res == JOptionPane.OK_OPTION) {
            val text = area.text.trim()
            if (existingRow >= 0) {
                if (text.isBlank()) {
                    repoInstructionsModel.removeRow(existingRow)
                } else {
                    repoInstructionsModel.setValueAt(text, existingRow, 1)
                }
            } else if (text.isNotBlank()) {
                repoInstructionsModel.addRow(arrayOf(repoSlug, text))
            }
        }
    }
}
