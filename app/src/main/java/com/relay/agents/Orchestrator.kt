package com.relay.agents

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import kotlin.coroutines.coroutineContext

/**
 * المنسّق: يخطط الهدف إلى خطوات، ثم ينفذ كل خطوة عبر AgentRouter (توزيع حمل + تبديل + backoff).
 * بعد كل خطوة تُحفظ نقطة الاستئناف، فإن أُغلق التطبيق يكمل من آخر نقطة.
 * تعليمات النماذج بالإنجليزية (أدق وعالمية) مع إلزام الرد بلغة المستخدم؛ وسجل الواجهة مترجم عبر tr/trf.
 */
class Orchestrator(
    private val storage: Storage,
    /** يطلب من المستخدم تنفيذ المهمة في موقع ويب ولصق الرد. يرجع null عند التخطي. */
    private val askManual: suspend (AgentConfig, String) -> String?,
    private val onUpdate: (Checkpoint, List<AgentState>) -> Unit
) {
    companion object {
        const val LANG_RULE = "Always reply in the same language as the user's goal."

        const val PLAN_SYSTEM =
            "You are a task coordinator. Think carefully, then split the goal into 3 to 6 short, sequential steps, " +
                "each independently executable and verifiable. Reply with ONLY a JSON array of strings, no explanation. " +
                "Example: [\"Step 1\",\"Step 2\",\"Step 3\"]. Write the step texts in the language of the goal."
        const val PLAN_CODING_SYSTEM =
            "You are a principal software engineer. Split the software project into 4 to 7 sequential engineering steps: " +
                "first the architecture, file list and exact library versions; then implement each part with its files; " +
                "finally a code review and bug-fix pass. Reply with ONLY a JSON array of strings, no explanation. " +
                "Example: [\"Step 1\",\"Step 2\"]. Write the step texts in the language of the goal."
        const val EXEC_SYSTEM =
            "You are an expert executor in a team. Continue the work of previous agents without repeating it. " +
                "Reason carefully, double-check facts and arithmetic, and answer with the result of the current step directly. " + LANG_RULE
        const val CODING_SYSTEM =
            "You are an expert software engineer in a team. Write complete, runnable code with no shortcuts, no \"...\" and no placeholder comments. " +
                "For every file use exactly this block format: ```file:path/to/Name.ext then the code then ```. " +
                "When changing a previous file, rewrite it completely with the same path. Respect the versions and APIs decided in earlier steps. " +
                "Keep explanations outside file blocks very short. " + LANG_RULE
        const val CRITIC_SYSTEM =
            "You are a strict technical reviewer. Inspect the draft for: logical or arithmetic errors, unverified claims, security issues, " +
                "missing edge cases, conflicts with previous steps, and anything missing. List the problems numbered and concise, each with a fix. " +
                "Do not rewrite the draft. If it is sound, say so and suggest the single most valuable improvement. " + LANG_RULE
        const val REFINE_SYSTEM =
            "Improve the draft using the critique: fix errors, fill gaps, remove unverified claims. " +
                "Reply with the final version only, without mentioning the draft or the critique. " + LANG_RULE
        const val REFINE_CODING_SYSTEM =
            "You are a software engineer. Apply the review notes to the draft and rewrite every changed file completely using " +
                "```file:path then the code then ```, with no shortcuts. Do not repeat unaffected files. Explain changes very briefly outside file blocks. " + LANG_RULE
        const val VISION_SYSTEM =
            "You are a visual analyst. Describe every image precisely: objects, visible text, approximate dimensions, colors and any detail " +
                "relevant to the user's project. Number the images in order. " + LANG_RULE
        const val FINAL_SYSTEM =
            "Combine the step results into one final, well-organized answer for the user. " + LANG_RULE
        const val FINAL_CODING_SYSTEM =
            "Write a brief engineering summary of the project: file structure, how to build and run it, and anything the user must pay attention to. " +
                "Do not rewrite the code. " + LANG_RULE
    }

    private val router = AgentRouter()
    private var cp = Checkpoint("")
    private var memory = ""
    private var attachCtx = ""

    private fun publish() = onUpdate(cp, router.snapshot())

    private fun log(msg: String) {
        cp = cp.copy(log = cp.log + msg)
        storage.saveCheckpoint(cp)
        publish()
    }

    /** ينفذ طلباً واحداً مع التبديل بين الوكلاء. يرجع (النص، اسم الوكيل) أو null عند الإلغاء/عدم وجود وكلاء. */
    private suspend fun callWithFailover(
        baseSystem: String,
        user: String,
        maxTokens: Int,
        images: List<ImageData> = emptyList(),
        eligible: (AgentConfig) -> Boolean = { true },
        withContext: Boolean = true
    ): Pair<String, String>? {
        val system = buildString {
            append(baseSystem)
            if (withContext && memory.isNotBlank())
                append("\n\n--- Memory of the user's previous projects (use when relevant, do not repeat it) ---\n$memory")
            if (withContext && attachCtx.isNotBlank())
                append("\n\n--- Task attachments ---\n$attachCtx")
        }
        while (coroutineContext.isActive) {
            when (val p = router.pick(eligible)) {
                AgentRouter.Pick.None -> {
                    log(tr("⛔ كل الوكلاء معطّلون (تحقق من المفاتيح والنماذج)"))
                    return null
                }
                is AgentRouter.Pick.Wait -> {
                    if (p.ms > 5_000) log(trf("⏳ كل الوكلاء في راحة، انتظار %s ثانية", p.ms / 1000))
                    publish()
                    delay(minOf(p.ms, 30_000))
                }
                is AgentRouter.Pick.Ready -> {
                    val a = p.health.agent
                    router.markStart(a.id)
                    if (a.provider.manual) {
                        log(trf("🖐️ %s: بانتظار تنفيذك اليدوي في الويب", a.name))
                        val reply = askManual(a, "$system\n\n---\n\n$user")
                        if (!reply.isNullOrBlank()) {
                            router.onSuccess(a.id)
                            return reply.trim() to a.name
                        }
                        log(trf("⏭️ تخطي %s (راحة 10 دقائق)", a.name))
                        router.park(a.id, 600_000, "Skipped")
                        continue
                    }
                    when (val r = ClaudeClient.send(a, system, user, maxTokens, images)) {
                        is ApiResult.Ok -> {
                            router.onSuccess(a.id)
                            return r.text to a.name
                        }
                        is ApiResult.Fail -> {
                            val cool = router.onFailure(a.id, r.code, r.retryAfterMs, r.fatal, r.message)
                            if (cool < 0) log(trf("❌ %s: عُطّل (%s: %s)", a.name, r.code, r.message.take(80)))
                            else log(trf("⚠️ %s توقف (%s: %s) — راحة %sث ويكمل الوكيل التالي", a.name, r.code, r.message.take(80), cool / 1000))
                        }
                    }
                }
            }
        }
        return null
    }

    /** يقبل مصفوفة JSON (حتى داخل ``` )، وإلا يستخرج قائمة مرقّمة/نقطية من النص. */
    private fun parseSteps(text: String): List<String> {
        val s = text.indexOf('['); val e = text.lastIndexOf(']')
        if (s >= 0 && e > s) {
            val fromJson = runCatching {
                val arr = JSONArray(text.substring(s, e + 1))
                (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
            }.getOrDefault(emptyList())
            if (fromJson.isNotEmpty()) return fromJson
        }
        val item = Regex("""^\s*(?:\d+\s*[.)\-:]|[-*•])\s+(.+)$""")
        return text.lines()
            .mapNotNull { item.find(it)?.groupValues?.get(1) }
            .map { it.replace("**", "").trim().trim('"', ',') }
            .filter { it.length > 2 }
            .take(8)
    }

    /** يبدأ هدفاً جديداً أو يكمل نقطة محفوظة. */
    suspend fun run(
        goal: String,
        agents: List<AgentConfig>,
        resume: Checkpoint?,
        memory: String = "",
        attachments: AttachmentBundle = AttachmentBundle(),
        coding: Boolean = false,
        thinking: Boolean = false,
        onDigest: (String) -> Unit = {}
    ) {
        this.memory = memory
        router.load(agents.filter { it.enabled && (it.apiKey.isNotBlank() || it.provider.manual) })
        cp = resume ?: Checkpoint(goal, coding = coding, thinking = thinking)
        if (router.size == 0) { log(tr("⛔ لا يوجد وكلاء مفعّلون")); return }

        // 0) المرفقات: وصف الصور/اللقطات مرة واحدة بوكيل رؤية (Claude/Gemini) ليستفيد منه كل الوكلاء.
        var bundle = attachments
        val images = bundle.allImages
        if (images.isNotEmpty() && bundle.imageDigest.isEmpty()) {
            if (router.hasEligible { it.provider.vision }) {
                log(trf("🖼️ تحليل %s صور/لقطات…", images.size))
                val d = callWithFailover(
                    baseSystem = VISION_SYSTEM,
                    user = "Goal: $goal\nFiles: ${bundle.items.joinToString { it.name }}",
                    maxTokens = 1500, images = images,
                    eligible = { it.provider.vision }, withContext = false
                )
                if (d != null) {
                    bundle = bundle.copy(imageDigest = d.first.trim())
                    onDigest(bundle.imageDigest)
                    log(trf("🖼️ تم وصف الصور بواسطة %s", d.second))
                } else log(tr("⚠️ تعذّر تحليل الصور؛ سيُكمل الفريق بدونها"))
            } else {
                log(tr("⚠️ لا يوجد وكيل رؤية (Claude أو Gemini) فارتباط الصور مهمل؛ أضف وكيل Gemini مجانياً لتحليلها"))
            }
        }
        attachCtx = bundle.toContext()

        // 1) التخطيط
        if (cp.steps.isEmpty()) {
            log(tr("🧠 تخطيط الهدف…"))
            var steps: List<String> = emptyList()
            var by = ""
            for (attempt in 1..2) {
                val plan = callWithFailover(
                    baseSystem = if (cp.coding) PLAN_CODING_SYSTEM else PLAN_SYSTEM,
                    user = "Goal: ${cp.goal}" + if (attempt == 2) "\n\nReply NOW with ONLY a JSON array of strings." else "",
                    maxTokens = 2048
                ) ?: return
                steps = parseSteps(plan.first)
                by = plan.second
                if (steps.isNotEmpty()) break
                log(trf("⚠️ رد غير مفهوم من %s (محاولة %s): %s", plan.second, attempt, plan.first.take(150).replace('\n', ' ')))
            }
            if (steps.isEmpty()) { log(tr("❌ تعذّر فهم الخطة بعد محاولتين")); return }
            if (cp.coding && steps.none { it.contains("مراجعة") || it.contains("review", ignoreCase = true) }) {
                steps = steps + "Review the whole code, fix bugs, and rewrite every changed file completely"
            }
            cp = cp.copy(steps = steps.map { Step(it) })
            log(trf("📋 الخطة (%s): %s خطوات", by, steps.size))
        } else {
            log(trf("▶️ استئناف من الخطوة %s", cp.nextIndex + 1))
        }

        // 2) التنفيذ خطوة بخطوة
        while (coroutineContext.isActive && cp.nextIndex != -1) {
            val i = cp.nextIndex
            val step = cp.steps[i]
            val doneSteps = cp.steps.filter { it.status == StepStatus.DONE }
            val context = if (cp.coding) {
                val files = CodeExtractor.extract(doneSteps.map { it.result })
                doneSteps.joinToString("\n") { "• ${it.title}" } + "\n\n" + CodeExtractor.contextFor(files)
            } else {
                doneSteps.joinToString("\n\n") { "• ${it.title}\n${it.result.take(1200)}" }
            }
            val prompt = buildString {
                append("Overall goal: ${cp.goal}\n\n")
                if (context.isNotEmpty()) append("Done so far:\n$context\n\n")
                append("Now execute step ${i + 1}/${cp.steps.size}: ${step.title}\nGive a focused, complete result for this step only.")
            }
            val res = callWithFailover(
                if (cp.coding) CODING_SYSTEM else EXEC_SYSTEM,
                prompt, if (cp.coding) 4096 else 2048
            ) ?: return
            // التفكير العميق: نقد المسودة بوكيل آخر ثم تحسينها (الموجّه يوزّع على الأقل استخداماً فيختلف الوكيل غالباً).
            var finalRes = res
            if (cp.thinking && coroutineContext.isActive) {
                log(trf("🔎 نقد الخطوة %s (%s كتب المسودة)…", i + 1, res.second))
                val crit = callWithFailover(CRITIC_SYSTEM, "Goal: ${cp.goal}\nStep: ${step.title}\n\nDraft:\n${res.first.take(12_000)}", 1500)
                if (crit != null) {
                    log(trf("🛠️ تحسين الخطوة %s بعد نقد %s…", i + 1, crit.second))
                    val refined = callWithFailover(
                        if (cp.coding) REFINE_CODING_SYSTEM else REFINE_SYSTEM,
                        "Goal: ${cp.goal}\nStep: ${step.title}\n\nDraft:\n${res.first.take(12_000)}\n\nCritique:\n${crit.first.take(4_000)}",
                        if (cp.coding) 4096 else 2048
                    )
                    if (refined != null) finalRes = refined.first to "${res.second} → ${crit.second} → ${refined.second}"
                }
            }
            val steps = cp.steps.toMutableList()
            steps[i] = step.copy(status = StepStatus.DONE, result = finalRes.first, doneBy = finalRes.second)
            cp = cp.copy(steps = steps)
            log(trf("✅ خطوة %s اكتملت بواسطة %s", i + 1, finalRes.second))
        }

        // 3) التجميع النهائي
        if (cp.finished && cp.finalAnswer.isEmpty()) {
            log(tr("🧩 تجميع النتيجة النهائية…"))
            val finRes = if (cp.coding) {
                val files = CodeExtractor.extract(cp.steps.map { it.result })
                callWithFailover(
                    FINAL_CODING_SYSTEM,
                    "Goal: ${cp.goal}\n\nProduced files (${files.size}):\n${files.keys.joinToString("\n")}\n\nSteps:\n" +
                        cp.steps.mapIndexed { n, s -> "${n + 1}. ${s.title}" }.joinToString("\n"),
                    2048
                )
            } else {
                val all = cp.steps.mapIndexed { n, s -> "${n + 1}. ${s.title}\n${s.result.take(1500)}" }.joinToString("\n\n")
                callWithFailover(FINAL_SYSTEM, "Goal: ${cp.goal}\n\nStep results:\n$all", 2048)
            }
            val fin = finRes ?: return
            cp = cp.copy(finalAnswer = fin.first)
            log(trf("🏁 انتهى (%s)", fin.second))
        }
    }
}
