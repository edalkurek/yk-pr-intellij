package com.ykb.review.ai

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiffFilterTest {

    @Test
    fun `lock ve binary dosyalari filtrelenmeli`() {
        assertTrue(DiffFilter.shouldIgnoreFile("package-lock.json"))
        assertTrue(DiffFilter.shouldIgnoreFile("yarn.lock"))
        assertTrue(DiffFilter.shouldIgnoreFile("build/classes/App.class"))
        assertTrue(DiffFilter.shouldIgnoreFile("assets/logo.png"))
        assertFalse(DiffFilter.shouldIgnoreFile("src/main/AuthService.kt"))
    }

    @Test
    fun `token ve secret bilgileri maskelenmeli`() {
        val sensitiveDiff = "+ val token = \"Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.dummysecret1234567890\""
        val masked = DiffFilter.maskSecrets(sensitiveDiff)

        assertFalse(masked.contains("dummysecret1234567890"))
        assertTrue(masked.contains("***MASKED_SECRET***"))
    }
}
