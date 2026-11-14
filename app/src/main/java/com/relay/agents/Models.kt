package com.relay.agents

import org.json.JSONArray
import org.json.JSONObject

/** مزوّد الذكاء الاصطناعي: Claude بمفتاح مدفوع، والبقية لها طبقات مجانية بمفتاح من موقع المزوّد. */
enum class Provider(val label: String, val defaultModel: String, val keyUrl: String, val free: Boolean) {
    CLAUDE("Claude", "claude-sonnet-4-5", "console.anthropic.com", false),
    GEMINI("Gemini (مجاني)", "gemini-2.5-flash", "aistudio.google.com/apikey", true),
    GROQ("Groq (مجاني)", "llama-3.3-70b-versatile", "console.groq.com/keys", true),
    OPENROUTER("OpenRouter (مجاني)", "meta-llama/llama-3.3-70b-instruct:free", "openrouter.ai/keys", true)
}

/** وكيل = اسم + مزوّد + مفتاح API + نموذج. */
data class AgentConfig(
    val id: String,
    val name: String,
    val apiKey: String,
    val model: String = Provider.CLAUDE.defaultModel,
    val enabled: Boolean = true,
    val provider: Provider = Provider.CLAUDE
) {
    fun toJson() = JSONObject()
        .put("id", id).put("name", name).put("apiKey", apiKey)
        .put("model", model).put("enabled", enabled).put("provider", provider.name)

    companion object {
        fun fromJson(o: JSONObject) = AgentConfig(
            id = o.getString("id"),
            name = o.getString("name"),
            apiKey = o.getString("apiKey"),
            model = o.optString("model", Provider.CLAUDE.defaultModel),
            enabled = o.optBoolean("enabled", true),
            provider = runCatching { Provider.valueOf(o.optString("provider", "CLAUDE")) }
                .getOrDefault(Provider.CLAUDE)
        )
    }
}

enum class StepStatus { PENDING, DONE }

data class Step(
    val title: String,
    val status: StepStatus = StepStatus.PENDING,
    val result: String = "",
    val doneBy: String = ""
) {
    fun toJson() = JSONObject()
        .put("title", title).put("status", status.name)
        .put("result", result).put("doneBy", doneBy)

    companion object {
        fun fromJson(o: JSONObject) = Step(
            o.getString("title"),
            StepStatus.valueOf(o.optString("status", "PENDING")),
            o.optString("result"),
            o.optString("doneBy")
        )
    }
}

/** نقطة الاستئناف: تُحفظ بعد كل خطوة. */
data class Checkpoint(
    val goal: String,
    val steps: List<Step> = emptyList(),
    val finalAnswer: String = "",
    val log: List<String> = emptyList()
) {
    val nextIndex: Int get() = steps.indexOfFirst { it.status == StepStatus.PENDING }
    val finished: Boolean get() = steps.isNotEmpty() && nextIndex == -1

    fun toJson() = JSONObject()
        .put("goal", goal)
        .put("steps", JSONArray(steps.map { it.toJson() }))
        .put("finalAnswer", finalAnswer)
        .put("log", JSONArray(log.takeLast(200)))

    companion object {
        fun fromJson(o: JSONObject): Checkpoint {
            val sa = o.optJSONArray("steps") ?: JSONArray()
            val la = o.optJSONArray("log") ?: JSONArray()
            return Checkpoint(
                goal = o.getString("goal"),
                steps = (0 until sa.length()).map { Step.fromJson(sa.getJSONObject(it)) },
                finalAnswer = o.optString("finalAnswer"),
                log = (0 until la.length()).map { la.getString(it) }
            )
        }
    }
}
