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
        (0 until arr.length()).map {
            AgentConfig.fromJson(arr.getJSONObject(it)).let { a -> a.copy(apiKey = SecretStore.decrypt(a.apiKey)) }
        }
    }.getOrDefault(emptyList()).also { list ->
        // ترحيل المفاتيح القديمة غير المشفّرة إلى التشفير مرة واحدة.
        if (list.isNotEmpty() && prefs.getString("agents", "")?.contains("\"apiKey\":\"enc1:") != true &&
            list.any { it.apiKey.isNotEmpty() }
        ) saveAgents(list)
    }

    fun saveAgents(list: List<AgentConfig>) {
        val arr = JSONArray(list.map { it.copy(apiKey = SecretStore.encrypt(it.apiKey)).toJson() })
        prefs.edit().putString("agents", arr.toString()).apply()
    }

    // ---------- إعدادات التطبيق (اللغة والمظهر) ----------
    fun setting(name: String, default: String): String = prefs.getString("set_$name", default) ?: default

    fun saveSetting(name: String, value: String) {
        prefs.edit().putString("set_$name", value).apply()
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
