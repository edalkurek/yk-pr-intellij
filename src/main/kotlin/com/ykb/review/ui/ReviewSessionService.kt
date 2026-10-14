package com.ykb.review.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.messages.Topic
import com.ykb.review.ai.ChatClient
import com.ykb.review.ai.DiffFilter
import com.ykb.review.ai.LLMClient
import com.ykb.review.ai.ReviewContext
import com.ykb.review.ai.ReviewEngine
import com.ykb.review.ai.ReviewEngineOptions
import com.ykb.review.ai.ReviewResult
import com.ykb.review.bitbucket.PullRequest
import com.ykb.review.bitbucket.PullRequestRef
import com.ykb.review.bitbucket.PullRequestUrlParser
import com.ykb.review.config.ConfigValidation
import com.ykb.review.config.ReviewSettingsState
import com.ykb.review.local.LocalGit
import com.ykb.review.review.ReviewCoordinator
import com.ykb.review.review.ReviewOutcome

/** Tool window'da gösterilen son inceleme. */
data class ReviewSession(
    val title: String,
    val review: ReviewResult,
    /** PR incelemesi ise dolu; yerel incelemede null. */
    val outcome: ReviewOutcome?,
    val root: String?,
)

fun interface ReviewSessionListener {
    fun sessionChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<ReviewSessionListener> = Topic("YKB Review Session", ReviewSessionListener::class.java)
    }
}

/** Proje bazlı inceleme akışları: PR / URL / yerel inceleme, yayınlama ve sonuç durumu. */
@Service(Service.Level.PROJECT)
class ReviewSessionService(private val project: Project) {

    private val log = Logger.getInstance(ReviewSessionService::class.java)

    @Volatile
    var current: ReviewSession? = null
        private set

    @Volatile
    var statusText: String = "Hazır."
        private set

    private fun update(session: ReviewSession? = current, status: String) {
        current = session
        statusText = status
        ApplicationManager.getApplication().invokeLater({
            project.messageBus.syncPublisher(ReviewSessionListener.TOPIC).sessionChanged()
        }, project.disposed)
    }

    fun showToolWindow() {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
    }

    /** VS Code choosePublication karşılığı. EDT'de çağrılmalı. @return null = iptal */
    fun choosePublication(): Boolean? {
        val choice = Messages.showDialog(
            project,
            "Review sonucu nasıl işlensin?",
            "YKB AI Review",
            arrayOf("Yalnızca IDE'de göster", "Bitbucket'a yorum yaz", "İptal"),
            0,
            Messages.getQuestionIcon(),
        )
        return when (choice) {
            0 -> false
            1 -> true
            else -> null
        }
    }

    fun reviewPullRequest(pullRequest: PullRequest?, ref: PullRequestRef, publish: Boolean) {
        showToolWindow()
        update(status = "PR #${ref.pullRequestId} inceleniyor...")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "PR #${ref.pullRequestId} AI tarafından inceleniyor…", true) {
            private var outcome: ReviewOutcome? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                outcome = ReviewCoordinator().review(ref, pullRequest, publish) { step, fraction ->
                    indicator.text = step
                    indicator.fraction = fraction
                }
            }

            override fun onSuccess() {
                val result = outcome ?: return
                val title = "PR #${ref.pullRequestId} · ${result.pullRequest.title}"
                project.basePath?.let { FindingsHighlighter.getInstance(project).report(it, result.review.findings) }
                val message = when {
                    result.skippedDuplicate -> "Bu commit için yorum zaten mevcut; tekrar yayınlanmadı."
                    result.published -> "AI review tamamlandı ve Bitbucket yorumuna yazıldı." +
                        (result.publication?.takeIf { it.generalOnlyFindings > 0 }
                            ?.let { " (${it.generalOnlyFindings} bulgu için kesin satır bulunamadı; genel yorumda.)" } ?: "")
                    else -> "AI review tamamlandı: ${result.review.findings.size} bulgu."
                }
                update(ReviewSession(title, result.review, result, project.basePath), "$message Risk: ${result.review.riskLevel.uppercase()}")
                ReviewNotifier.notify(project, message)
            }

            override fun onCancel() = update(status = "İnceleme iptal edildi.")

