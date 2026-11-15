package com.relay.agents

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed class ApiResult {
    data class Ok(val text: String) : ApiResult()

    /** fatal: مفتاح/نموذج خاطئ → عطّل الوكيل. غير ذلك مؤقت (حد معدّل/ازدحام/شبكة) → جرّب وكيلاً آخر. */
    data class Fail(
        val code: Int,
        val message: String,
        val fatal: Boolean,
        /** قيمة Retry-After من الخادم (مللي ثانية) إن وُجدت. */
        val retryAfterMs: Long? = null
    ) : ApiResult()
}

/** عميل موحّد لواجهات API الرسمية: Claude وGemini وGroq وOpenRouter وغيرها. الصور تُرسل لـ Claude وGemini فقط. */
object ClaudeClient {

    suspend fun send(
        agent: AgentConfig,
        system: String,
        user: String,
        maxTokens: Int = 2048,
        images: List<ImageData> = emptyList()
    ): ApiResult = withContext(Dispatchers.IO) {
        try {
            val (url, headers, body) = build(agent, system, user, maxTokens, images)
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 180_000
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
                val retryAfter = conn.getHeaderField("retry-after")?.trim()?.toDoubleOrNull()
                    ?.let { (it * 1000).toLong() }
                val msg = errorMessage(raw)
                // 400 بسبب اسم النموذج = إعداد خاطئ لن يصلحه الانتظار → عطّل الوكيل بدل تكرار المحاولة.
                val badModel = code == 400 && msg.contains("model", ignoreCase = true)
                ApiResult.Fail(code, msg, fatal = code == 401 || code == 403 || badModel, retryAfterMs = retryAfter)
            }
        } catch (e: Exception) {
            ApiResult.Fail(0, e.message ?: e.javaClass.simpleName, fatal = false)
        }
    }

    /** اسم النموذج بصيغة صالحة: بلا مسافات وبلا بادئة models/ (سبب خطأ 400 في Gemini). */
    fun modelId(a: AgentConfig): String = a.model.trim().removePrefix("models/")

    /** يجلب النماذج المتاحة فعلاً لهذا المفتاح، فلا نعتمد على أسماء ثابتة قد تُسحب. */
    suspend fun listModels(provider: Provider, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (url, headers) = when (provider) {
                    Provider.CLAUDE -> "https://api.anthropic.com/v1/models?limit=100" to
                        mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
                    Provider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200" to
                        mapOf("x-goog-api-key" to apiKey)
                    Provider.WEB -> error("لا ينطبق على وكيل الويب")
                    else -> provider.endpoint.replace("/chat/completions", "/models") to
                        mapOf("Authorization" to "Bearer $apiKey")
                }
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) error("$code: ${errorMessage(raw)}")
                val j = JSONObject(raw)
                val names: List<String> = if (provider == Provider.GEMINI) {
                    val arr = j.optJSONArray("models") ?: JSONArray()
                    (0 until arr.length()).map { i -> arr.getJSONObject(i) }
                        .filter { m ->
                            val ms = m.optJSONArray("supportedGenerationMethods")
                            ms == null || (0 until ms.length()).any { k -> ms.getString(k) == "generateContent" }
                        }
                        .map { m -> m.getString("name").removePrefix("models/") }
                } else {
                    val arr = j.optJSONArray("data") ?: JSONArray()
                    (0 until arr.length()).map { i -> arr.getJSONObject(i).getString("id") }
                }
                val filtered = if (provider == Provider.OPENROUTER) names.filter { n -> n.endsWith(":free") } else names
                filtered.sorted()
            }
        }

    private data class Req(val url: String, val headers: Map<String, String>, val body: JSONObject)

    private fun build(a: AgentConfig, system: String, user: String, maxTokens: Int, images: List<ImageData>): Req =
        when (a.provider) {
            Provider.CLAUDE -> {
                val content: Any = if (images.isEmpty()) user else JSONArray().apply {
                    images.forEach { img ->
                        put(
                            JSONObject().put("type", "image").put(
                                "source",
                                JSONObject().put("type", "base64").put("media_type", img.mime).put("data", img.b64)
                            )
                        )
                    }
                    put(JSONObject().put("type", "text").put("text", user))
                }
                Req(
                    "https://api.anthropic.com/v1/messages",
                    mapOf("x-api-key" to a.apiKey, "anthropic-version" to "2023-06-01"),
                    JSONObject().put("model", modelId(a)).put("max_tokens", maxTokens).put("system", system)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                )
            }
            Provider.GEMINI -> {
                val parts = JSONArray().apply {
                    images.forEach { img ->
                        put(JSONObject().put("inlineData", JSONObject().put("mimeType", img.mime).put("data", img.b64)))
                    }
                    put(JSONObject().put("text", user))
                }
                Req(
                    "https://generativelanguage.googleapis.com/v1beta/models/${modelId(a)}:generateContent",
                    mapOf("x-goog-api-key" to a.apiKey),
                    JSONObject()
                        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                        // نماذج Gemini "المفكّرة" تستهلك جزءاً من الحد في التفكير، فنرفع السقف كي لا يُقطع الرد.
                        .put("generationConfig", JSONObject().put("maxOutputTokens", maxOf(maxTokens * 3, 4096)))
                )
            }
            Provider.WEB -> error("وكيل الويب يدوي ولا يُستدعى عبر API")
            Provider.GROQ, Provider.OPENROUTER, Provider.MISTRAL, Provider.CEREBRAS, Provider.HUGGINGFACE -> Req(
                a.provider.endpoint,
                mapOf("Authorization" to "Bearer ${a.apiKey}"),
                JSONObject().put("model", modelId(a)).put("max_tokens", maxTokens).put(
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
