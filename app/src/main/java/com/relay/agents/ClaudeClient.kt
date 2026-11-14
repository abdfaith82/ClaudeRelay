package com.relay.agents

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed class ApiResult {
    data class Ok(val text: String) : ApiResult()

    /** retryable: خطأ مؤقت (حد معدّل/ازدحام/شبكة) → جرّب وكيلاً آخر. fatal: مفتاح خاطئ → عطّل الوكيل. */
    data class Fail(val code: Int, val message: String, val fatal: Boolean) : ApiResult()
}

/** عميل Claude Messages API الرسمي. كل وكيل يستخدم مفتاحه. */
object ClaudeClient {
    private const val URL_STR = "https://api.anthropic.com/v1/messages"

    suspend fun send(agent: AgentConfig, system: String, user: String, maxTokens: Int = 2048): ApiResult =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("model", agent.model)
                    .put("max_tokens", maxTokens)
                    .put("system", system)
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", user))
                    )
                val conn = (URL(URL_STR).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    doOutput = true
                    setRequestProperty("content-type", "application/json")
                    setRequestProperty("x-api-key", agent.apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    val content = JSONObject(raw).getJSONArray("content")
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val b = content.getJSONObject(i)
                        if (b.optString("type") == "text") sb.append(b.optString("text"))
                    }
                    ApiResult.Ok(sb.toString())
                } else {
                    val msg = runCatching {
                        JSONObject(raw).getJSONObject("error").getString("message")
                    }.getOrDefault(raw.take(200))
                    ApiResult.Fail(code, msg, fatal = code == 401 || code == 403)
                }
            } catch (e: Exception) {
                ApiResult.Fail(0, e.message ?: e.javaClass.simpleName, fatal = false)
            }
        }
}
