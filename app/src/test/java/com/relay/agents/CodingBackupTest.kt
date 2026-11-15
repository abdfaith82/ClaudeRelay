package com.relay.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class CodingBackupTest {
    @Test
    fun extractsFilesAndLaterVersionWins() {
        val a = "شرح\n```kotlin file:app/Main.kt\nfun a() {}\n```\nنص\n```file:README.md\n# hi\n```"
        val b = "```kotlin file:app/Main.kt\nfun a() { println(1) }\n```"
        val files = CodeExtractor.extract(listOf(a, b))
        assertEquals(setOf("app/Main.kt", "README.md"), files.keys)
        assertEquals("fun a() { println(1) }\n", files["app/Main.kt"])
    }

    @Test
    fun rejectsPathTraversal() {
        assertNull(CodeExtractor.sanitize("../../etc/passwd"))
        assertNull(CodeExtractor.sanitize("a/../b"))
        assertEquals("a/b.txt", CodeExtractor.sanitize("/a//b.txt"))
        val files = CodeExtractor.extract(listOf("```file:../x.kt\nbad\n```"))
        assertTrue(files.isEmpty())
    }

    @Test
    fun zipContainsAllFiles() {
        val out = ByteArrayOutputStream()
        CodeExtractor.writeZip(mapOf("a/b.kt" to "x\n", "c.txt" to "y\n"), out)
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) { names += (z.nextEntry ?: break).name }
        }
        assertEquals(listOf("a/b.kt", "c.txt"), names)
    }

    @Test
    fun contextListsPathsAndCapsSize() {
        val ctx = CodeExtractor.contextFor(mapOf("big.kt" to "z".repeat(50_000)), budget = 1_000)
        assertTrue(ctx.contains("big.kt"))
        assertTrue(ctx.length < 1_500)
    }

    @Test
    fun backupRoundTripAndWrongPassword() {
        val text = AgentBackup.toJson(listOf(AgentConfig("1", "G", "secret-key", "m", true, Provider.GEMINI)))
        val enc = BackupCrypto.encrypt(text, "pass1234")
        assertTrue(!enc.contains("secret-key"))
        assertEquals(text, BackupCrypto.decrypt(enc, "pass1234"))
        try {
            BackupCrypto.decrypt(enc, "wrong-pass")
            fail("expected failure")
        } catch (e: Exception) {
            // مطلوب: كلمة مرور خاطئة ترفض فك التشفير
        }
    }

    @Test
    fun mergeSkipsDuplicates() {
        val a = AgentConfig("1", "G1", "k1", "m", true, Provider.GEMINI)
        val dup = AgentConfig("2", "G1 copy", "k1", "m", true, Provider.GEMINI)
        val fresh = AgentConfig("3", "Groq", "k2", "m", true, Provider.GROQ)
        val (merged, added) = AgentBackup.merge(listOf(a), listOf(dup, fresh))
        assertEquals(1, added)
        assertEquals(2, merged.size)
    }
}
