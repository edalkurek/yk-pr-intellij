package com.ykb.review.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.ykb.review.ai.ReviewConstants
import com.ykb.review.ai.ReviewFinding
import com.ykb.review.config.ReviewSettingsState
import com.ykb.review.review.FindingsPresenter
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.Icon

/**
 * Bulguları bir editöre işler: dalgalı alt çizgi, kaydırma çubuğu işareti, tıklanabilir gutter ikonu ve
 * satır sonunda kısa açıklama (inlay). Hem yerel editörler ([FindingsHighlighter]) hem PR diff görüntüleyicisi
 * ([FindingsDiffExtension]) aynı görünümü kullanır.
 */
object FindingMarkup {

    /** Bir editöre eklenen işaretler; [remove] ile temizlenir. */
    class Applied(private val editor: Editor, val highlighters: List<RangeHighlighter>, val inlays: List<Inlay<*>>) {
        fun remove() {
            if (editor.isDisposed) return
            highlighters.forEach { editor.markupModel.removeHighlighter(it) }
            inlays.forEach { Disposer.dispose(it) }
        }
    }

    /**
     * @param lineOf bulgunun bu editördeki 0 tabanlı satırı; null ise bulgu atlanır.
     * @param onClick gutter ikonuna tıklanınca (ör. tool window'da bulguyu seç).
     */
    fun apply(
        editor: Editor,
        findings: List<ReviewFinding>,
        lineOf: (ReviewFinding) -> Int?,
        onClick: ((ReviewFinding) -> Unit)? = null,
    ): Applied {
        val document = editor.document
        val highlighters = mutableListOf<RangeHighlighter>()
        val inlays = mutableListOf<Inlay<*>>()
        val byLine = findings.mapNotNull { f -> lineOf(f)?.takeIf { it in 0 until document.lineCount }?.let { it to f } }
            .groupBy({ it.first }, { it.second })

        for ((line, items) in byLine) {
            val sorted = items.sortedByDescending { ReviewConstants.riskScore(it.severity) }
            val top = sorted.first()
            val color = SeverityStyle.color(top.severity)
            val attributes = TextAttributes().apply {
                effectType = EffectType.WAVE_UNDERSCORE
                effectColor = color
                errorStripeColor = color
            }
            val highlighter = editor.markupModel.addLineHighlighter(line, HighlighterLayer.WARNING, attributes)
            val tooltip = tooltipHtml(sorted)
            highlighter.errorStripeTooltip = tooltip
            highlighter.gutterIconRenderer = FindingGutterIcon(SeverityStyle.gutterIcon(top.severity), tooltip, top, onClick)
            highlighters.add(highlighter)

            if (ReviewSettingsState.instance.showFindingInlays) {
                val extra = if (sorted.size > 1) "  (+${sorted.size - 1})" else ""
                val text = "${FindingsPresenter.typeLabel(top.type)}: ${FindingsPresenter.headline(top.message, 90)}$extra"
                editor.inlayModel.addAfterLineEndElement(document.getLineEndOffset(line), true, FindingInlayRenderer(text, top.severity))
                    ?.let { inlays.add(it) }
            }
        }
        return Applied(editor, highlighters, inlays)
    }

    fun tooltipHtml(findings: List<ReviewFinding>): String {
        val codeStyle = "font-family:monospace;"
        val body = findings.joinToString("<hr>") { f ->
            "<b style='color:${SeverityStyle.hex(SeverityStyle.color(f.severity))};'>● ${SeverityStyle.label(f.severity)}</b> · " +
                "<b>${FindingsPresenter.typeLabel(f.type)}</b><br>" +
                FindingsPresenter.textToHtml(f.message, codeStyle) +
                "<br><br><i>Öneri:</i> " + FindingsPresenter.textToHtml(f.suggestion, codeStyle)
        }
        return "<html><body style='width:420px'><b>YKB AI Review</b><br>$body</body></html>"
    }

    private class FindingGutterIcon(
        private val icon: Icon,
        private val tooltip: String,
        private val finding: ReviewFinding,
        private val onClick: ((ReviewFinding) -> Unit)?,
    ) : GutterIconRenderer() {
        override fun getIcon(): Icon = icon
        override fun getTooltipText(): String = tooltip
        override fun isNavigateAction(): Boolean = onClick != null
        override fun getClickAction(): AnAction? = onClick?.let { handler -> DumbAwareAction.create { handler(finding) } }
        override fun equals(other: Any?): Boolean = other is FindingGutterIcon && other.tooltip == tooltip
        override fun hashCode(): Int = tooltip.hashCode()
    }

    /** Satır sonunda soluk italik metin: "● Güvenlik: SQL injection riski". */
    private class FindingInlayRenderer(private val text: String, private val severity: String) : EditorCustomElementRenderer {
        private val bullet = "● "

        private fun font(editor: Editor) = editor.colorsScheme.getFont(EditorFontType.ITALIC)

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val metrics = inlay.editor.contentComponent.getFontMetrics(font(inlay.editor))
            return metrics.stringWidth(bullet + text) + JBUI.scale(PADDING * 2)
        }

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val editor = inlay.editor
            val font = font(editor)
            val metrics = editor.contentComponent.getFontMetrics(font)
            val baseline = targetRegion.y + (targetRegion.height - metrics.height) / 2 + metrics.ascent
            var x = targetRegion.x + JBUI.scale(PADDING)
            g.font = font
            g.color = SeverityStyle.color(severity)
            g.drawString(bullet, x, baseline)
            x += metrics.stringWidth(bullet)
            g.color = JBColor.GRAY
            g.drawString(text, x, baseline)
        }

        companion object {
            private const val PADDING = 6
        }
    }
}
