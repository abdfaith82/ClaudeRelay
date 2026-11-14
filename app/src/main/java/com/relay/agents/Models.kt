package com.relay.agents

import org.json.JSONArray
import org.json.JSONObject

/** وكيل = اسم + مفتاح API + نموذج. */
data class AgentConfig(
    val id: String,
    val name: String,
    val apiKey: String,
    val model: String = "claude-sonnet-4-5",
    val enabled: Boolean = true
) {
    fun toJson() = JSONObject()
        .put("id", id).put("name", name).put("apiKey", apiKey)
        .put("model", model).put("enabled", enabled)

    companion object {
        fun fromJson(o: JSONObject) = AgentConfig(
            id = o.getString("id"),
            name = o.getString("name"),
            apiKey = o.getString("apiKey"),
            model = o.optString("model", "claude-sonnet-4-5"),
            enabled = o.optBoolean("enabled", true)
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
