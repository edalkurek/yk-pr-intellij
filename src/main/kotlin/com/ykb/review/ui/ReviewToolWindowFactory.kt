package com.ykb.review.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ReviewToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val reviewPanel = ReviewPanel(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(reviewPanel, "Bitbucket PRs", false)
        toolWindow.contentManager.addContent(content)
    }
}
