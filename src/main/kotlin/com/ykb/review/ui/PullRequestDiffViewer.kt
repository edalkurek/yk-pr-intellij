package com.ykb.review.ui

import com.ykb.review.ai.DiffLocations
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.notification.NotificationType
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Pair as IdeaPair
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.ui.ChangeDiffRequestChain
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.vcsUtil.VcsUtil
import com.ykb.review.ai.ReviewFinding
import com.ykb.review.bitbucket.BitbucketClient
import com.ykb.review.bitbucket.PullRequestChanges
import com.ykb.review.bitbucket.PullRequestFileChange
import com.ykb.review.bitbucket.PullRequestRef
import com.ykb.review.bitbucket.RemoteMatcher
import com.ykb.review.local.LocalGit
import com.ykb.review.review.FindingsPresenter
import com.ykb.review.util.safeMessage
import java.io.File

/**
 * Reviewer olunan PR'ın değişikliklerini IntelliJ'in yerel diff görüntüleyicisinde açar (checkout gerekmez).
 * Dosya listesi Bitbucket `/changes`, içerikler `/raw` uç noktasından ve yalnızca dosya açıldığında (lazy) çekilir.
 */
object PullRequestDiffViewer {

    /** Açılışta seçilecek dosya ve (varsa) kaydırılacak satır. side: "old" | "new" | null */
    data class Focus(val path: String, val line: Int? = null, val side: String? = null)

    /** Diff isteğine eklenen, o dosyaya ait AI bulguları ([FindingsDiffExtension] okur). */
    val FINDINGS_KEY: Key<List<ReviewFinding>> = Key.create("YKB.Review.DiffFindings")

