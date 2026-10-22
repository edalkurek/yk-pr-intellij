package com.ykb.review.ui

import com.ykb.review.ai.DiffLocations
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.ykb.review.ai.ReviewFinding
import com.ykb.review.bitbucket.PullRequest
import com.ykb.review.config.ReviewSettingsConfigurable
import com.ykb.review.config.ReviewSettingsState
import com.ykb.review.review.AutoReviewStatusListener
import com.ykb.review.review.AutomaticReviewService
import com.ykb.review.review.FindingsPresenter
import com.ykb.review.review.ReviewCoordinator
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Paths
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JProgressBar
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent
import javax.swing.plaf.basic.BasicComboBoxRenderer

/**
 * Tool window ana paneli.
 * Üstte araç çubuğu; üst bölümde reviewer PR listesi (iki satırlı, hızlı aramalı, repo filtreli); alt bölümde
 * inceleme başlık bandı (risk rozeti, geçmiş) + Bulgular / Özet sekmeleri; en altta durum, ilerleme ve yayın düğmeleri.
 */
class ReviewPanel(private val project: Project, parentDisposable: Disposable) : SimpleToolWindowPanel(true, true) {

    private val session = ReviewSessionService.getInstance(project)

    // ---- PR listesi
    private var loadedPRs: List<PullRequest> = emptyList()
    private var reviewedPrKeys: Set<String> = emptySet()
    private var everLoaded = false
    private val prListModel = DefaultListModel<PullRequest>()
    private val prList = JBList(prListModel)
    private val prLoading = JBLoadingPanel(BorderLayout(), parentDisposable)
    private val prHeaderLabel = JBLabel()
    private val autoStatusLabel = JBLabel()
    private val repoFilter = ComboBox(DefaultComboBoxModel<String?>())

    // ---- inceleme
    private var renderedFindings: List<ReviewFinding>? = null
    private val sessionTitleLabel = JBLabel()
    private val sessionMetaLabel = JBLabel()
    private val historyCombo = ComboBox(DefaultComboBoxModel<ReviewSession>())
    private var updatingHistory = false
    private val openPrLink = ActionLink("Bitbucket'ta aç") { session.current?.outcome?.pullRequest?.url?.takeIf { it.isNotBlank() }?.let(BrowserUtil::browse) }
    private val openDiffLink = ActionLink("Diff'i aç") { session.current?.outcome?.pullRequest?.ref?.let { session.showChanges(it) } }
    private val sessionHeader = JPanel(BorderLayout())
    private val summaryPane = JEditorPane()
    private val tabs = JBTabbedPane()
    private val findingsView = FindingsView(object : FindingsView.Actions {
        override fun activate(finding: ReviewFinding) = navigateTo(finding)
        override fun openInEditor(finding: ReviewFinding) {
            openFileInEditor(finding)
        }

        override fun canShowInDiff(): Boolean = session.current?.outcome != null
        override fun showInDiff(path: String, finding: ReviewFinding?) = showFindingInDiff(path, finding)
        override fun canExclude(): Boolean = session.current?.outcome?.let { !it.published && !it.skippedDuplicate } == true
        override fun isExcluded(finding: ReviewFinding): Boolean = session.current?.excluded?.contains(finding) == true
        override fun setExcluded(findings: List<ReviewFinding>, exclude: Boolean) = session.setExcluded(findings, exclude)
        override fun snippet(finding: ReviewFinding): List<Pair<Int, String>>? = readSnippet(finding)
        override fun title(): String? = session.current?.title
    })

    // ---- alt çubuk
    private val statusLabel = JBLabel("Hazır.")
    private val progressBar = JProgressBar(0, 1000)
    private val cancelLink = ActionLink("İptal") { session.cancelActive() }
    private val progressPanel = JPanel(BorderLayout(JBUI.scale(6), 0))
    private val publishBtn = JButton("Bitbucket'a Gönder", AllIcons.Actions.Upload)
    private val clearBtn = JButton("Temizle")

