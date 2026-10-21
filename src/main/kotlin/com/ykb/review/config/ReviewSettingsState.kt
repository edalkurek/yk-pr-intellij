package com.ykb.review.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Transient
import com.ykb.review.bitbucket.PullRequestRef
import java.net.URI

@State(
    name = "com.ykb.review.config.ReviewSettingsState",
    storages = [Storage("YkbAiReviewSettings.xml")]
)
class ReviewSettingsState : PersistentStateComponent<ReviewSettingsState> {

    // Bitbucket Entegrasyon Ayarları (YKB Varsayılanları)
    var bitbucketBaseUrl: String = DEFAULT_BITBUCKET_URL

    /**
     * ESKİ SÜRÜM ALANI: Token eskiden bu XML'de düz metin tutuluyordu.
     * Artık PasswordSafe kullanılır; ilk erişimde [SecretStore] değeri taşır ve bu alanı temizler.
     */
    var bitbucketToken: String = ""

    // AI Servis Ayarları (YKB Bifrost Gateway & Yerel Modeller)
    var aiBaseUrl: String = DEFAULT_MODEL_URL

    /** ESKİ SÜRÜM ALANI: bkz. [bitbucketToken]. */
    var aiApiKey: String = ""

    // Model Katmanları (Tiered Engine)
    var fastModelName: String = DEFAULT_FAST_MODEL
    var complexModelName: String = DEFAULT_COMPLEX_MODEL

    // Otomatik Arka Plan Review
    var automaticReviewEnabled: Boolean = false
    var automaticReviewIntervalMinutes: Int = 5

    // İnceleme Parametreleri & Repo Bazlı Talimatlar
    var minSeverity: String = "low" // low, medium, high
    var maxChunkChars: Int = DEFAULT_MAX_CHUNK_CHARS
    var extraInstructions: String = ""

    /** Anahtar: `host/projectKey/repoSlug` (küçük harf, VS Code eklentisiyle aynı). Eski sürümlerden kalan yalnızca-slug anahtarlar da desteklenir. */
    var repoInstructions: MutableMap<String, String> = mutableMapOf()

    /** Ortak talimat reposu (`PROJECT/slug`, aynı Bitbucket sunucusunda); boşsa yalnızca IDE talimatları kullanılır. */
    var sharedRulesRepo: String = ""
    var sharedRulesBranch: String = "master"

    var maskSecretsInDiff: Boolean = true

    /** Kurumsal ortamda sertifika doğrulamasını tamamen kapatır (VS Code eklentisinin varsayılan davranışı). */
    var disableTlsVerification: Boolean = false

    // Arayüz tercihleri
    /** İnceleme sonucunun işlenme biçimi: "ask" (her seferinde sor) | "ide" (yalnızca IDE) | "publish" (Bitbucket'a yaz). */
    var publicationMode: String = PUBLICATION_ASK

    /** PR incelemesi bitince değişiklikleri diff görüntüleyicide otomatik aç. */
    var openDiffAfterReview: Boolean = true

    /** Editör ve diff'te bulgu satırlarının sonunda kısa açıklama (inlay) göster. */
    var showFindingInlays: Boolean = true

    /** PasswordSafe'e erişmeden UI'da "kayıtlı" bilgisini göstermek için. */
    var bitbucketTokenStored: Boolean = false
    var modelApiKeyStored: Boolean = false

    /** Yayınlanmış review / yorum anahtarları (tekrar yayınlamayı önler). En yeni sonda. */
    var publishedReviewKeys: MutableList<String> = mutableListOf()

    /** ESKİ SÜRÜM ALANI: `project/repo/prId -> commit`. Yalnızca tekrar kontrolü için okunur. */
    var lastReviewedCommits: MutableMap<String, String> = mutableMapOf()

    @Transient
    private val publishLock = Any()

    fun isPublished(key: String): Boolean = synchronized(publishLock) { publishedReviewKeys.contains(key) }

