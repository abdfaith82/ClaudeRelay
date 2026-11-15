package com.relay.agents

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** وضع البرمجة: استخراج ملفات المشروع من ردود الوكلاء وتصديرها كـ ZIP. */
object CodeExtractor {
    // ```kotlin file:app/src/Main.kt  (أو ```file:path)  ثم الشيفرة ثم ```
    private val BLOCK = Regex("```[^\\n`]*?file:([^\\s`]+)[^\\n]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    /** مسار آمن (بلا .. ولا / بادئة) أو null إن كان مرفوضاً. */
    fun sanitize(path: String): String? {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** يجمع الملفات من الردود بالترتيب؛ النسخة الأحدث للملف نفسه تحل محل القديمة. */
    fun extract(texts: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        texts.forEach { t ->
            BLOCK.findAll(t).forEach { m ->
                val p = sanitize(m.groupValues[1]) ?: return@forEach
                out[p] = m.groupValues[2].trimEnd('\n') + "\n"
            }
        }
        return out
    }

    /** سياق الملفات المنجزة للخطوة التالية (الأحدث أولاً ضمن ميزانية). */
    fun contextFor(files: Map<String, String>, budget: Int = 12_000): String {
        if (files.isEmpty()) return ""
        var left = budget
        return buildString {
            append("الملفات المنجزة حتى الآن (${files.size}): ${files.keys.joinToString(", ")}\n")
            files.entries.reversed().forEach { (path, content) ->
                if (left <= 0) return@forEach
                val chunk = content.take(minOf(left, 4_000))
                left -= chunk.length
                append("\n```file:$path\n$chunk\n```\n")
            }
        }
    }

    fun writeZip(files: Map<String, String>, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            files.forEach { (path, content) ->
                z.putNextEntry(ZipEntry(path))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
    }
}
