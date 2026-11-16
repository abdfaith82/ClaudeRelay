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
        val d = GitHubClient.diff(mapOf("a.txt" to "1".toByteArray(), "b.txt" to "2".toByteArray(), "c.txt" to "3".toByteArray()), remote)
        assertEquals(listOf("b.txt", "c.txt"), d.added)
        assertEquals(listOf("a.txt"), d.same)
        assertEquals(emptyList<String>(), d.modified)
        assertEquals(1, d.remoteOnly)
        val m = GitHubClient.diff(mapOf("a.txt" to "changed".toByteArray()), remote)
        assertEquals(listOf("a.txt"), m.modified)
    }

    @Test fun effectiveFiltersHiddenAndJunk() {
        val b = ByteArray(1)
        val staged = mapOf(
            "a.txt" to b, ".gitignore" to b, ".github/workflows/x.yml" to b,
            "app/build/o.class" to b, ".git/config" to b, "src/m.kt" to b
        )
        val all = LocalFiles.effective(staged, "", true, true)
        assertEquals(setOf("a.txt", ".gitignore", ".github/workflows/x.yml", "src/m.kt"), all.keys)
        val vis = LocalFiles.effective(staged, "proj/", false, true)
        assertEquals(setOf("proj/a.txt", "proj/src/m.kt"), vis.keys)
        val withJunk = LocalFiles.effective(staged, "", true, false)
        assert("app/build/o.class" in withJunk.keys && ".git/config" !in withJunk.keys)
    }

    @Test fun cleanPathRejectsTraversal() {
        assertEquals("a/b", LocalFiles.cleanPath("/a//./b/"))
        assertEquals(null, LocalFiles.cleanPath("../x"))
        assertEquals(null, LocalFiles.cleanPath(""))
    }

    @Test fun secretScanFindsTokensButNotPlainText() {
        // رموز تجريبية تُبنى وقت التشغيل كي لا تُطابق فاحص الأسرار في المستودع.
        val tok = "gh" + "p_" + "a".repeat(36)
        val files = mapOf(
            "x.kt" to ("val t = \"" + tok + "\"").toByteArray(),
            "ok.kt" to "val a = 1".toByteArray(),
            "bin.dat" to byteArrayOf(0, 1, 2)
        )
        val found = SecretScan.find(files)
        assertEquals(setOf("x.kt"), found.keys)
    }
}