    init {
        toolbar = createToolbar().component
        setContent(createContent())

        project.messageBus.connect(parentDisposable).apply {
            subscribe(ReviewSessionListener.TOPIC, ReviewSessionListener { renderSession() })
            subscribe(FindingFocusListener.TOPIC, FindingFocusListener { finding ->
                tabs.selectedIndex = 0
                findingsView.focus(finding)
            })
        }
        ApplicationManager.getApplication().messageBus.connect(parentDisposable)
            .subscribe(AutoReviewStatusListener.TOPIC, AutoReviewStatusListener {
                ApplicationManager.getApplication().invokeLater {
                    renderAutoStatus()
                    // Ayarlar kaydedildiğinde de yayınlanır: ilk yapılandırmadan sonra listeyi otomatik getir.
                    if (!everLoaded && ReviewSettingsState.instance.isBitbucketConfigured) refreshReviewerPullRequests() else renderPrEmptyState()
                }
            })
        renderAutoStatus()
        renderPrEmptyState()
        renderSession()
        if (ReviewSettingsState.instance.isBitbucketConfigured) refreshReviewerPullRequests()
    }

    // ------------------------------------------------------------------ yerleşim

    private fun createToolbar() = ActionManager.getInstance().createActionToolbar(
        "YKB.Review.Main",
        DefaultActionGroup().apply {
            add(action("PR'larımı Getir", "Reviewer olduğunuz açık PR'ları yeniler", AllIcons.Actions.Refresh) { refreshReviewerPullRequests() })
            add(action("Seçili PR'ı AI ile İncele", "Seçili PR'ı yapay zeka ile inceler (çift tıklama / Enter)", AllIcons.Actions.Execute, ::hasSelection) { reviewSelected() })
            add(action("Değişiklikleri Göster", "Seçili PR'ın değişikliklerini diff görüntüleyicide açar (checkout gerekmez)", AllIcons.Actions.Diff, ::hasSelection) { showSelectedChanges() })
            add(action("Tarayıcıda Aç", "Seçili PR'ı Bitbucket'ta açar", AllIcons.General.Web, { selectedPr()?.url?.isNotBlank() == true }) {
                selectedPr()?.url?.let(BrowserUtil::browse)
            })
            addSeparator()
            add(action("URL ile PR İncele…", "Bitbucket PR bağlantısı ile inceleme başlatır (panodaki bağlantı otomatik doldurulur)", AllIcons.Actions.Search) { session.reviewUrlInteractive() })
            add(action("Yerel Değişiklikleri İncele", "Seçili dosya/class veya repository kapsamındaki yerel değişiklikleri inceler", AllIcons.Vcs.Changelist) { session.reviewLocal() })
            addSeparator()
            ActionManager.getInstance().getAction("YKB.Review.RunAutoReviewNow")?.let { add(it) }
            ActionManager.getInstance().getAction("YKB.Review.OpenSettings")?.let { add(it) }
        },
        true,
    ).also { it.targetComponent = this }

