package com.ykb.review.bitbucket

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteMatcherTest {

    @Test
    fun `https ve ssh remote adresleri eslesir`() {
        assertTrue(RemoteMatcher.matches("https://sdlc.yapikredi.com.tr/bitbucket/scm/proj/my-repo.git", "PROJ", "my-repo"))
        assertTrue(RemoteMatcher.matches("ssh://git@sdlc.yapikredi.com.tr:7999/proj/my-repo.git", "PROJ", "my-repo"))
        assertTrue(RemoteMatcher.matches("git@host:PROJ/My-Repo.git", "proj", "my-repo"))
    }

    @Test
    fun `farkli repo eslesmez`() {
        assertFalse(RemoteMatcher.matches("https://host/scm/proj/my-repo-2.git", "PROJ", "my-repo"))
        assertFalse(RemoteMatcher.matches("https://host/scm/other/my-repo.git", "PROJ", "my-repo"))
    }
}
