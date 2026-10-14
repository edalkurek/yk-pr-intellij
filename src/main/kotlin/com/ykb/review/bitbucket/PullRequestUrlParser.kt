package com.ykb.review.bitbucket

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Bitbucket Server/Data Center PR tarayıcı adreslerini çözer (VS Code urlParser.ts karşılığı). */
object PullRequestUrlParser {

    fun parse(value: String): PullRequestRef {
        val uri = try {
            URI(value.trim())
        } catch (_: Exception) {
            throw IllegalArgumentException("Geçersiz Bitbucket PR bağlantısı.")
        }
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "https" && scheme != "http") || uri.host.isNullOrBlank() || !uri.rawUserInfo.isNullOrEmpty()) {
            throw IllegalArgumentException("Geçersiz Bitbucket PR bağlantısı.")
        }
        if (uri.host.equals("bitbucket.org", ignoreCase = true)) {
            throw IllegalArgumentException("Bu eklenti yalnızca Bitbucket Server/Data Center PR bağlantılarını destekler.")
        }
        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        val projectsIndex = segments.indexOfFirst { it.equals("projects", ignoreCase = true) }
        if (projectsIndex < 0 ||
            !segments.getOrNull(projectsIndex + 2).equals("repos", ignoreCase = true) ||
            !segments.getOrNull(projectsIndex + 4).equals("pull-requests", ignoreCase = true) ||
            segments.getOrNull(projectsIndex + 5) == null
        ) {
            throw IllegalArgumentException("Desteklenmeyen Bitbucket Server PR bağlantısı. Örn: https://host/bitbucket/projects/PROJ/repos/repo/pull-requests/123")
        }
        val contextPath = segments.subList(0, projectsIndex).joinToString("/")
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return PullRequestRef(
            baseUrl = "$scheme://${uri.host}$port${if (contextPath.isNotEmpty()) "/$contextPath" else ""}",
            projectKey = decode(segments[projectsIndex + 1], "proje"),
            repositorySlug = decode(segments[projectsIndex + 3], "repo"),
            pullRequestId = parseId(segments[projectsIndex + 5]),
        )
    }

    private fun decode(value: String, label: String): String {
        val decoded = try {
            URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
        if (decoded.isNullOrEmpty() || decoded == "." || decoded == ".." || decoded.contains('/')) {
            throw IllegalArgumentException("Bitbucket PR bağlantısında geçersiz $label bilgisi.")
        }
        return decoded
    }

    private fun parseId(value: String): Long {
        if (!value.matches(Regex("\\d+"))) throw IllegalArgumentException("Bitbucket PR bağlantısında geçersiz PR numarası.")
        val id = value.toLongOrNull() ?: throw IllegalArgumentException("Bitbucket PR bağlantısında geçersiz PR numarası.")
        if (id <= 0) throw IllegalArgumentException("Bitbucket PR bağlantısında geçersiz PR numarası.")
        return id
    }
}
