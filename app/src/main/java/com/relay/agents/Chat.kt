package com.relay.agents

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** رسالة محادثة. extras = مخرجات الوكلاء الفردية (مقارنة) أو المسودة والنقد (خط أنابيب). */
data class ChatMsg(
    val role: String, // "user" | "assistant"
    val text: String,
    val by: String = "",
    val extras: List<Pair<String, String>> = emptyList(),
    val ts: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject().put("role", role).put("text", text).put("by", by).put("ts", ts)
        .put("extras", JSONArray(extras.map { JSONObject().put("n", it.first).put("t", it.second) }))

    companion object {
        fun fromJson(o: JSONObject): ChatMsg {
            val ex = o.optJSONArray("extras") ?: JSONArray()
            return ChatMsg(
                o.getString("role"), o.getString("text"), o.optString("by"),
                (0 until ex.length()).map { ex.getJSONObject(it).let { e -> e.optString("n") to e.optString("t") } },
                o.optLong("ts")
            )
        }
    }
}

/** أسلوب التنسيق بين النماذج في المحادثة. */
enum class ChatMode { AUTO, COMPARE, PIPELINE }

class ChatStore(private val ctx: Context) {
    private val file get() = File(ctx.filesDir, "chat.json")

    fun load(): List<ChatMsg> = runCatching {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { ChatMsg.fromJson(arr.getJSONObject(it)) }
        }
    }.getOrDefault(emptyList())

    fun save(list: List<ChatMsg>) {
        runCatching {
            val tmp = File(ctx.filesDir, "chat.json.tmp")
            tmp.writeText(JSONArray(list.takeLast(100).map { it.toJson() }).toString())
            tmp.renameTo(file)
        }
    }
}

/**
 * محرك المحادثة: يوزّع كل رد على الوكلاء بنفس موجّه التبديل التلقائي (راحة/أخطاء/مفاتيح مرفوضة)،
 * ويدعم ثلاثة أساليب تنسيق يختارها المستخدم، مع تعليمات تنسيق حرة تُحقن في كل طلب.
 * الوكيل اليدوي (الويب) مستبعد لأنه يحتاج تدخلاً بشرياً في كل رد.
 */
class ChatEngine {
    companion object {
        const val CHAT_SYSTEM =
            "You are the built-in assistant of the Claude Relay Android app. The app connects several AI agents " +
                "(each with its own API key) and automatically routes every reply to an available agent, failing over when one stops. " +
                "You can chat, explain, plan and write code. If the user wants a full multi-step project with checkpoints, " +
                "tell them to type '/task <goal>' (or '/task' followed by the goal) in this chat. " +
                "Follow the user's coordination instructions when given. Be concise unless asked for detail. " +
                "Always reply in the language of the user's last message."
        const val SYNTH_SYSTEM =
            "Several agents answered the same question. Merge their answers into one best answer: keep what they agree on, " +
                "resolve conflicts by reasoning (say briefly if unsure), drop errors and repetition. " +
                "Do not mention the agents. Reply in the language of the question."
        const val CRITIC_SYSTEM =
            "You are a strict reviewer. Check the draft answer for errors, unverified claims, missing points and risks. " +
                "List numbered, concise problems each with a fix. Reply in the language of the question."
        const val REFINE_SYSTEM =
            "Improve the draft answer using the critique. Reply with the final answer only, without mentioning the draft or critique. " +
                "Reply in the language of the question."
    }

    private val router = AgentRouter()
    private var sig = ""
    private var lastError = ""

    /** false = لا تبديل بين الوكلاء: تتوقف المحادثة عند فشل الوكيل الأول. */
    var failover = true

    private class Ans(val text: String, val id: String, val name: String)

    private fun sync(agents: List<AgentConfig>) {
        val s = agents.joinToString("|") { "${it.id}:${it.model}:${it.enabled}:${it.apiKey.hashCode()}" }
        if (s != sig) { sig = s; router.load(agents) }
    }

    private suspend fun ask(
        system: String, user: String, tokens: Int,
        eligible: (AgentConfig) -> Boolean, status: (String) -> Unit
    ): Ans? {
        var waited = 0L
        var tries = 0
        while (tries < 8) {
            if (!failover && tries >= 1) return null
            when (val p = router.pick(eligible)) {
                AgentRouter.Pick.None -> {
                    if (lastError.isEmpty()) lastError = "No usable agent"
                    return null
                }
                is AgentRouter.Pick.Wait -> {
                    if (waited > 45_000) { lastError = "All agents are cooling down"; return null }
                    val d = minOf(p.ms, 5_000L)
                    status(trf("⏳ كل الوكلاء في راحة… %s ثانية", p.ms / 1000))
                    delay(d); waited += d
                }
                is AgentRouter.Pick.Ready -> {
                    tries++
                    val a = p.health.agent
                    router.markStart(a.id)
                    status(trf("⏳ %s يجيب…", a.name))
                    when (val r = ClaudeClient.send(a, system, user, tokens)) {
                        is ApiResult.Ok -> { router.onSuccess(a.id); return Ans(r.text.trim(), a.id, a.name) }
                        is ApiResult.Fail -> {
                            router.onFailure(a.id, r.code, r.retryAfterMs, r.fatal, r.message)
                            lastError = "${a.name}: ${r.code} ${r.message.take(120)}"
                            status(trf("⚠️ %s توقف، ينتقل للتالي", a.name))
                        }
                    }
                }
            }
        }
        return null
    }

