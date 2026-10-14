package com.ykb.review.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.ykb.review.ai.ReviewCoordinator
import com.ykb.review.ai.ReviewFinding
import com.ykb.review.ai.ReviewResult
import com.ykb.review.bitbucket.BitbucketClient
import com.ykb.review.bitbucket.PullRequest
import com.ykb.review.config.ReviewSettingsState
import com.ykb.review.review.AutomaticReviewService
import com.ykb.review.review.CommentFormatter
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
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
    private val reviewBtn = JButton("Seçili PR'ı İncele")
    private val batchBtn = JButton("Açık PR'ları Şimdi Tara & İncele (Batch)")
    private val reviewUrlBtn = JButton("URL ile PR İncele")

    init {
        border = JBUI.Borders.empty(8)

        // Üst Araç Çubuğu
        val topPanel = JPanel(BorderLayout(5, 5)).apply {
            border = JBUI.Borders.emptyBottom(8)
            val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
                val state = ReviewSettingsState.instance
                val autoStatus = if (state.automaticReviewEnabled) "Açık (${state.automaticReviewIntervalMinutes} dk)" else "Kapalı"
                add(JBLabel("<html><b>Bitbucket Reviewer PR'larım</b> | Otomatik Tarama: <font color='gray'>$autoStatus</font></html>"))
            }
            val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                val refreshBtn = JButton("PR'larımı Getir").apply {
                    addActionListener { refreshReviewerPullRequests() }
                }
                add(refreshBtn)
                add(batchBtn.apply {
                    addActionListener { runBatchReviewNow() }
                })
                add(reviewUrlBtn.apply {
                    addActionListener { promptAndReviewUrl() }
                })
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
                statusLabel.text = "Seçili PR: #${activePR?.id} - ${activePR?.title} [${activePR?.projectKey}/${activePR?.repositorySlug}]"
            } else {
                reviewBtn.isEnabled = false
            }
        }
        val prScrollPane = JBScrollPane(prList).apply {
            preferredSize = Dimension(350, 150)
            border = BorderFactory.createTitledBorder("Reviewer Olduğum Açık Pull Request'ler")
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

    /**
     * Dashboard API'sinden reviewer olunan tüm açık PR'ları çeker
     */
    fun refreshReviewerPullRequests() {
        val state = ReviewSettingsState.instance
        if (state.bitbucketToken.isBlank()) {
            statusLabel.text = "Lütfen önce Ayarlar menüsünden Bitbucket Access Token giriniz."
            Messages.showWarningDialog("Lütfen önce Bitbucket Access Token giriniz.", "Yetki Hatası")
            return
        }

        statusLabel.text = "Reviewer olduğunuz PR'lar getiriliyor..."
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Reviewer PR Listesi Çekiliyor", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val client = BitbucketClient()
                    val prs = client.listOpenPullRequestsForReviewer()
                    loadedPRs = prs
                    ApplicationManager.getApplication().invokeLater {
                        prListModel.clear()
                        for (pr in prs) {
                            val author = pr.author?.user?.displayName ?: "Bilinmiyor"
                            val repoInfo = if (pr.repositorySlug.isNotBlank()) "[${pr.projectKey}/${pr.repositorySlug}] " else ""
                            prListModel.addElement("#${pr.id} | $repoInfo${pr.title} ($author)")
                        }
                        statusLabel.text = "${prs.size} adet açık PR bulundu."
                        if (prs.isEmpty()) {
                            showNotification("Reviewer olduğunuz açık PR bulunamadı.", NotificationType.INFORMATION)
                        }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = "Hata: ${e.message}"
                        showNotification("PR listesi çekilemedi: ${e.message}", NotificationType.ERROR)
                    }
                }
            }
        })
    }

    private fun runBatchReviewNow() {
        val autoService = AutomaticReviewService.getInstance(project)
        statusLabel.text = "Açık PR'lar taranıyor ve inceleniyor..."
        autoService.runScanAndReview(manual = true) { summary ->
            ApplicationManager.getApplication().invokeLater {
                statusLabel.text = summary
                refreshReviewerPullRequests()
            }
        }
    }

    private fun promptAndReviewUrl() {
        val url = Messages.showInputDialog(
            project,
            "Bitbucket PR URL'ini giriniz:\nÖrn: https://sdlc.yapikredi.com.tr/bitbucket/projects/PROJ/repos/repo/pull-requests/42",
            "URL ile PR İncele",
            Messages.getQuestionIcon()
        ) ?: return

        val parsed = parseBitbucketPrUrl(url.trim())
        if (parsed == null) {
            Messages.showErrorDialog("Geçersiz Bitbucket PR URL'i.", "Format Hatası")
            return
        }

        val (projectKey, repoSlug, prId) = parsed
        statusLabel.text = "URL İncelemesi: $projectKey/$repoSlug #$prId..."

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "URL PR İncelemesi", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val client = BitbucketClient()
                    val rawDiff = client.getPullRequestDiff(projectKey, repoSlug, prId)
                    val coordinator = ReviewCoordinator()
                    val state = ReviewSettingsState.instance
                    val repoInstructions = state.getEffectiveInstructions(repoSlug)

                    val result = coordinator.executeReview(
                        prTitle = "URL PR Review: $repoSlug #$prId",
                        prDescription = "Manuel URL incelemesi",
                        fromBranch = "source",
                        toBranch = "target",
                        rawDiff = rawDiff
                    ) { step, frac ->
                        indicator.text = step
                        indicator.fraction = frac
                    }

                    lastResult = result
                    ApplicationManager.getApplication().invokeLater {
                        renderResult(result)
                        publishBtn.isEnabled = true
                        statusLabel.text = "URL İncelemesi tamamlandı. Risk: ${result.riskLevel.uppercase()}"
                        showNotification("PR #$prId incelendi: ${result.findings.size} bulgu tespit edildi.", NotificationType.INFORMATION)
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = "Hata: ${e.message}"
                        showNotification("URL inceleme hatası: ${e.message}", NotificationType.ERROR)
                    }
                }
            }
        })
    }

    private fun parseBitbucketPrUrl(url: String): Triple<String, String, Long>? {
        return try {
            val u = java.net.URI(url)
            val parts = u.path.split("/").filter { it.isNotBlank() }
            val projIdx = parts.indexOf("projects")
            val repoIdx = parts.indexOf("repos")
            val prIdx = parts.indexOf("pull-requests")
            if (projIdx != -1 && repoIdx != -1 && prIdx != -1 && parts.size > prIdx + 1) {
                val projKey = parts[projIdx + 1]
                val repoSlug = parts[repoIdx + 1]
                val prId = parts[prIdx + 1].toLong()
                Triple(projKey, repoSlug, prId)
            } else null
        } catch (e: Exception) {
            null
        }
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
                    val projectKey = if (pr.projectKey.isNotBlank()) pr.projectKey else state.defaultProjectKey
                    val repoSlug = if (pr.repositorySlug.isNotBlank()) pr.repositorySlug else state.defaultRepoSlug

                    val rawDiff = client.getPullRequestDiff(projectKey, repoSlug, pr.id)
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
                    val projectKey = if (pr.projectKey.isNotBlank()) pr.projectKey else state.defaultProjectKey
                    val repoSlug = if (pr.repositorySlug.isNotBlank()) pr.repositorySlug else state.defaultRepoSlug

                    indicator.text = "Genel özet yorumu gönderiliyor..."
                    val marker = "<!-- YKB_AI_REVIEW: prId=${pr.id} commitHash=${pr.latestCommitHash} -->"
                    val generalComment = "${CommentFormatter.formatGeneralComment(result)}\n\n$marker"
                    client.postGeneralComment(projectKey, repoSlug, pr.id, generalComment)

                    indicator.text = "Satır içi yorumlar gönderiliyor..."
                    var publishedCount = 0
                    for (f in result.findings) {
                        if (f.line != null) {
                            val inlineText = CommentFormatter.formatInlineComment(f)
                            val ok = client.postInlineComment(
                                projectKey = projectKey,
                                repoSlug = repoSlug,
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
