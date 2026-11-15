package com.relay.agents

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubDiffTest {
    @Test fun blobShaMatchesGit() {
        // git hash-object لملف يحتوي "hello\n"
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitHubClient.blobSha("hello\n".toByteArray()))
    }

    @Test fun diffClassifies() {
        val remote = mapOf("a.txt" to GitHubClient.blobSha("1".toByteArray()), "old.txt" to "x")
        val d = GitHubClient.diff(mapOf("a.txt" to "1", "b.txt" to "2", "c.txt" to "3"), remote)
        assertEquals(listOf("b.txt", "c.txt"), d.added)
        assertEquals(listOf("a.txt"), d.same)
        assertEquals(emptyList<String>(), d.modified)
        assertEquals(1, d.remoteOnly)
        val m = GitHubClient.diff(mapOf("a.txt" to "changed"), remote)
        assertEquals(listOf("a.txt"), m.modified)
    }
}
