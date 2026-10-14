package com.ykb.review.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import java.awt.Component
import com.intellij.util.ui.JBUI
import com.ykb.review.ai.ReviewCoordinator
import com.ykb.review.ai.ReviewFinding
import com.ykb.review.ai.ReviewResult
import com.ykb.review.bitbucket.BitbucketClient
import com.ykb.review.bitbucket.PullRequest
import com.ykb.review.config.ReviewSettingsState
import com.ykb.review.review.CommentFormatter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.io.File
import javax.swing.*
import javax.swing.table.DefaultTableModel

class ReviewPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val prListModel = DefaultListModel<String>()
    private val prList = JBList(prListModel)
    private var loadedPRs: List<PullRequest> = emptyList()
    private var lastResult: ReviewResult? = null
    private var activePR: PullRequest? = null

    private val statusLabel = JBLabel("Hazır.")
    private val summaryArea = JBTextArea()
    private val findingsTableModel = DefaultTableModel(arrayOf("Seviye", "Tür", "Dosya", "Satır", "Mesaj"), 0)
    private val findingsTable = JBTable(findingsTableModel)

    private val publishBtn = JButton("Bitbucket'a Yorumları Gönder")
    private val reviewBtn = JButton("AI İncelemesini Başlat")

    init {
        border = JBUI.Borders.empty(8)

        // Üst Araç Çubuğu
        val topPanel = JPanel(BorderLayout(5, 5)).apply {
            border = JBUI.Borders.emptyBottom(8)
            val infoPanel = JPanel().apply {
                val state = ReviewSettingsState.instance
                add(JBLabel("Bitbucket PR Review | Model Stratejisi: Hibrit"))
            }
            val btnPanel = JPanel().apply {
                val refreshBtn = JButton("PR Listesini Yenile").apply {
                    addActionListener { refreshPullRequests() }
                }
                add(refreshBtn)
                add(reviewBtn.apply {
                    addActionListener { startReviewForSelectedPR() }
                    isEnabled = false
                })
            }
            add(infoPanel, BorderLayout.WEST)
            add(btnPanel, BorderLayout.EAST)
        }

        // Sol / Üst PR Seçim Listesi
        prList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        prList.addListSelectionListener {
            val idx = prList.selectedIndex
            if (idx >= 0 && idx < loadedPRs.size) {
                activePR = loadedPRs[idx]
                reviewBtn.isEnabled = true
                statusLabel.text = "Seçili PR: #${activePR?.id} - ${activePR?.title}"
            } else {
                reviewBtn.isEnabled = false
            }
        }
        val prScrollPane = JBScrollPane(prList).apply {
            preferredSize = Dimension(350, 150)
            border = BorderFactory.createTitledBorder("Açık Pull Request'ler")
        }

        // Alt Sonuç Paneli (Tabbed: Bulgular, Özet)
        summaryArea.isEditable = false
        summaryArea.lineWrap = true
        summaryArea.wrapStyleWord = true

        val tabbedPane = JBTabbedPane().apply {
            addTab("Bulgular (${findingsTableModel.rowCount})", JBScrollPane(findingsTable as JComponent))
            addTab("İnceleme Özeti", JBScrollPane(summaryArea as JComponent))
        }

        // Çift tıklanan bulgunun kaynak dosyasına gitme
        findingsTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val row = findingsTable.selectedRow
                    if (row >= 0) {
                        val filePath = findingsTableModel.getValueAt(row, 2) as? String ?: return
                        val line = (findingsTableModel.getValueAt(row, 3) as? String)?.toIntOrNull() ?: 1
                        navigateToSource(filePath, line)
                    }
                }
            }
        })

        // Alt Aksiyon Butonları & Durum
        val bottomPanel = JPanel(BorderLayout(5, 5)).apply {
            border = JBUI.Borders.emptyTop(8)
            add(statusLabel, BorderLayout.WEST)
            val actionPanel = JPanel().apply {
                publishBtn.isEnabled = false
                publishBtn.addActionListener { publishCommentsToBitbucket() }
                add(publishBtn)
            }
            add(actionPanel, BorderLayout.EAST)
        }

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, prScrollPane, tabbedPane).apply {
            dividerLocation = 160
            resizeWeight = 0.3
        }

        add(topPanel, BorderLayout.NORTH)
        add(splitPane, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    fun refreshPullRequests() {
        val state = ReviewSettingsState.instance
        if (state.defaultProjectKey.isBlank() || state.defaultRepoSlug.isBlank()) {
            statusLabel.text = "Lütfen önce Ayarlar menüsünden Proje ve Depo adını giriniz."
            return
        }

        statusLabel.text = "Bitbucket PR'ları getiriliyor..."
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "PR Listesi Çekiliyor", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val client = BitbucketClient()
                    val prs = client.getOpenPullRequests(state.defaultProjectKey, state.defaultRepoSlug)
                    loadedPRs = prs
                    ApplicationManager.getApplication().invokeLater {
                        prListModel.clear()
                        for (pr in prs) {
                            prListModel.addElement("#${pr.id} | ${pr.title} (${pr.author?.user?.displayName ?: "Bilinmiyor"})")
                        }
                        statusLabel.text = "${prs.size} adet açık PR yüklendi."
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = "Hata: ${e.message}"
                        showNotification("Bitbucket PR'lar yüklenemedi: ${e.message}", NotificationType.ERROR)
                    }
                }
            }
        })
    }

    private fun startReviewForSelectedPR() {
        val pr = activePR ?: return
        val state = ReviewSettingsState.instance

        findingsTableModel.rowCount = 0
        summaryArea.text = ""
        publishBtn.isEnabled = false
        statusLabel.text = "PR #${pr.id} inceleniyor..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI PR İncelemesi Yapılıyor", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Bitbucket'tan diff çekiliyor..."
                    val client = BitbucketClient()
                    val rawDiff = client.getPullRequestDiff(state.defaultProjectKey, state.defaultRepoSlug, pr.id)

                    val coordinator = ReviewCoordinator()
                    val result = coordinator.executeReview(
                        prTitle = pr.title,
                        prDescription = pr.description,
                        fromBranch = pr.fromRef?.displayId,
                        toBranch = pr.toRef?.displayId,
                        rawDiff = rawDiff
                    ) { step, frac ->
                        indicator.text = step
                        indicator.fraction = frac
                    }

                    lastResult = result

                    ApplicationManager.getApplication().invokeLater {
                        renderResult(result)
                        publishBtn.isEnabled = true
                        statusLabel.text = "İnceleme bitti. Risk Seviyesi: ${result.riskLevel.uppercase()}"
                        showNotification("AI İncelemesi tamamlandı: ${result.findings.size} bulgu tespit edildi.", NotificationType.INFORMATION)

                        if (state.autoPublishComments) {
                            publishCommentsToBitbucket()
                        }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = "İnceleme hatası: ${e.message}"
                        showNotification("İnceleme sırasında hata oluştu: ${e.message}", NotificationType.ERROR)
                    }
                }
            }
        })
    }

    private fun renderResult(result: ReviewResult) {
        summaryArea.text = buildString {
            append("GENEL RİSK DURUMU: ").append(result.riskLevel.uppercase()).append("\n")
            if (!result.modelUsed.isNullOrBlank()) {
                append("Çalıştırılan Model Katmanı: ").append(result.modelUsed).append("\n")
            }
            append("\n--- ÖZET ---\n")
            append(result.summary).append("\n\n")
            if (result.testSuggestions.isNotEmpty()) {
                append("--- TEST ÖNERİLERİ ---\n")
                result.testSuggestions.forEach { append("• ").append(it).append("\n") }
                append("\n")
            }
            if (result.finalComment.isNotBlank()) {
                append("--- DEĞERLENDİRME ---\n")
                append(result.finalComment)
            }
        }

        findingsTableModel.rowCount = 0
        for (f in result.findings) {
            findingsTableModel.addRow(arrayOf(
                f.severity.uppercase(),
                f.type,
                f.file,
                f.line?.toString() ?: "-",
                f.message
            ))
        }
    }

    private fun publishCommentsToBitbucket() {
        val pr = activePR ?: return
        val result = lastResult ?: return
        val state = ReviewSettingsState.instance

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Yorumlar Bitbucket'a Gönderiliyor", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val client = BitbucketClient()
                    // 1. Genel Özet Yorumu
                    indicator.text = "Genel özet yorumu gönderiliyor..."
                    val generalComment = CommentFormatter.formatGeneralComment(result)
                    client.postGeneralComment(state.defaultProjectKey, state.defaultRepoSlug, pr.id, generalComment)

                    // 2. Satır içi bulgular
                    indicator.text = "Satır içi yorumlar gönderiliyor..."
                    var publishedCount = 0
                    for (f in result.findings) {
                        if (f.line != null) {
                            val inlineText = CommentFormatter.formatInlineComment(f)
                            val ok = client.postInlineComment(
                                projectKey = state.defaultProjectKey,
                                repoSlug = state.defaultRepoSlug,
                                pullRequestId = pr.id,
                                filePath = f.file,
                                lineNumber = f.line,
                                side = f.side ?: "new",
                                comment = inlineText
                            )
                            if (ok) publishedCount++
                        }
                    }

                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = "Bitbucket'a genel özet ve $publishedCount satır içi yorum yayınlandı."
                        showNotification("Bitbucket'a yorumlar başarıyla kaydedildi.", NotificationType.INFORMATION)
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = "Yorum gönderim hatası: ${e.message}"
                        showNotification("Yorum gönderilemedi: ${e.message}", NotificationType.ERROR)
                    }
                }
            }
        })
    }

    private fun navigateToSource(relativeFilePath: String, line: Int) {
        val basePath = project.basePath ?: return
        val file = File(basePath, relativeFilePath)
        if (file.exists()) {
            val vFile = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return
            OpenFileDescriptor(project, vFile, (line - 1).coerceAtLeast(0), 0).navigate(true)
        }
    }

    private fun showNotification(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("YKB AI Review Notifications")
            .createNotification("YKB AI Review", content, type)
            .notify(project)
    }
}
