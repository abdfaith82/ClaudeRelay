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
    private val store = ProjectStore(app)

    var agents by mutableStateOf(storage.loadAgents())
        private set
    var checkpoint by mutableStateOf(storage.loadCheckpoint())
        private set
    var states by mutableStateOf<List<AgentState>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set
    var projects by mutableStateOf(store.list())
        private set

    private var current: Project? = storage.currentProjectId()?.let { store.load(it) }
    private var job: Job? = null

    val currentProjectId: String? get() = current?.id

    // ---------- الوكلاء ----------

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
        agents = agents + AgentConfig(
            UUID.randomUUID().toString(), name.trim(), key.trim(),
            model.trim().ifEmpty { provider.defaultModel }, true, provider
        )
        storage.saveAgents(agents)
    }

    fun removeAgent(id: String) {
        agents = agents.filterNot { it.id == id }
        storage.saveAgents(agents)
    }

    fun updateModel(id: String, model: String) {
        val m = model.trim().removePrefix("models/")
        if (m.isEmpty()) return
        agents = agents.map { if (it.id == id) it.copy(model = m) else it }
        storage.saveAgents(agents)
    }

    fun toggleAgent(id: String) {
        agents = agents.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        storage.saveAgents(agents)
    }

    // ---------- التشغيل والمشاريع ----------

    fun start(goal: String) {
        if (running || goal.isBlank()) return
        val g = goal.trim()
        val now = System.currentTimeMillis()
        val p = Project(UUID.randomUUID().toString(), g.take(50), now, now, Checkpoint(g))
        current = p
        storage.setCurrentProjectId(p.id)
        store.save(p)
        storage.clearCheckpoint()
        checkpoint = p.checkpoint
        refreshProjects()
        launchRun(g, null)
    }

    fun resume() {
        val cp = checkpoint ?: return
        if (running) return
        if (current == null) {
            val now = System.currentTimeMillis()
            current = Project(UUID.randomUUID().toString(), cp.goal.take(50), now, now, cp)
            storage.setCurrentProjectId(current!!.id)
        }
        launchRun(cp.goal, cp)
    }

    fun stop() {
        job?.cancel()
    }

    /** يمسح المشروع الحالي من شاشة التشغيل فقط؛ يبقى محفوظاً في قائمة المشاريع. */
    fun reset() {
        job?.cancel()
        storage.clearCheckpoint()
        storage.setCurrentProjectId(null)
        current = null
        checkpoint = null
        states = emptyList()
    }

    /** يفتح مشروعاً محفوظاً ليُستأنف أو يُراجع. */
    fun openProject(id: String): Boolean {
        if (running) return false
        val p = store.load(id) ?: return false
        current = p
        storage.setCurrentProjectId(p.id)
        storage.saveCheckpoint(p.checkpoint)
        checkpoint = p.checkpoint
        states = emptyList()
        return true
    }

    fun deleteProject(id: String) {
        if (running && current?.id == id) return
        store.delete(id)
        if (current?.id == id) reset()
        refreshProjects()
    }

    fun markdownOf(id: String): String? = store.load(id)?.let { ProjectExport.toMarkdown(it) }

    fun allProjectsJson(): String = ProjectExport.allToJson(store.list())

    fun projectName(id: String): String = projects.firstOrNull { it.id == id }?.name ?: "project"

    private fun refreshProjects() {
        projects = store.list()
    }

    private fun persist(cp: Checkpoint) {
        current = current?.copy(checkpoint = cp, updatedAt = System.currentTimeMillis())
            ?.also { store.save(it) }
    }

    private fun launchRun(goal: String, resume: Checkpoint?) {
        running = true
        // ذاكرة الوكلاء: ملخص المشاريع الأخرى المحفوظة.
        val memory = MemoryBuilder.build(store.list().filter { it.id != current?.id }, goal)
        job = viewModelScope.launch {
            val orch = Orchestrator(storage, ::askManual) { cp, st ->
                checkpoint = cp
                states = st
                persist(cp)
            }
            try {
                orch.run(goal, agents, resume, memory)
            } finally {
                running = false
                refreshProjects()
            }
        }
    }
}
