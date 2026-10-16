package com.ykb.review.local

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.progress.ProgressManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

data class LocalSnapshot(val title: String, val diff: String)

/** VS Code local/git.ts karşılığı. */
object LocalGit {

    private val DIFF_OPTIONS = listOf("--no-ext-diff", "--no-textconv", "--binary", "--no-color", "--no-renames")
    private const val MAX_UNTRACKED_BYTES = 8L * 1024 * 1024

    fun git(root: String, vararg args: String, timeoutMs: Int = 60_000): String {
        val command = GeneralCommandLine(listOf("git") + args)
            .withWorkDirectory(root)
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment("GIT_OPTIONAL_LOCKS", "0")
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        val handler = CapturingProcessHandler(command)
        val indicator = ProgressManager.getGlobalProgressIndicator()
        val output = if (indicator != null) handler.runProcessWithProgressIndicator(indicator, timeoutMs) else handler.runProcess(timeoutMs)
        if (output.isCancelled) throw com.intellij.openapi.progress.ProcessCanceledException()
        if (output.isTimeout) throw IllegalStateException("Git komutu zaman aşımına uğradı: ${args.firstOrNull()}")
        if (output.exitCode != 0) throw IllegalStateException("Git komutu başarısız: ${args.firstOrNull()} (${output.exitCode}).")
        return output.stdout
    }

    fun topLevel(path: String): String? = try {
        git(path, "rev-parse", "--show-toplevel").trim().ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    fun remoteUrls(root: String): List<String> = try {
        git(root, "remote", "-v").lines().mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1) }.distinct()
    } catch (_: Exception) {
        emptyList()
    }

    /** Staged ve unstaged bölümleri ayrı tutar (zıt düzenlemeler birbirini götürmesin), untracked dosyaları ekler. */
    fun localSnapshot(root: String, paths: List<String> = emptyList()): LocalSnapshot {
        val pathspec = if (paths.isEmpty()) emptyArray() else (listOf("--") + paths).toTypedArray()
        val staged = git(root, "diff", "--cached", *DIFF_OPTIONS.toTypedArray(), *pathspec)
        val unstaged = git(root, "diff", *DIFF_OPTIONS.toTypedArray(), *pathspec)
        val diff = StringBuilder()
        if (staged.isNotEmpty()) diff.append("Staged değişiklikler:\n").append(staged)
        if (unstaged.isNotEmpty()) diff.append("\nUnstaged değişiklikler:\n").append(unstaged)

        val untracked = git(root, "ls-files", "--others", "--exclude-standard", "-z", *pathspec)
            .split('\u0000')
            .filter { it.isNotEmpty() }
        for (file in untracked) {
            ProgressManager.checkCanceled()
            val path = Paths.get(root, file)
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("İncelenemeyen untracked dosya: $file")
            if (Files.size(path) > MAX_UNTRACKED_BYTES) throw IllegalStateException("Untracked dosya çok büyük: $file")
            val content = Files.readAllBytes(path)
            diff.append("\ndiff --git a/$file b/$file\nnew file mode 100644\n")
            if (content.any { it == 0.toByte() }) {
                diff.append("Binary dosya (metin içeriği incelenemiyor)\n")
            } else {
                val lines = String(content, StandardCharsets.UTF_8).split('\n')
                diff.append("--- /dev/null\n+++ b/$file\n@@ -0,0 +1,${lines.size} @@\n")
                diff.append(lines.joinToString("\n") { "+$it" }).append('\n')
            }
        }
        val scope = when (paths.size) {
            0 -> ""
            1 -> " · ${paths.single()}"
            else -> " · ${paths.size} seçili dosya"
        }
        return LocalSnapshot("Yerel değişiklikler · staged, unstaged ve untracked$scope", diff.toString())
    }

    fun exists(path: String) = File(path).exists()
}
