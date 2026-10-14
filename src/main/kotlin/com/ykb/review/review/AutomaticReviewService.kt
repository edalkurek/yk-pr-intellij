package com.ykb.review.review

import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import com.ykb.review.bitbucket.BitbucketClient
import com.ykb.review.config.ReviewSettingsState
import com.ykb.review.ui.ReviewNotifier
import com.ykb.review.util.Cancellation
import com.ykb.review.util.rethrowIfControlFlow
import com.ykb.review.util.safeMessage
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Auto review durumu / ayar değişikliği yayını (status bar ve panel dinler). */
fun interface AutoReviewStatusListener {
    fun statusChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<AutoReviewStatusListener> = Topic("YKB Auto Review Status", AutoReviewStatusListener::class.java)

        fun publish() {
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).statusChanged()
        }
    }
}

/**
 * Uygulama seviyesinde TEK zamanlayıcı (VS Code AutomaticReviewController karşılığı).
 * Önceki sürümde proje servisi olduğu için birden fazla proje açıkken aynı PR'lar paralel taranıyordu ve
 * servis lazy olduğundan IDE açılışında hiç başlamıyordu; artık startup activity [ensureStarted] çağırır.
 */
@Service(Service.Level.APP)
class AutomaticReviewService : Disposable {

    private val log = Logger.getInstance(AutomaticReviewService::class.java)
    private val lock = Any()
    private var scheduledTask: ScheduledFuture<*>? = null
    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    @Volatile
    var lastRunTime: Long? = null
        private set

    @Volatile
    var lastRunSummary: String? = null
        private set

    val isRunning: Boolean get() = running.get()

    fun ensureStarted() {
        if (started.compareAndSet(false, true)) restartTimer()
    }

    fun restartTimer() {
        synchronized(lock) {
            scheduledTask?.cancel(false)
            scheduledTask = null
            val settings = ReviewSettingsState.instance
            if (settings.automaticReviewEnabled) {
                val interval = settings.automaticReviewIntervalMinutes.coerceIn(1, 1440).toLong()
                scheduledTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                    { runInBackground() },
                    10, // ilk tarama IDE açıldıktan 10 sn sonra
                    interval * 60,
                    TimeUnit.SECONDS,
                )
                log.info("Otomatik review $interval dakikalık aralıkla etkin.")
            }
        }
        AutoReviewStatusListener.publish()
    }

    fun toggleEnabled(project: Project?) {
        val settings = ReviewSettingsState.instance
        val enable = !settings.automaticReviewEnabled
        if (enable && !settings.bitbucketTokenStored && settings.bitbucketToken.isBlank()) {
            ReviewNotifier.error(
                project,
                com.ykb.review.config.ConfigurationError("Auto PR Review'u etkinleştirmeden önce Bitbucket bağlantı ayarlarını yapılandırmalısınız."),
            )
            return
        }
        settings.automaticReviewEnabled = enable
        restartTimer()
        ReviewNotifier.notify(
            project,
            if (enable) "Auto PR Review etkinleştirildi. (${settings.automaticReviewIntervalMinutes} dakikada bir otomatik kontrol edilecek)"
            else "Auto PR Review devre dışı bırakıldı.",
        )
    }

    /** Manuel çalıştırma: iptal edilebilir ilerleme çubuğu ile. */
    fun runNow(project: Project?) {
        if (running.get()) {
            ReviewNotifier.notify(project, "Önceki review turu devam ettiği için işlem başlatılamadı.", NotificationType.WARNING)
            return
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Auto PR Review çalıştırılıyor…", true) {
            override fun run(indicator: ProgressIndicator) {
                val summary = execute(manual = true, indicator = indicator) ?: return
                ReviewNotifier.notify(project, "Auto PR Review: $summary")
            }
        })
    }

    private fun runInBackground() {
        try {
            execute(manual = false, indicator = null)
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            log.warn("Otomatik review turu başarısız", e)
        }
    }

    /** @return özet; başka bir tur çalışıyorsa null. */
    private fun execute(manual: Boolean, indicator: ProgressIndicator?): String? {
        if (!running.compareAndSet(false, true)) {
            log.info("Önceki review turu devam ettiği için işlem başlatılamadı.")
            return null
        }
        AutoReviewStatusListener.publish()
        try {
            val summary = processPullRequests(manual, indicator)
            lastRunTime = System.currentTimeMillis()
            lastRunSummary = summary
            return summary
        } finally {
            running.set(false)
            AutoReviewStatusListener.publish()
        }
    }

    private fun processPullRequests(manual: Boolean, indicator: ProgressIndicator?): String {
        try {
            val settings = ReviewSettingsState.instance
            if (!manual && !settings.automaticReviewEnabled) return "Otomatik review kapalı"

            val coordinator = ReviewCoordinator(settings)
            val client = BitbucketClient.fromSettings()
            val pullRequests = client.listOpenPullRequestsForReviewer()
            if (pullRequests.isEmpty()) return "Açık PR bulunamadı"

            var reviewed = 0
            var skipped = 0
            var errors = 0
            for ((i, pr) in pullRequests.withIndex()) {
                if (indicator?.isCanceled == true) return "İşlem kullanıcı tarafından iptal edildi"
                indicator?.text = "PR #${pr.ref.pullRequestId} inceleniyor (${i + 1}/${pullRequests.size})…"
                indicator?.fraction = i.toDouble() / pullRequests.size
                try {
                    if (coordinator.hasReviewFor(pr, client)) {
                        log.info("PR #${pr.ref.pullRequestId}: commit daha önce incelenmiş, atlandı.")
                        skipped++
                        continue
                    }
                    val outcome = coordinator.review(pr.ref, pr, publish = true) { step, _ -> indicator?.text2 = step }
                    if (outcome.skippedDuplicate) {
                        skipped++
                    } else {
                        reviewed++
                        ReviewNotifier.notify(
                            null,
                            "PR #${pr.ref.pullRequestId} (${pr.ref.repositorySlug}) otomatik olarak incelendi ve yorumlar gönderildi. (Risk: ${outcome.review.riskLevel.uppercase()})",
                        )
                    }
                } catch (e: Throwable) {
                    e.rethrowIfControlFlow()
                    errors++
                    log.warn("PR #${pr.ref.pullRequestId} otomatik review hatası: ${e.safeMessage()}", e)
                }
                Cancellation.check()
            }
            val parts = buildList {
                if (reviewed > 0) add("$reviewed PR incelendi")
                if (skipped > 0) add("$skipped atlandı")
                if (errors > 0) add("$errors hata")
            }
            return if (parts.isEmpty()) "İncelenecek yeni PR bulunamadı" else parts.joinToString(", ")
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            log.warn("Otomatik review turu başarısız: ${e.safeMessage()}", e)
            return "Hata: ${e.safeMessage()}"
        }
    }

    override fun dispose() {
        synchronized(lock) {
            scheduledTask?.cancel(true)
            scheduledTask = null
        }
    }

    companion object {
        fun getInstance(): AutomaticReviewService =
            ApplicationManager.getApplication().getService(AutomaticReviewService::class.java)
    }
}
