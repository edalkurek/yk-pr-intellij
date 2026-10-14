package com.ykb.review.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.ykb.review.config.ReviewSettingsState
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

class ReviewStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = "YkbReviewStatusBarWidget"
    override fun getDisplayName(): String = "YKB Auto PR Review"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = ReviewStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) {}
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class ReviewStatusBarWidget(private val project: Project) : CustomStatusBarWidget {
    private val label = JBLabel()

    override fun ID(): String = "YkbReviewStatusBarWidget"

    override fun install(statusBar: StatusBar) {
        updateLabel()
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("YKB AI Review")
                toolWindow?.show()
            }
        })
    }

    fun updateLabel() {
        val state = ReviewSettingsState.instance
        if (state.automaticReviewEnabled) {
            label.text = "🤖 Auto Review (${state.automaticReviewIntervalMinutes}m)"
            label.toolTipText = "YKB Auto PR Review Etkin. Tıklayarak paneli açın."
        } else {
            label.text = "🤖 Auto Review"
            label.toolTipText = "YKB Auto PR Review (Kapalı). Tıklayarak paneli açın."
        }
    }

    override fun getComponent(): JComponent = label

    override fun dispose() {}
}