    fun show(project: Project, ref: PullRequestRef, focus: Focus? = null, findings: List<ReviewFinding> = emptyList()) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "PR #${ref.pullRequestId} değişiklikleri yükleniyor…", true) {
            private var result: PullRequestChanges? = null
            private var changes: List<Change> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Değişen dosyalar alınıyor…"
                val client = BitbucketClient.fromSettings()
                val info = client.getPullRequestChanges(ref)
                indicator.text = "Yerel depo eşleştiriliyor…"
                val localRoot = findLocalRoot(project, ref)
                result = info
                changes = info.files.mapNotNull { toChange(client, ref, info, it, localRoot) }
            }

            override fun onSuccess() {
                val info = result ?: return
                if (changes.isEmpty()) {
                    ReviewNotifier.notify(project, "PR #${ref.pullRequestId} içinde görüntülenecek dosya değişikliği yok.", NotificationType.WARNING)
                    return
                }
                if (info.truncated) {
                    ReviewNotifier.notify(
                        project,
                        "PR #${ref.pullRequestId} çok fazla dosya içeriyor; yalnızca ilk ${changes.size} dosya gösteriliyor.",
                        NotificationType.WARNING,
                    )
                }
                val focusPath = focus?.path?.replace('\\', '/')?.removePrefix("./")?.replace(DiffLocations.PATH_PREFIX, "")
                val focusIndex = focusPath?.let { path ->
                    info.files.indexOfFirst { it.path == path || it.srcPath == path }.takeIf { it >= 0 }
                        ?: info.files.indexOfFirst { it.path.endsWith("/$path") }.takeIf { it >= 0 }
                }
                val findingsByPath = findings.groupBy { FindingsPresenter.normalizePath(it.file) }
                val producers = changes.mapIndexedNotNull { index, change ->
                    val context = mutableMapOf<Key<*>, Any>()
                    if (index == focusIndex && focus?.line != null) {
                        val side = if (focus.side == "old" || change.afterRevision == null) Side.LEFT else Side.RIGHT
                        context[DiffUserDataKeys.SCROLL_TO_LINE] = IdeaPair.create(side, (focus.line - 1).coerceAtLeast(0))
                    }
                    val file = info.files.getOrNull(index)
                    val fileFindings = file?.let { f ->
                        findingsByPath[f.path].orEmpty() + (f.srcPath?.takeIf { it != f.path }?.let { findingsByPath[it] }.orEmpty())
                    }.orEmpty()
                    if (fileFindings.isNotEmpty()) context[FINDINGS_KEY] = fileFindings
                    ChangeDiffRequestProducer.create(project, change, context)
                }
                if (producers.isEmpty()) {
                    ReviewNotifier.notify(project, "PR değişiklikleri görüntülenemedi.", NotificationType.WARNING)
                    return
                }
                if (focusPath != null && focusIndex == null) {
                    ReviewNotifier.notify(project, "'$focusPath' bu PR'ın değişen dosyaları arasında bulunamadı.", NotificationType.WARNING)
                }
                val focusChange = focusIndex?.let { changes.getOrNull(it) }
                val selected = producers.indexOfFirst { it.change === focusChange }.coerceAtLeast(0)
                DiffManager.getInstance().showDiff(project, ChangeDiffRequestChain(producers, selected), DiffDialogHints.DEFAULT)
            }

            override fun onThrowable(error: Throwable) {
                if (error is ProcessCanceledException) return
                ReviewNotifier.error(project, error, "PR değişiklikleri yüklenemedi")
            }
        })
    }

    private fun toChange(
        client: BitbucketClient,
        ref: PullRequestRef,
        info: PullRequestChanges,
        file: PullRequestFileChange,
        localRoot: String?,
    ): Change? {
        val targetRepo = ref.projectKey to ref.repositorySlug
        val sourceRepo = info.sourceProjectKey to info.sourceRepositorySlug
        // PR commit'leri hedef depoda da bulunur; fork PR'larında kaynak depo yedek olarak denenir.
        val afterRepos = listOf(targetRepo, sourceRepo).distinct()
        val beforePath = file.srcPath ?: file.path

        val before = if (file.type == "ADD") null else BitbucketRevision(
            client, listOf(targetRepo), beforePath, info.toHash, filePath(localRoot, ref, beforePath), "Hedef",
        )
        val after = if (file.type == "DELETE") null else BitbucketRevision(
            client, afterRepos, file.path, info.fromHash, filePath(localRoot, ref, file.path), "PR",
        )
        if (before == null && after == null) return null
        val status = when (file.type) {
            "ADD" -> FileStatus.ADDED
            "DELETE" -> FileStatus.DELETED
            else -> FileStatus.MODIFIED
        }
        return Change(before, after, status)
    }

    /** Yerel depo bulunursa dosya yolları ona bağlanır: dosya kodlaması (ör. windows-1254) ve "kaynağa git" doğru çalışır. */
    private fun filePath(localRoot: String?, ref: PullRequestRef, path: String): FilePath =
        if (localRoot != null) VcsUtil.getFilePath(File(localRoot, path), false)
        else VcsUtil.getFilePath("/${ref.projectKey}/${ref.repositorySlug}/$path", false)

    /** Projede bu PR'ın deposuna (remote eşleşmesiyle) karşılık gelen git kökü. Arka plan thread'inde çağrılmalı. */
    internal fun findLocalRoot(project: Project, ref: PullRequestRef): String? {
        val roots = ProjectLevelVcsManager.getInstance(project).allVcsRoots
            .filter { it.vcs?.name.equals("Git", ignoreCase = true) }
            .map { it.path.path }
            .distinct()
        return roots.firstOrNull { root ->
            LocalGit.remoteUrls(root).any { RemoteMatcher.matches(it, ref.projectKey, ref.repositorySlug) }
        }
    }

    private class CommitRevision(private val commit: String, private val label: String) : VcsRevisionNumber {
        override fun asString(): String = "$label · ${commit.take(12)}"
        override fun compareTo(other: VcsRevisionNumber?): Int = 0
    }

    private class BitbucketRevision(
        private val client: BitbucketClient,
        private val repositories: List<Pair<String, String>>,
        private val path: String,
        private val commit: String,
        private val filePath: FilePath,
        label: String,
    ) : ByteBackedContentRevision {
        private val revision = CommitRevision(commit, label)

        @Volatile
        private var cached: ByteArray? = null

        override fun getContentAsBytes(): ByteArray {
            cached?.let { return it }
            val loaded = try {
                repositories.firstNotNullOfOrNull { (projectKey, slug) -> client.getRawFile(projectKey, slug, path, commit) }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                throw VcsException(e.safeMessage(), e)
            } ?: throw VcsException("Dosya içeriği Bitbucket'ta bulunamadı: $path @ ${commit.take(12)}")
            cached = loaded
            return loaded
        }

        override fun getContent(): String = String(getContentAsBytes(), filePath.charset)

        override fun getFile(): FilePath = filePath

        override fun getRevisionNumber(): VcsRevisionNumber = revision
    }
}
