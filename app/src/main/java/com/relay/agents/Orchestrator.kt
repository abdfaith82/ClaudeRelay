package com.relay.agents

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import kotlin.coroutines.coroutineContext

/**
 * المنسّق: يخطط الهدف إلى خطوات، ثم ينفذ كل خطوة عبر AgentRouter (توزيع حمل + تبديل + backoff).
 * بعد كل خطوة تُحفظ نقطة الاستئناف، فإن أُغلق التطبيق يكمل من آخر نقطة.
 */
class Orchestrator(
    private val storage: Storage,
    /** يطلب من المستخدم تنفيذ المهمة في موقع ويب ولصق الرد. يرجع null عند التخطي. */
    private val askManual: suspend (AgentConfig, String) -> String?,
    private val onUpdate: (Checkpoint, List<AgentState>) -> Unit
) {
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
                append("\n\n--- ذاكرة المشاريع (للرجوع إليها والبناء عليها عند الصلة، لا تكررها) ---\n$memory")
            if (withContext && attachCtx.isNotBlank())
                append("\n\n--- مرفقات المهمة ---\n$attachCtx")
        }
        while (coroutineContext.isActive) {
            when (val p = router.pick(eligible)) {
                AgentRouter.Pick.None -> {
                    log("⛔ كل الوكلاء معطّلون (تحقق من المفاتيح والنماذج)")
                    return null
                }
                is AgentRouter.Pick.Wait -> {
                    if (p.ms > 5_000) log("⏳ كل الوكلاء في راحة، انتظار ${p.ms / 1000} ثانية")
                    publish()
                    delay(minOf(p.ms, 30_000))
                }
                is AgentRouter.Pick.Ready -> {
                    val a = p.health.agent
                    router.markStart(a.id)
                    if (a.provider.manual) {
                        log("🖐️ ${a.name}: بانتظار تنفيذك اليدوي في الويب")
                        val reply = askManual(a, "$system\n\n---\n\n$user")
                        if (!reply.isNullOrBlank()) {
                            router.onSuccess(a.id)
                            return reply.trim() to a.name
                        }
                        log("⏭️ تخطي ${a.name} (راحة 10 دقائق)")
                        router.park(a.id, 600_000, "تخطي")
                        continue
                    }
                    when (val r = ClaudeClient.send(a, system, user, maxTokens, images)) {
                        is ApiResult.Ok -> {
                            router.onSuccess(a.id)
                            return r.text to a.name
                        }
                        is ApiResult.Fail -> {
                            val cool = router.onFailure(a.id, r.code, r.retryAfterMs, r.fatal, r.message)
                            if (cool < 0) log("❌ ${a.name}: عُطّل (${r.code}: ${r.message.take(80)})")
                            else log("⚠️ ${a.name} توقف (${r.code}: ${r.message.take(80)}) — راحة ${cool / 1000}ث ويكمل الوكيل التالي")
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
        onDigest: (String) -> Unit = {}
    ) {
        this.memory = memory
        router.load(agents.filter { it.enabled && (it.apiKey.isNotBlank() || it.provider.manual) })
        cp = resume ?: Checkpoint(goal)
        if (router.size == 0) { log("⛔ لا يوجد وكلاء مفعّلون"); return }

        // 0) المرفقات: وصف الصور/اللقطات مرة واحدة بوكيل رؤية (Claude/Gemini) ليستفيد منه كل الوكلاء.
        var bundle = attachments
        val images = bundle.allImages
        if (images.isNotEmpty() && bundle.imageDigest.isEmpty()) {
            if (router.hasEligible { it.provider.vision }) {
                log("🖼️ تحليل ${images.size} صور/لقطات…")
                val d = callWithFailover(
                    baseSystem = "أنت محلل بصري. صف كل صورة بدقة وبالعربية: العناصر والنصوص الظاهرة والأبعاد التقريبية والألوان والتفاصيل المهمة لمشروع المستخدم. رقّم الصور بالترتيب.",
                    user = "الهدف: $goal\nالملفات: ${bundle.items.joinToString { it.name }}",
                    maxTokens = 1500, images = images,
                    eligible = { it.provider.vision }, withContext = false
                )
                if (d != null) {
                    bundle = bundle.copy(imageDigest = d.first.trim())
                    onDigest(bundle.imageDigest)
                    log("🖼️ تم وصف الصور بواسطة ${d.second}")
                } else log("⚠️ تعذّر تحليل الصور؛ سيُكمل الفريق بدونها")
            } else {
                log("⚠️ لا يوجد وكيل رؤية (Claude أو Gemini) فارتباط الصور مهمل؛ أضف وكيل Gemini مجانياً لتحليلها")
            }
        }
        attachCtx = bundle.toContext()

        // 1) التخطيط
        if (cp.steps.isEmpty()) {
            log("🧠 تخطيط الهدف…")
            var steps: List<String> = emptyList()
            var by = ""
            for (attempt in 1..2) {
                val plan = callWithFailover(
                    baseSystem = "أنت منسّق مهام. قسّم الهدف إلى 3 إلى 6 خطوات قصيرة ومتسلسلة. أجب بمصفوفة JSON من النصوص فقط دون أي شرح، مثال: [\"خطوة 1\",\"خطوة 2\",\"خطوة 3\"]",
                    user = "الهدف: ${cp.goal}" + if (attempt == 2) "\n\nأعد الإجابة الآن كمصفوفة JSON فقط." else "",
                    maxTokens = 2048
                ) ?: return
                steps = parseSteps(plan.first)
                by = plan.second
                if (steps.isNotEmpty()) break
                log("⚠️ رد غير مفهوم من ${plan.second} (محاولة $attempt): ${plan.first.take(150).replace('\n', ' ')}")
            }
            if (steps.isEmpty()) { log("❌ تعذّر فهم الخطة بعد محاولتين"); return }
            cp = cp.copy(steps = steps.map { Step(it) })
            log("📋 الخطة ($by): ${steps.size} خطوات")
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