            override fun onThrowable(error: Throwable) = handleError(error, "PR incelemesi başarısız")
        })
    }

    fun reviewUrlInteractive() {
        val value = Messages.showInputDialog(
            project,
            "Bitbucket pull request bağlantısını girin:",
            "URL ile PR İncele",
            Messages.getQuestionIcon(),
            "",
            object : InputValidatorEx {
                override fun getErrorText(inputString: String): String? = try {
                    PullRequestUrlParser.parse(inputString)
                    null
                } catch (e: IllegalArgumentException) {
                    e.message
                }

                override fun checkInput(inputString: String) = getErrorText(inputString) == null
                override fun canClose(inputString: String) = checkInput(inputString)
            },
        ) ?: return
        val ref = PullRequestUrlParser.parse(value)
        val publish = choosePublication() ?: return
        reviewPullRequest(null, ref, publish)
    }

    /** Ekranda gösterilen PR sonucunu Bitbucket'a yayınlar (yanlış PR'a yayın riskini önlemek için sonucun kendi ref'i kullanılır). */
    fun publishCurrent() {
        val session = current ?: return
        val outcome = session.outcome ?: return
        if (outcome.published) return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Yorumlar Bitbucket'a gönderiliyor", true) {
            private var result: ReviewOutcome? = null

            override fun run(indicator: ProgressIndicator) {
                result = ReviewCoordinator().publish(outcome)
            }

            override fun onSuccess() {
                val r = result ?: return
                val message = if (r.skippedDuplicate) "Bu commit için yorum zaten mevcut; tekrar yayınlanmadı."
                else "Bitbucket'a ${r.publication?.inlineComments ?: 0} satır yorumu ve genel özet yayınlandı."
                update(session.copy(outcome = r.copy(published = true)), message)
                ReviewNotifier.notify(project, message)
            }

            override fun onThrowable(error: Throwable) = handleError(error, "Yorum gönderilemedi")
        })
    }

    fun reviewLocal() {
        val roots = ProjectLevelVcsManager.getInstance(project).allVcsRoots
            .filter { it.vcs?.name.equals("Git", ignoreCase = true) }
            .map { it.path.path }
            .distinct()
        val candidate: String = when {
            roots.size == 1 -> roots[0]
            roots.size > 1 -> {
                val names = roots.toTypedArray()
                val index = Messages.showChooseDialog(project, "İncelenecek depoyu seçin:", "Yerel AI Review", Messages.getQuestionIcon(), names, names[0])
                if (index < 0) return
                roots[index]
            }
            else -> project.basePath ?: return
        }
        showToolWindow()
        update(status = "Yerel değişiklikler inceleniyor...")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Yerel değişiklikler inceleniyor…", true) {
            private var session: ReviewSession? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = "Yerel git diff hesaplanıyor..."
                val root = LocalGit.topLevel(candidate) ?: throw IllegalStateException("Git deposu bulunamadı: $candidate")
                val snapshot = LocalGit.localSnapshot(root)
                if (snapshot.diff.isBlank()) {
                    session = ReviewSession(
                        snapshot.title,
                        ReviewResult(
                            summary = "İncelenecek kod değişikliği yok.",
                            riskLevel = "low",
                            finalComment = "İşlem kapsamındaki kod dışı veya boş değişiklikleri kontrol edin.",
                        ),
                        null,
                        root,
                    )
                    return
                }
                val settings = ReviewSettingsState.instance
                val llm = LLMClient.fromSettings()
                val instructions = settings.effectiveInstructions(settings.repoInstructionForRemotes(LocalGit.remoteUrls(root)))
                val input = if (settings.maskSecretsInDiff) DiffFilter.maskSecrets(snapshot.diff) else snapshot.diff
                val review = ReviewEngine.review(
                    ChatClient { model, messages -> llm.chat(model, messages) },
                    input,
                    ReviewContext(title = snapshot.title, extraInstructions = instructions),
                    ReviewEngineOptions(
                        fastModel = settings.fastModelName.trim(),
                        complexModel = ConfigValidation.modelName(settings.complexModelName, "Güçlü model (Tier 2) adı"),
                        maxChunkChars = settings.maxChunkChars.coerceAtLeast(4_000),
                        failOnChunkError = true,
                        minSeverity = settings.minSeverity,
                        onProgress = { step, fraction ->
                            indicator.text = step
                            indicator.fraction = fraction
                        },
                    ),
                )
                session = ReviewSession(snapshot.title, review, null, root)
            }

            override fun onSuccess() {
                val s = session ?: return
                s.root?.let { FindingsHighlighter.getInstance(project).report(it, s.review.findings) }
                val message = "Yerel inceleme tamamlandı: ${s.review.findings.size} bulgu. Risk: ${s.review.riskLevel.uppercase()}"
                update(s, message)
                ReviewNotifier.notify(
                    project, message,
                    if (s.review.riskLevel == "high") NotificationType.WARNING else NotificationType.INFORMATION,
                )
            }

            override fun onCancel() = update(status = "İnceleme iptal edildi.")

            override fun onThrowable(error: Throwable) = handleError(error, "Yerel inceleme başarısız")
        })
    }

    fun clearFindings() {
        FindingsHighlighter.getInstance(project).clear()
        update(null, "Bulgular temizlendi.")
    }

    /** Task hatalarını IDE "Internal Error" yerine kullanıcı bildirimi olarak gösterir. */
    private fun handleError(error: Throwable, prefix: String) {
        if (error is ProcessCanceledException) {
            update(status = "İnceleme iptal edildi.")
            return
        }
        log.warn(prefix, error)
        update(status = "$prefix: ${error.message ?: error.javaClass.simpleName}")
        ReviewNotifier.error(project, error, prefix)
    }

    companion object {
        const val TOOL_WINDOW_ID = "YKB AI Review"

        fun getInstance(project: Project): ReviewSessionService = project.getService(ReviewSessionService::class.java)
    }
}
