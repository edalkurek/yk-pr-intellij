package com.ykb.review.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.OccurenceNavigator
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import com.ykb.review.ai.ReviewConstants
import com.ykb.review.ai.ReviewFinding
import com.ykb.review.review.FindingsPresenter
import com.ykb.review.review.FindingsPresenter.GroupBy
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent
import javax.swing.plaf.basic.BasicComboBoxRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Bulgular paneli: solda araç çubuğu (gezinme, gruplama, kopyalama, yayından hariç tutma), üstte önem/tür filtreleri,
 * ortada gruplu ağaç, altta seçili bulgunun tam metni ve (varsa) kod parçası.
 */
class FindingsView(private val actions: Actions) : JPanel(BorderLayout()), OccurenceNavigator {

    interface Actions {
        /** Çift tıklama / Enter: PR incelemesinde diff'e, yerel incelemede editöre gider. */
        fun activate(finding: ReviewFinding)
        fun openInEditor(finding: ReviewFinding)
        fun canShowInDiff(): Boolean
        fun showInDiff(path: String, finding: ReviewFinding?)

        /** Yayından hariç tutma yalnızca henüz yayınlanmamış PR incelemelerinde anlamlıdır. */
        fun canExclude(): Boolean
        fun isExcluded(finding: ReviewFinding): Boolean
        fun setExcluded(findings: List<ReviewFinding>, exclude: Boolean)

        /** Bulgu satırı çevresindeki kod (satır no → metin); yerel çalışma kopyası incelenen kodla aynı değilse null. */
        fun snippet(finding: ReviewFinding): List<Pair<Int, String>>?

        /** Kopyalanan Markdown belgesinin başlığı. */
        fun title(): String?
    }

    /** Ağaçtaki grup: dosya, önem veya tür. */
    private class GroupNode(val kind: GroupBy, val key: String, val findings: List<ReviewFinding>) : DefaultMutableTreeNode(key) {
        val fileGroup: FindingsPresenter.FileGroup? = if (kind == GroupBy.FILE) FindingsPresenter.FileGroup(key, findings) else null
        override fun toString(): String = when (kind) {
            GroupBy.FILE -> key
            GroupBy.SEVERITY -> FindingsPresenter.severityLabel(key)
            GroupBy.TYPE -> FindingsPresenter.typeLabel(key)
        }
    }

    private class FindingNode(val finding: ReviewFinding) : DefaultMutableTreeNode(finding) {
        override fun toString(): String =
            "${FindingsPresenter.typeLabel(finding.type)} ${FindingsPresenter.headline(finding.message)} ${FindingsPresenter.normalizePath(finding.file)}"
    }

