package com.ykb.review.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.ykb.review.ai.ReviewFinding
import java.awt.Color
import java.nio.file.Paths
import java.util.WeakHashMap
import javax.swing.Icon

/**
 * Bulguları açık editörlerde satır vurgusu + gutter ikonu + kaydırma çubuğu işareti olarak gösterir
 * (VS Code diagnosticsReporter.ts / Problems karşılığı).
 */
@Service(Service.Level.PROJECT)
class FindingsHighlighter(private val project: Project) : Disposable {

    private var findingsByPath: Map<String, List<ReviewFinding>> = emptyMap()
    private val highlighters = WeakHashMap<Editor, MutableList<RangeHighlighter>>()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                if (editor.project == null || editor.project == project) apply(editor)
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                highlighters.remove(event.editor)
            }
        }, this)
    }

    fun report(root: String, findings: List<ReviewFinding>) {
        val map = findings
            .filter { it.file.isNotBlank() && it.side != "old" }
            .groupBy { normalize(root, it.file) }
        ApplicationManager.getApplication().invokeLater({
            clearHighlighters()
            findingsByPath = map
            EditorFactory.getInstance().allEditors.filter { it.project == null || it.project == project }.forEach { apply(it) }
        }, project.disposed)
    }

    fun clear() {
        ApplicationManager.getApplication().invokeLater({
            findingsByPath = emptyMap()
            clearHighlighters()
        }, project.disposed)
    }

    private fun clearHighlighters() {
        for ((editor, list) in highlighters) {
            if (!editor.isDisposed) list.forEach { editor.markupModel.removeHighlighter(it) }
        }
        highlighters.clear()
    }

    private fun apply(editor: Editor) {
        if (findingsByPath.isEmpty()) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val findings = findingsByPath[Paths.get(file.path).normalize().toString().lowercase()] ?: return
        val document = editor.document
        val list = highlighters.getOrPut(editor) { mutableListOf() }
        for (finding in findings) {
            val line = ((finding.line ?: 1) - 1).coerceAtLeast(0)
            if (line >= document.lineCount) continue
            val color = colorFor(finding.severity)
            val attributes = TextAttributes().apply {
                effectType = EffectType.WAVE_UNDERSCORE
                effectColor = color
                errorStripeColor = color
            }
            val highlighter = editor.markupModel.addLineHighlighter(line, HighlighterLayer.WARNING, attributes)
            val tooltip = "YKB AI Review [${finding.severity.uppercase()} · ${finding.type}]: ${finding.message}\nÖneri: ${finding.suggestion}"
            highlighter.errorStripeTooltip = tooltip
            highlighter.gutterIconRenderer = FindingGutterIcon(iconFor(finding.severity), tooltip)
            list.add(highlighter)
        }
    }

    private fun normalize(root: String, file: String): String {
        val relative = file.replace(Regex("^[ab]/"), "")
        val path = Paths.get(relative).let { if (it.isAbsolute) it else Paths.get(root).resolve(relative) }
        return path.normalize().toString().lowercase()
    }

    private fun colorFor(severity: String): Color = when (severity) {
        "high" -> JBColor.RED
        "medium" -> JBColor.ORANGE
        else -> JBColor.BLUE
    }

    private fun iconFor(severity: String): Icon = when (severity) {
        "high" -> AllIcons.General.Error
        "medium" -> AllIcons.General.Warning
        else -> AllIcons.General.Information
    }

    override fun dispose() {
        highlighters.clear()
    }

    private class FindingGutterIcon(private val icon: Icon, private val tooltip: String) : GutterIconRenderer() {
        override fun getIcon(): Icon = icon
        override fun getTooltipText(): String = tooltip
        override fun equals(other: Any?): Boolean = other is FindingGutterIcon && other.tooltip == tooltip
        override fun hashCode(): Int = tooltip.hashCode()
    }

    companion object {
        fun getInstance(project: Project): FindingsHighlighter = project.getService(FindingsHighlighter::class.java)
    }
}
