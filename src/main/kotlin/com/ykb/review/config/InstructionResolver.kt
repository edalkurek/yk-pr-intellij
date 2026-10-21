package com.ykb.review.config

import com.ykb.review.bitbucket.PullRequestRef
import java.net.URI

/** Modele gidecek talimat metni ve hangi kaynaklardan geldiği. */
data class ResolvedInstructions(val text: String, val sources: List<String>) {
    /** İnceleme sonucunda gösterilecek kısa özet (örn. "Ortak genel + Ortak repo (PAY/api) + IDE genel"). */
    val summary: String? get() = sources.takeIf { it.isNotEmpty() }?.joinToString(" + ")
}

/**
 * Ortak talimat reposu ([SharedInstructions]) ile IDE ayarlarındaki talimatları birleştirir.
 * Sıra genelden özele: ortak genel → ortak proje → ortak repo → IDE genel → IDE repo.
 */
class InstructionResolver(
    private val settings: ReviewSettingsState = ReviewSettingsState.instance,
    private val shared: SharedInstructions = SharedInstructions.instance,
) {

    fun forPullRequest(ref: PullRequestRef): ResolvedInstructions =
        resolve(ref.projectKey, ref.repositorySlug, settings.repoInstructionFor(ref))

    /** Yerel depo: remote URL'lerinden Bitbucket proje/repo bilgisi çıkarılır; bulunamazsa yalnızca genel katmanlar uygulanır. */
    fun forLocal(remoteUrls: List<String>): ResolvedInstructions {
        val identity = repoIdentityForRemotes(remoteUrls, settings.bitbucketBaseUrl)
        return resolve(identity?.first, identity?.second, settings.repoInstructionForRemotes(remoteUrls))
    }

    private fun resolve(projectKey: String?, slug: String?, ideRepoInstruction: String?): ResolvedInstructions {
        val layers = mutableListOf<InstructionLayer>()
        SharedRulesRepo.parse(settings.sharedRulesRepo, settings.sharedRulesBranch)?.let { repo ->
            layers += shared.layers(repo, projectKey, slug)
        }
        settings.extraInstructions.trim().takeIf { it.isNotEmpty() }?.let { layers += InstructionLayer("IDE genel", it) }
        ideRepoInstruction?.trim()?.takeIf { it.isNotEmpty() }?.let { layers += InstructionLayer("IDE repo", it) }
        return merge(layers, settings.effectiveInstructions(ideRepoInstruction))
    }

    companion object {
        /**
         * Ortak katman yoksa metin eski biçimde ([ReviewSettingsState.effectiveInstructions]) kalır;
         * böylece ortak repo kullanmayanların prompt'u değişmez.
         */
        fun merge(layers: List<InstructionLayer>, ideOnlyText: String): ResolvedInstructions {
            val sources = layers.map { it.source }
            if (layers.none { it.source.startsWith("Ortak") }) return ResolvedInstructions(ideOnlyText, sources)
            val text = layers.joinToString("\n\n") { "${it.header}:\n${it.text}" }
            return ResolvedInstructions(text, sources)
        }

        /**
         * Git remote URL'lerinden Bitbucket `PROJECT`/`slug` çıkarır. Desteklenen biçimler:
         * `https://host/[context/]scm/proj/repo.git`, `ssh://git@host:7999/proj/repo.git`, `git@host:proj/repo.git`.
         * Bitbucket host'uyla eşleşen remote tercih edilir; kişisel (`~user`) repolar atlanır.
         */
        fun repoIdentityForRemotes(remoteUrls: List<String>, bitbucketBaseUrl: String): Pair<String, String>? {
            val bitbucketHost = try {
                URI(bitbucketBaseUrl.trim()).host?.lowercase()
            } catch (_: Exception) {
                null
            }
            val parsed = remoteUrls.mapNotNull { url -> parseRemote(url)?.let { url to it } }
            val preferred = parsed.firstOrNull { (url, _) -> bitbucketHost != null && url.lowercase().contains(bitbucketHost) }
            return (preferred ?: parsed.firstOrNull())?.second
        }

        private fun parseRemote(url: String): Pair<String, String>? {
            val raw = url.trim().removeSuffix("/").removeSuffix(".git")
            val path = when {
                raw.contains("://") -> try {
                    URI(raw).path.orEmpty()
                } catch (_: Exception) {
                    return null
                }
                raw.contains(':') -> raw.substringAfter(':') // scp biçimi: git@host:proj/repo
                else -> return null
            }
            val segments = path.split('/').filter { it.isNotEmpty() }
            if (segments.size < 2) return null
            val project = segments[segments.size - 2]
            val slug = segments.last()
            if (project.startsWith("~") || project.equals("users", ignoreCase = true)) return null
            return project.uppercase() to slug.lowercase()
        }
    }
}