    private fun transcript(history: List<ChatMsg>): String = buildString {
        history.takeLast(14).forEach {
            append(if (it.role == "user") "User: " else "Assistant: ")
            append(it.text.take(3000)).append("\n\n")
        }
        append("Reply to the last User message.")
    }

    private fun failMsg(): ChatMsg =
        ChatMsg("assistant", tr("⛔ تعذّر الرد: لا يوجد وكيل متاح.") + if (lastError.isNotEmpty()) "\n$lastError" else "", "system")

    suspend fun reply(
        history: List<ChatMsg>,
        mode: ChatMode,
        agents: List<AgentConfig>,
        instructions: String,
        memory: String,
        include: Set<String>,
        profile: String,
        status: (String) -> Unit
    ): ChatMsg {
        sync(agents)
        lastError = ""
        val eligible = { a: AgentConfig -> a.enabled && !a.provider.manual && (include.isEmpty() || a.id in include) }
        val team = agents.filter(eligible)
        if (team.isEmpty()) return failMsg()

        val system = buildString {
            append(CHAT_SYSTEM)
            append("\n\nAgents currently in the team: ").append(team.joinToString { "${it.name} (${it.provider.label}, ${it.model})" })
            append("\nCoordination mode: ").append(
                when (mode) {
                    ChatMode.AUTO -> "single agent answers, automatic failover"
                    ChatMode.COMPARE -> "several agents answer in parallel and the answers are merged"
                    ChatMode.PIPELINE -> "one agent drafts, a different agent critiques, then the answer is refined"
                }
            )
            if (profile.isNotBlank()) append("\n\n--- User profile and preferences (always follow) ---\n").append(profile)
            if (instructions.isNotBlank()) append("\n\n--- User's coordination instructions ---\n").append(instructions.trim())
            if (memory.isNotBlank()) append("\n\n--- Memory of the user's saved projects (use when relevant) ---\n").append(memory)
        }
        val prompt = transcript(history)
        val question = history.lastOrNull { it.role == "user" }?.text.orEmpty().take(3000)

        when (mode) {
            ChatMode.AUTO -> {
                val a = ask(system, prompt, 2048, eligible, status) ?: return failMsg()
                return ChatMsg("assistant", a.text, a.name)
            }
            ChatMode.COMPARE -> {
                status(trf("⏳ %s وكلاء يجيبون بالتوازي…", minOf(team.size, 4)))
                val answers = coroutineScope {
                    team.take(4).map { a ->
                        async { router.markStart(a.id); a to ClaudeClient.send(a, system, prompt, 1500) }
                    }.awaitAll()
                }
                val ok = ArrayList<Pair<String, String>>()
                for ((a, r) in answers) {
                    when (r) {
                        is ApiResult.Ok -> { router.onSuccess(a.id); ok.add(a.name to r.text.trim()) }
                        is ApiResult.Fail -> {
                            router.onFailure(a.id, r.code, r.retryAfterMs, r.fatal, r.message)
                            lastError = "${a.name}: ${r.code} ${r.message.take(120)}"
                        }
                    }
                }
                if (ok.isEmpty()) return failMsg()
                if (ok.size == 1) return ChatMsg("assistant", ok[0].second, ok[0].first)
                status(tr("⏳ دمج الإجابات…"))
                val merged = ask(
                    SYNTH_SYSTEM,
                    "Question:\n$question\n\n" + ok.joinToString("\n\n") { "Answer from ${it.first}:\n${it.second.take(3500)}" },
                    2048, eligible, status
                )
                return if (merged == null) ChatMsg("assistant", ok[0].second, ok[0].first, ok)
                else ChatMsg("assistant", merged.text, merged.name + " ⟵ " + ok.joinToString("+") { it.first }, ok)
            }
            else -> {
                val draft = ask(system, prompt, 2048, eligible, status) ?: return failMsg()
                status(trf("⏳ مراجعة المسودة (من %s)…", draft.name))
                val critique = ask(
                    CRITIC_SYSTEM, "Question:\n$question\n\nDraft answer:\n${draft.text.take(5000)}", 1200,
                    { eligible(it) && (it.id != draft.id || team.size == 1) }, status
                )
                if (critique == null) return ChatMsg("assistant", draft.text, draft.name, listOf(draft.name to draft.text))
                status(tr("⏳ التحسين النهائي…"))
                val fin = ask(
                    REFINE_SYSTEM,
                    "Question:\n$question\n\nDraft answer:\n${draft.text.take(5000)}\n\nCritique:\n${critique.text.take(3000)}",
                    2048, eligible, status
                )
                val extras = listOf(
                    (tr("مسودة") + " · " + draft.name) to draft.text,
                    (tr("نقد") + " · " + critique.name) to critique.text
                )
                return ChatMsg("assistant", fin?.text ?: draft.text, (fin?.name ?: draft.name) + " ⟵ " + draft.name + "+" + critique.name, extras)
            }
        }
    }
}
