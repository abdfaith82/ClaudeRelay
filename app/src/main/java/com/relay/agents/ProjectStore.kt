package com.relay.agents

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** حفظ المشاريع: ملف JSON لكل مشروع داخل filesDir/projects. */
class ProjectStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "projects").apply { mkdirs() }

    private fun file(id: String) = File(dir, "${id.filter { it.isLetterOrDigit() || it == '-' }}.json")

    fun save(p: Project) {
        val tmp = File(dir, "${p.id}.tmp")
        tmp.writeText(p.toJson().toString())
        tmp.renameTo(file(p.id))
    }

    fun load(id: String): Project? = runCatching { Project.fromJson(JSONObject(file(id).readText())) }.getOrNull()

    fun list(): List<Project> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f -> runCatching { Project.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    fun delete(id: String) {
        file(id).delete()
    }
}

/** ذاكرة الوكلاء: ملخص المشاريع السابقة يُحقن في تعليمات كل وكيل ليتعرف عليها ويبني فوقها. */
object MemoryBuilder {
    fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()

    fun build(projects: List<Project>, goal: String, maxList: Int = 10): String {
        if (projects.isEmpty()) return ""
        val sorted = projects.sortedByDescending { it.updatedAt }
        val goalTokens = tokens(goal)
        val relevant = sorted
            .map { p -> p to tokens(p.name + " " + p.checkpoint.goal).intersect(goalTokens).size }
            .filter { (p, score) -> score > 0 && p.completed }
            .sortedByDescending { it.second }
            .take(2).map { it.first }

        return buildString {
            append("المشاريع السابقة المحفوظة (${projects.size}):\n")
            sorted.take(maxList).forEach { p ->
                val steps = p.checkpoint.steps
                val done = steps.count { it.status == StepStatus.DONE }
                val status = if (p.completed) "مكتمل" else "غير مكتمل $done/${steps.size}"
                append("- ${p.name} [$status]: ${p.checkpoint.goal.take(160)}\n")
            }
            if (relevant.isNotEmpty()) {
                append("\nمقتطفات من مشاريع مشابهة:\n")
                relevant.forEach { append("### ${it.name}\n${it.checkpoint.finalAnswer.take(900)}\n") }
            }
        }.take(4000)
    }
}

/** تصدير المشاريع. */
object ProjectExport {
    private fun date(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    fun toMarkdown(p: Project): String = buildString {
        val c = p.checkpoint
        append("# ${p.name}\n\n")
        append("- أُنشئ: ${date(p.createdAt)}\n- آخر تحديث: ${date(p.updatedAt)}\n")
        append("- الحالة: ${if (p.completed) "مكتمل" else "غير مكتمل"}\n\n")
        append("## الهدف\n\n${c.goal}\n\n")
        if (c.steps.isNotEmpty()) {
            append("## الخطوات\n\n")
            c.steps.forEachIndexed { i, s ->
                append("### ${i + 1}. ${s.title}")
                if (s.doneBy.isNotEmpty()) append(" (بواسطة ${s.doneBy})")
                append("\n\n")
                append(if (s.status == StepStatus.DONE) s.result else "_لم تُنفَّذ بعد_")
                append("\n\n")
            }
        }
        if (c.finalAnswer.isNotEmpty()) append("## النتيجة النهائية\n\n${c.finalAnswer}\n")
    }

    fun allToJson(list: List<Project>): String =
        JSONObject().put("exportedAt", System.currentTimeMillis())
            .put("projects", JSONArray(list.map { it.toJson() })).toString(2)

    fun safeFileName(name: String, ext: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(40).ifEmpty { "project" } + "." + ext
}
