package com.ykb.review.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.ykb.review.config.ReviewSettingsConfigurable
import com.ykb.review.config.SecretStore
import com.ykb.review.config.ReviewSettingsState
import com.ykb.review.review.AutomaticReviewService
import com.ykb.review.ui.ReviewNotifier
import com.ykb.review.ui.ReviewSessionService
import com.ykb.review.ui.ReviewToolWindowFactory

abstract class ProjectAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

/** Açık PR'ları listele (tool window'u açar ve listeyi yeniler). */
class ListPullRequestsAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ReviewToolWindowFactory.showAndRefresh(e.project ?: return)
    }
}

/** URL ile PR incele: URL sorar, yayın tercihini sorar, incelemeyi başlatır. */
class ReviewUrlAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ReviewSessionService.getInstance(e.project ?: return).reviewUrlInteractive()
    }
}

/** URL ile PR değişikliklerini yerel diff görüntüleyicide aç (AI incelemesi yapmadan). */
class ShowPullRequestChangesAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ReviewSessionService.getInstance(e.project ?: return).showChangesUrlInteractive()
    }
}

/** Yerel (staged / unstaged / untracked) değişiklikleri AI ile incele. */
class LocalReviewAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ReviewSessionService.getInstance(e.project ?: return).reviewLocal()
    }
}

class ClearFindingsAction : ProjectAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ReviewSessionService.getInstance(e.project ?: return).clearFindings()
    }
}

class ToggleAutoReviewAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.text = if (ReviewSettingsState.instance.automaticReviewEnabled) {
            "Auto PR Review'u Devre Dışı Bırak"
        } else {
            "Auto PR Review'u Etkinleştir"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        AutomaticReviewService.getInstance().toggleEnabled(e.project)
    }
}

class RunAutoReviewNowAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !AutomaticReviewService.getInstance().isRunning
    }

    override fun actionPerformed(e: AnActionEvent) {
        AutomaticReviewService.getInstance().runNow(e.project)
    }
}

class OpenSettingsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, ReviewSettingsConfigurable::class.java)
    }
}

class ClearCredentialsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val answer = Messages.showYesNoDialog(
            e.project,
            "YKB AI Review tarafından saklanan tüm kimlik bilgileri silinsin mi?",
            "Kayıtlı Bilgileri Temizle",
            "Sil",
            "Vazgeç",
            Messages.getWarningIcon(),
        )
        if (answer == Messages.YES) {
            SecretStore.clearAll()
            ReviewNotifier.notify(e.project, "Saklanan kimlik bilgileri silindi.")
        }
    }
}
