package com.ykb.review.ui

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
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
import com.ykb.review.ai.ReviewFinding
import com.ykb.review.ai.ReviewResult
import com.ykb.review.bitbucket.PullRequest
import com.ykb.review.bitbucket.PullRequestRef
import com.ykb.review.bitbucket.PullRequestUrlParser
import com.ykb.review.config.ConfigValidation
import com.ykb.review.config.ReviewSettingsState
import com.ykb.review.local.LocalGit
import com.ykb.review.review.ReviewCoordinator
import com.ykb.review.review.ReviewOutcome
import java.awt.datatransfer.DataFlavor
import java.util.concurrent.atomic.AtomicLong

/** Tool window'da gösterilen bir inceleme sonucu. */
data class ReviewSession(
    val title: String,
    val review: ReviewResult,
    /** PR incelemesi ise dolu; yerel incelemede null. */
    val outcome: ReviewOutcome?,
    /** Dosyaların bulunduğu yerel git kökü (PR için remote eşleşen depo; yoksa proje kökü). */
    val root: String?,
    /** Kullanıcının "yayından hariç tut" olarak işaretlediği bulgular. */
    val excluded: Set<ReviewFinding> = emptySet(),
    /** Yerel çalışma kopyası incelenen kodla aynı mı? (Yerel inceleme veya HEAD == PR commit'i.) Editörde açma / kod parçası için. */
    val workingCopyMatches: Boolean = outcome == null,
    val createdAt: Long = System.currentTimeMillis(),
    val id: Long = NEXT_ID.incrementAndGet(),
) {
    val isPullRequest: Boolean get() = outcome != null
    val includedFindings: List<ReviewFinding> get() = review.findings.filter { it !in excluded }

    companion object {
        private val NEXT_ID = AtomicLong()
    }
}

/** Tool window içinde gösterilen uzun işlem durumu. */
data class ReviewProgress(val text: String, val fraction: Double?)

fun interface ReviewSessionListener {
    fun sessionChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<ReviewSessionListener> = Topic("YKB Review Session", ReviewSessionListener::class.java)
    }
}

/** Editör / diff gutter ikonuna tıklanınca bulgunun tool window'da seçilmesi için. */
fun interface FindingFocusListener {
    fun focus(finding: ReviewFinding)

    companion object {
        @JvmField
        val TOPIC: Topic<FindingFocusListener> = Topic("YKB Review Finding Focus", FindingFocusListener::class.java)
    }
}

/** Proje bazlı inceleme akışları: PR / URL / yerel inceleme, yayınlama, geçmiş ve sonuç durumu. */
@Service(Service.Level.PROJECT)
class ReviewSessionService(private val project: Project) {

    private val log = Logger.getInstance(ReviewSessionService::class.java)

    @Volatile
    var current: ReviewSession? = null
        private set

    /** Son incelemeler (en yeni başta). "Bulguları Temizle" yalnızca görünümü temizler; sonuç burada kalır. */
    @Volatile
    var history: List<ReviewSession> = emptyList()
        private set

    @Volatile
    var statusText: String = "Hazır."
        private set

    /** Son durum bir hata mı? (Panel hata ikonu gösterir.) */
    @Volatile
    var statusIsError: Boolean = false
        private set

    @Volatile
    var progress: ReviewProgress? = null
        private set

    @Volatile
    private var activeIndicator: ProgressIndicator? = null

    private fun fire() {
        ApplicationManager.getApplication().invokeLater({
            project.messageBus.syncPublisher(ReviewSessionListener.TOPIC).sessionChanged()
        }, project.disposed)
    }

    private fun update(session: ReviewSession? = current, status: String, error: Boolean = false) {
        current = session
        if (session != null) {
            history = (listOf(session) + history.filter { it.id != session.id }).take(MAX_HISTORY)
        }
        statusText = status
        statusIsError = error
        fire()
    }

    private fun setProgress(text: String, fraction: Double?) {
        progress = ReviewProgress(text, fraction)
        fire()
    }

    private fun endProgress() {
        progress = null
        activeIndicator = null
    }

    fun cancelActive() {
        activeIndicator?.cancel()
    }

    fun showToolWindow() {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
    }

