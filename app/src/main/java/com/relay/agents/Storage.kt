package com.relay.agents

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** تخزين الوكلاء (SharedPreferences خاص بالتطبيق) ونقطة الاستئناف (ملف JSON). */
class Storage(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("relay", Context.MODE_PRIVATE)
    private val cpFile get() = File(ctx.filesDir, "checkpoint.json")

    fun loadAgents(): List<AgentConfig> = runCatching {
        val arr = JSONArray(prefs.getString("agents", "[]"))
        (0 until arr.length()).map { AgentConfig.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun saveAgents(list: List<AgentConfig>) {
        prefs.edit().putString("agents", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun loadCheckpoint(): Checkpoint? = runCatching {
        if (!cpFile.exists()) null else Checkpoint.fromJson(JSONObject(cpFile.readText()))
    }.getOrNull()

    fun saveCheckpoint(cp: Checkpoint) {
        val tmp = File(ctx.filesDir, "checkpoint.json.tmp")
        tmp.writeText(cp.toJson().toString())
        tmp.renameTo(cpFile)
    }

    fun currentProjectId(): String? = prefs.getString("currentProject", null)

    fun setCurrentProjectId(id: String?) {
        prefs.edit().putString("currentProject", id).apply()
    }

    fun clearCheckpoint() {
        cpFile.delete()
    }
}
