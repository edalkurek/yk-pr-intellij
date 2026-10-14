package com.ykb.review.local

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.file.Paths

/**
 * VS Code hookInstaller.ts (uninstallHooks) karşılığı: eklentinin eski sürümlerinin kurduğu
 * `ykb-ai-review-hooks` core.hooksPath ayarını ve dosyalarını temizler; özgün hooksPath'i geri yükler.
 */
object HookCleaner {
    private val log = Logger.getInstance(HookCleaner::class.java)

    fun uninstallHooks(root: String) {
        try {
            fun gitOrEmpty(vararg args: String) = try {
                LocalGit.git(root, *args).trim()
            } catch (_: Exception) {
                ""
            }

            val worktreeConfig = gitOrEmpty("config", "--get", "extensions.worktreeConfig") == "true"
            val scope = if (worktreeConfig) "--worktree" else "--local"
            val currentHooksPath = gitOrEmpty("config", scope, "--get", "core.hooksPath")
            val descriptorPath = gitOrEmpty("rev-parse", "--git-path", "ykb-ai-review.json")
            val descriptor = if (descriptorPath.isNotEmpty()) Paths.get(root).resolve(descriptorPath).normalize().toFile() else null

            val base: File? = if (worktreeConfig && descriptor != null) {
                descriptor.parentFile
            } else {
                gitOrEmpty("rev-parse", "--git-common-dir").takeIf { it.isNotEmpty() }
                    ?.let { Paths.get(root).resolve(it).normalize().toFile() }
            }
            val directory = base?.let { File(it, "ykb-ai-review-hooks") }
            val normalizedDir = directory?.path?.replace('\\', '/')
            val normalizedCurrent = if (currentHooksPath.isNotEmpty()) {
                Paths.get(root).resolve(currentHooksPath).normalize().toString().replace('\\', '/')
            } else ""

            if (directory != null && (normalizedCurrent == normalizedDir || currentHooksPath.endsWith("ykb-ai-review-hooks"))) {
                val originalPath = try {
                    Regex("\"originalPath\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                        .find(File(directory, "state.json").readText())?.groupValues?.get(1)?.replace("\\\\", "\\")
                } catch (_: Exception) {
                    null
                }
                if (originalPath != null && !originalPath.endsWith("ykb-ai-review-hooks")) {
                    gitOrEmpty("config", scope, "core.hooksPath", originalPath.replace('\\', '/'))
                } else {
                    gitOrEmpty("config", scope, "--unset", "core.hooksPath")
                }
            }
            directory?.takeIf { it.exists() }?.deleteRecursively()
            descriptor?.takeIf { it.exists() }?.delete()
        } catch (e: Exception) {
            log.debug("Hook temizliği atlandı: $root", e)
        }
    }
}
