package com.ykb.review.local

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalReviewScopeTest {

    @Test
    fun `selected class is limited to its nearest repository and line range`() {
        val workspace = Paths.get("workspace").toAbsolutePath().normalize()
        val nestedRepo = workspace.resolve("service")
        val file = nestedRepo.resolve("src/main/kotlin/AccountService.kt")

        val scopes = LocalReviewScopeResolver.resolve(
            listOf(workspace.toString(), nestedRepo.toString()),
            LocalReviewTarget(listOf(file.toString()), 12, 48),
        )

        assertEquals(1, scopes.size)
        assertEquals(nestedRepo.toString(), scopes.single().root)
        assertEquals(listOf("src/main/kotlin/AccountService.kt"), scopes.single().relativePaths)
        assertTrue(scopes.single().extraInstructions.orEmpty().contains("12-48. satırlardır"))
    }

    @Test
    fun `selected repository root reviews the whole repository`() {
        val root = Paths.get("workspace", "service").toAbsolutePath().normalize()

        val scopes = LocalReviewScopeResolver.resolve(
            listOf(root.toString()),
            LocalReviewTarget(listOf(root.toString())),
        )

        assertEquals(1, scopes.size)
        assertTrue(scopes.single().relativePaths.isEmpty())
    }

    @Test
    fun `selection outside known repositories is rejected`() {
        val root = Paths.get("workspace", "service").toAbsolutePath().normalize()
        val outside = Paths.get("other", "Unrelated.kt").toAbsolutePath().normalize()

        val scopes = LocalReviewScopeResolver.resolve(
            listOf(root.toString()),
            LocalReviewTarget(listOf(outside.toString())),
        )

        assertTrue(scopes.isEmpty())
    }
}