    private var allFindings: List<ReviewFinding> = emptyList()
    private var groupBy: GroupBy = runCatching { GroupBy.valueOf(PropertiesComponent.getInstance().getValue(GROUP_BY_KEY, GroupBy.FILE.name)) }
        .getOrDefault(GroupBy.FILE)

    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)
    private val detail = JEditorPane()

    private val severityChecks = linkedMapOf(
        "high" to JBCheckBox(),
        "medium" to JBCheckBox(),
        "low" to JBCheckBox(),
    )
    private val typeCombo = ComboBox(DefaultComboBoxModel<String?>())
    private val countLabel = JBLabel()

    init {
        // ---- filtreler
        severityChecks.forEach { (severity, check) ->
            check.isSelected = true
            check.toolTipText = "${FindingsPresenter.severityLabel(severity)} önemli bulguları göster"
            check.addActionListener { rebuild() }
        }
        typeCombo.renderer = object : BasicComboBoxRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                val text = (value as? String)?.let { FindingsPresenter.typeLabel(it) } ?: "Tüm türler"
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
        typeCombo.addActionListener { rebuild() }
        countLabel.foreground = UIUtil.getContextHelpForeground()

        val filterBar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.empty(2, 0, 4, 0)
            severityChecks.forEach { (severity, check) ->
                add(JBLabel(SeverityStyle.dot(severity)))
                add(check)
            }
            add(typeCombo)
            add(countLabel)
        }

        // ---- ağaç
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.emptyText.text = "Gösterilecek bulgu yok."
        tree.cellRenderer = Renderer()
        tree.addTreeSelectionListener { renderDetail() }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) activateSelection()
            }

            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> activateSelection()
                    KeyEvent.VK_SPACE, KeyEvent.VK_DELETE -> if (actions.canExclude()) toggleExcludedSelection()
                    else -> return
                }
                e.consume()
            }
        })
        TreeSpeedSearch.installOn(tree, false) { path -> path.lastPathComponent?.toString().orEmpty() }

        // ---- detay
        detail.isEditable = false
        detail.editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        detail.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        detail.font = UIUtil.getLabelFont()
        detail.border = JBUI.Borders.empty(8)
        detail.addHyperlinkListener { e ->
            if (e.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val finding = selectedFinding()
            val group = selectedGroup()
            when (e.description) {
                "open" -> finding?.let { actions.openInEditor(it) }
                "diff" -> (finding?.file ?: group?.fileGroup?.path)?.let { actions.showInDiff(it, finding) }
                "copy" -> finding?.let { copy(listOf(it)) }
                "exclude" -> toggleExcludedSelection()
            }
        }

        val splitter = OnePixelSplitter(true, "YKB.Review.FindingsSplitter", 0.5f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
            secondComponent = ScrollPaneFactory.createScrollPane(detail, true)
        }

        val toolbar = ActionManager.getInstance().createActionToolbar("YKB.Review.Findings", createToolbarActions(), false)
        toolbar.targetComponent = this

        add(toolbar.component, BorderLayout.WEST)
        add(JPanel(BorderLayout()).apply {
            add(filterBar, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        setFindings(emptyList())
    }

    private fun createToolbarActions(): DefaultActionGroup {
        val common = CommonActionsManager.getInstance()
        val expander = DefaultTreeExpander(tree)
        val groupActions = DefaultActionGroup("Grupla", true).apply {
            templatePresentation.icon = AllIcons.Actions.GroupBy
            GroupBy.values().forEach { mode ->
                add(object : ToggleAction("${mode.label} bazında grupla") {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun isSelected(e: AnActionEvent) = groupBy == mode
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        if (state) setGroupBy(mode)
                    }
                })
            }
        }
        return DefaultActionGroup().apply {
            add(common.createPrevOccurenceAction(this@FindingsView))
            add(common.createNextOccurenceAction(this@FindingsView))
            add(common.createExpandAllAction(expander, tree))
            add(common.createCollapseAllAction(expander, tree))
            addSeparator()
            add(groupActions)
            addSeparator()
            add(object : DumbAwareAction("Görünen Bulguları Markdown Olarak Kopyala", null, AllIcons.Actions.Copy) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = visibleFindings().isNotEmpty()
                }

                override fun actionPerformed(e: AnActionEvent) = copy(visibleFindings())
            })
            add(object : DumbAwareAction("Yayından Hariç Tut / Dahil Et (Boşluk)", null, AllIcons.Actions.Cancel) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = actions.canExclude() && selectedFindings().isNotEmpty()
                }

                override fun actionPerformed(e: AnActionEvent) = toggleExcludedSelection()
            })
        }
    }

    fun setFindings(findings: List<ReviewFinding>) {
        allFindings = findings
        severityChecks.forEach { (severity, check) ->
            check.text = "${FindingsPresenter.severityLabel(severity)} (${findings.count { it.severity == severity }})"
        }
        val previous = typeCombo.selectedItem as? String
        val types = findings.map { it.type }.distinct().sortedBy { ReviewConstants.FINDING_TYPES.indexOf(it) }
        typeCombo.model = DefaultComboBoxModel<String?>((listOf<String?>(null) + types).toTypedArray())
        typeCombo.selectedItem = previous?.takeIf { it in types }
        typeCombo.isVisible = types.size > 1
        rebuild()
    }

    /** Hariç tutma durumu değişti: ağacı yeniden kurmadan (seçim korunarak) yeniden çiz. */
    fun refreshState() {
        tree.repaint()
        renderDetail()
    }

    /** Dışarıdan (editör / diff gutter ikonu) bir bulguyu seç; filtrelerce gizlenmişse filtreleri sıfırla. */
    fun focus(finding: ReviewFinding) {
        var node = findNode(finding)
        if (node == null && finding in allFindings) {
            severityChecks.values.forEach { it.isSelected = true }
            typeCombo.selectedItem = null
            rebuild()
            node = findNode(finding)
        }
        node ?: return
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        tree.requestFocusInWindow()
    }

    /** Özet sekmesindeki sayılardan: yalnızca verilen önemdeki bulguları göster. */
    fun showOnly(severity: String) {
        severityChecks.forEach { (s, check) -> check.isSelected = s == severity }
        typeCombo.selectedItem = null
        rebuild()
    }

    private fun setGroupBy(mode: GroupBy) {
        if (groupBy == mode) return
        val selected = selectedFinding()
        groupBy = mode
        PropertiesComponent.getInstance().setValue(GROUP_BY_KEY, mode.name)
        rebuild()
        selected?.let { focus(it) }
    }

    private fun visibleFindings(): List<ReviewFinding> = findingNodes().map { it.finding }

    private fun rebuild() {
        val severities = severityChecks.filterValues { it.isSelected }.keys
        val visible = FindingsPresenter.filter(allFindings, severities, typeCombo.selectedItem as? String)
        root.removeAllChildren()
        val groups: List<Pair<String, List<ReviewFinding>>> = when (groupBy) {
            GroupBy.FILE -> FindingsPresenter.group(visible).map { it.path to it.findings }
            GroupBy.SEVERITY -> FindingsPresenter.groupBySeverity(visible).map { it.key to it.findings }
            GroupBy.TYPE -> FindingsPresenter.groupByType(visible).map { it.key to it.findings }
        }
        for ((key, items) in groups) {
            val groupNode = GroupNode(groupBy, key, items)
            items.forEach { groupNode.add(FindingNode(it)) }
            root.add(groupNode)
        }
        treeModel.reload()
        TreeUtil.expandAll(tree)
        countLabel.text = if (visible.size == allFindings.size) "" else "${visible.size}/${allFindings.size} gösteriliyor"
        tree.emptyText.text = if (allFindings.isEmpty()) "Gösterilecek bulgu yok." else "Filtrelere uyan bulgu yok."
        // İlk bulguyu seç: detay paneli hemen dolu gelsin.
        val first = findingNodes().firstOrNull()
        if (first != null) tree.selectionPath = TreePath(first.path) else renderDetail()
    }

    private fun findingNodes(): List<FindingNode> =
        (0 until root.childCount).flatMap { i ->
            val group = root.getChildAt(i)
            (0 until group.childCount).mapNotNull { group.getChildAt(it) as? FindingNode }
        }

    private fun findNode(finding: ReviewFinding): FindingNode? = findingNodes().firstOrNull { it.finding == finding }

    private fun selectedNode(): Any? = tree.selectionPath?.lastPathComponent
    private fun selectedFinding(): ReviewFinding? = (selectedNode() as? FindingNode)?.finding
    private fun selectedGroup(): GroupNode? = when (val node = selectedNode()) {
        is GroupNode -> node
        is FindingNode -> node.parent as? GroupNode
        else -> null
    }

    /** Seçili bulgu veya seçili gruptaki tüm bulgular. */
    private fun selectedFindings(): List<ReviewFinding> = when (val node = selectedNode()) {
        is FindingNode -> listOf(node.finding)
        is GroupNode -> node.findings
        else -> emptyList()
    }

    private fun toggleExcludedSelection() {
        val targets = selectedFindings().ifEmpty { return }
        // Grupta en az biri dahilse hepsini hariç tut; hepsi hariçse hepsini dahil et.
        val exclude = targets.any { !actions.isExcluded(it) }
        actions.setExcluded(targets, exclude)
    }

    private fun activateSelection() {
        when (val node = selectedNode()) {
            is FindingNode -> actions.activate(node.finding)
            is GroupNode -> node.findings.firstOrNull()?.let { actions.activate(it) }
        }
    }

    private fun copy(findings: List<ReviewFinding>) {
        if (findings.isEmpty()) return
        val text = if (findings.size == 1) FindingsPresenter.toMarkdown(findings[0]) else FindingsPresenter.toMarkdown(findings, actions.title())
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun maybeShowPopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        tree.selectionPath = path
        val finding = selectedFinding()
        val group = selectedGroup() ?: return
        JPopupMenu().apply {
            add(JMenuItem("Dosyada Aç", AllIcons.Actions.EditSource).apply {
                addActionListener { (finding ?: group.findings.first()).let { actions.openInEditor(it) } }
            })
            add(JMenuItem("PR Diff'inde Göster", AllIcons.Actions.Diff).apply {
                isEnabled = actions.canShowInDiff()
                addActionListener {
                    val target = finding ?: group.findings.first()
                    actions.showInDiff(target.file, finding)
                }
            })
            addSeparator()
            add(JMenuItem(if (finding != null) "Markdown Olarak Kopyala" else "Gruptakileri Markdown Olarak Kopyala", AllIcons.Actions.Copy).apply {
                addActionListener { copy(finding?.let { listOf(it) } ?: group.findings) }
            })
            if (actions.canExclude()) {
                val targets = finding?.let { listOf(it) } ?: group.findings
                val exclude = targets.any { !actions.isExcluded(it) }
                add(JMenuItem(if (exclude) "Yayından Hariç Tut" else "Yayına Dahil Et", AllIcons.Actions.Cancel).apply {
                    addActionListener { actions.setExcluded(targets, exclude) }
                })
            }
        }.show(tree, e.x, e.y)
    }

    // ------------------------------------------------------------------ OccurenceNavigator (Ctrl+Alt+↑/↓)

    private fun occurrenceIndex(): Int {
        val nodes = findingNodes()
        return when (val node = selectedNode()) {
            is FindingNode -> nodes.indexOf(node)
            is GroupNode -> nodes.indexOfFirst { it.parent === node } - 1
            else -> -1
        }
    }

    override fun hasNextOccurence(): Boolean = occurrenceIndex() < findingNodes().size - 1
    override fun hasPreviousOccurence(): Boolean = occurrenceIndex() > 0
    override fun goNextOccurence(): OccurenceNavigator.OccurenceInfo? = goTo(occurrenceIndex() + 1)
    override fun goPreviousOccurence(): OccurenceNavigator.OccurenceInfo? = goTo(occurrenceIndex() - 1)
    override fun getNextOccurenceActionName(): String = "Sonraki Bulgu"
    override fun getPreviousOccurenceActionName(): String = "Önceki Bulgu"

    private fun goTo(index: Int): OccurenceNavigator.OccurenceInfo? {
        val nodes = findingNodes()
        val node = nodes.getOrNull(index) ?: return null
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        return OccurenceNavigator.OccurenceInfo(null, index + 1, nodes.size)
    }

    // ------------------------------------------------------------------ detay HTML

    private fun renderDetail() {
        val codeStyle = "font-family:monospace; background-color:${SeverityStyle.hex(SeverityStyle.CODE_BACKGROUND)};"
        val muted = SeverityStyle.hex(UIUtil.getContextHelpForeground())
        val html = when (val node = selectedNode()) {
            is FindingNode -> {
                val f = node.finding
                val excluded = actions.canExclude() && actions.isExcluded(f)
                val links = buildList {
                    add("<a href='open'>Dosyada aç</a>")
                    if (actions.canShowInDiff()) add("<a href='diff'>PR diff'inde göster</a>")
                    add("<a href='copy'>Markdown kopyala</a>")
                    if (actions.canExclude()) add(if (excluded) "<a href='exclude'>Yayına dahil et</a>" else "<a href='exclude'>Yayından hariç tut</a>")
                }.joinToString(" &nbsp;·&nbsp; ")
                val excludedNote = if (excluded) {
                    "<div style='color:$muted; margin-top:4px;'><i>Bu bulgu Bitbucket'a gönderilmeyecek.</i></div>"
                } else {
                    ""
                }
                """
                <div><span style='color:${SeverityStyle.hex(SeverityStyle.color(f.severity))}; font-weight:bold;'>● ${FindingsPresenter.severityLabel(f.severity)}</span>
                &nbsp;&nbsp;<b>${FindingsPresenter.typeLabel(f.type)}</b></div>
                <div style='color:$muted; margin-top:2px;'>${FindingsPresenter.escape(FindingsPresenter.location(f))}</div>
                $excludedNote
                ${snippetHtml(f, muted)}
                <p style='margin-top:10px; margin-bottom:2px;'><b>Sorun</b></p>
                <div>${FindingsPresenter.textToHtml(f.message, codeStyle)}</div>
                <p style='margin-top:10px; margin-bottom:2px;'><b>Düzeltme önerisi</b></p>
                <div>${FindingsPresenter.textToHtml(f.suggestion, codeStyle)}</div>
                <p style='margin-top:12px; color:$muted;'>$links</p>
                """
            }
            is GroupNode -> {
                val title = when (node.kind) {
                    GroupBy.FILE -> node.fileGroup!!.fileName
                    GroupBy.SEVERITY -> "${FindingsPresenter.severityLabel(node.key)} önemli bulgular"
                    GroupBy.TYPE -> "${FindingsPresenter.typeLabel(node.key)} bulguları"
                }
                val subtitle = if (node.kind == GroupBy.FILE) "${FindingsPresenter.escape(node.key)} · " else ""
                val items = node.findings.joinToString("") { f ->
                    val where = if (node.kind == GroupBy.FILE) f.line?.let { ":$it" }.orEmpty() else FindingsPresenter.location(f)
                    "<li><span style='color:${SeverityStyle.hex(SeverityStyle.color(f.severity))};'>●</span> " +
                        "<b>${FindingsPresenter.typeLabel(f.type)}</b> <span style='color:$muted;'>${FindingsPresenter.escape(where)}</span> — " +
                        "${FindingsPresenter.escape(FindingsPresenter.headline(f.message))}</li>"
                }
                val diffLink = if (node.kind == GroupBy.FILE && actions.canShowInDiff()) "<p style='margin-top:10px;'><a href='diff'>PR diff'inde göster</a></p>" else ""
                """
                <div><b>${FindingsPresenter.escape(title)}</b></div>
                <div style='color:$muted;'>$subtitle${node.findings.size} bulgu</div>
                <ul style='margin-top:8px; margin-left:16px;'>$items</ul>
                $diffLink
                """
            }
            else -> "<div style='color:$muted;'>${if (allFindings.isEmpty()) "Henüz bulgu yok." else "Ayrıntıları görmek için bir bulgu seçin."}</div>"
        }
        detail.text = "<html><body>$html</body></html>"
        detail.caretPosition = 0
    }

    /** Bulgu satırı çevresindeki kodu satır numaralarıyla, bulgu satırı vurgulu gösterir. */
    private fun snippetHtml(f: ReviewFinding, muted: String): String {
        val lines = actions.snippet(f)?.takeIf { it.isNotEmpty() } ?: return ""
        val highlight = SeverityStyle.hex(SeverityStyle.color(f.severity))
        val width = lines.maxOf { it.first }.toString().length
        val body = lines.joinToString("<br>") { (number, text) ->
            val code = FindingsPresenter.escape(text.replace("\t", "    ")).replace(" ", "&nbsp;")
            val num = number.toString().padStart(width).replace(" ", "&nbsp;")
            if (number == f.line) "<span style='color:$highlight;'><b>$num&nbsp;▶&nbsp;$code</b></span>"
            else "<span style='color:$muted;'>$num</span>&nbsp;&nbsp;&nbsp;$code"
        }
        return "<div style='margin-top:8px; padding:4px; font-family:monospace; background-color:${SeverityStyle.hex(SeverityStyle.CODE_BACKGROUND)};'>$body</div>"
    }

    // ------------------------------------------------------------------ ağaç görünümü

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (value) {
                is GroupNode -> {
                    when (value.kind) {
                        GroupBy.FILE -> {
                            val g = value.fileGroup!!
                            icon = FileTypeManager.getInstance().getFileTypeByFileName(g.fileName).icon ?: AllIcons.FileTypes.Any_type
                            append(g.fileName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                            if (g.directory.isNotEmpty()) append("  ${g.directory}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                            toolTipText = g.path
                        }
                        GroupBy.SEVERITY -> {
                            icon = SeverityStyle.dot(value.key)
                            append(FindingsPresenter.severityLabel(value.key), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                            toolTipText = null
                        }
                        GroupBy.TYPE -> {
                            icon = AllIcons.Nodes.Folder
                            append(FindingsPresenter.typeLabel(value.key), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                            toolTipText = null
                        }
                    }
                    // Önem dağılımı: ●2 ●1
                    if (value.kind == GroupBy.SEVERITY) {
                        append("  ${value.findings.size}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    } else {
                        FindingsPresenter.severityCounts(value.findings).filterValues { it > 0 }.forEach { (severity, count) ->
                            append("  ●$count", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, SeverityStyle.color(severity)))
                        }
                    }
                }
                is FindingNode -> {
                    val f = value.finding
                    val excluded = actions.canExclude() && actions.isExcluded(f)
                    icon = SeverityStyle.dot(f.severity)
                    if (excluded) {
                        append(FindingsPresenter.typeLabel(f.type), SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, UIUtil.getInactiveTextColor()))
                        append("  ")
                        append(FindingsPresenter.headline(f.message), SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, UIUtil.getInactiveTextColor()))
                        append("  (yayın dışı)", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    } else {
                        append(FindingsPresenter.typeLabel(f.type), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, SeverityStyle.color(f.severity)))
                        append("  ")
                        append(FindingsPresenter.headline(f.message))
                    }
                    val where = if (groupBy == GroupBy.FILE) f.line?.let { ":$it" } else FindingsPresenter.normalizePath(f.file).substringAfterLast('/') + (f.line?.let { ":$it" } ?: "")
                    where?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    toolTipText = "<html><body style='width:420px'>${FindingsPresenter.escape(f.message)}</body></html>"
                }
            }
        }
    }

    companion object {
        private const val GROUP_BY_KEY = "YKB.Review.FindingsGroupBy"

        /** Geriye dönük uyumluluk: eski çağrılar için ortak renk kaynağına yönlendirir. */
        fun severityColor(severity: String) = SeverityStyle.color(severity)
    }
}
