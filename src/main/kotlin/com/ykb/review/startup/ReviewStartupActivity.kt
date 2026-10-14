package com.ykb.review.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.ykb.review.local.HookCleaner
import com.ykb.review.local.LocalGit
import com.ykb.review.review.AutomaticReviewService
import kotlin.coroutines.cancellation.CancellationException

/**
 * IDE/proje açılışında çalışır (VS Code onStartupFinished karşılığı):
 * - Uygulama seviyesindeki tek Auto Review zamanlayıcısını başlatır.
 * - Eski sürümlerden kalan git hook yapılandırmasını temizler.
 */
class ReviewStartupActivity : ProjectActivity {
    private val log = Logger.getInstance(ReviewStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        try {
            AutomaticReviewService.getInstance().ensureStarted()
        } catch (e: Throwable) {
            if (e is CancellationException || e is ControlFlowException) throw e
            log.warn("YKB AI Review: otomatik review zamanlayıcısı başlatılamadı", e)
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (project.isDisposed) return@executeOnPooledThread
                val roots = ProjectLevelVcsManager.getInstance(project).allVcsRoots
                    .filter { it.vcs?.name.equals("Git", ignoreCase = true) }
                    .map { it.path.path }
                    .ifEmpty { listOfNotNull(project.basePath?.let { LocalGit.topLevel(it) }) }
                roots.distinct().forEach { HookCleaner.uninstallHooks(it) }
            } catch (e: Throwable) {
                if (e is ControlFlowException) return@executeOnPooledThread
                log.warn("YKB AI Review: eski git hook temizliği başarısız", e)
            }
        }
    }
}
