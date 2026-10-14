package com.ykb.review.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.ykb.review.ai.ReviewFinding
import com.ykb.review.bitbucket.PullRequest
import com.ykb.review.config.ReviewSettingsState
import com.ykb.review.review.AutoReviewStatusListener
import com.ykb.review.review.AutomaticReviewService
import com.ykb.review.review.ReviewCoordinator
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

class ReviewPanel(private val project: Project, parentDisposable: Disposable) : JPanel(BorderLayout()) {

    private val session = ReviewSessionService.getInstance(project)

    private val prListModel = DefaultListModel<String>()
    private val prList = JBList(prListModel)
    private var loadedPRs: List<PullRequest> = emptyList()
    private var shownFindings: List<ReviewFinding> = emptyList()

    private val autoStatusLabel = JBLabel()
    private val statusLabel = JBLabel("Hazır.")
    private val summaryArea = JBTextArea()
    private val findingsTableModel = object : DefaultTableModel(arrayOf("Seviye", "Tür", "Dosya", "Satır", "Mesaj", "Öneri"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val findingsTable = JBTable(findingsTableModel)
    private val tabs = JBTabbedPane()

    private val refreshBtn = JButton("PR'larımı Getir")
    private val reviewBtn = JButton("Seçili PR'ı İncele")
    private val reviewUrlBtn = JButton("URL ile PR İncele")
    private val localBtn = JButton("Yerel Değişiklikleri İncele")
    private val batchBtn = JButton("Açık PR'ları Şimdi Tara")
    private val publishBtn = JButton("Bitbucket'a Yorumları Gönder")
    private val clearBtn = JButton("Bulguları Temizle")

    init {
        border = JBUI.Borders.empty(8)

        val topPanel = JPanel(BorderLayout(5, 5)).apply {
            border = JBUI.Borders.emptyBottom(8)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply { add(autoStatusLabel) }, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
                add(refreshBtn); add(reviewBtn); add(reviewUrlBtn); add(localBtn); add(batchBtn)
            }, BorderLayout.CENTER)
        }

        refreshBtn.addActionListener { refreshReviewerPullRequests() }
        reviewBtn.isEnabled = false
        reviewBtn.addActionListener { reviewSelected() }
        reviewUrlBtn.addActionListener { session.reviewUrlInteractive() }
        localBtn.addActionListener { session.reviewLocal() }
        batchBtn.addActionListener { AutomaticReviewService.getInstance().runNow(project) }
        publishBtn.isEnabled = false
        publishBtn.addActionListener { session.publishCurrent() }
        clearBtn.addActionListener { session.clearFindings() }

        prList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        prList.addListSelectionListener { reviewBtn.isEnabled = prList.selectedIndex in loadedPRs.indices }
        prList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) reviewSelected()
            }
        })
        val prScrollPane = JBScrollPane(prList).apply {
            preferredSize = Dimension(350, 150)
            border = BorderFactory.createTitledBorder("Reviewer Olduğum Açık Pull Request'ler")
        }

        summaryArea.isEditable = false
        summaryArea.lineWrap = true
        summaryArea.wrapStyleWord = true
        tabs.addTab("Bulgular (0)", JBScrollPane(findingsTable))
        tabs.addTab("İnceleme Özeti", JBScrollPane(summaryArea))

        findingsTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = findingsTable.selectedRow
                    if (row >= 0) shownFindings.getOrNull(findingsTable.convertRowIndexToModel(row))?.let { navigateTo(it) }
                }
            }
        })

        val bottomPanel = JPanel(BorderLayout(5, 5)).apply {
            border = JBUI.Borders.emptyTop(8)
            add(statusLabel, BorderLayout.CENTER)
            add(JPanel().apply { add(clearBtn); add(publishBtn) }, BorderLayout.EAST)
        }

        add(topPanel, BorderLayout.NORTH)
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, prScrollPane, tabs).apply {
            dividerLocation = 160
            resizeWeight = 0.3
        }, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)

        project.messageBus.connect(parentDisposable).subscribe(ReviewSessionListener.TOPIC, ReviewSessionListener { renderSession() })
        ApplicationManager.getApplication().messageBus.connect(parentDisposable)
            .subscribe(AutoReviewStatusListener.TOPIC, AutoReviewStatusListener {
                ApplicationManager.getApplication().invokeLater { renderAutoStatus() }
            })
        renderAutoStatus()
        renderSession()
    }

    fun refreshReviewerPullRequests() {
        statusLabel.text = "Reviewer olduğunuz PR'lar getiriliyor..."
        refreshBtn.isEnabled = false
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Reviewer PR listesi çekiliyor", true) {
            private var prs: List<PullRequest> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                prs = ReviewCoordinator().listPullRequests()
            }

            override fun onSuccess() {
                loadedPRs = prs
                prListModel.clear()
                prs.forEach { pr ->
                    prListModel.addElement(
                        "#${pr.ref.pullRequestId} | [${pr.ref.projectKey}/${pr.ref.repositorySlug}] ${pr.title}  " +
                            "(${pr.sourceBranch} → ${pr.destinationBranch} · ${pr.authorName} · ${pr.latestCommitHash.take(12)})"
                    )
                }
                statusLabel.text = if (prs.isEmpty()) "Reviewer olduğunuz açık pull request bulunamadı." else "${prs.size} adet açık PR bulundu."
            }

            override fun onThrowable(error: Throwable) {
                statusLabel.text = "Hata: ${error.message}"
                ReviewNotifier.error(project, error, "PR listesi çekilemedi")
            }

            override fun onFinished() {
                refreshBtn.isEnabled = true
            }
        })
    }

    private fun reviewSelected() {
        val pr = loadedPRs.getOrNull(prList.selectedIndex) ?: return
        val publish = session.choosePublication() ?: return
        session.reviewPullRequest(pr, pr.ref, publish)
    }

    private fun renderAutoStatus() {
        val settings = ReviewSettingsState.instance
        val auto = AutomaticReviewService.getInstance()
        val state = when {
            auto.isRunning -> "Çalışıyor…"
            settings.automaticReviewEnabled -> "Açık (${settings.automaticReviewIntervalMinutes} dk)"
            else -> "Kapalı"
        }
        val last = auto.lastRunSummary?.let { " · Son: $it" } ?: ""
        autoStatusLabel.text = "<html><b>Bitbucket Reviewer PR'larım</b> | Otomatik Tarama: <font color='gray'>${escape(state + last)}</font></html>"
        batchBtn.isEnabled = !auto.isRunning
    }

    private fun renderSession() {
        statusLabel.text = session.statusText
        val current = session.current
        findingsTableModel.rowCount = 0
        shownFindings = current?.review?.findings ?: emptyList()
        for (f in shownFindings) {
            findingsTableModel.addRow(arrayOf(f.severity.uppercase(), f.type, f.file, f.line?.toString() ?: "-", f.message, f.suggestion))
        }
        tabs.setTitleAt(0, "Bulgular (${shownFindings.size})")
        summaryArea.text = if (current == null) "" else buildString {
            append(current.title).append("\n\n")
            append("GENEL RİSK DURUMU: ").append(current.review.riskLevel.uppercase()).append("\n")
            current.review.modelUsed?.let { append("Kullanılan model(ler): ").append(it).append("\n") }
            append("\n--- ÖZET ---\n").append(current.review.summary).append("\n\n")
            if (current.review.testSuggestions.isNotEmpty()) {
                append("--- TEST ÖNERİLERİ ---\n")
                current.review.testSuggestions.forEach { append("• ").append(it).append("\n") }
                append("\n")
            }
            append("--- DEĞERLENDİRME ---\n").append(current.review.finalComment)
        }
        val outcome = current?.outcome
        publishBtn.isEnabled = outcome != null && !outcome.published && !outcome.skippedDuplicate
        publishBtn.toolTipText = outcome?.let { "Hedef: ${it.pullRequest.ref.projectKey}/${it.pullRequest.ref.repositorySlug} #${it.pullRequest.ref.pullRequestId}" }
        clearBtn.isEnabled = current != null
    }

    private fun navigateTo(finding: ReviewFinding) {
        val root = session.current?.root ?: project.basePath ?: return
        val relative = finding.file.replace(Regex("^[ab]/"), "")
        val path = Paths.get(root).resolve(relative).normalize().toFile()
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path)
        if (vFile == null) {
            statusLabel.text = "Dosya çalışma alanında bulunamadı: $relative"
            return
        }
        OpenFileDescriptor(project, vFile, ((finding.line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