    private fun createContent(): JPanel {
        // PR bölümü
        prList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        prList.cellRenderer = PullRequestRenderer()
        prList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) reviewSelected()
            }

            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)
        })
        prList.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER) reviewSelected()
            }
        })
        ListSpeedSearch.installOn(prList) { pr ->
            "#${pr.ref.pullRequestId} ${pr.title} ${pr.ref.projectKey}/${pr.ref.repositorySlug} ${pr.authorName} ${pr.sourceBranch}"
        }
        prLoading.add(ScrollPaneFactory.createScrollPane(prList, true), BorderLayout.CENTER)

        repoFilter.renderer = object : BasicComboBoxRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component =
                super.getListCellRendererComponent(list, (value as? String) ?: "Tüm repolar", index, isSelected, cellHasFocus)
        }
        repoFilter.addActionListener { applyRepoFilter() }
        repoFilter.isVisible = false

        autoStatusLabel.foreground = UIUtil.getContextHelpForeground()
        autoStatusLabel.font = JBUI.Fonts.smallFont()
        val prHeader = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(4, 8)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(prHeaderLabel)
                add(autoStatusLabel)
            }, BorderLayout.CENTER)
            add(repoFilter, BorderLayout.EAST)
        }
        val prSection = JPanel(BorderLayout()).apply {
            add(prHeader, BorderLayout.NORTH)
            add(prLoading, BorderLayout.CENTER)
        }

        // İnceleme bölümü
        sessionMetaLabel.foreground = UIUtil.getContextHelpForeground()
        historyCombo.renderer = object : BasicComboBoxRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                val s = value as? ReviewSession
                val text = s?.let { "${it.title}  ·  ${FindingsPresenter.relativeTime(it.createdAt)}  ·  ${it.review.findings.size} bulgu" } ?: ""
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
        historyCombo.toolTipText = "Son incelemeler"
        historyCombo.addActionListener {
            if (updatingHistory) return@addActionListener
            (historyCombo.selectedItem as? ReviewSession)?.let { session.selectSession(it.id) }
        }
        sessionHeader.border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor()), JBUI.Borders.empty(6, 8))
        sessionHeader.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(sessionTitleLabel)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(sessionMetaLabel)
                add(openDiffLink)
                add(openPrLink)
            })
        }, BorderLayout.CENTER)
        sessionHeader.add(historyCombo, BorderLayout.EAST)

        summaryPane.isEditable = false
        summaryPane.editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        summaryPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        summaryPane.font = UIUtil.getLabelFont()
        summaryPane.border = JBUI.Borders.empty(8)
        summaryPane.addHyperlinkListener { e ->
            if (e.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            when {
                e.description.startsWith("sev:") -> {
                    tabs.selectedIndex = 0
                    findingsView.showOnly(e.description.removePrefix("sev:"))
                }
                e.description == "copy" -> copySummary()
            }
        }
        tabs.addTab("Bulgular (0)", findingsView)
        tabs.addTab("İnceleme Özeti", ScrollPaneFactory.createScrollPane(summaryPane, true))

        val reviewSection = JPanel(BorderLayout()).apply {
            add(sessionHeader, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }

        // Alt çubuk
        progressBar.preferredSize = JBUI.size(120, progressBar.preferredSize.height)
        progressPanel.isOpaque = false
        progressPanel.add(progressBar, BorderLayout.CENTER)
        progressPanel.add(cancelLink, BorderLayout.EAST)
        publishBtn.addActionListener { session.confirmAndPublishCurrent() }
        clearBtn.toolTipText = "Bulguları ve editör işaretlerini temizler (sonuç geçmişte kalır)"
        clearBtn.addActionListener {
            val answer = Messages.showYesNoDialog(
                project,
                "Gösterilen bulgular ve editör işaretleri temizlensin mi?\nSonuç geçmişte kalır; başlıktaki listeden tekrar açabilirsiniz.",
                "Bulguları Temizle",
                "Temizle",
                "Vazgeç",
                Messages.getQuestionIcon(),
            )
            if (answer == Messages.YES) session.clearFindings()
        }
        val bottom = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor()), JBUI.Borders.empty(4, 8))
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(statusLabel, BorderLayout.CENTER)
                add(progressPanel, BorderLayout.EAST)
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(clearBtn)
                add(publishBtn)
            }, BorderLayout.EAST)
        }

        val splitter = OnePixelSplitter(true, "YKB.Review.MainSplitter", 0.3f).apply {
            firstComponent = prSection
            secondComponent = reviewSection
        }
        return JPanel(BorderLayout()).apply {
            add(splitter, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
    }

    private fun action(
        text: String,
        description: String,
        icon: javax.swing.Icon,
        enabled: () -> Boolean = { true },
        run: () -> Unit,
    ) = object : DumbAwareAction(text, description, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }

        override fun actionPerformed(e: AnActionEvent) = run()
    }

    // ------------------------------------------------------------------ PR listesi

    private fun selectedPr(): PullRequest? = prList.selectedValue
    private fun hasSelection(): Boolean = selectedPr() != null
    private fun prKey(pr: PullRequest) = "${pr.ref.projectKey}/${pr.ref.repositorySlug}#${pr.ref.pullRequestId}@${pr.latestCommitHash}"

    fun refreshReviewerPullRequests() {
        if (!ReviewSettingsState.instance.isBitbucketConfigured) {
            renderPrEmptyState()
            statusLabel.text = "Bitbucket bağlantısı yapılandırılmamış."
            return
        }
        statusLabel.text = "Reviewer olduğunuz PR'lar getiriliyor..."
        statusLabel.icon = null
        prLoading.startLoading()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Reviewer PR listesi çekiliyor", true) {
            private var prs: List<PullRequest> = emptyList()
            private var reviewed: Set<String> = emptySet()

            override fun run(indicator: ProgressIndicator) {
                val coordinator = ReviewCoordinator()
                prs = coordinator.listPullRequests()
                reviewed = prs.filter { coordinator.isReviewedLocally(it) }.map(::prKey).toSet()
            }

            override fun onSuccess() {
                everLoaded = true
                loadedPRs = prs
                reviewedPrKeys = reviewed
                val repos = prs.map { "${it.ref.projectKey}/${it.ref.repositorySlug}" }.distinct().sorted()
                val previous = repoFilter.selectedItem as? String
                repoFilter.model = DefaultComboBoxModel<String?>((listOf<String?>(null) + repos).toTypedArray())
                repoFilter.selectedItem = previous?.takeIf { it in repos }
                repoFilter.isVisible = repos.size > 1
                applyRepoFilter()
                statusLabel.text = if (prs.isEmpty()) "Reviewer olduğunuz açık pull request bulunamadı." else "${prs.size} adet açık PR bulundu."
            }

            override fun onThrowable(error: Throwable) {
                statusLabel.text = "PR listesi çekilemedi: ${error.message}"
                statusLabel.icon = AllIcons.General.Error
                prList.emptyText.clear()
                prList.emptyText.appendLine("PR listesi alınamadı.")
                prList.emptyText.appendLine("Tekrar dene", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { refreshReviewerPullRequests() }
                ReviewNotifier.error(project, error, "PR listesi çekilemedi") { refreshReviewerPullRequests() }
            }

            override fun onFinished() {
                prLoading.stopLoading()
            }
        })
    }

    private fun applyRepoFilter() {
        val repo = repoFilter.selectedItem as? String
        val selected = selectedPr()?.ref
        val visible = loadedPRs.filter { repo == null || "${it.ref.projectKey}/${it.ref.repositorySlug}" == repo }
        prListModel.clear()
        visible.forEach { prListModel.addElement(it) }
        visible.indexOfFirst { it.ref == selected }.takeIf { it >= 0 }?.let { prList.selectedIndex = it }
        prHeaderLabel.text = "<html><b>Reviewer olduğum açık PR'lar</b>" +
            (if (everLoaded) " <font color='${SeverityStyle.hex(UIUtil.getContextHelpForeground())}'>(${visible.size}${if (repo != null) "/${loadedPRs.size}" else ""})</font>" else "") +
            "</html>"
        renderPrEmptyState()
    }

    private fun renderPrEmptyState() {
        val text = prList.emptyText
        text.clear()
        when {
            !ReviewSettingsState.instance.isBitbucketConfigured -> {
                text.appendLine("Başlamak için Bitbucket bağlantısını yapılandırın.")
                text.appendLine("Ayarları Aç", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, ReviewSettingsConfigurable::class.java)
                }
            }
            !everLoaded -> {
                text.appendLine("PR listesi henüz yüklenmedi.")
                text.appendLine("PR'larımı Getir", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { refreshReviewerPullRequests() }
            }
            else -> {
                text.appendLine("Reviewer olduğunuz açık PR yok.")
                text.appendLine("Yenile", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { refreshReviewerPullRequests() }
            }
        }
        if (prHeaderLabel.text.isNullOrEmpty()) prHeaderLabel.text = "<html><b>Reviewer olduğum açık PR'lar</b></html>"
    }

    private fun reviewSelected() {
        val pr = selectedPr() ?: return
        val publish = session.choosePublication() ?: return
        session.reviewPullRequest(pr, pr.ref, publish)
    }

    private fun showSelectedChanges() {
        val pr = selectedPr() ?: return
        session.showChanges(pr.ref)
    }

    private fun maybeShowPopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val index = prList.locationToIndex(e.point)
        if (index < 0 || prList.getCellBounds(index, index)?.contains(e.point) != true) return
        prList.selectedIndex = index
        val pr = prListModel.getElementAt(index)
        JPopupMenu().apply {
            add(JMenuItem("AI ile İncele…", AllIcons.Actions.Execute).apply { addActionListener { reviewSelected() } })
            add(JMenuItem("Değişiklikleri Göster", AllIcons.Actions.Diff).apply { addActionListener { session.showChanges(pr.ref) } })
            if (pr.url.isNotBlank()) {
                addSeparator()
                add(JMenuItem("Tarayıcıda Aç", AllIcons.General.Web).apply { addActionListener { BrowserUtil.browse(pr.url) } })
                add(JMenuItem("Bağlantıyı Kopyala", AllIcons.Actions.Copy).apply {
                    addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(pr.url)) }
                })
            }
        }.show(prList, e.x, e.y)
    }

    /** İki satırlı PR hücresi: başlık + repo/dal/yazar/zaman; sağda "AI incelendi" rozeti. */
    private inner class PullRequestRenderer : ListCellRenderer<PullRequest> {
        private val title = SimpleColoredComponent()
        private val meta = SimpleColoredComponent()
        private val badge = JBLabel()
        private val panel = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(4, 8)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(title)
                add(meta)
            }, BorderLayout.CENTER)
            add(badge, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out PullRequest>, pr: PullRequest, index: Int, selected: Boolean, focused: Boolean,
        ): Component {
            val bg = UIUtil.getListBackground(selected, list.hasFocus())
            val fg = UIUtil.getListForeground(selected, list.hasFocus())
            panel.background = bg
            title.clear()
            meta.clear()
            title.isOpaque = false
            meta.isOpaque = false
            title.append("#${pr.ref.pullRequestId}  ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, if (selected) fg else UIUtil.getContextHelpForeground()))
            title.append(pr.title, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, fg))
            val metaAttr = SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, if (selected) fg else UIUtil.getContextHelpForeground())
            meta.append("${pr.ref.projectKey}/${pr.ref.repositorySlug}", metaAttr)
            meta.append("  ·  ${pr.sourceBranch} → ${pr.destinationBranch}", metaAttr)
            if (pr.authorName.isNotBlank()) meta.append("  ·  ${pr.authorName}", metaAttr)
            FindingsPresenter.relativeTime(pr.updatedDate).takeIf { it.isNotEmpty() }?.let { meta.append("  ·  $it", metaAttr) }

            val reviewedHere = prKey(pr) in reviewedPrKeys ||
                session.history.any { it.outcome?.pullRequest?.ref == pr.ref && it.outcome?.commitHash == pr.latestCommitHash }
            badge.icon = if (reviewedHere) AllIcons.RunConfigurations.TestPassed else null
            badge.text = if (reviewedHere) "AI" else ""
            badge.foreground = fg
            badge.toolTipText = if (reviewedHere) "Son commit (${pr.latestCommitHash.take(12)}) AI ile incelendi" else null
            panel.toolTipText = "<html><b>${FindingsPresenter.escape(pr.title)}</b><br>${FindingsPresenter.escape(pr.sourceBranch)} → ${FindingsPresenter.escape(pr.destinationBranch)}<br>Commit: ${pr.latestCommitHash.take(12)}</html>"
            return panel
        }
    }

    // ------------------------------------------------------------------ durum

    private fun renderAutoStatus() {
        val settings = ReviewSettingsState.instance
        val auto = AutomaticReviewService.getInstance()
        val state = when {
            auto.isRunning -> "çalışıyor…"
            settings.automaticReviewEnabled -> "açık (${settings.automaticReviewIntervalMinutes} dk)"
            else -> "kapalı"
        }
        val last = auto.lastRunSummary?.let { " · Son: $it" } ?: ""
        autoStatusLabel.text = "Otomatik tarama: $state$last"
    }

    private fun renderSession() {
        val current = session.current
        val findings = current?.review?.findings ?: emptyList()
        // Yalnızca durum / hariç tutma değiştiğinde seçim/filtre sıfırlanmasın.
        if (findings !== renderedFindings) {
            renderedFindings = findings
            findingsView.setFindings(findings)
        } else {
            findingsView.refreshState()
        }
        val high = findings.count { it.severity == "high" }
        tabs.setTitleAt(0, if (high > 0) "Bulgular (${findings.size} · $high yüksek)" else "Bulgular (${findings.size})")

        renderSessionHeader(current)
        renderSummary(current)
        renderBottom(current)
        prList.repaint() // "AI incelendi" rozetleri
    }

    private fun renderSessionHeader(current: ReviewSession?) {
        val history = session.history
        updatingHistory = true
        try {
            historyCombo.model = DefaultComboBoxModel(history.toTypedArray())
            historyCombo.selectedItem = current?.let { c -> history.firstOrNull { it.id == c.id } }
            historyCombo.isVisible = history.size > 1 || (current == null && history.isNotEmpty())
        } finally {
            updatingHistory = false
        }
        if (current == null) {
            sessionTitleLabel.text = "<html><b>İnceleme yok</b></html>"
            sessionMetaLabel.text = if (history.isNotEmpty()) "Geçmişten bir inceleme seçebilirsiniz." else "Bir PR seçip ▶ ile inceleyin veya yerel değişiklikleri inceleyin."
            openDiffLink.isVisible = false
            openPrLink.isVisible = false
            return
        }
        val risk = current.review.riskLevel
        sessionTitleLabel.text = "<html>${SeverityStyle.badgeHtml("RİSK: ${risk.uppercase()}", SeverityStyle.color(risk))}&nbsp; <b>${FindingsPresenter.escape(current.title)}</b></html>"
        val outcome = current.outcome
        sessionMetaLabel.text = buildList {
            add(if (outcome == null) "Yerel inceleme" else when {
                outcome.published -> "Bitbucket'a gönderildi ✓"
                outcome.skippedDuplicate -> "Bu commit için yorum zaten var"
                else -> "Henüz gönderilmedi"
            })
            current.review.modelUsed?.let { add(it) }
            add(FindingsPresenter.relativeTime(current.createdAt))
        }.joinToString("  ·  ")
        openDiffLink.isVisible = outcome != null
        openPrLink.isVisible = outcome?.pullRequest?.url?.isNotBlank() == true
    }

    private fun renderSummary(current: ReviewSession?) {
        val muted = SeverityStyle.hex(UIUtil.getContextHelpForeground())
        if (current == null) {
            summaryPane.text = "<html><body><div style='color:$muted;'>Henüz inceleme özeti yok.</div></body></html>"
            return
        }
        val review = current.review
        val codeStyle = "font-family:monospace; background-color:${SeverityStyle.hex(SeverityStyle.CODE_BACKGROUND)};"
        val counts = FindingsPresenter.severityCounts(review.findings).entries.joinToString(" &nbsp; ") { (severity, count) ->
            val dot = "<span style='color:${SeverityStyle.hex(SeverityStyle.color(severity))};'>●</span>"
            if (count > 0) "$dot <a href='sev:$severity'>$count ${FindingsPresenter.severityLabel(severity).lowercase()}</a>"
            else "$dot <span style='color:$muted;'>0 ${FindingsPresenter.severityLabel(severity).lowercase()}</span>"
        }
        val tests = if (review.testSuggestions.isEmpty()) "" else
            "<p style='margin-top:12px; margin-bottom:2px;'><b>Test önerileri</b></p>" +
                review.testSuggestions.joinToString("") { "<div>☐ ${FindingsPresenter.textToHtml(it, codeStyle)}</div>" }
        summaryPane.text = """
            <html><body>
            <div>${SeverityStyle.badgeHtml("RİSK: ${review.riskLevel.uppercase()}", SeverityStyle.color(review.riskLevel))}</div>
            <div style='margin-top:6px;'><b>${FindingsPresenter.escape(current.title)}</b></div>
            <div style='color:$muted;'>${review.modelUsed?.let { "Model: ${FindingsPresenter.escape(it)}" } ?: ""}</div>
            <div style='color:$muted;'>${review.instructionSources?.let { "Talimatlar: ${FindingsPresenter.escape(it)}" } ?: ""}</div>
            <div style='margin-top:8px;'>$counts</div>
            <p style='margin-top:12px; margin-bottom:2px;'><b>Özet</b></p>
            <div>${FindingsPresenter.textToHtml(review.summary, codeStyle)}</div>
            $tests
            <p style='margin-top:12px; margin-bottom:2px;'><b>Değerlendirme</b></p>
            <div>${FindingsPresenter.textToHtml(review.finalComment, codeStyle)}</div>
            <p style='margin-top:14px; color:$muted;'><a href='copy'>Özeti Markdown olarak kopyala</a></p>
            </body></html>
        """.trimIndent()
        summaryPane.caretPosition = 0
    }

    private fun copySummary() {
        val current = session.current ?: return
        val review = current.review
        val text = buildString {
            append("## ").append(current.title).append("\n\n")
            append("**Risk:** ").append(review.riskLevel.uppercase()).append("\n\n")
            append("### Özet\n").append(review.summary).append("\n\n")
            if (review.testSuggestions.isNotEmpty()) {
                append("### Test önerileri\n")
                review.testSuggestions.forEach { append("- [ ] ").append(it).append('\n') }
                append('\n')
            }
            append("### Değerlendirme\n").append(review.finalComment)
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        statusLabel.text = "Özet panoya kopyalandı."
    }

    private fun renderBottom(current: ReviewSession?) {
        statusLabel.text = session.statusText
        statusLabel.icon = if (session.statusIsError) AllIcons.General.Error else null
        statusLabel.toolTipText = session.statusText

        val progress = session.progress
        progressPanel.isVisible = progress != null
        if (progress != null) {
            statusLabel.text = progress.text
            progressBar.isIndeterminate = progress.fraction == null
            progress.fraction?.let { progressBar.value = (it * 1000).toInt().coerceIn(0, 1000) }
        }

        val outcome = current?.outcome
        val canPublish = outcome != null && !outcome.published && !outcome.skippedDuplicate && progress == null
        publishBtn.isEnabled = canPublish
        publishBtn.text = when {
            outcome == null -> "Bitbucket'a Gönder"
            outcome.published -> "Gönderildi ✓"
            outcome.skippedDuplicate -> "Zaten yorumlanmış"
            else -> "Bitbucket'a Gönder (${current.includedFindings.size})"
        }
        publishBtn.toolTipText = outcome?.let {
            "Hedef: ${it.pullRequest.ref.projectKey}/${it.pullRequest.ref.repositorySlug} #${it.pullRequest.ref.pullRequestId}" +
                (if (current.excluded.isNotEmpty()) " · ${current.excluded.size} bulgu hariç" else "")
        } ?: "Yalnızca PR incelemeleri Bitbucket'a gönderilebilir"
        clearBtn.isEnabled = current != null
    }

    // ------------------------------------------------------------------ gezinme

    /** PR incelemesinde satır numaraları PR sürümüne ait olduğu için diff'te; yerel incelemede editörde açar. */
    private fun navigateTo(finding: ReviewFinding) {
        if (session.current?.outcome != null) {
            showFindingInDiff(finding.file, finding)
            return
        }
        openFileInEditor(finding)
    }

    private fun resolveFile(finding: ReviewFinding): java.io.File? {
        val root = session.current?.root ?: project.basePath ?: return null
        val relative = finding.file.replace(DiffLocations.PATH_PREFIX, "")
        return Paths.get(root).resolve(relative).normalize().toFile()
    }

    private fun openFileInEditor(finding: ReviewFinding): Boolean {
        val path = resolveFile(finding) ?: return false
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path)
        if (vFile == null) {
            statusLabel.text = "Dosya çalışma alanında bulunamadı: ${finding.file}"
            return false
        }
        OpenFileDescriptor(project, vFile, ((finding.line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
        val current = session.current
        if (current?.outcome != null && !current.workingCopyMatches) {
            statusLabel.text = "Uyarı: yerel dosya PR sürümünden farklı olabilir; satırlar kaymış olabilir."
        }
        return true
    }

    private fun showFindingInDiff(path: String, finding: ReviewFinding?) {
        val ref = session.current?.outcome?.pullRequest?.ref
        if (ref == null) {
            finding?.let { openFileInEditor(it) }
            return
        }
        session.showChanges(ref, PullRequestDiffViewer.Focus(path, finding?.line, finding?.side))
    }

    /** Yerel çalışma kopyası incelenen kodla aynıysa bulgu satırının ±3 satırı. */
    private fun readSnippet(finding: ReviewFinding): List<Pair<Int, String>>? {
        val current = session.current ?: return null
        val line = finding.line ?: return null
        if (!current.workingCopyMatches || finding.side == "old") return null
        val file = resolveFile(finding) ?: return null
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return null
        if (vFile.isDirectory || vFile.length > MAX_SNIPPET_FILE_BYTES) return null
        val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return null
        val index = line - 1
        if (index !in 0 until document.lineCount) return null
        return (maxOf(0, index - SNIPPET_CONTEXT)..minOf(document.lineCount - 1, index + SNIPPET_CONTEXT)).map { i ->
            val text = document.getText(com.intellij.openapi.util.TextRange(document.getLineStartOffset(i), document.getLineEndOffset(i)))
            (i + 1) to text.take(200)
        }
    }

    companion object {
        private const val SNIPPET_CONTEXT = 3
        private const val MAX_SNIPPET_FILE_BYTES = 2L * 1024 * 1024
    }
}
