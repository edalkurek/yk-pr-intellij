package com.ykb.review.review

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.ykb.review.ai.ReviewCoordinator
import com.ykb.review.bitbucket.BitbucketClient
import com.ykb.review.bitbucket.PullRequest
import com.ykb.review.config.ReviewSettingsState
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class AutomaticReviewService(private val project: Project) : Disposable {

    private var scheduledTask: ScheduledFuture<*>? = null
    private val isRunning = AtomicBoolean(false)
    var lastRunTime: Long? = null
        private set
    var lastRunSummary: String = "Henüz çalıştırılmadı."
        private set

    init {
        restartTimer()
    }

    fun restartTimer() {
        scheduledTask?.cancel(false)
        scheduledTask = null

        val state = ReviewSettingsState.instance
        if (!state.automaticReviewEnabled) {
            lastRunSummary = "Otomatik tarama kapalı."
            return
        }

        val interval = state.automaticReviewIntervalMinutes.coerceIn(1, 1440).toLong()
        scheduledTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { runScanAndReview(manual = false) },
            10, // İlk tarama 10 saniye sonra
            interval * 60,
            TimeUnit.SECONDS
        )
    }

    fun runScanAndReview(manual: Boolean, onComplete: ((summary: String) -> Unit)? = null) {
        if (!isRunning.compareAndSet(false, true)) {
            val busyMsg = "Önceki inceleme turu devam ettiği için işlem bekletildi."
            if (manual) showNotification(busyMsg, NotificationType.WARNING)
            onComplete?.invoke(busyMsg)
            return
        }

        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val state = ReviewSettingsState.instance
                if (state.bitbucketToken.isBlank()) {
                    val msg = "Bitbucket token tanımlı değil. Otomatik tarama yapılamıyor."
                    if (manual) showNotification(msg, NotificationType.WARNING)
                    lastRunSummary = msg
                    onComplete?.invoke(msg)
                    return@execute
                }

                val client = BitbucketClient()
                val prs = try {
                    client.listOpenPullRequestsForReviewer()
                } catch (e: Exception) {
                    val err = "Reviewer PR'ları çekilemedi: ${e.message}"
                    if (manual) showNotification(err, NotificationType.ERROR)
                    lastRunSummary = err
                    onComplete?.invoke(err)
                    return@execute
                }

                if (prs.isEmpty()) {
                    val noPrMsg = "Reviewer olduğunuz açık PR bulunamadı."
                    lastRunSummary = noPrMsg
                    lastRunTime = System.currentTimeMillis()
                    if (manual) showNotification(noPrMsg, NotificationType.INFORMATION)
                    onComplete?.invoke(noPrMsg)
                    return@execute
                }

                var reviewedCount = 0
                var skippedCount = 0
                val coordinator = ReviewCoordinator()

                for (pr in prs) {
                    val projectKey = pr.projectKey
                    val repoSlug = pr.repositorySlug
                    val prId = pr.id
                    val latestCommit = pr.latestCommitHash

                    if (projectKey.isBlank() || repoSlug.isBlank()) {
                        continue
                    }

                    // 1. Zaten bu commit hash için review yapıldı mı?
                    val prKey = "$projectKey/$repoSlug/$prId"
                    val cachedCommit = state.lastReviewedCommits[prKey]
                    val marker = "<!-- YKB_AI_REVIEW: prId=$prId commitHash=$latestCommit -->"

                    if (cachedCommit == latestCommit || (latestCommit.isNotBlank() && client.hasCommentMarker(projectKey, repoSlug, prId, marker))) {
                        skippedCount++
                        continue
                    }

                    // 2. Yeni commit veya incelenmemiş PR -> Diff çek ve incele
                    try {
                        val rawDiff = client.getPullRequestDiff(projectKey, repoSlug, prId)
                        val repoInstructions = state.getEffectiveInstructions(repoSlug)

                        val result = coordinator.executeReview(
                            prTitle = pr.title,
                            prDescription = pr.description,
                            fromBranch = pr.fromRef?.displayId,
                            toBranch = pr.toRef?.displayId,
                            rawDiff = rawDiff
                        )

                        // 3. Yorumları Bitbucket'a gönder
                        val generalCommentWithMarker = "${CommentFormatter.formatGeneralComment(result)}\n\n$marker"
                        client.postGeneralComment(projectKey, repoSlug, prId, generalCommentWithMarker)

                        // Satır içi bulgular
                        for (f in result.findings) {
                            if (f.line != null) {
                                client.postInlineComment(
                                    projectKey = projectKey,
                                    repoSlug = repoSlug,
                                    pullRequestId = prId,
                                    filePath = f.file,
                                    lineNumber = f.line,
                                    side = f.side ?: "new",
                                    comment = CommentFormatter.formatInlineComment(f)
                                )
                            }
                        }

                        // Commit'i önbelleğe al
                        if (latestCommit.isNotBlank()) {
                            state.lastReviewedCommits[prKey] = latestCommit
                        }

                        reviewedCount++
                        showNotification(
                            "PR #${pr.id} (${repoSlug}) otomatik olarak incelendi ve yorumlar gönderildi. (Risk: ${result.riskLevel.uppercase()})",
                            NotificationType.INFORMATION
                        )
                    } catch (e: Exception) {
                        System.err.println("Otomatik review hatası ($repoSlug #$prId): ${e.message}")
                    }
                }

                lastRunTime = System.currentTimeMillis()
                lastRunSummary = "Tamamlandı: $reviewedCount PR incelendi, $skippedCount PR güncel/atlandı."
                if (manual) {
                    showNotification(lastRunSummary, NotificationType.INFORMATION)
                }
                onComplete?.invoke(lastRunSummary)
            } finally {
                isRunning.set(false)
            }
        }
    }

    private fun showNotification(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("YKB AI Review Notifications")
            .createNotification("YKB Auto PR Review", content, type)
            .notify(project)
    }

    override fun dispose() {
        scheduledTask?.cancel(true)
        scheduledTask = null
    }

    companion object {
        fun getInstance(project: Project): AutomaticReviewService =
            project.getService(AutomaticReviewService::class.java)
    }
}
