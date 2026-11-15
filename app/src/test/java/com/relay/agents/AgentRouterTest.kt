package com.relay.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentRouterTest {
    private var t = 1_000_000L
    private lateinit var router: AgentRouter

    private fun agent(id: String, p: Provider = Provider.GROQ) =
        AgentConfig(id, id, "key-$id", p.defaultModel, true, p)

    private fun ready(p: AgentRouter.Pick): String = (p as AgentRouter.Pick.Ready).health.agent.id

    @Before
    fun setUp() {
        t = 1_000_000L
        router = AgentRouter(now = { t }, jitter = { 1.0 })
    }

    @Test
    fun spreadsLoadAcrossAgents() {
        router.load(listOf(agent("A"), agent("B")))
        val first = ready(router.pick()); router.markStart(first)
        val second = ready(router.pick()); router.markStart(second)
        assertEquals(setOf("A", "B"), setOf(first, second))
    }

    @Test
    fun respectsMinIntervalBetweenRequests() {
        router.load(listOf(agent("A")))
        router.markStart("A")
        val p = router.pick()
        assertTrue(p is AgentRouter.Pick.Wait)
        assertEquals(2_000L, (p as AgentRouter.Pick.Wait).ms)
        t += 2_000
        assertEquals("A", ready(router.pick()))
    }

    @Test
    fun failedAgentCoolsDownAndOtherTakesOver() {
        router.load(listOf(agent("A"), agent("B")))
        router.markStart("A")
        assertEquals(30_000L, router.onFailure("A", 429, null))
        assertEquals("B", ready(router.pick()))
        t += 31_000
        router.markStart("B")
        assertEquals("A", ready(router.pick()))
    }

    @Test
    fun honorsRetryAfterAndCapsIt() {
        router.load(listOf(agent("A")))
        assertEquals(5_000L, router.onFailure("A", 429, 5_000))
        assertEquals(AgentRouter.MAX_COOLDOWN_MS, router.onFailure("A", 429, 99_999_999))
    }

    @Test
    fun backoffDoublesCapsAndResetsOnSuccess() {
        router.load(listOf(agent("A")))
        val got = (1..6).map { router.onFailure("A", 429, null) }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 600_000L), got)
        router.onSuccess("A")
        assertEquals(30_000L, router.onFailure("A", 429, null))
    }

    @Test
    fun rejectedKeyDisablesAgent() {
        router.load(listOf(agent("A"), agent("B")))
        assertEquals(-1L, router.onFailure("A", 401, null))
        assertTrue(router.snapshot().first { it.agent.id == "A" }.disabled)
        assertEquals("B", ready(router.pick()))
    }

    @Test
    fun noAgentsLeftReturnsNone() {
        router.load(listOf(agent("A")))
        router.onFailure("A", 403, null)
        assertTrue(router.pick() is AgentRouter.Pick.None)
    }

    @Test
    fun manualAgentOnlyWhenApiAgentsWaitLong() {
        router.load(listOf(agent("A"), agent("M", Provider.WEB)))
        router.markStart("A")
        router.onFailure("A", 429, 5_000)
        assertTrue(router.pick() is AgentRouter.Pick.Wait)
        router.onFailure("A", 429, 120_000)
        assertEquals("M", ready(router.pick()))
    }
}
