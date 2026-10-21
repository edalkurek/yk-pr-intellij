package com.ykb.review.config

import com.intellij.openapi.diagnostic.Logger
import com.ykb.review.bitbucket.BitbucketClient
import com.ykb.review.util.rethrowIfControlFlow
import com.ykb.review.util.safeMessage
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** Talimat metninin bir katmanı; [source] inceleme sonucunda hangi kaynakların uygulandığını göstermek için kullanılır. */
data class InstructionLayer(val source: String, val text: String) {
    val header: String get() = "$source talimatları"
}

/** Ortak talimat reposunun konumu (`PROJECT/slug` + dal). */
data class SharedRulesRepo(val projectKey: String, val slug: String, val branch: String) {
    companion object {
        /** `PROJECT/slug` biçimini çözer; boş veya geçersizse null. */
        fun parse(value: String, branch: String): SharedRulesRepo? {
            val parts = value.trim().trim('/').split('/')
            if (parts.size != 2 || parts.any { it.isBlank() }) return null
            return SharedRulesRepo(parts[0].trim(), parts[1].trim(), branch.trim().ifEmpty { "master" })
        }
    }
}

/**
 * Bitbucket'taki ortak talimat reposundan genel / proje / repo talimatlarını okur.
 *
 * Düzen:
 * - `general.md`: tüm repolar
 * - `projects/<PROJECT>.md`: o projedeki tüm repolar
 * - `repos/<PROJECT>/<slug>.md`: yalnızca o repo
 *
 * Sonuçlar [ttlMs] boyunca önbellekte tutulur; okuma hatasında son başarılı içerik kullanılır, inceleme düşmez.
 */
class SharedInstructions(
    private val loader: (repo: SharedRulesRepo, path: String) -> String?,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val text: String?, val fetchedAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun invalidate() = cache.clear()

    /** Repo için ortak katmanları genelden özele sırayla döner; dosyası olmayan katmanlar atlanır. */
    fun layers(repo: SharedRulesRepo, projectKey: String?, slug: String?): List<InstructionLayer> {
        val project = projectKey?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val repoSlug = slug?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val candidates = buildList {
            add("Ortak genel" to "general.md")
            if (project != null) {
                add("Ortak proje ($project)" to "projects/$project.md")
                if (repoSlug != null) add("Ortak repo ($project/$repoSlug)" to "repos/$project/$repoSlug.md")
            }
        }
        var remaining = MAX_TOTAL_CHARS
        return candidates.mapNotNull { (source, path) ->
            val text = read(repo, path)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (remaining <= 0) {
                log.warn("Ortak talimat toplam sınırı aşıldı; $path atlandı.")
                return@mapNotNull null
            }
            val limited = truncate(text, minOf(MAX_FILE_CHARS, remaining), path)
            remaining -= limited.length
            InstructionLayer(source, limited)
        }
    }

    private fun read(repo: SharedRulesRepo, path: String): String? {
        val key = "${repo.projectKey}/${repo.slug}@${repo.branch}:$path"
        val cached = cache[key]
        if (cached != null && now() - cached.fetchedAt < ttlMs) return cached.text
        return try {
            val text = loader(repo, path)
            cache[key] = Entry(text, now())
            text
        } catch (e: Throwable) {
            e.rethrowIfControlFlow()
            log.warn("Ortak talimat okunamadı ($path): ${e.safeMessage()}" + if (cached != null) " Önbellekteki sürüm kullanılıyor." else "")
            cached?.text
        }
    }

    private fun truncate(text: String, max: Int, path: String): String {
        if (text.length <= max) return text
        log.warn("Ortak talimat dosyası $max karakterde kesildi: $path")
        return text.substring(0, max)
    }

    companion object {
        private val log = Logger.getInstance(SharedInstructions::class.java)
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L
        const val MAX_FILE_CHARS = 20_000
        const val MAX_TOTAL_CHARS = 40_000
        private const val MAX_FETCH_BYTES = 256 * 1024

        /** Uygulama genelinde paylaşılan örnek; Bitbucket istemcisi her okumada güncel ayarlardan kurulur. */
        val instance: SharedInstructions by lazy {
            SharedInstructions({ repo, path ->
                BitbucketClient.fromSettings()
                    .getRawFile(repo.projectKey, repo.slug, path, repo.branch, MAX_FETCH_BYTES)
                    ?.toString(StandardCharsets.UTF_8)
            })
        }
    }
}
