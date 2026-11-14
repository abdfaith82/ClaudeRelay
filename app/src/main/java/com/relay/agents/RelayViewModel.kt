package com.relay.agents

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

class RelayViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = Storage(app)

    var agents by mutableStateOf(storage.loadAgents())
        private set
    var checkpoint by mutableStateOf(storage.loadCheckpoint())
        private set
    var states by mutableStateOf<List<Orchestrator.AgentState>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set

    private var job: Job? = null

    fun addAgent(name: String, key: String, model: String, provider: Provider) {
        if (name.isBlank() || key.isBlank()) return
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

    fun toggleAgent(id: String) {
        agents = agents.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }
        storage.saveAgents(agents)
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
            val orch = Orchestrator(storage) { cp, st ->
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
