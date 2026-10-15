package com.ykb.review.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
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
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
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
 * Bulgular paneli: üstte önem/tür filtreleri, ortada dosyaya göre gruplu ağaç, altta seçili bulgunun tam metni.
 * Eski 6 sütunlu tablonun yerini alır (uzun mesaj/öneri metinleri kesilmeden, satır kaydırmalı gösterilir).
 */
class FindingsView(private val actions: Actions) : JPanel(BorderLayout()) {

    interface Actions {
        /** Çift tıklama / Enter: PR incelemesinde diff'e, yerel incelemede editöre gider. */
        fun activate(finding: ReviewFinding)
        fun openInEditor(finding: ReviewFinding)
        fun canShowInDiff(): Boolean
        fun showInDiff(path: String, finding: ReviewFinding?)
    }

    private class FileNode(val group: FindingsPresenter.FileGroup) : DefaultMutableTreeNode(group)
    private class FindingNode(val finding: ReviewFinding) : DefaultMutableTreeNode(finding)

    private var allFindings: List<ReviewFinding> = emptyList()

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
            check.icon = null
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
            border = JBUI.Borders.emptyBottom(4)
            severityChecks.forEach { (severity, check) ->
                add(JBLabel(SeverityIcon(severity)))
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
                if (e.keyCode == KeyEvent.VK_ENTER) activateSelection()
            }
        })

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
                "diff" -> (finding?.file ?: group?.path)?.let { actions.showInDiff(it, finding) }
                "copy" -> finding?.let { copy(it) }
            }
        }

        val splitter = OnePixelSplitter(true, 0.5f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(tree, true)
            secondComponent = ScrollPaneFactory.createScrollPane(detail, true)
        }
        add(filterBar, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        setFindings(emptyList())
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

    private fun rebuild() {
        val severities = severityChecks.filterValues { it.isSelected }.keys
        val visible = FindingsPresenter.filter(allFindings, severities, typeCombo.selectedItem as? String)
        root.removeAllChildren()
        for (group in FindingsPresenter.group(visible)) {
            val fileNode = FileNode(group)
            group.findings.forEach { fileNode.add(FindingNode(it)) }
            root.add(fileNode)
        }
        treeModel.reload()
        TreeUtil.expandAll(tree)
        countLabel.text = if (visible.size == allFindings.size) "" else "${visible.size}/${allFindings.size} gösteriliyor"
        tree.emptyText.text = if (allFindings.isEmpty()) "Gösterilecek bulgu yok." else "Filtrelere uyan bulgu yok."
        // İlk bulguyu seç: detay paneli hemen dolu gelsin.
        val firstFinding = if (root.childCount > 0) (root.getChildAt(0) as DefaultMutableTreeNode).firstChild as? DefaultMutableTreeNode else null
        if (firstFinding != null) tree.selectionPath = TreePath(firstFinding.path) else renderDetail()
    }

    private fun selectedNode(): Any? = tree.selectionPath?.lastPathComponent
    private fun selectedFinding(): ReviewFinding? = (selectedNode() as? FindingNode)?.finding
    private fun selectedGroup(): FindingsPresenter.FileGroup? = when (val node = selectedNode()) {
        is FileNode -> node.group
        is FindingNode -> (node.parent as? FileNode)?.group
        else -> null
    }

    private fun activateSelection() {
        when (val node = selectedNode()) {
            is FindingNode -> actions.activate(node.finding)
            is FileNode -> node.group.findings.firstOrNull()?.let { actions.activate(it) }
        }
    }

    private fun copy(finding: ReviewFinding) {
        CopyPasteManager.getInstance().setContents(StringSelection(FindingsPresenter.toMarkdown(finding)))
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
                addActionListener { actions.showInDiff(group.path, finding) }
            })
            if (finding != null) {
                addSeparator()
                add(JMenuItem("Markdown Olarak Kopyala", AllIcons.Actions.Copy).apply { addActionListener { copy(finding) } })
            }
        }.show(tree, e.x, e.y)
    }

    // ------------------------------------------------------------------ detay HTML

    private fun renderDetail() {
        val codeStyle = "font-family:monospace; background-color:${hex(CODE_BACKGROUND)};"
        val muted = hex(UIUtil.getContextHelpForeground())
        val html = when (val node = selectedNode()) {
            is FindingNode -> {
                val f = node.finding
                val links = buildList {
                    add("<a href='open'>Dosyada aç</a>")
                    if (actions.canShowInDiff()) add("<a href='diff'>PR diff'inde göster</a>")
                    add("<a href='copy'>Markdown kopyala</a>")
                }.joinToString(" &nbsp;·&nbsp; ")
                """
                <div><span style='color:${hex(severityColor(f.severity))}; font-weight:bold;'>● ${FindingsPresenter.severityLabel(f.severity)}</span>
                &nbsp;&nbsp;<b>${FindingsPresenter.typeLabel(f.type)}</b></div>
                <div style='color:$muted; margin-top:2px;'>${FindingsPresenter.escape(FindingsPresenter.location(f))}</div>
                <p style='margin-top:10px; margin-bottom:2px;'><b>Sorun</b></p>
                <div>${FindingsPresenter.textToHtml(f.message, codeStyle)}</div>
                <p style='margin-top:10px; margin-bottom:2px;'><b>Düzeltme önerisi</b></p>
                <div>${FindingsPresenter.textToHtml(f.suggestion, codeStyle)}</div>
                <p style='margin-top:12px; color:$muted;'>$links</p>
                """
            }
            is FileNode -> {
                val g = node.group
                val items = g.findings.joinToString("") { f ->
                    "<li><span style='color:${hex(severityColor(f.severity))};'>●</span> " +
                        "<b>${FindingsPresenter.typeLabel(f.type)}</b>${f.line?.let { " <span style='color:$muted;'>:$it</span>" } ?: ""} — " +
                        "${FindingsPresenter.escape(FindingsPresenter.headline(f.message))}</li>"
                }
                val diffLink = if (actions.canShowInDiff()) "<p style='margin-top:10px;'><a href='diff'>PR diff'inde göster</a></p>" else ""
                """
                <div><b>${FindingsPresenter.escape(g.fileName)}</b></div>
                <div style='color:$muted;'>${FindingsPresenter.escape(g.path)} · ${g.findings.size} bulgu</div>
                <ul style='margin-top:8px; margin-left:16px;'>$items</ul>
                $diffLink
                """
            }
            else -> "<div style='color:$muted;'>${if (allFindings.isEmpty()) "Henüz bulgu yok." else "Ayrıntıları görmek için bir bulgu seçin."}</div>"
        }
        detail.text = "<html><body>$html</body></html>"
        detail.caretPosition = 0
    }

    // ------------------------------------------------------------------ ağaç görünümü

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (value) {
                is FileNode -> {
                    val g = value.group
                    icon = FileTypeManager.getInstance().getFileTypeByFileName(g.fileName).icon ?: AllIcons.FileTypes.Any_type
                    append(g.fileName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    if (g.directory.isNotEmpty()) append("  ${g.directory}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  ${g.findings.size}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    toolTipText = g.path
                }
                is FindingNode -> {
                    val f = value.finding
                    icon = SeverityIcon(f.severity)
                    append(FindingsPresenter.typeLabel(f.type), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, severityColor(f.severity)))
                    append("  ")
                    append(FindingsPresenter.headline(f.message))
                    f.line?.let { append("  :$it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    toolTipText = "<html><body style='width:420px'>${FindingsPresenter.escape(f.message)}</body></html>"
                }
            }
        }
    }

    private class SeverityIcon(severity: String) : Icon {
        private val color = severityColor(severity)
        private val size = JBUI.scale(10)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillOval(x + 1, y + 1, size - 2, size - 2)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth() = size
        override fun getIconHeight() = size
    }

    companion object {
        private val HIGH = JBColor(Color(0xCF222E), Color(0xF85149))
        private val MEDIUM = JBColor(Color(0xBF8700), Color(0xD29922))
        private val LOW = JBColor(Color(0x1A7F37), Color(0x3FB950))
        private val CODE_BACKGROUND = JBColor(Color(0xF0F1F3), Color(0x2B2D30))

        fun severityColor(severity: String): Color = when (severity) {
            "high" -> HIGH
            "medium" -> MEDIUM
            else -> LOW
        }

        private fun hex(color: Color) = "#%02x%02x%02x".format(color.red, color.green, color.blue)
    }
}
