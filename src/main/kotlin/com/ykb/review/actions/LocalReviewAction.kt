package com.ykb.review.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.ykb.review.ai.ReviewCoordinator
import com.ykb.review.review.CommentFormatter
import java.io.File
import java.util.concurrent.TimeUnit

class LocalReviewAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Yerel Değişiklikler AI ile İnceleniyor (Pre-Commit)", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Yerel git diff hesaplanıyor..."
                val diffOutput = getLocalGitDiff(basePath)

                if (diffOutput.isBlank()) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(
                            project,
                            "İncelenecek uncommitted (staged veya unstaged) yerel değişiklik bulunamadı.",
                            "YKB Yerel Kod İnceleme"
                        )
                    }
                    return
                }

                try {
                    val coordinator = ReviewCoordinator()
                    val result = coordinator.executeReview(
                        prTitle = "Yerel Ön İnceleme (Pre-Commit)",
                        prDescription = "Geliştiricinin yerel çalışma alanındaki commit edilmemiş değişiklikleri",
                        fromBranch = "local-working-tree",
                        toBranch = "HEAD",
                        rawDiff = diffOutput
                    ) { step, frac ->
                        indicator.text = step
                        indicator.fraction = frac
                    }

                    ApplicationManager.getApplication().invokeLater {
                        val formatted = CommentFormatter.formatGeneralComment(result)
                        val title = "Yerel İnceleme Sonucu (Risk: ${result.riskLevel.uppercase()})"
                        
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("YKB AI Review Notifications")
                            .createNotification(
                                title,
                                "Toplam ${result.findings.size} bulgu tespit edildi. Detaylar konsolda ve diyalog penceresinde.",
                                if (result.riskLevel == "high") NotificationType.WARNING else NotificationType.INFORMATION
                            )
                            .notify(project)

                        Messages.showDialog(
                            project,
                            formatted,
                            title,
                            arrayOf("Tamam"),
                            0,
                            Messages.getInformationIcon()
                        )
                    }
                } catch (ex: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "İnceleme başarısız: ${ex.message}", "AI İnceleme Hatası")
                    }
                }
            }
        })
    }

    private fun getLocalGitDiff(workingDir: String): String {
        return try {
            val process = ProcessBuilder("git", "diff", "HEAD")
                .directory(File(workingDir))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (finished && process.exitValue() == 0) output else ""
        } catch (e: Exception) {
            ""
        }
    }
}
