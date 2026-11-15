package com.relay.agents

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** حارس: كل نص يمرّ عبر tr(...) / trf(...) في الكود يجب أن تكون له ترجمة إنجليزية في القاموس. */
class I18nCoverageTest {
    private val dir = File("src/main/java/com/relay/agents")
    private val call = Regex("""\btrf?\(\s*"((?:[^"\\\n]|\\.)*)"""")

    @Test
    fun everyTrLiteralHasEnglish() {
        val missing = mutableListOf<String>()
        dir.listFiles { f -> f.extension == "kt" && f.name != "I18n.kt" }!!.forEach { f ->
            call.findAll(f.readText()).forEach { m ->
                val key = m.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n")
                if (Translations.EN[key] == null) missing += "${f.name}: $key"
            }
        }
        assertTrue("Missing English translations:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun templatesKeepPlaceholderCount() {
        Translations.EN.forEach { (ar, en) ->
            val a = Regex("%s").findAll(ar).count()
            val e = Regex("%s").findAll(en).count()
            assertTrue("Placeholder mismatch: $ar", a == e)
        }
    }
}
