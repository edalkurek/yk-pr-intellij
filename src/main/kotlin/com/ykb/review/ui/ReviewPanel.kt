package com.ykb.review.ui

import com.intellij.ide.BrowserUtil
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
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

class ReviewPanel(private val project: Project, parentDisposable: Disposable) : JPanel(BorderLayout()) {

    private val session = ReviewSessionService.getInstance(project)

    private val prListModel = DefaultListModel<String>()
    private val prList = JBList(prListModel)
    private var loadedPRs: List<PullRequest> = emptyList()
    private var renderedFindings: List<ReviewFinding>? = null

    private val autoStatusLabel = JBLabel()
    private val statusLabel = JBLabel("Hazır.")
    private val summaryArea = JBTextArea()
    private val findingsView = FindingsView(object : FindingsView.Actions {
        override fun activate(finding: ReviewFinding) = navigateTo(finding)
        override fun openInEditor(finding: ReviewFinding) {
            openFileInEditor(finding)
        }
        override fun canShowInDiff(): Boolean = session.current?.outcome != null
        override fun showInDiff(path: String, finding: ReviewFinding?) = showFindingInDiff(path, finding)
    })
    private val tabs = JBTabbedPane()

    private val refreshBtn = JButton("PR'larımı Getir")
    private val reviewBtn = JButton("Seçili PR'ı İncele")
    private val changesBtn = JButton("Değişiklikleri Göster")
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
                add(refreshBtn); add(reviewBtn); add(changesBtn); add(reviewUrlBtn); add(localBtn); add(batchBtn)
            }, BorderLayout.CENTER)
        }

        refreshBtn.addActionListener { refreshReviewerPullRequests() }
        reviewBtn.isEnabled = false
        reviewBtn.addActionListener { reviewSelected() }
        changesBtn.isEnabled = false
        changesBtn.toolTipText = "Seçili PR'ın değişikliklerini IntelliJ diff görüntüleyicisinde açar (checkout gerekmez)"
        changesBtn.addActionListener { showSelectedChanges() }
        reviewUrlBtn.addActionListener { session.reviewUrlInteractive() }
        localBtn.addActionListener { session.reviewLocal() }
        batchBtn.addActionListener { AutomaticReviewService.getInstance().runNow(project) }
        publishBtn.isEnabled = false
        publishBtn.addActionListener { session.publishCurrent() }
        clearBtn.addActionListener { session.clearFindings() }

        prList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        prList.addListSelectionListener {
            val selected = prList.selectedIndex in loadedPRs.indices
            reviewBtn.isEnabled = selected
            changesBtn.isEnabled = selected
        }
        prList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) reviewSelected()
            }

            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)
        })
        val prScrollPane = JBScrollPane(prList).apply {
            preferredSize = Dimension(350, 150)
            border = BorderFactory.createTitledBorder("Reviewer Olduğum Açık Pull Request'ler")
        }

        summaryArea.isEditable = false
        summaryArea.lineWrap = true
        summaryArea.wrapStyleWord = true
        tabs.addTab("Bulgular (0)", findingsView)
        tabs.addTab("İnceleme Özeti", JBScrollPane(summaryArea))

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

    private fun showSelectedChanges() {
        val pr = loadedPRs.getOrNull(prList.selectedIndex) ?: return
        session.showChanges(pr.ref)
    }

    private fun maybeShowPopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val index = prList.locationToIndex(e.point)
        if (index !in loadedPRs.indices || prList.getCellBounds(index, index)?.contains(e.point) != true) return
        prList.selectedIndex = index
        val pr = loadedPRs[index]
        JPopupMenu().apply {
            add(JMenuItem("AI ile İncele…").apply { addActionListener { reviewSelected() } })
            add(JMenuItem("Değişiklikleri Göster").apply { addActionListener { session.showChanges(pr.ref) } })
            if (pr.url.isNotBlank()) {
                addSeparator()
                add(JMenuItem("Tarayıcıda Aç").apply { addActionListener { BrowserUtil.browse(pr.url) } })
            }
        }.show(prList, e.x, e.y)
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
        val findings = current?.review?.findings ?: emptyList()
        // Yalnızca durum metni değiştiğinde seçim/filtre sıfırlanmasın.
        if (findings !== renderedFindings) {
            renderedFindings = findings
            findingsView.setFindings(findings)
        }
        val high = findings.count { it.severity == "high" }
        tabs.setTitleAt(0, if (high > 0) "Bulgular (${findings.size} · $high yüksek)" else "Bulgular (${findings.size})")
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

    /** PR incelemesinde satır numaraları PR sürümüne ait olduğu için diff'te; yerel incelemede editörde açar. */
    private fun navigateTo(finding: ReviewFinding) {
        if (session.current?.outcome != null) {
            showFindingInDiff(finding.file, finding)
            return
        }
        openFileInEditor(finding)
    }

    private fun openFileInEditor(finding: ReviewFinding): Boolean {
        val root = session.current?.root ?: project.basePath ?: return false
        val relative = finding.file.replace(Regex("^[ab]/"), "")
        val path = Paths.get(root).resolve(relative).normalize().toFile()
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path)
        if (vFile == null) {
            statusLabel.text = "Dosya çalışma alanında bulunamadı: $relative"
            return false
        }
        OpenFileDescriptor(project, vFile, ((finding.line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
        return true
    }

    private fun showFindingInDiff(path: String, finding: ReviewFinding?) {
        val ref = session.current?.outcome?.pullRequest?.ref
        if (ref == null) {
            finding?.let { openFileInEditor(it) }
            return
        }
        PullRequestDiffViewer.show(project, ref, PullRequestDiffViewer.Focus(path, finding?.line, finding?.side))
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
