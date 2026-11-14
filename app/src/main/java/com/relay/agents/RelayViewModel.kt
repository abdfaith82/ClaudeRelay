package com.relay.agents

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

class RelayViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = Storage(app)

    var agents by mutableStateOf(storage.loadAgents())
        private set
    var checkpoint by mutableStateOf(storage.loadCheckpoint())
        private set

    /** رسالة للمستخدم (خطأ تخزين أو تحقق من إدخال)؛ تُعرض أعلى الشاشة حتى يغلقها. */
    var message by mutableStateOf<String?>(storage.lastError)
        private set

    /** نتيجة اختبار اتصال كل وكيل (id → نص). */
    var testResults by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    fun dismissMessage() {
        message = null
    }

    private fun persistAgents() {
        if (!storage.saveAgents(agents)) message = storage.lastError ?: "تعذّر حفظ الوكلاء."
    }

    /** يرسل طلباً صغيراً للتأكد أن المفتاح والنموذج صحيحان قبل التشغيل. */
    fun testAgent(id: String) {
        val a = agents.firstOrNull { it.id == id } ?: return
        if (a.provider.manual) {
            testResults = testResults + (id to "الوكيل اليدوي لا يُختبر عبر API")
            return
        }
        testResults = testResults + (id to "جارٍ الاختبار…")
        viewModelScope.launch {
            val text = when (val r = ClaudeClient.send(a, "Reply with the single word OK.", "ping", 256)) {
                is ApiResult.Ok -> "✅ يعمل"
                is ApiResult.Fail ->
                    // رد فارغ = وصل الطلب للمزوّد والمفتاح مقبول (قد يستهلك النموذج الميزانية في التفكير).
                    if (r.code == 0 && r.message == ApiResult.EMPTY_REPLY) "✅ الاتصال سليم (الرد فارغ)"
                    else "❌ ${r.code}: ${r.message.take(100)}"
            }
            testResults = testResults + (id to text)
        }
    }
    var states by mutableStateOf<List<Orchestrator.AgentState>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set

    private var job: Job? = null

    /** طلب يدوي معلّق: المهمة التي على المستخدم تنفيذها في موقع الويب ولصق الرد. */
    class ManualRequest(val agent: AgentConfig, val prompt: String, val reply: CompletableDeferred<String?>)

    var manualRequest by mutableStateOf<ManualRequest?>(null)
        private set

    fun answerManual(text: String?) {
        manualRequest?.reply?.complete(text)
    }

    private suspend fun askManual(agent: AgentConfig, prompt: String): String? {
        val req = ManualRequest(agent, prompt, CompletableDeferred())
        manualRequest = req
        return try {
            req.reply.await()
        } finally {
            manualRequest = null
        }
    }

    fun addAgent(name: String, key: String, model: String, provider: Provider) {
        if (name.isBlank() || (key.isBlank() && !provider.manual)) return
        val modelOrUrl = model.trim().ifEmpty { provider.defaultModel }
        // الوكيل اليدوي يفتح الرابط داخل WebView: نقبل https فقط (لا http ولا file ولا javascript).
        if (provider.manual && !modelOrUrl.startsWith("https://")) {
            message = "رابط موقع الدردشة يجب أن يبدأ بـ https://"
            return
        }
        agents = agents + AgentConfig(
            UUID.randomUUID().toString(), name.trim(), key.trim(),
            modelOrUrl, true, provider
        )
        persistAgents()
    }

    fun removeAgent(id: String) {
        agents = agents.filterNot { it.id == id }
        testResults = testResults - id
        persistAgents()
    }

    fun toggleAgent(id: String) {
        agents = agents.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        persistAgents()
    }

    fun start(goal: String) {
        if (running || goal.isBlank()) return
        storage.clearCheckpoint()
        launchRun(goal.trim(), null)
    }

    fun resume() {
        val cp = checkpoint ?: return
        if (running) return
        launchRun(cp.goal, cp)
    }

    fun stop() {
        job?.cancel()
    }

    fun reset() {
        job?.cancel()
        storage.clearCheckpoint()
        checkpoint = null
        states = emptyList()
    }

    private fun launchRun(goal: String, resume: Checkpoint?) {
        running = true
        job = viewModelScope.launch {
            val orch = Orchestrator(storage, ::askManual) { cp, st ->
                checkpoint = cp
                states = st
            }
            try {
                orch.run(goal, agents, resume)
            } finally {
                running = false
            }
        }
    }
}
