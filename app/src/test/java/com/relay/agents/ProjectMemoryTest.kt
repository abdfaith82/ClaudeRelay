package com.relay.agents

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectMemoryTest {
    private fun project(id: String, goal: String, final: String = "", ts: Long = 1L) = Project(
        id, goal.take(50), ts, ts,
        Checkpoint(
            goal,
            listOf(Step("خطوة أولى", StepStatus.DONE, "نتيجة", "A")),
            final
        )
    )

    @Test
    fun emptyMemoryWhenNoProjects() {
        assertEquals("", MemoryBuilder.build(emptyList(), "أي هدف"))
    }

    @Test
    fun listsProjectsWithStatus() {
        val m = MemoryBuilder.build(
            listOf(project("1", "تصميم مطبخ حديث", "نتيجة نهائية"), project("2", "تطبيق حسابات")),
            "شيء آخر"
        )
        assertTrue(m.contains("[complete]"))
        assertTrue(m.contains("[incomplete 1/1]"))
        assertFalse(m.contains("Excerpts"))
    }

    @Test
    fun includesExcerptOfSimilarCompletedProject() {
        val m = MemoryBuilder.build(
            listOf(project("1", "تصميم مطبخ حديث", "التفاصيل المهمة للمطبخ")),
            "تصميم مطبخ ثاني"
        )
        assertTrue(m.contains("Excerpts"))
        assertTrue(m.contains("التفاصيل المهمة للمطبخ"))
    }

    @Test
    fun projectJsonRoundTrip() {
        val p = project("x", "هدف", "نهاية", 123L)
        val back = Project.fromJson(JSONObject(p.toJson().toString()))
        assertEquals(p, back)
    }

    @Test
    fun markdownContainsSections() {
        val md = ProjectExport.toMarkdown(project("x", "هدف المشروع", "الخلاصة"))
        assertTrue(md.contains("## Goal"))
        assertTrue(md.contains("### 1. خطوة أولى (by A)"))
        assertTrue(md.contains("## Final result"))
    }

    @Test
    fun safeFileNameStripsIllegalChars() {
        assertEquals("a b.md", ProjectExport.safeFileName("a/b", "md"))
        assertEquals("project.md", ProjectExport.safeFileName("   ", "md"))
    }
}
