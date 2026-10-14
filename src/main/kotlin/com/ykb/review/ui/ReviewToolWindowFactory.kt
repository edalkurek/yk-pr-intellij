package com.ykb.review.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

class ReviewToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val reviewPanel = ReviewPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(reviewPanel, "Bitbucket PRs", false)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        /** Tool window'u açar ve reviewer PR listesini yeniler. */
        fun showAndRefresh(project: Project) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ReviewSessionService.TOOL_WINDOW_ID) ?: return
            toolWindow.show {
                (toolWindow.contentManager.contents.firstOrNull()?.component as? ReviewPanel)?.refreshReviewerPullRequests()
            }
        }
    }
}
