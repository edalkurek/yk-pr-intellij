package com.ykb.review.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.ykb.review.ai.ReviewFinding

/**
 * PR diff görüntüleyicisinde AI bulgularını ilgili satırlarda gösterir (gutter ikonu, alt çizgi, satır sonu açıklaması).
 * Yalnızca [PullRequestDiffViewer.FINDINGS_KEY] taşıyan isteklerde çalışır; diğer diff pencerelerini etkilemez.
 * Gutter ikonuna tıklamak bulguyu tool window'da seçer.
 */
class FindingsDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val findings = request.getUserData(PullRequestDiffViewer.FINDINGS_KEY)?.takeIf { it.isNotEmpty() } ?: return
        val project = context.project
        val onClick: ((ReviewFinding) -> Unit)? = project?.let { p -> { f -> ReviewSessionService.getInstance(p).revealFinding(f) } }

        fun line(f: ReviewFinding): Int? = f.line?.let { (it - 1).coerceAtLeast(0) }
        fun isOld(f: ReviewFinding) = f.side == "old"

        when (viewer) {
            is TwosideTextDiffViewer -> {
                FindingMarkup.apply(viewer.getEditor(Side.LEFT), findings.filter(::isOld), ::line, onClick)
                FindingMarkup.apply(viewer.getEditor(Side.RIGHT), findings.filterNot(::isOld), ::line, onClick)
            }
            is OnesideTextDiffViewer -> {
                // Eklenen / silinen dosya: tek editör; taraf bilgisi uyumsuz olsa da satırlar aynı içeriğe aittir.
                FindingMarkup.apply(viewer.editor, findings, ::line, onClick)
            }
            is UnifiedDiffViewer -> {
                // Birleşik görünümde satırlar her yeniden hesaplamada değişir; işaretler her rediff sonrası yenilenir.
                var applied: FindingMarkup.Applied? = null
                viewer.addListener(object : DiffViewerListener() {
                    override fun onAfterRediff() {
                        applied?.remove()
                        applied = FindingMarkup.apply(viewer.editor, findings, { f ->
                            val side = if (isOld(f)) Side.LEFT else Side.RIGHT
                            line(f)?.let { viewer.transferLineFromOnesideStrict(side, it) }?.takeIf { it >= 0 }
                        }, onClick)
                    }
                })
            }
        }
    }
}
