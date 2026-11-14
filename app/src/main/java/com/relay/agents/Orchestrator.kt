package com.relay.agents

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import kotlin.coroutines.coroutineContext

/**
 * المنسّق: يخطط الهدف إلى خطوات، ثم ينفذ كل خطوة بأول وكيل متاح.
 * إذا فشل وكيل (حد معدّل/ازدحام/شبكة) يُوضع في فترة راحة ويكمل التالي من نفس الخطوة.
 * بعد كل خطوة تُحفظ نقطة الاستئناف، فإن أُغلق التطبيق يكمل من آخر نقطة.
 */
class Orchestrator(
    private val storage: Storage,
    private val onUpdate: (Checkpoint, List<AgentState>) -> Unit
) {
    data class AgentState(val agent: AgentConfig, val cooldownUntil: Long = 0L, val disabled: Boolean = false, val note: String = "")

    private var states: MutableList<AgentState> = mutableListOf()
    private var cp = Checkpoint("")

    private fun log(msg: String) {
        cp = cp.copy(log = cp.log + msg)
        storage.saveCheckpoint(cp)
        onUpdate(cp, states.toList())
    }

    private fun setState(id: String, f: (AgentState) -> AgentState) {
        val i = states.indexOfFirst { it.agent.id == id }
        if (i >= 0) states[i] = f(states[i])
    }

    /** يرجع أول وكيل جاهز، أو null إن كانوا جميعاً في راحة/معطّلين. */
    private fun pick(skip: Set<String>): AgentState? {
        val now = System.currentTimeMillis()
        return states.firstOrNull { !it.disabled && it.cooldownUntil <= now && it.agent.id !in skip }
    }

    private fun nextWakeMs(): Long {
        val now = System.currentTimeMillis()
        val t = states.filter { !it.disabled }.minOfOrNull { it.cooldownUntil } ?: return -1
        return (t - now).coerceAtLeast(1_000)
    }

    /** ينفذ طلباً واحداً مع التبديل بين الوكلاء. يرجع (النص، اسم الوكيل) أو null عند الإلغاء/عدم وجود وكلاء. */
    private suspend fun callWithFailover(system: String, user: String, maxTokens: Int): Pair<String, String>? {
        val triedThisRound = mutableSetOf<String>()
        while (coroutineContext.isActive) {
            val st = pick(triedThisRound)
            if (st == null) {
                val wait = nextWakeMs()
                if (wait < 0) {
                    log("⛔ كل الوكلاء معطّلون (تحقق من المفاتيح)")
                    return null
                }
                log("⏳ كل الوكلاء في راحة، انتظار ${wait / 1000} ثانية")
                triedThisRound.clear()
                delay(minOf(wait, 60_000))
                continue
            }
            val a = st.agent
            when (val r = ClaudeClient.send(a, system, user, maxTokens)) {
                is ApiResult.Ok -> return r.text to a.name
                is ApiResult.Fail -> {
                    if (r.fatal) {
                        log("❌ ${a.name}: مفتاح مرفوض (${r.code}) — تم تعطيله")
                        setState(a.id) { it.copy(disabled = true, note = "مفتاح مرفوض") }
                    } else {
                        val cool = when (r.code) {
                            429 -> 60_000L
                            529, 503 -> 30_000L
                            else -> 15_000L
                        }
                        log("⚠️ ${a.name} توقف (${r.code}: ${r.message.take(80)}) — يكمل الوكيل التالي")
                        setState(a.id) { it.copy(cooldownUntil = System.currentTimeMillis() + cool, note = "راحة") }
                    }
                    triedThisRound += a.id
                }
            }
        }
        return null
    }

    private fun parseSteps(text: String): List<String> {
        val s = text.indexOf('['); val e = text.lastIndexOf(']')
        if (s < 0 || e <= s) return emptyList()
        return runCatching {
            val arr = JSONArray(text.substring(s, e + 1))
            (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    /** يبدأ هدفاً جديداً أو يكمل نقطة محفوظة (إن كان goal نفسه). */
    suspend fun run(goal: String, agents: List<AgentConfig>, resume: Checkpoint?) {
        states = agents.filter { it.enabled && it.apiKey.isNotBlank() }
            .map { AgentState(it) }.toMutableList()
        cp = resume ?: Checkpoint(goal)
        if (states.isEmpty()) { log("⛔ لا يوجد وكلاء مفعّلون"); return }

        // 1) التخطيط
        if (cp.steps.isEmpty()) {
            log("🧠 تخطيط الهدف…")
            val plan = callWithFailover(
                system = "أنت منسّق مهام. قسّم الهدف إلى 3 إلى 6 خطوات قصيرة ومتسلسلة. أجب بمصفوفة JSON من النصوص فقط دون أي شرح.",
                user = "الهدف: ${cp.goal}", maxTokens = 600
            ) ?: return
            val steps = parseSteps(plan.first)
            if (steps.isEmpty()) { log("❌ تعذّر فهم الخطة"); return }
            cp = cp.copy(steps = steps.map { Step(it) })
            log("📋 الخطة (${plan.second}): ${steps.size} خطوات")
        } else {
            log("▶️ استئناف من الخطوة ${cp.nextIndex + 1}")
        }

        // 2) التنفيذ خطوة بخطوة
        while (coroutineContext.isActive && cp.nextIndex != -1) {
            val i = cp.nextIndex
            val step = cp.steps[i]
            val context = cp.steps.filter { it.status == StepStatus.DONE }
                .joinToString("\n\n") { "• ${it.title}\n${it.result.take(1200)}" }
            val prompt = buildString {
                append("الهدف العام: ${cp.goal}\n\n")
                if (context.isNotEmpty()) append("ما أُنجز حتى الآن:\n$context\n\n")
                append("نفّذ الآن الخطوة ${i + 1}/${cp.steps.size}: ${step.title}\nأعطِ نتيجة مركّزة وكاملة لهذه الخطوة فقط.")
            }
            val res = callWithFailover(
                "أنت وكيل منفّذ ضمن فريق. أكمل عمل من سبقك دون تكرار، وأجب بنتيجة الخطوة مباشرة.",
                prompt, 2048
            ) ?: return
            val steps = cp.steps.toMutableList()
            steps[i] = step.copy(status = StepStatus.DONE, result = res.first, doneBy = res.second)
            cp = cp.copy(steps = steps)
            log("✅ خطوة ${i + 1} اكتملت بواسطة ${res.second}")
        }

        // 3) التجميع النهائي
        if (cp.finished && cp.finalAnswer.isEmpty()) {
            log("🧩 تجميع النتيجة النهائية…")
            val all = cp.steps.mapIndexed { n, s -> "${n + 1}. ${s.title}\n${s.result.take(1500)}" }.joinToString("\n\n")
            val fin = callWithFailover(
                "اجمع نتائج الخطوات في إجابة نهائية واحدة منظمة للمستخدم.",
                "الهدف: ${cp.goal}\n\nنتائج الخطوات:\n$all", 2048
            ) ?: return
            cp = cp.copy(finalAnswer = fin.first)
            log("🏁 انتهى (${fin.second})")
        }
    }
}
