package com.relay.agents

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** حالة وكيل كما تُعرض في الواجهة. */
data class AgentState(
    val agent: AgentConfig,
    val cooldownUntil: Long = 0L,
    val disabled: Boolean = false,
    val note: String = ""
)

/**
 * موجّه الوكلاء (بلا اعتماد على أندرويد لسهولة الاختبار).
 *
 * - توزيع الحمل: أقل وكيل استخداماً مؤخراً أولاً (LRU) فتتوزع الحصص المجانية بين المزوّدين.
 * - حد معدّل لكل مزوّد: فاصل أدنى بين طلبين لتجنب 429 قبل وقوعها.
 * - قاطع دارة: كل فشل متتالٍ يضاعف الراحة (backoff أسّي مع jitter وسقف 10 دقائق)، والنجاح يصفّر العدّاد.
 * - Retry-After من الخادم له الأولوية على الحساب الذاتي.
 * - مفتاح مرفوض (401/403) أو مسار/نموذج غير موجود (404) → تعطيل الوكيل.
 * - الوكيل اليدوي (ويب) احتياطي أخير: لا يُطلب إلا إن كان انتظار وكلاء API أطول من صبر محدد.
 */
class AgentRouter(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val jitter: () -> Double = { 0.8 + Random.nextDouble() * 0.4 }
) {
    class Health(val agent: AgentConfig) {
        var failures = 0
        var successes = 0
        var cooldownUntil = 0L
        var lastStart = 0L
        var disabled = false
        var note = ""
    }

    sealed class Pick {
        data class Ready(val health: Health) : Pick()
        data class Wait(val ms: Long) : Pick()
        object None : Pick()
    }

    private val items = mutableListOf<Health>()

    fun load(agents: List<AgentConfig>) {
        items.clear()
        agents.forEach { items += Health(it) }
    }

    val size: Int get() = items.size

    fun snapshot(): List<AgentState> = items.map {
        AgentState(
            it.agent, it.cooldownUntil, it.disabled,
            listOfNotNull(
                it.note.ifEmpty { null },
                if (it.successes > 0) "✓${it.successes}" else null
            ).joinToString(" ")
        )
    }

    private fun availableAt(h: Health): Long = max(h.cooldownUntil, h.lastStart + minIntervalMs(h.agent.provider))

    fun hasEligible(eligible: (AgentConfig) -> Boolean): Boolean =
        items.any { !it.disabled && eligible(it.agent) }

    /** eligible: يحصر الاختيار بوكلاء معيّنين (مثل وكلاء الرؤية). */
    fun pick(eligible: (AgentConfig) -> Boolean = { true }): Pick {
        val t = now()
        val alive = items.filter { !it.disabled && eligible(it.agent) }
        val auto = alive.filter { !it.agent.provider.manual }

        val ready = auto.filter { availableAt(it) <= t }.minByOrNull { it.lastStart }
        if (ready != null) return Pick.Ready(ready)

        val soonestAuto = auto.minOfOrNull { availableAt(it) }
        if (soonestAuto != null && soonestAuto - t <= MANUAL_PATIENCE_MS) {
            return Pick.Wait(max(soonestAuto - t, MIN_WAIT_MS))
        }

        val manual = alive.firstOrNull { it.agent.provider.manual && availableAt(it) <= t }
        if (manual != null) return Pick.Ready(manual)

        val any = alive.minOfOrNull { availableAt(it) } ?: return Pick.None
        return Pick.Wait(max(any - t, MIN_WAIT_MS))
    }

    private fun find(id: String) = items.firstOrNull { it.agent.id == id }

    fun markStart(id: String) {
        find(id)?.lastStart = now()
    }

    fun onSuccess(id: String) {
        find(id)?.apply { failures = 0; successes++; note = "" }
    }

    /** يرجع مدة الراحة بالمللي ثانية، أو -1 إن عُطّل الوكيل نهائياً. */
    fun onFailure(
        id: String, code: Int, retryAfterMs: Long?,
        fatal: Boolean = code == 401 || code == 403,
        message: String = ""
    ): Long {
        val h = find(id) ?: return -1
        val detail = message.replace('\n', ' ').take(90)
        if (fatal || code == 404) {
            h.disabled = true
            val why = if (code == 401 || code == 403) "Key rejected" else "Invalid model (use Fetch models)"
            h.note = "$why [$code] $detail".trim()
            return -1
        }
        h.failures++
        val cooldown = if (retryAfterMs != null && retryAfterMs > 0) {
            min(retryAfterMs, MAX_COOLDOWN_MS)
        } else {
            backoff(code, h.failures)
        }
        h.cooldownUntil = now() + cooldown
        h.note = "Cooling [$code] $detail".trim()
        return cooldown
    }

    /** تأجيل وكيل لمدة محددة (مثل تخطي الوكيل اليدوي). */
    fun park(id: String, ms: Long, note: String) {
        find(id)?.apply { cooldownUntil = now() + ms; this.note = note }
    }

    private fun backoff(code: Int, failures: Int): Long {
        val base = when {
            code == 429 -> 30_000L
            code == 529 || code in 500..599 -> 15_000L
            else -> 5_000L
        }
        val exp = base * (1L shl min(failures - 1, 6))
        return min((exp * jitter()).toLong(), MAX_COOLDOWN_MS)
    }

    companion object {
        const val MAX_COOLDOWN_MS = 600_000L
        const val MANUAL_PATIENCE_MS = 20_000L
        const val MIN_WAIT_MS = 200L

        /** فاصل أدنى تقريبي لتوافق حدود الطبقات المجانية (قابل للتعديل). */
        fun minIntervalMs(p: Provider): Long = when (p) {
            Provider.GEMINI -> 4_000L
            Provider.OPENROUTER -> 3_000L
            Provider.GROQ, Provider.MISTRAL, Provider.CEREBRAS, Provider.HUGGINGFACE -> 2_000L
            Provider.CLAUDE, Provider.WEB -> 0L
        }
    }
}
