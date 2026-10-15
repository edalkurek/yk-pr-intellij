package com.ykb.review.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.ykb.review.ai.ReviewFinding
import java.nio.file.Paths
import java.util.WeakHashMap

/**
 * Bulguları açık editörlerde satır vurgusu + tıklanabilir gutter ikonu + kaydırma çubuğu işareti + satır sonu açıklaması
 * olarak gösterir (VS Code diagnosticsReporter.ts / Problems karşılığı). Görünüm [FindingMarkup] ile ortaktır.
 */
@Service(Service.Level.PROJECT)
class FindingsHighlighter(private val project: Project) : Disposable {

    private var findingsByPath: Map<String, List<ReviewFinding>> = emptyMap()
    private val applied = WeakHashMap<Editor, FindingMarkup.Applied>()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                if (editor.project == null || editor.project == project) apply(editor)
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                applied.remove(event.editor)
            }
        }, this)
    }

    fun report(root: String, findings: List<ReviewFinding>) {
        val map = findings
            .filter { it.file.isNotBlank() && it.side != "old" }
            .groupBy { normalize(root, it.file) }
        ApplicationManager.getApplication().invokeLater({
            clearMarkup()
            findingsByPath = map
            EditorFactory.getInstance().allEditors.filter { it.project == null || it.project == project }.forEach { apply(it) }
        }, project.disposed)
    }

    fun clear() {
        ApplicationManager.getApplication().invokeLater({
            findingsByPath = emptyMap()
            clearMarkup()
        }, project.disposed)
    }

    private fun clearMarkup() {
        applied.values.forEach { it.remove() }
        applied.clear()
    }

    private fun apply(editor: Editor) {
        if (findingsByPath.isEmpty()) return
        // Diff görüntüleyicinin editörleri FindingsDiffExtension tarafından işaretlenir; çift işaret olmasın.
        if (editor.editorKind == com.intellij.openapi.editor.EditorKind.DIFF) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val findings = findingsByPath[Paths.get(file.path).normalize().toString().lowercase()] ?: return
        applied.remove(editor)?.remove()
        applied[editor] = FindingMarkup.apply(
            editor,
            findings,
            lineOf = { ((it.line ?: 1) - 1).coerceAtLeast(0) },
            onClick = { ReviewSessionService.getInstance(project).revealFinding(it) },
        )
    }

    private fun normalize(root: String, file: String): String {
        val relative = file.replace(Regex("^[ab]/"), "")
        val path = Paths.get(relative).let { if (it.isAbsolute) it else Paths.get(root).resolve(relative) }
        return path.normalize().toString().lowercase()
    }

    override fun dispose() {
        applied.clear()
    }

    companion object {
        fun getInstance(project: Project): FindingsHighlighter = project.getService(FindingsHighlighter::class.java)
    }
}
