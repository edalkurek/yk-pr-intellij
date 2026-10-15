package com.ykb.review.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.ykb.review.config.ReviewSettingsState
import com.ykb.review.review.AutoReviewStatusListener
import com.ykb.review.review.AutomaticReviewService
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.DateFormat
import java.util.Date
import javax.swing.JComponent

class ReviewStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID
    override fun getDisplayName(): String = "YKB Auto PR Review"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = ReviewStatusBarWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val WIDGET_ID = "YkbReviewStatusBarWidget"
    }
}

/** VS Code status bar öğesi karşılığı: durum ikonu, ayrıntılı tooltip ve tıklayınca Auto Review menüsü. */
class ReviewStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val label = JBLabel().apply { border = JBUI.Borders.empty(0, 4) }

    override fun ID(): String = ReviewStatusBarWidgetFactory.WIDGET_ID

    override fun install(statusBar: StatusBar) {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(AutoReviewStatusListener.TOPIC, AutoReviewStatusListener {
                ApplicationManager.getApplication().invokeLater { updateLabel() }
            })
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = showMenu(e)
        })
        updateLabel()
    }

    private fun showMenu(e: MouseEvent) {
        val group = ActionManager.getInstance().getAction("YKB.Review.AutoReviewMenu") as? ActionGroup ?: return
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Auto PR Review",
            group,
            DataManager.getInstance().getDataContext(label),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
        val size = popup.content.preferredSize
        popup.show(RelativePoint(label, Point(0, -size.height)))
    }

    private fun updateLabel() {
        val settings = ReviewSettingsState.instance
        val auto = AutomaticReviewService.getInstance()
        val enabled = settings.automaticReviewEnabled
        label.icon = when {
            auto.isRunning -> AnimatedIcon.Default.INSTANCE
            enabled -> AllIcons.Actions.Execute
            else -> AllIcons.Actions.Suspend
        }
        label.text = when {
            auto.isRunning -> "AI Review: taranıyor…"
            enabled -> "AI Review"
            else -> "AI Review: kapalı"
        }
        val status = when {
            auto.isRunning -> "Açık PR'lar taranıyor ve inceleniyor…"
            enabled -> "Etkin (her ${settings.automaticReviewIntervalMinutes} dakikada bir)"
            else -> "Devre dışı (aralık: ${settings.automaticReviewIntervalMinutes} dk)"
        }
        val last = auto.lastRunTime?.let { time ->
            "<br>Son kontrol: ${DateFormat.getTimeInstance().format(Date(time))}" +
                (auto.lastRunSummary?.let { " (${escape(it)})" } ?: "")
        } ?: ""
        label.toolTipText = "<html><b>YKB Auto PR Review</b><br>Durum: $status$last<br><i>Menü için tıklayın.</i></html>"
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun getComponent(): JComponent = label

    override fun dispose() {}
}