    fun markPublished(key: String) {
        synchronized(publishLock) {
            publishedReviewKeys.remove(key)
            publishedReviewKeys.add(key)
            while (publishedReviewKeys.size > MAX_PUBLISHED_KEYS) publishedReviewKeys.removeAt(0)
        }
    }

    fun legacyReviewedCommit(ref: PullRequestRef): String? =
        lastReviewedCommits["${ref.projectKey}/${ref.repositorySlug}/${ref.pullRequestId}"]

    /** Genel + repo özel talimatları VS Code eklentisiyle aynı biçimde birleştirir. */
    fun effectiveInstructions(repoInstruction: String?): String {
        val global = extraInstructions.trim()
        val repo = repoInstruction?.trim().orEmpty()
        return when {
            repo.isNotEmpty() && global.isNotEmpty() -> "$global\n\nRepo özel talimatları:\n$repo"
            repo.isNotEmpty() -> repo
            else -> global
        }
    }

    fun repoInstructionFor(ref: PullRequestRef): String? {
        val key = repoKey(ref.baseUrl, ref.projectKey, ref.repositorySlug)
        repoInstructions[key]?.takeIf { it.isNotBlank() }?.let { return it }
        // Eski sürüm: yalnızca slug ile kaydedilmiş kurallar
        return repoInstructions.entries.firstOrNull {
            !it.key.contains('/') && it.key.equals(ref.repositorySlug, ignoreCase = true)
        }?.value?.takeIf { it.isNotBlank() }
    }

    /** Yerel depo için git remote URL'lerinden repo talimatı bulur. */
    fun repoInstructionForRemotes(remoteUrls: List<String>): String? {
        val remotes = remoteUrls.map { it.lowercase().removeSuffix("/").removeSuffix(".git") }
        for ((key, value) in repoInstructions) {
            if (value.isBlank()) continue
            val k = key.lowercase()
            val parts = k.split('/')
            val matched = if (parts.size == 3) {
                val (host, project, slug) = parts
                remotes.any { url -> url.contains(k) || (url.contains(host) && url.endsWith("/$project/$slug")) }
            } else {
                remotes.any { url -> url.endsWith("/$k") }
            }
            if (matched) return value
        }
        return null
    }

    /** Token kayıtlı ve URL dolu mu? (Panel boş durumunda "Ayarları Aç" yönlendirmesi için; PasswordSafe'e erişmez.) */
    @get:Transient
    val isBitbucketConfigured: Boolean
        get() = bitbucketBaseUrl.isNotBlank() && (bitbucketTokenStored || bitbucketToken.isNotBlank())

    override fun getState(): ReviewSettingsState = this

    override fun loadState(state: ReviewSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        const val DEFAULT_BITBUCKET_URL = "https://sdlc.yapikredi.com.tr/bitbucket"
        const val DEFAULT_MODEL_URL = "https://bifrost-proxy-server-dsp-ddi.apps.lan-dev-dc1-ocp.yapikredi.com.tr/v1"
        const val DEFAULT_FAST_MODEL = "cyankiwi/Qwen3.6-35B-A3B-AWQ-4bit"
        const val DEFAULT_COMPLEX_MODEL = "openai/gpt-oss-120b"
        const val DEFAULT_MAX_CHUNK_CHARS = 60_000
        const val PUBLICATION_ASK = "ask"
        const val PUBLICATION_IDE = "ide"
        const val PUBLICATION_PUBLISH = "publish"
        val PRESET_MODELS = listOf(DEFAULT_FAST_MODEL, DEFAULT_COMPLEX_MODEL)
        private const val MAX_PUBLISHED_KEYS = 5000

        val instance: ReviewSettingsState
            get() = ApplicationManager.getApplication().getService(ReviewSettingsState::class.java)

        fun repoKey(baseUrl: String, projectKey: String, slug: String): String {
            val host = try {
                URI(baseUrl).authority ?: baseUrl
            } catch (_: Exception) {
                baseUrl
            }
            return "$host/$projectKey/$slug".lowercase()
        }
    }
}