    /** Geçmişten bir incelemeyi tekrar görüntüler (editör vurguları da o incelemeye göre yenilenir). */
    fun selectSession(id: Long) {
        val session = history.firstOrNull { it.id == id } ?: return
        if (session.id == current?.id) return
        highlight(session)
        update(session, "Geçmiş inceleme görüntüleniyor: ${session.title}")
    }

    /** Editör / diff'teki gutter ikonundan: tool window'u aç ve bulguyu seç. */
    fun revealFinding(finding: ReviewFinding) {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show {
            project.messageBus.syncPublisher(FindingFocusListener.TOPIC).focus(finding)
        }
    }

    fun setExcluded(findings: Collection<ReviewFinding>, exclude: Boolean) {
        val session = current ?: return
        val excluded = if (exclude) session.excluded + findings else session.excluded - findings.toSet()
        val included = session.review.findings.count { it !in excluded }
        update(session.copy(excluded = excluded), "Yayına dahil: $included / ${session.review.findings.size} bulgu.")
    }

    /**
     * VS Code choosePublication karşılığı. Ayarlarda tercih kaydedildiyse sormaz. EDT'de çağrılmalı.
     * @return null = iptal
     */
    fun choosePublication(): Boolean? {
        val settings = ReviewSettingsState.instance
        when (settings.publicationMode) {
            ReviewSettingsState.PUBLICATION_IDE -> return false
            ReviewSettingsState.PUBLICATION_PUBLISH -> return true
        }
        var remember = false
        val doNotAsk = object : DialogWrapper.DoNotAskOption.Adapter() {
            override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                remember = isSelected
            }

            override fun getDoNotShowMessage(): String = "Tercihimi hatırla (Ayarlar'dan değiştirilebilir)"
        }
        val choice = Messages.showDialog(
            project,
            "Review sonucu nasıl işlensin?\n\nIDE'de gösterilen sonuçları daha sonra panelden seçerek Bitbucket'a gönderebilirsiniz.",
            "YKB AI Review",
            arrayOf("Yalnızca IDE'de göster", "Bitbucket'a yorum yaz", "İptal"),
            0,
            Messages.getQuestionIcon(),
            doNotAsk,
        )
        val result = when (choice) {
            0 -> false
            1 -> true
            else -> null
        }
        if (remember && result != null) {
            settings.publicationMode = if (result) ReviewSettingsState.PUBLICATION_PUBLISH else ReviewSettingsState.PUBLICATION_IDE
        }
        return result
    }

    fun reviewPullRequest(pullRequest: PullRequest?, ref: PullRequestRef, publish: Boolean) {
        showToolWindow()
        update(status = "PR #${ref.pullRequestId} inceleniyor...")
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "PR #${ref.pullRequestId} AI tarafından inceleniyor…", true) {
            private var outcome: ReviewOutcome? = null
            private var localRoot: String? = null
            private var headMatches = false

            override fun run(indicator: ProgressIndicator) {
                activeIndicator = indicator
                indicator.isIndeterminate = false
                setProgress("PR #${ref.pullRequestId} hazırlanıyor…", 0.0)
                val result = ReviewCoordinator().review(ref, pullRequest, publish) { step, fraction ->
                    indicator.text = step
                    indicator.fraction = fraction
                    setProgress(step, fraction)
                }
                outcome = result
                // Satır numaraları PR sürümüne aittir: editör vurgusu yalnızca yerel HEAD aynı commit'teyse doğrudur.
                localRoot = PullRequestDiffViewer.findLocalRoot(project, ref)
                headMatches = localRoot?.let { root ->
                    runCatching { LocalGit.git(root, "rev-parse", "HEAD").trim() }.getOrNull() == result.commitHash
                } ?: false
            }

            override fun onSuccess() {
                val result = outcome ?: return
                val title = "PR #${ref.pullRequestId} · ${result.pullRequest.title}"
                val session = ReviewSession(title, result.review, result, localRoot ?: project.basePath, workingCopyMatches = headMatches)
                highlight(session)
                val message = when {
                    result.skippedDuplicate -> "Bu commit için yorum zaten mevcut; tekrar yayınlanmadı."
                    result.published -> "AI review tamamlandı ve Bitbucket yorumuna yazıldı." +
                        (result.publication?.takeIf { it.generalOnlyFindings > 0 }
                            ?.let { " (${it.generalOnlyFindings} bulgu için kesin satır bulunamadı; genel yorumda.)" } ?: "")
                    else -> "AI review tamamlandı: ${result.review.findings.size} bulgu."
                }
                update(session, "$message Risk: ${result.review.riskLevel.uppercase()}")
                ReviewNotifier.notify(
                    project,
                    "$title<br>$message",
                    if (result.review.riskLevel == "high") NotificationType.WARNING else NotificationType.INFORMATION,
                    actions = buildList {
                        add(ReviewNotifier.Action("Bulguları Göster") { showToolWindow() })
                        add(ReviewNotifier.Action("Diff'i Aç") { showChanges(session) })
                        if (result.pullRequest.url.isNotBlank()) add(ReviewNotifier.Action("Bitbucket'ta Aç") { BrowserUtil.browse(result.pullRequest.url) })
                    },
                )
                // VS Code'daki gibi incelenen PR'ın değişikliklerini editörde aç (ayarlardan kapatılabilir).
                if (ReviewSettingsState.instance.openDiffAfterReview) showChanges(session)
            }

            override fun onCancel() = update(status = "İnceleme iptal edildi.")

            override fun onThrowable(error: Throwable) =
                handleError(error, "PR incelemesi başarısız") { reviewPullRequest(pullRequest, ref, publish) }

            override fun onFinished() {
                endProgress()
                fire()
            }
        })
    }

    /** PR URL'i sorar (panoda geçerli bir PR bağlantısı varsa önceden doldurur); geçersiz girişte diyalog kapanmaz. @return null = iptal */
    private fun askPullRequestUrl(title: String): PullRequestRef? {
        val clipboard = runCatching {
            CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)?.trim()
        }.getOrNull()?.takeIf { text -> text.length < 2_000 && runCatching { PullRequestUrlParser.parse(text) }.isSuccess }
        val value = Messages.showInputDialog(
            project,
            "Bitbucket pull request bağlantısını girin:",
            title,
            Messages.getQuestionIcon(),
            clipboard.orEmpty(),
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
        ) ?: return null
        return PullRequestUrlParser.parse(value)
    }

    fun reviewUrlInteractive() {
        val ref = askPullRequestUrl("URL ile PR İncele") ?: return
        val publish = choosePublication() ?: return
        reviewPullRequest(null, ref, publish)
    }

    /** AI incelemesi yapmadan PR değişikliklerini yerel diff görüntüleyicide açar. */
    fun showChangesUrlInteractive() {
        val ref = askPullRequestUrl("PR Değişikliklerini Göster") ?: return
        PullRequestDiffViewer.show(project, ref)
    }

    /** PR değişikliklerini açar; bu PR için gösterilen bir inceleme varsa bulgular diff içinde işaretlenir. */
    fun showChanges(ref: PullRequestRef, focus: PullRequestDiffViewer.Focus? = null) {
        val session = current?.takeIf { it.outcome?.pullRequest?.ref == ref }
            ?: history.firstOrNull { it.outcome?.pullRequest?.ref == ref }
        PullRequestDiffViewer.show(project, ref, focus, session?.review?.findings.orEmpty())
    }

    private fun showChanges(session: ReviewSession) {
        val ref = session.outcome?.pullRequest?.ref ?: return
        PullRequestDiffViewer.show(project, ref, null, session.review.findings)
    }

    /** Yayına dahil edilecek bulguların özetiyle onay ister. EDT'de çağrılmalı. */
    fun confirmAndPublishCurrent() {
        val session = current ?: return
        val outcome = session.outcome ?: return
        if (outcome.published) return
        val ref = outcome.pullRequest.ref
        val included = session.includedFindings
        val excludedCount = session.review.findings.size - included.size
        val message = buildString {
            append("PR #${ref.pullRequestId} · ${outcome.pullRequest.title}\n")
            append("${ref.projectKey}/${ref.repositorySlug}\n\n")
            append("Gönderilecek: ${included.size} bulgu (satır yorumu; konumu bulunamayanlar genel yoruma eklenir) + 1 genel özet yorumu.")
            if (excludedCount > 0) append("\nYayından hariç tutulan: $excludedCount bulgu.")
        }
        val answer = Messages.showYesNoDialog(project, message, "Bitbucket'a Gönder", "Gönder", "Vazgeç", Messages.getQuestionIcon())
        if (answer == Messages.YES) publishCurrent()
    }

    /** Ekranda gösterilen PR sonucunu Bitbucket'a yayınlar (yanlış PR'a yayın riskini önlemek için sonucun kendi ref'i kullanılır). */
    fun publishCurrent() {
        val session = current ?: return
        val outcome = session.outcome ?: return
        if (outcome.published) return
        // Hariç tutulan bulgular yayına girmez; panelde görünmeye devam eder.
        val toPublish = outcome.copy(review = outcome.review.copy(findings = session.includedFindings))
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Yorumlar Bitbucket'a gönderiliyor", true) {
            private var result: ReviewOutcome? = null

            override fun run(indicator: ProgressIndicator) {
                activeIndicator = indicator
                setProgress("Yorumlar Bitbucket'a gönderiliyor…", null)
                result = ReviewCoordinator().publish(toPublish)
            }

            override fun onSuccess() {
                val r = result ?: return
                val message = if (r.skippedDuplicate) "Bu commit için yorum zaten mevcut; tekrar yayınlanmadı."
                else "Bitbucket'a ${r.publication?.inlineComments ?: 0} satır yorumu ve genel özet yayınlandı."
                update(
                    session.copy(outcome = outcome.copy(published = true, skippedDuplicate = r.skippedDuplicate, publication = r.publication)),
                    message,
                )
                ReviewNotifier.notify(
                    project, message,
                    actions = listOfNotNull(
                        outcome.pullRequest.url.takeIf { it.isNotBlank() }?.let { url -> ReviewNotifier.Action("Bitbucket'ta Aç") { BrowserUtil.browse(url) } },
                    ),
                )
            }

            override fun onThrowable(error: Throwable) = handleError(error, "Yorum gönderilemedi") { publishCurrent() }

            override fun onFinished() {
                endProgress()
                fire()
            }
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
                activeIndicator = indicator
                indicator.isIndeterminate = false
                indicator.text = "Yerel git diff hesaplanıyor..."
                setProgress("Yerel git diff hesaplanıyor…", null)
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
                            setProgress(step, fraction)
                        },
                    ),
                )
                session = ReviewSession(snapshot.title, review, null, root)
            }

            override fun onSuccess() {
                val s = session ?: return
                highlight(s)
                val message = "Yerel inceleme tamamlandı: ${s.review.findings.size} bulgu. Risk: ${s.review.riskLevel.uppercase()}"
                update(s, message)
                ReviewNotifier.notify(
                    project, message,
                    if (s.review.riskLevel == "high") NotificationType.WARNING else NotificationType.INFORMATION,
                    actions = listOf(ReviewNotifier.Action("Bulguları Göster") { showToolWindow() }),
                )
            }

            override fun onCancel() = update(status = "İnceleme iptal edildi.")

            override fun onThrowable(error: Throwable) = handleError(error, "Yerel inceleme başarısız") { reviewLocal() }

            override fun onFinished() {
                endProgress()
                fire()
            }
        })
    }

    /** Görünümü ve editör işaretlerini temizler; sonuç geçmişte kalır (geçmiş listesinden geri açılabilir). */
    fun clearFindings() {
        FindingsHighlighter.getInstance(project).clear()
        update(null, "Bulgular temizlendi. Geçmişten tekrar açabilirsiniz.")
    }

    /** Editör vurgusu: yerel incelemede her zaman; PR incelemesinde yalnızca yerel HEAD aynı commit'teyse (aksi halde satırlar kayar). */
    private fun highlight(session: ReviewSession) {
        val highlighter = FindingsHighlighter.getInstance(project)
        val root = session.root
        if (root != null && session.workingCopyMatches) highlighter.report(root, session.review.findings) else highlighter.clear()
    }

    /** Task hatalarını IDE "Internal Error" yerine kullanıcı bildirimi olarak gösterir. */
    private fun handleError(error: Throwable, prefix: String, retry: (() -> Unit)? = null) {
        if (error is ProcessCanceledException) {
            update(status = "İnceleme iptal edildi.")
            return
        }
        log.warn(prefix, error)
        update(status = "$prefix: ${error.message ?: error.javaClass.simpleName}", error = true)
        ReviewNotifier.error(project, error, prefix, retry)
    }

    companion object {
        const val TOOL_WINDOW_ID = "YKB AI Review"
        private const val MAX_HISTORY = 10

        fun getInstance(project: Project): ReviewSessionService = project.getService(ReviewSessionService::class.java)
    }
}
