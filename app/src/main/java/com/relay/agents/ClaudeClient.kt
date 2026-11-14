package com.relay.agents

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed class ApiResult {
    data class Ok(val text: String) : ApiResult()

    /** fatal: مفتاح خاطئ → عطّل الوكيل. غير ذلك مؤقت (حد معدّل/ازدحام/شبكة) → جرّب وكيلاً آخر. */
    data class Fail(val code: Int, val message: String, val fatal: Boolean) : ApiResult()
}

/** عميل موحّد لواجهات API الرسمية: Claude وGemini وGroq وOpenRouter. */
object ClaudeClient {

    suspend fun send(agent: AgentConfig, system: String, user: String, maxTokens: Int = 2048): ApiResult =
        withContext(Dispatchers.IO) {
            try {
                val (url, headers, body) = build(agent, system, user, maxTokens)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    doOutput = true
                    setRequestProperty("content-type", "application/json")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    val text = parse(agent.provider, JSONObject(raw))
                    if (text.isBlank()) ApiResult.Fail(0, "رد فارغ", fatal = false) else ApiResult.Ok(text)
                } else {
                    ApiResult.Fail(code, errorMessage(raw), fatal = code == 401 || code == 403)
                }
            } catch (e: Exception) {
                ApiResult.Fail(0, e.message ?: e.javaClass.simpleName, fatal = false)
            }
        }

    private data class Req(val url: String, val headers: Map<String, String>, val body: JSONObject)

    private fun build(a: AgentConfig, system: String, user: String, maxTokens: Int): Req = when (a.provider) {
        Provider.CLAUDE -> Req(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to a.apiKey, "anthropic-version" to "2023-06-01"),
            JSONObject().put("model", a.model).put("max_tokens", maxTokens).put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        )
        Provider.GEMINI -> Req(
            "https://generativelanguage.googleapis.com/v1beta/models/${a.model}:generateContent",
            mapOf("x-goog-api-key" to a.apiKey),
            JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", user)))
                    )
                )
                .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
        )
        Provider.WEB -> error("وكيل الويب يدوي ولا يُستدعى عبر API")
        Provider.GROQ, Provider.OPENROUTER, Provider.MISTRAL, Provider.CEREBRAS, Provider.HUGGINGFACE -> Req(
            a.provider.endpoint,
            mapOf("Authorization" to "Bearer ${a.apiKey}"),
            JSONObject().put("model", a.model).put("max_tokens", maxTokens).put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        )
    }

    private fun parse(p: Provider, j: JSONObject): String = when (p) {
        Provider.CLAUDE -> {
            val c = j.getJSONArray("content")
            buildString {
                for (i in 0 until c.length()) {
                    val b = c.getJSONObject(i)
                    if (b.optString("type") == "text") append(b.optString("text"))
                }
            }
        }
        Provider.GEMINI -> {
            val parts = j.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }
        }
        Provider.WEB -> ""
        Provider.GROQ, Provider.OPENROUTER, Provider.MISTRAL, Provider.CEREBRAS, Provider.HUGGINGFACE ->
            j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }

    private fun errorMessage(raw: String): String = runCatching {
        val o = JSONObject(raw)
        val e = o.opt("error")
        if (e is JSONObject) e.optString("message") else o.optString("message", raw.take(200))
    }.getOrDefault(raw.take(200))
}
