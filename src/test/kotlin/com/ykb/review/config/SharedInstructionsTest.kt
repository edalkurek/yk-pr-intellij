package com.ykb.review.config

import com.ykb.review.bitbucket.PullRequestRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class SharedInstructionsTest {

    private val rulesRepo = SharedRulesRepo("SDLC", "ai-review-rules", "master")

    private class FakeLoader(var files: Map<String, String>) {
        val requested = mutableListOf<String>()
        var fail = false
        fun load(repo: SharedRulesRepo, path: String): String? {
            requested += "${repo.projectKey}/${repo.slug}@${repo.branch}:$path"
            if (fail) throw IOException("ağ hatası")
            return files[path]
        }
    }

    @Test
    fun `genel proje ve repo dosyalari genelden ozele okunur, olmayanlar atlanir`() {
        val loader = FakeLoader(mapOf("general.md" to "G", "repos/PAY/payment-api.md" to "R"))
        val shared = SharedInstructions(loader::load)

        val layers = shared.layers(rulesRepo, "pay", "Payment-API")

        assertEquals(
            listOf(
                "SDLC/ai-review-rules@master:general.md",
                "SDLC/ai-review-rules@master:projects/PAY.md",
                "SDLC/ai-review-rules@master:repos/PAY/payment-api.md",
            ),
            loader.requested,
        )
        assertEquals(listOf("Ortak genel" to "G", "Ortak repo (PAY/payment-api)" to "R"), layers.map { it.source to it.text })
    }

    @Test
    fun `proje bilinmiyorsa yalnizca genel dosya okunur`() {
        val loader = FakeLoader(mapOf("general.md" to "G"))

        val layers = SharedInstructions(loader::load).layers(rulesRepo, null, null)

        assertEquals(listOf("Ortak genel"), layers.map { it.source })
        assertEquals(1, loader.requested.size)
    }

    @Test
    fun `TTL icinde ikinci okuma aga gitmez, sure dolunca yeniden okunur`() {
        var now = 0L
        val loader = FakeLoader(mapOf("general.md" to "G1"))
        val shared = SharedInstructions(loader::load, ttlMs = 1000, now = { now })

        shared.layers(rulesRepo, null, null)
        loader.files = mapOf("general.md" to "G2")
        now = 500
        assertEquals("G1", shared.layers(rulesRepo, null, null).single().text)
        assertEquals(1, loader.requested.size)

        now = 1500
        assertEquals("G2", shared.layers(rulesRepo, null, null).single().text)
    }

    @Test
    fun `okuma hatasinda son basarili icerik kullanilir`() {
        var now = 0L
        val loader = FakeLoader(mapOf("general.md" to "G"))
        val shared = SharedInstructions(loader::load, ttlMs = 1000, now = { now })
        shared.layers(rulesRepo, null, null)

        loader.fail = true
        now = 5000

        assertEquals("G", shared.layers(rulesRepo, null, null).single().text)
    }

    @Test
    fun `hic onbellek yokken okuma hatasi incelemeyi dusurmez`() {
        val loader = FakeLoader(emptyMap()).apply { fail = true }

        assertTrue(SharedInstructions(loader::load).layers(rulesRepo, "PAY", "api").isEmpty())
    }

    @Test
    fun `dosya ve toplam boyut siniri uygulanir`() {
        val big = "x".repeat(SharedInstructions.MAX_FILE_CHARS + 500)
        val loader = FakeLoader(mapOf("general.md" to big, "projects/PAY.md" to big, "repos/PAY/api.md" to big))

        val layers = SharedInstructions(loader::load).layers(rulesRepo, "PAY", "api")

        assertEquals(SharedInstructions.MAX_FILE_CHARS, layers[0].text.length)
        assertEquals(SharedInstructions.MAX_TOTAL_CHARS, layers.sumOf { it.text.length })
        assertEquals(2, layers.size)
    }

    @Test
    fun `repo ayari PROJE-slug bicimini zorunlu kilar`() {
        assertEquals(SharedRulesRepo("SDLC", "rules", "master"), SharedRulesRepo.parse(" SDLC/rules ", ""))
        assertEquals("develop", SharedRulesRepo.parse("SDLC/rules", "develop")?.branch)
        assertNull(SharedRulesRepo.parse("", "master"))
        assertNull(SharedRulesRepo.parse("rules", "master"))
        assertNull(SharedRulesRepo.parse("a/b/c", "master"))
    }

    @Test
    fun `repo ayari Bitbucket repo adresini ve kisisel repoyu kabul eder`() {
        assertEquals(
            SharedRulesRepo("SDLC", "rules", "master"),
            SharedRulesRepo.parse("https://git.test/bitbucket/projects/SDLC/repos/rules/browse", ""),
        )
        // Kişisel repo REST API'de ~kullanıcı projesidir.
        assertEquals(
            SharedRulesRepo("~t0101505", "ai-review-rules", "master"),
            SharedRulesRepo.parse("https://git.test/bitbucket/users/t0101505/repos/ai-review-rules/browse", ""),
        )
        assertNull(SharedRulesRepo.parse("https://git.test/bitbucket/users/t0101505", "master"))
    }

    @Test
    fun `ortak katman yoksa talimat metni eski bicimde kalir`() {
        val settings = ReviewSettingsState().apply {
            extraInstructions = "genel kural"
            repoInstructions = mutableMapOf("host/pay/api" to "repo kuralı")
        }
        val resolver = InstructionResolver(settings, SharedInstructions({ _, _ -> error("çağrılmamalı") }))

        val resolved = resolver.forPullRequest(PullRequestRef("https://host", "PAY", "api", 1))

        assertEquals("genel kural\n\nRepo özel talimatları:\nrepo kuralı", resolved.text)
        assertEquals("IDE genel + IDE repo", resolved.summary)
    }

    @Test
    fun `ortak ve IDE katmanlari genelden ozele basliklarla birlestirilir`() {
        val settings = ReviewSettingsState().apply {
            sharedRulesRepo = "SDLC/ai-review-rules"
            extraInstructions = "ide genel"
            repoInstructions = mutableMapOf("host/pay/api" to "ide repo")
        }
        val loader = FakeLoader(mapOf("general.md" to "ortak genel", "projects/PAY.md" to "ortak proje", "repos/PAY/api.md" to "ortak repo"))
        val resolver = InstructionResolver(settings, SharedInstructions(loader::load))

        val resolved = resolver.forPullRequest(PullRequestRef("https://host", "PAY", "api", 1))

        assertEquals(
            """
            Ortak genel talimatları:
            ortak genel

            Ortak proje (PAY) talimatları:
            ortak proje

            Ortak repo (PAY/api) talimatları:
            ortak repo

            IDE genel talimatları:
            ide genel

            IDE repo talimatları:
            ide repo
            """.trimIndent(),
            resolved.text,
        )
        assertEquals("Ortak genel + Ortak proje (PAY) + Ortak repo (PAY/api) + IDE genel + IDE repo", resolved.summary)
    }

    @Test
    fun `remote URL'lerinden proje ve repo cikarilir`() {
        val base = "https://sdlc.example.com/bitbucket"
        fun id(vararg urls: String) = InstructionResolver.repoIdentityForRemotes(urls.toList(), base)

        assertEquals("PAY" to "payment-api", id("https://sdlc.example.com/bitbucket/scm/pay/payment-api.git"))
        assertEquals("PAY" to "payment-api", id("ssh://git@sdlc.example.com:7999/pay/payment-api.git"))
        assertEquals("PAY" to "payment-api", id("git@sdlc.example.com:pay/payment-api.git"))
        assertEquals(
            "PAY" to "payment-api",
            id("https://github.com/someone/fork.git", "https://sdlc.example.com/bitbucket/scm/pay/payment-api.git"),
        )
        assertNull(id("https://sdlc.example.com/bitbucket/scm/~user/kisisel.git"))
        assertNull(id())
    }
}
