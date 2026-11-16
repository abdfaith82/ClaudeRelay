package com.relay.agents

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val attStore = AttachmentStore(app)
    private val processor = AttachmentProcessor(app)

    /** مرفقات تُجهَّز لمهمة جديدة (قبل الضغط على ابدأ). */
    var pending by mutableStateOf<List<Attachment>>(emptyList())
        private set
    var processing by mutableStateOf(false)
        private set

    /** مرفقات المشروع الحالي. */
    var projectAttachments by mutableStateOf(AttachmentBundle())
        private set

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            processing = true
            val results = withContext(Dispatchers.IO) { uris.map { processor.process(it) } }
            pending = (pending + results).take(MAX_FILES)
            processing = false
        }
    }

    fun removePending(index: Int) {
        pending = pending.filterIndexed { i, _ -> i != index }
    }

    /** يحدّ مجموع الصور المرسلة للتحليل. */
    private fun trimImages(list: List<Attachment>): List<Attachment> {
        var left = AttachmentBundle.MAX_IMAGES
        return list.map { a ->
            val keep = a.images.take(left)
            left -= keep.size
            a.copy(images = keep)
        }
    }

    // ---------- المحادثة ----------
    private val chatStore = ChatStore(app)
    private val chatEngine = ChatEngine()
    private var chatJob: Job? = null

    var chat by mutableStateOf(chatStore.load())
        private set
    var chatBusy by mutableStateOf(false)
        private set
    var chatStatus by mutableStateOf("")
        private set
    var chatMode by mutableStateOf(runCatching { ChatMode.valueOf(storage.setting("chat_mode", "AUTO")) }.getOrDefault(ChatMode.AUTO))
        private set
    var chatInstructions by mutableStateOf(storage.setting("chat_instr", ""))
        private set
    var chatInclude by mutableStateOf(storage.setting("chat_include", "").split(",").filter { it.isNotEmpty() }.toSet())
        private set
    var chatMemory by mutableStateOf(storage.setting("chat_mem", "1") == "1")
        private set

    fun selectChatMode(m: ChatMode) { chatMode = m; storage.saveSetting("chat_mode", m.name) }
    fun editChatInstructions(t: String) { chatInstructions = t.take(2000); storage.saveSetting("chat_instr", chatInstructions) }
    fun switchChatMemory(on: Boolean) { chatMemory = on; storage.saveSetting("chat_mem", if (on) "1" else "0") }
    fun toggleChatAgent(id: String) {
        chatInclude = if (id in chatInclude) chatInclude - id else chatInclude + id
        storage.saveSetting("chat_include", chatInclude.joinToString(","))
    }

    /** موصلات الإشعارات (Webhook/تيليجرام/ntfy). */
    val connectors = ConnectorConfig(storage)

    // ---------- الملف الشخصي والقدرات ----------
    var profileName by mutableStateOf(storage.setting("profile_name", ""))
        private set
    var profilePrefs by mutableStateOf(storage.setting("profile_prefs", ""))
        private set
    var defaultCoding by mutableStateOf(storage.setting("cap_coding", "0") == "1")
        private set
    var defaultThinking by mutableStateOf(storage.setting("cap_thinking", "0") == "1")
        private set
    var chatFailover by mutableStateOf(storage.setting("cap_failover", "1") == "1")
        private set
    var cloudEnabled by mutableStateOf(storage.setting("cap_cloud", "1") == "1")
        private set

    fun saveProfile(name: String, prefs: String) {
        profileName = name.trim().take(60)
        profilePrefs = prefs.trim().take(4000)
        storage.saveSetting("profile_name", profileName)
        storage.saveSetting("profile_prefs", profilePrefs)
    }

    fun updateCapability(key: String, on: Boolean) {
        when (key) {
            "coding" -> defaultCoding = on
            "thinking" -> defaultThinking = on
            "failover" -> chatFailover = on
            "cloud" -> cloudEnabled = on
            "memory" -> { chatMemory = on; storage.saveSetting("chat_mem", if (on) "1" else "0"); return }
        }
        storage.saveSetting("cap_$key", if (on) "1" else "0")
    }

    private fun profileText(): String = buildString {
        if (profileName.isNotBlank()) append("The user's name: ").append(profileName).append('\n')
        if (profilePrefs.isNotBlank()) append(profilePrefs)
    }.trim()

    fun stopChat() { chatJob?.cancel() }

    fun clearChat() {
        chatJob?.cancel()
        chat = emptyList()
        chatStore.save(chat)
    }

    /** يرسل رسالة. يرجع true إن كانت أمر /task (بدأت مهمة تنسيق كاملة في تبويب التشغيل). */
    fun sendChat(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || chatBusy) return false
        val taskPrefix = listOf("/task ", "/مهمة ").firstOrNull { t.startsWith(it, ignoreCase = true) }
        if (taskPrefix != null) {
            val goal = t.substring(taskPrefix.length).trim()
            if (goal.isEmpty() || running) {
                chat = chat + ChatMsg("user", t) + ChatMsg("assistant",
                    if (running) tr("توجد مهمة قيد التشغيل الآن. أوقفها أو انتظر انتهاءها.") else tr("اكتب الهدف بعد /task"), "system")
                chatStore.save(chat)
                return false
            }
            chat = chat + ChatMsg("user", t) + ChatMsg("assistant", trf("▶️ بدأت مهمة تنسيق كاملة: %s", goal.take(80)), "system")
            chatStore.save(chat)
            start(goal)
            return true
        }
        chat = chat + ChatMsg("user", t)
        chatStore.save(chat)
        chatBusy = true
        chatStatus = ""
        val history = chat
        chatEngine.failover = chatFailover
        val memory = if (chatMemory) MemoryBuilder.build(store.list(), t) else ""
        chatJob = viewModelScope.launch {
            try {
                val msg = chatEngine.reply(history, chatMode, agents, chatInstructions, memory, chatInclude, profileText()) { chatStatus = it }
                chat = chat + msg
                chatStore.save(chat)
            } finally {
                chatBusy = false
                chatStatus = ""
            }
        }
        return false
    }

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

    fun start(goal: String, coding: Boolean = false, thinking: Boolean = false) {
        if (running || goal.isBlank()) return
        val g = goal.trim()
        val now = System.currentTimeMillis()
        val p = Project(UUID.randomUUID().toString(), g.take(50), now, now, Checkpoint(g, coding = coding, thinking = thinking))
        current = p
        storage.setCurrentProjectId(p.id)
        store.save(p)
        storage.clearCheckpoint()
        checkpoint = p.checkpoint
        val bundle = AttachmentBundle(trimImages(pending))
        attStore.save(p.id, bundle)
        projectAttachments = bundle
        pending = emptyList()
        refreshProjects()
        launchRun(g, null, bundle)
    }

    fun resume() {
        val cp = checkpoint ?: return
        if (running) return
        if (current == null) {
            val now = System.currentTimeMillis()
            current = Project(UUID.randomUUID().toString(), cp.goal.take(50), now, now, cp)
            storage.setCurrentProjectId(current!!.id)
        }
        launchRun(cp.goal, cp, projectAttachments)
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
        projectAttachments = AttachmentBundle()
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
        projectAttachments = attStore.load(p.id)
        states = emptyList()
        return true
    }

    fun renameProject(id: String, name: String) {
        val n = name.trim().take(60)
        if (n.isEmpty()) return
        updateProject(id) { it.copy(name = n) }
    }

    fun togglePin(id: String) {
        updateProject(id) { it.copy(pinned = !it.pinned) }
    }

    /** تعديل بيانات وصفية لمشروع دون تغيير تاريخ آخر تحديث (لا يقفز في الترتيب). */
    private fun updateProject(id: String, change: (Project) -> Project) {
        val p = (if (current?.id == id) current else store.load(id)) ?: return
        val updated = change(p)
        store.save(updated)
        if (current?.id == id) current = updated
        refreshProjects()
    }

    fun deleteProject(id: String) {
        if (running && current?.id == id) return
        store.delete(id)
        attStore.delete(id)
        if (current?.id == id) reset()
        refreshProjects()
    }

    /** ملفات الشيفرة المستخرجة من مشروع (وضع البرمجة). */
    fun codeFiles(id: String): Map<String, String> =
        store.load(id)?.let { p -> CodeExtractor.extract(p.checkpoint.steps.map { it.result }) } ?: emptyMap()

    /** نسخة احتياطية مشفّرة بكلمة مرور (المفاتيح داخلها) لاستعادة الوكلاء بعد إعادة التثبيت. */
    fun exportAgentsBackup(pass: String): String = BackupCrypto.encrypt(AgentBackup.toJson(agents), pass)

    fun importAgentsBackup(text: String, pass: String): Result<Int> = runCatching {
        val incoming = AgentBackup.fromJson(BackupCrypto.decrypt(text, pass))
        val (merged, added) = AgentBackup.merge(agents, incoming)
        agents = merged
        storage.saveAgents(agents)
        added
    }

    fun markdownOf(id: String): String? = store.load(id)?.let { ProjectExport.toMarkdown(it) }

    fun allProjectsJson(): String = ProjectExport.allToJson(store.list())

    fun projectName(id: String): String = projects.firstOrNull { it.id == id }?.name ?: "project"

    companion object {
        const val MAX_FILES = 12
    }

    private fun refreshProjects() {
        projects = store.list()
    }

    private fun persist(cp: Checkpoint) {
        current = current?.copy(checkpoint = cp, updatedAt = System.currentTimeMillis())
            ?.also { store.save(it) }
    }

    private fun launchRun(goal: String, resume: Checkpoint?, bundle: AttachmentBundle) {
        running = true
        // ذاكرة الوكلاء: ملخص المشاريع الأخرى المحفوظة.
        val memory = if (chatMemory) MemoryBuilder.build(store.list().filter { it.id != current?.id }, goal) else ""
        job = viewModelScope.launch {
            val orch = Orchestrator(storage, ::askManual) { cp, st ->
                checkpoint = cp
                states = st
                persist(cp)
            }
            orch.profile = profileText()
            try {
                orch.run(goal, agents, resume, memory, bundle, (resume ?: checkpoint)?.coding ?: false, (resume ?: checkpoint)?.thinking ?: false) { digest ->
                    projectAttachments = projectAttachments.copy(imageDigest = digest)
                    current?.let { attStore.save(it.id, projectAttachments) }
                }
            } finally {
                running = false
                refreshProjects()
            }
            // تُرسل الإشعارات فقط عند اكتمال المهمة طبيعياً (ليس عند الإيقاف أو الإلغاء).
            val done = checkpoint
            if (cloudEnabled && done != null && done.finalAnswer.isNotEmpty()) {
                Notifier.broadcast(connectors, "✅ " + (current?.name ?: "Task") + "\n\n" + done.finalAnswer.take(1200))
            }
        }
    }
}
